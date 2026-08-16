package zip.arcanum.arcanum.containers.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.biometric.BiometricPrompt
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.containers.domain.Container
import zip.arcanum.billing.BillingManagerInterface
import zip.arcanum.BuildConfig
import zip.arcanum.core.database.entities.ContainerEntity
import zip.arcanum.core.security.AppPreferences
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.security.BiometricAuth
import zip.arcanum.core.security.BiometricCryptoManager
import zip.arcanum.crypto.CryptoError
import zip.arcanum.crypto.CryptoResult
import zip.arcanum.crypto.VeraCryptEngine
import zip.arcanum.usb.UsbBlockDevice
import zip.arcanum.usb.UsbVolumeManager
import javax.crypto.Cipher
import javax.inject.Inject

private val Context.vaultDisplayDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "vault_display_prefs")

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val repo: ContainerRepository,
    private val cryptoEngine: VeraCryptEngine,
    private val biometricCryptoManager: BiometricCryptoManager,
    private val biometricAuth: BiometricAuth,
    private val billingManager: BillingManagerInterface,
    private val mountLogger: MountLogger,
    private val prefs: AppPreferences,
    private val usbVolumes: zip.arcanum.usb.UsbVolumeManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    enum class SortBy        { NAME, SIZE, LAST_OPENED }
    enum class SortDirection { ASCENDING, DESCENDING }
    enum class GroupBy       { NONE, LOCATION }

    data class SortState(
        val sortBy:         SortBy        = SortBy.NAME,
        val direction:      SortDirection = SortDirection.ASCENDING,
        val groupBy:        GroupBy       = GroupBy.NONE,
        val biometricFirst: Boolean       = false
    )

    private object DisplayKeys {
        val SORT_BY         = stringPreferencesKey("sort_by")
        val SORT_DIRECTION  = stringPreferencesKey("sort_direction")
        val GROUP_BY        = stringPreferencesKey("group_by")
        val BIOMETRIC_FIRST = booleanPreferencesKey("biometric_first")
    }

    private val _sortState = MutableStateFlow(SortState())
    val sortState = _sortState.asStateFlow()

    // True when the installed version is newer than the last version the user acknowledged.
    // null lastSeenVersionCode means first install — not an update.
    val showUpdateBanner: StateFlow<Boolean> = prefs.lastSeenVersionCode
        .map { lastSeen -> lastSeen != null && lastSeen < BuildConfig.VERSION_CODE }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun initVersionCheck() {
        viewModelScope.launch {
            if (prefs.lastSeenVersionCode.first() == null) {
                // First install — record current version without showing the banner
                prefs.setLastSeenVersionCode(BuildConfig.VERSION_CODE)
            }
        }
    }

    /** One-shot hint on the mount screen: where the unlock control now is. */
    val mountHintShown: StateFlow<Boolean> = prefs.mountHintShown
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun markMountHintShown() {
        viewModelScope.launch { prefs.setMountHintShown() }
    }

    fun markUpdateSeen() {
        viewModelScope.launch { prefs.setLastSeenVersionCode(BuildConfig.VERSION_CODE) }
    }

    // ── Support prompt ────────────────────────────────────────────────────
    // A day after the user first got to the vault list, then once a month. On the Play
    // flavour the ask is to buy the full version instead of to donate.

    private val _supportPrompt = MutableStateFlow<InAppNotification?>(null)
    val supportPrompt: StateFlow<InAppNotification?> = _supportPrompt.asStateFlow()

    fun checkSupportPrompt() {
        viewModelScope.launch {
            val now = System.currentTimeMillis()

            // First run: start the clock and say nothing. Recorded here rather than at
            // install so the day counts from real use, not from an APK sitting unopened.
            val firstSeen = prefs.firstSeenAt.first()
            if (firstSeen == null) {
                prefs.setFirstSeenAt(now)
                return@launch
            }
            // Guards against a clock that moved backwards: treat it as not yet due
            // rather than letting a negative age satisfy every interval.
            if (now - firstSeen < FIRST_PROMPT_AFTER_MS) return@launch

            val lastShown = prefs.lastSupportPromptAt.first()
            if (lastShown != null && now - lastShown < PROMPT_INTERVAL_MS) return@launch

            // Nothing to sell to someone who already bought it.
            if (BuildConfig.HAS_BILLING && billingManager.isPro.value) return@launch

            _supportPrompt.value =
                if (BuildConfig.HAS_BILLING) InAppNotification.GoPremium
                else InAppNotification.SupportDeveloper
        }
    }

    /** Called once the banner has actually been put on screen, not when it is dismissed. */
    fun markSupportPromptShown() {
        _supportPrompt.value = null
        viewModelScope.launch { prefs.setLastSupportPromptAt(System.currentTimeMillis()) }
    }


    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                unmountContainersOnStop(isLocked = true)
            }
        }
    }

    private val appBackgroundObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            unmountContainersOnStop(isLocked = false)
        }
    }

    init {
        context.registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        ProcessLifecycleOwner.get().lifecycle.addObserver(appBackgroundObserver)
        viewModelScope.launch {
            val prefs = context.vaultDisplayDataStore.data.first()
            _sortState.value = SortState(
                sortBy         = prefs[DisplayKeys.SORT_BY]
                                     ?.let { runCatching { SortBy.valueOf(it) }.getOrNull() }
                                     ?: SortBy.NAME,
                direction      = prefs[DisplayKeys.SORT_DIRECTION]
                                     ?.let { runCatching { SortDirection.valueOf(it) }.getOrNull() }
                                     ?: SortDirection.ASCENDING,
                groupBy        = prefs[DisplayKeys.GROUP_BY]
                                     ?.let { runCatching { GroupBy.valueOf(it) }.getOrNull() }
                                     ?: GroupBy.NONE,
                biometricFirst = prefs[DisplayKeys.BIOMETRIC_FIRST] ?: false
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        ProcessLifecycleOwner.get().lifecycle.removeObserver(appBackgroundObserver)
        context.unregisterReceiver(screenOffReceiver)
    }

    private fun persistSortState(state: SortState) {
        viewModelScope.launch {
            context.vaultDisplayDataStore.edit { prefs ->
                prefs[DisplayKeys.SORT_BY]         = state.sortBy.name
                prefs[DisplayKeys.SORT_DIRECTION]  = state.direction.name
                prefs[DisplayKeys.GROUP_BY]        = state.groupBy.name
                prefs[DisplayKeys.BIOMETRIC_FIRST] = state.biometricFirst
            }
        }
    }

    val canAddMoreContainers = combine(repo.getAllContainersRaw(), billingManager.isPro) { list, pro ->
        pro || list.size < 2
    }.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val containers = combine(repo.getAllContainersRaw(), _sortState) { list, sort ->
        var sorted = when (sort.sortBy) {
            SortBy.NAME        -> list.sortedBy { it.name.lowercase() }
            SortBy.SIZE        -> list.sortedBy { it.size }
            SortBy.LAST_OPENED -> list.sortedBy { it.lastAccessedAt }
        }
        if (sort.direction == SortDirection.DESCENDING) sorted = sorted.reversed()
        if (sort.biometricFirst) sorted = sorted.sortedByDescending { it.hasBiometric }
        sorted
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun updateSort(sortBy: SortBy, direction: SortDirection) {
        _sortState.update { it.copy(sortBy = sortBy, direction = direction) }
        persistSortState(_sortState.value)
    }

    fun updateGroupBy(groupBy: GroupBy) {
        _sortState.update { it.copy(groupBy = groupBy) }
        persistSortState(_sortState.value)
    }

    fun toggleBiometricFirst() {
        _sortState.update { it.copy(biometricFirst = !it.biometricFirst) }
        persistSortState(_sortState.value)
    }

    sealed interface MountState {
        object Idle                  : MountState
        object Loading               : MountState
        /** credentialHint = true keeps the generic "check password/PIM/PRF/..." reason list
         *  (a failed decryption); false shows [message] verbatim (a specific, self-explanatory
         *  failure such as a read-only storage location or a too-many-mounted limit). */
        data class Error(val message: String, val credentialHint: Boolean = false) : MountState
    }

    private val _mountState = MutableStateFlow<MountState>(MountState.Idle)
    val mountState = _mountState.asStateFlow()

    // null when showMountLog is off; live list of timestamped lines when on.
    val mountLogs: StateFlow<List<String>?> =
        prefs.showMountLog.combine(mountLogger.lines) { show, lines ->
            if (show) lines else null
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var mountJob: Job? = null
    private var lastMountTimeMillis = 0L

    fun cancelMount() {
        mountJob?.cancel()
        mountJob = null
        _mountState.value = MountState.Idle
    }

    fun mountContainer(
        container: ContainerEntity,
        password: String,
        keyfileData: List<ByteArray> = emptyList(),
        pim: Int = 0,
        algorithm: Int = VeraCryptEngine.ALGO_AUTO,
        hashAlgorithm: Int = VeraCryptEngine.HASH_AUTO,
        protectHiddenPassword: String? = null,
        protectHiddenPim: Int = 0,
        protectHiddenKeyfileData: List<ByteArray> = emptyList(),
        readOnly: Boolean = false,
        onSuccess: (containerId: String) -> Unit
    ) {
        mountJob = viewModelScope.launch {
            _mountState.value = MountState.Loading
            mountLogger.start()
            mountLogger.log("Container: ${container.name}")
            val isUsb = container.usbSaltHash.isNotEmpty()

            // The gate for a USB-hosted vault, in the two stages the user experiences as
            // one action. Presence is passive and cheap; identity costs a claim, so it can
            // only be checked once the drive is in hand. Keeping the two apart is what
            // lets "no drive" and "the wrong drive" say different things - the second
            // would otherwise leave someone retrying at a drive that will never match.
            var usbDevice: UsbBlockDevice? = null
            var usbSpan: UsbVolumeManager.VolumeSpan? = null
            if (isUsb) {
                mountLogger.log("Source: USB drive")
                if (!usbVolumes.ensurePermission()) {
                    mountLogger.log("Mount aborted: no drive attached, or USB permission refused")
                    _mountState.value = MountState.Error(USB_NOT_CONNECTED)
                    persistMountLog()
                    return@launch
                }
                when (val opened = usbVolumes.openMatching(
                    container.usbSaltHash, readOnly, container.usbStartByte,
                )) {
                    is UsbVolumeManager.OpenResult.Ok -> {
                        usbDevice = opened.device
                        usbSpan = opened.span
                        if (opened.span.startByte != container.usbStartByte) {
                            // The volume moved: found by its salt, not where it was last
                            // seen. Record the new place so the next mount is one read.
                            mountLogger.log("Volume found at offset ${opened.span.startByte}, not the remembered ${container.usbStartByte}")
                            repo.updateUsbStartByte(container.id, opened.span.startByte)
                        }
                    }
                    UsbVolumeManager.OpenResult.NoDrive -> {
                        mountLogger.log("Mount aborted: no USB drive attached")
                        _mountState.value = MountState.Error(USB_NOT_CONNECTED)
                        persistMountLog()
                        return@launch
                    }
                    is UsbVolumeManager.OpenResult.WrongVolume -> {
                        mountLogger.log("Mount aborted: attached drive holds a different volume (${opened.found.label})")
                        _mountState.value = MountState.Error(USB_WRONG_DEVICE)
                        persistMountLog()
                        return@launch
                    }
                    is UsbVolumeManager.OpenResult.Failed -> {
                        mountLogger.log("Mount aborted: ${opened.reason}")
                        _mountState.value = MountState.Error(USB_NOT_CONNECTED)
                        persistMountLog()
                        return@launch
                    }
                }
            }

            val isSaf = container.safUri.isNotEmpty()
            val pfd: ParcelFileDescriptor? = if (isSaf) {
                mountLogger.log("Source: SAF URI (${container.safUri.takeLast(40)})")
                // Some DocumentsProviders (SMB / network shares, some cloud roots) expose a file
                // for reading but refuse "rw" - openFileDescriptor then throws (or returns null)
                // instead of handing back a writable fd. Catch it so the mount ends with a clear
                // reason instead of an uncaught exception, and never fall through to the native
                // open("") path below (which would report a misleading "Cannot open file").
                try {
                    context.contentResolver.openFileDescriptor(Uri.parse(container.safUri), if (readOnly) "r" else "rw")
                } catch (e: Exception) {
                    mountLogger.log("ERROR: storage refused ${if (readOnly) "read" else "read-write"} access (${e.javaClass.simpleName})")
                    null
                }
            } else {
                mountLogger.log("Source: ${container.path}")
                null
            }

            if (isSaf && pfd == null) {
                mountLogger.log("Mount aborted: storage did not provide a file descriptor")
                _mountState.value = MountState.Error(
                    if (readOnly) "This storage location can't be opened."
                    else "This storage location is read-only. Enable Read-only to mount it."
                )
                persistMountLog()
                return@launch
            }

            var pfdConsumed = false
            try {
                if (keyfileData.isNotEmpty()) mountLogger.log("Keyfiles: ${keyfileData.size} file(s)")
                if (pim > 0) mountLogger.log("PIM: $pim")
                val algoLabel = if (algorithm  == VeraCryptEngine.ALGO_AUTO) "auto-detect (all ciphers)"
                                else VeraCryptEngine.algorithmIdToString(algorithm)
                val hashLabel = if (hashAlgorithm == VeraCryptEngine.HASH_AUTO) "auto-detect (all PRFs)"
                                else VeraCryptEngine.hashIdToString(hashAlgorithm)
                mountLogger.log("Cipher: $algoLabel")
                mountLogger.log("PRF: $hashLabel")
                if (!protectHiddenPassword.isNullOrBlank()) mountLogger.log("Hidden volume protection: enabled")
                if (readOnly) mountLogger.log("Mode: read-only")
                mountLogger.log("Submitting credentials to crypto engine...")
                mountLogger.log("Running PBKDF2 key derivation (may take several seconds)...")
                // Only create the listener when the log terminal is visible — avoids
                // allocating a JNI callback object when the user doesn't need the output.
                val progressListener: VeraCryptEngine.MountProgressListener? =
                    if (mountLogs.value != null) {
                        object : VeraCryptEngine.MountProgressListener {
                            override fun onTrying(cipher: String, prf: String, attempt: Int, total: Int) {
                                mountLogger.log("[$attempt/$total] $cipher + $prf")
                            }
                        }
                    } else null
                val result = if (usbDevice != null) {
                    // Goes through the manager rather than the engine directly: it takes
                    // ownership of the transport, so the drive being pulled later has a
                    // single owner to tear the volume down.
                    usbVolumes.mount(
                        device = usbDevice,
                        span = usbSpan ?: UsbVolumeManager.VolumeSpan(0L, usbDevice.sizeBytes),
                        password = password, readOnly = readOnly,
                        keyfileData = keyfileData, pim = pim,
                        algorithm = algorithm, hashAlgorithm = hashAlgorithm,
                        protectHiddenPassword = protectHiddenPassword,
                        protectHiddenKeyfileData = protectHiddenKeyfileData,
                        protectHiddenPim = protectHiddenPim,
                        mountProgressListener = progressListener,
                        label = container.name
                    )
                } else if (pfd != null) {
                    cryptoEngine.mountContainerFd(
                        fd = pfd.fd, password = password,
                        keyfileData = keyfileData, pim = pim,
                        algorithm = algorithm, hashAlgorithm = hashAlgorithm,
                        protectHiddenPassword = protectHiddenPassword,
                        protectHiddenKeyfileData = protectHiddenKeyfileData,
                        protectHiddenPim = protectHiddenPim,
                        mountProgressListener = progressListener,
                        readOnly = readOnly
                    )
                } else {
                    cryptoEngine.mountContainer(
                        path = container.path, password = password,
                        keyfileData = keyfileData, pim = pim,
                        algorithm = algorithm, hashAlgorithm = hashAlgorithm,
                        protectHiddenPassword = protectHiddenPassword,
                        protectHiddenKeyfileData = protectHiddenKeyfileData,
                        protectHiddenPim = protectHiddenPim,
                        mountProgressListener = progressListener,
                        readOnly = readOnly
                    )
                }
                when (result) {
                    is CryptoResult.Success -> {
                        mountLogger.log("Header decrypted successfully.")
                        val handle     = result.value
                        var isHidden   = false
                        var dataSize   = 0L
                        var algId      = -1
                        var hashId     = -1
                        var fsType     = -1
                        var keySize    = 0
                        var iterations = 0
                        var needsCheck = false
                        withContext(Dispatchers.IO) {
                            isHidden   = cryptoEngine.getVolumeType(handle) == 1
                            dataSize   = cryptoEngine.getDataSize(handle).coerceAtLeast(0L)
                            algId      = cryptoEngine.getAlgorithmId(handle)
                            hashId     = cryptoEngine.getHashId(handle)
                            fsType     = cryptoEngine.getFilesystem(handle)
                            keySize    = cryptoEngine.getKeySize(handle)
                            iterations = cryptoEngine.getIterationCount(handle)
                            needsCheck = cryptoEngine.ext4NeedsCheck(handle)
                        }
                        val hasHidden = !protectHiddenPassword.isNullOrBlank()
                        if (algId  >= 0) mountLogger.log("Cipher: ${VeraCryptEngine.algorithmIdToString(algId)}")
                        if (hashId >= 0) mountLogger.log("PRF: ${VeraCryptEngine.hashIdToString(hashId)}")
                        if (iterations > 0) mountLogger.log("PKCS-5 iterations: $iterations")
                        mountLogger.log("Mounting FatFs virtual filesystem...")
                        repo.mountContainer(container.id, handle, pim,
                            isHidden = isHidden, hasHidden = hasHidden,
                            dataSize = dataSize, parcelFd = pfd,
                            isReadOnly = readOnly)
                        pfdConsumed = true
                        if (algId      >= 0) repo.updateAlgorithm(container.id, VeraCryptEngine.algorithmIdToString(algId))
                        if (hashId     >= 0) repo.updatePrf(container.id, VeraCryptEngine.hashIdToString(hashId))
                        if (fsType     >= 0) repo.updateFilesystem(container.id, VeraCryptEngine.filesystemIdToString(fsType))
                        if (keySize    >  0) repo.updateKeySize(container.id, keySize)
                        if (iterations >  0) repo.updatePkcs5Iterations(container.id, iterations)
                        mountLogger.log("Mount successful.")
                        // Only logged here; the banner is raised by
                        // ContainerScreenViewModel, on the screen this navigates to.
                        if (needsCheck)
                            mountLogger.log("The last session that wrote to this vault did not finish.")
                        lastMountTimeMillis = System.currentTimeMillis()
                        _mountState.value = MountState.Idle
                        onSuccess(container.id)
                    }
                    is CryptoResult.Failure -> {
                        mountLogger.log("ERROR: ${result.error.name}")
                        _mountState.value = when (result.error) {
                            CryptoError.IO_ERROR             -> MountState.Error("Cannot open container file")
                            CryptoError.UNSUPPORTED_ALGORITHM -> MountState.Error("Unsupported container format")
                            CryptoError.TOO_MANY_MOUNTED      -> MountState.Error("Too many containers mounted — unmount one and try again")
                            CryptoError.CORRUPTED_CONTAINER   -> MountState.Error("Container filesystem could not be mounted")
                            else -> MountState.Error("Wrong password", credentialHint = true)
                        }
                    }
                }
            } finally {
                if (!pfdConsumed) pfd?.close()
                persistMountLog()
            }
        }
    }

    /**
     * Writes the just-finished mount log to disk when the debug "Save mount log" toggle is on,
     * so it can be copied from Settings > Debug (the live terminal is otherwise ephemeral and
     * can't be grabbed for a bug report). Snapshot is read on the caller's thread; the file
     * write is offloaded to IO. Best-effort - never affects the mount outcome.
     */
    private fun persistMountLog() {
        val snapshot = mountLogger.lines.value
        if (snapshot.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (!prefs.saveMountLog.first()) return@launch
                val ts = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
                java.io.File(context.filesDir, zip.arcanum.ArcanumApp.MOUNT_LOG_FILE)
                    .writeText("Mount log - $ts\n" + snapshot.joinToString("\n"))
            } catch (_: Exception) {}
        }
    }

    fun resetMountState() { _mountState.value = MountState.Idle }

    // ── Add-vault result ───────────────────────────────────────────────

    sealed interface AddVaultResult {
        data class Added(val fileName: String)         : AddVaultResult
        data class AlreadyExists(val fileName: String) : AddVaultResult
        data object InvalidFile                        : AddVaultResult
        data object LimitReached                       : AddVaultResult
        data class Error(val message: String)          : AddVaultResult
        /** No USB drive is attached; the user is asked to plug one in and retry. */
        data object NoUsbDrive                         : AddVaultResult
    }

    private val _addVaultResult = MutableStateFlow<AddVaultResult?>(null)
    val addVaultResult = _addVaultResult.asStateFlow()

    fun addContainerFromPath(path: String) {
        viewModelScope.launch {
            if (!billingManager.isPro.value && repo.getAllContainersRaw().first().size >= 2) {
                _addVaultResult.value = AddVaultResult.LimitReached
                return@launch
            }
            val file = java.io.File(path)
            val size = file.length()
            if (!file.exists() || size < 131072L || size % 512 != 0L) {
                _addVaultResult.value = AddVaultResult.InvalidFile
                return@launch
            }
            if (repo.containsPath(path)) {
                _addVaultResult.value = AddVaultResult.AlreadyExists(file.name)
                return@launch
            }
            try {
                repo.addContainerFromPath(path)
                _addVaultResult.value = AddVaultResult.Added(file.name)
            } catch (e: Exception) {
                _addVaultResult.value = AddVaultResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun addContainerFromUri(uri: Uri) {
        viewModelScope.launch {
            if (!billingManager.isPro.value && repo.getAllContainersRaw().first().size >= 2) {
                _addVaultResult.value = AddVaultResult.LimitReached
                return@launch
            }
            val safUri = zip.arcanum.core.utils.FileUtils.normalizeSafUri(uri).toString()
            val docId  = zip.arcanum.core.utils.FileUtils.safUriDocumentId(uri)
            val alreadyExists = if (docId != null) {
                repo.containsDocumentId(uri.authority ?: "", docId)
            } else {
                repo.containsSafUri(safUri)
            }
            if (alreadyExists) {
                val name = resolveDisplayName(uri) ?: "vault.hc"
                _addVaultResult.value = AddVaultResult.AlreadyExists(name)
                return@launch
            }
            val (name, size) = resolveUriMeta(uri) ?: run {
                _addVaultResult.value = AddVaultResult.InvalidFile
                return@launch
            }
            if (size < 131072L || size % 512 != 0L) {
                _addVaultResult.value = AddVaultResult.InvalidFile
                return@launch
            }
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            try {
                repo.addContainerFromUri(safUri, name, size)
                _addVaultResult.value = AddVaultResult.Added(name)
            } catch (e: Exception) {
                _addVaultResult.value = AddVaultResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    private fun resolveDisplayName(uri: Uri): String? =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun resolveUriMeta(uri: Uri): Pair<String, Long>? =
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null, null, null
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            val name = cursor.getString(0) ?: return null
            val size = cursor.getLong(1)
            name to size
        }

    /**
     * Adds the attached USB drive as a vault (#95).
     *
     * Unlike the file paths above there is nothing to browse: the drive either is here or
     * is not. Claiming it to read the volume's salt is what makes this possible, and also
     * what makes it non-passive - the drive leaves Android's file manager until replugged.
     *
     * Whether the drive actually holds a VeraCrypt volume cannot be determined here.
     * Ciphertext is indistinguishable from random bytes, so a blank drive is added just as
     * happily; the truth comes out at mount time, exactly as it does for a file.
     *
     * [partition] is what the user picked: null for the whole drive, otherwise the volume
     * starts at that partition. Passing the wrong one is not dangerous, only useless - the
     * salt read there will not decrypt and the vault will refuse to mount.
     */
    fun addUsbContainer(partition: zip.arcanum.usb.UsbPartition? = null) {
        viewModelScope.launch {
            if (!billingManager.isPro.value && repo.getAllContainersRaw().first().size >= 2) {
                _addVaultResult.value = AddVaultResult.LimitReached
                return@launch
            }
            val identity = usbVolumes.identifyAttachedDrive(partition).getOrElse { e ->
                _addVaultResult.value =
                    if (e is zip.arcanum.usb.UsbVolumeManager.NoDriveException) AddVaultResult.NoUsbDrive
                    else AddVaultResult.Error(e.message ?: "Unknown error")
                return@launch
            }
            if (repo.containsUsbSaltHash(identity.saltHash)) {
                _addVaultResult.value = AddVaultResult.AlreadyExists(identity.label)
                return@launch
            }
            try {
                repo.addUsbContainer(
                    identity.saltHash, identity.label, identity.sizeBytes, identity.startByte,
                )
                _addVaultResult.value = AddVaultResult.Added(identity.label)
            } catch (e: Exception) {
                _addVaultResult.value = AddVaultResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    /** Passive presence check: no claim, so the drive stays where Android put it. */
    fun isUsbDriveAttached(): Boolean = usbVolumes.attachedDrive() != null

    private val _usbLayout = MutableStateFlow<zip.arcanum.usb.UsbVolumeManager.DriveLayout?>(null)
    /** Non-null while the partition picker is up. */
    val usbLayout: StateFlow<zip.arcanum.usb.UsbVolumeManager.DriveLayout?> = _usbLayout.asStateFlow()

    private val _usbLayoutLoading = MutableStateFlow(false)
    val usbLayoutLoading: StateFlow<Boolean> = _usbLayoutLoading.asStateFlow()

    /**
     * Reads the drive's partition table so the user can say what to add.
     *
     * A drive with no table is not an error and does not need a picker: there is only the
     * whole device to choose, so this adds it directly and the user sees no extra step.
     */
    fun loadUsbLayout() {
        viewModelScope.launch {
            _usbLayoutLoading.value = true
            val layout = usbVolumes.listPartitions().getOrElse { e ->
                _usbLayoutLoading.value = false
                _addVaultResult.value =
                    if (e is zip.arcanum.usb.UsbVolumeManager.NoDriveException) AddVaultResult.NoUsbDrive
                    else AddVaultResult.Error(e.message ?: "Unknown error")
                return@launch
            }
            _usbLayoutLoading.value = false
            if (layout.partitions.isEmpty()) addUsbContainer(null) else _usbLayout.value = layout
        }
    }

    fun dismissUsbLayout() { _usbLayout.value = null }

    /** Asks for USB permission for the attached drive, so [addUsbContainer] can claim it. */
    suspend fun ensureUsbPermission(): Boolean = usbVolumes.ensurePermission()

    fun clearAddVaultResult() { _addVaultResult.value = null }

    // ── Bulk delete ────────────────────────────────────────────────────

    fun unmountContainer(id: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val handle = repo.getContainerHandle(id)
            if (handle != null) closeByHandle(handle)
            repo.unmountContainer(id)
            onDone()
        }
    }

    /**
     * Closes a mounted volume by whichever route owns it.
     *
     * A USB volume must go through UsbVolumeManager rather than straight to the engine:
     * the manager holds the transport, and closing the container behind its back would
     * leave the USB interface claimed and the manager still believing a volume is
     * mounted - so the drive would stay missing from Android and a later detach would
     * fire against something already gone.
     */
    private suspend fun closeByHandle(handle: Long) {
        if (usbVolumes.mounted.value?.handle == handle) usbVolumes.unmount()
        else cryptoEngine.unmountContainer(handle)
    }

    /** Enriched domain container (mount-only fields resolved) for the details sheet. */
    suspend fun getContainerDomain(id: String): Container? = repo.getContainerById(id)

    fun deleteContainers(ids: Set<String>) {
        viewModelScope.launch {
            ids.forEach { id ->
                val handle = repo.getContainerHandle(id)
                if (handle != null) cryptoEngine.unmountContainer(handle)
            }
            repo.deleteContainersById(ids)
        }
    }

    fun updateUnmountOnLock(id: String, value: Boolean) {
        viewModelScope.launch { repo.updateUnmountOnLock(id, value) }
    }

    fun updateUnmountOnBackground(id: String, value: Boolean) {
        viewModelScope.launch { repo.updateUnmountOnBackground(id, value) }
    }

    fun updateExternalAccessEnabled(id: String, value: Boolean) {
        viewModelScope.launch { repo.updateExternalAccessEnabled(id, value) }
    }

    fun unmountContainersOnStop(isLocked: Boolean) {
        // Skip background unmounting if a container was just mounted — ProcessLifecycleOwner.onStop
        // can fire during the mount animation or navigation transition, causing the freshly-mounted
        // container to be unmounted immediately. Screen-off (isLocked=true) is not affected.
        if (!isLocked && System.currentTimeMillis() - lastMountTimeMillis < 3_000L) return
        viewModelScope.launch {
            repo.getAllContainersRaw().first().filter { it.isMounted }.forEach { c ->
                // Flush first, and regardless of the settings below. The USB backend holds
                // writes back to merge them, and Android kills backgrounded apps without
                // warning - those bytes exist nowhere else. Whether the vault should also
                // be closed is a choice; whether it should lose data is not.
                repo.getContainerHandle(c.id)?.let { cryptoEngine.flushContainer(it) }
                if (c.unmountOnBackground || (isLocked && c.unmountOnLock)) {
                    val handle = repo.getContainerHandle(c.id)
                    if (handle != null) closeByHandle(handle)
                    repo.unmountContainer(c.id)
                }
            }
        }
    }

    fun removeFromList(id: String) {
        viewModelScope.launch {
            val handle = repo.getContainerHandle(id)
            if (handle != null) closeByHandle(handle)
            repo.deleteContainersById(setOf(id))
        }
    }

    // ── Biometric ──────────────────────────────────────────────────────

    fun isBiometricAvailable(): Boolean = biometricAuth.isAvailable()

    fun hasBiometricCredentials(containerId: String): Boolean =
        biometricCryptoManager.hasSavedCredentials(containerId)

    fun getBiometricCryptoObjectForEncrypt(): BiometricPrompt.CryptoObject? =
        try { biometricCryptoManager.getCryptoObjectForEncrypt() } catch (_: Exception) { null }

    fun getBiometricCryptoObjectForDecrypt(containerId: String): BiometricPrompt.CryptoObject? =
        biometricCryptoManager.getCryptoObjectForDecrypt(containerId)

    fun saveBiometricCredentials(containerId: String, cipher: Cipher, password: String, pim: Int) {
        biometricCryptoManager.saveEncryptedCredentials(containerId, cipher, password, pim)
        viewModelScope.launch { repo.updateBiometric(containerId, true) }
    }

    fun saveKeyfileUrisForBiometric(containerId: String, uris: List<String>) {
        biometricCryptoManager.saveKeyfileUris(containerId, uris)
    }

    fun decryptBiometricCredentials(containerId: String, cipher: Cipher): Pair<String, Int>? =
        biometricCryptoManager.loadDecryptedCredentials(containerId, cipher)

    fun deleteBiometricCredentials(containerId: String) {
        biometricCryptoManager.deleteCredentials(containerId)
        viewModelScope.launch { repo.updateBiometric(containerId, false) }
    }

    fun biometricMountContainer(
        container: ContainerEntity,
        cipher: Cipher,
        onMissingKeyfiles: () -> Unit,
        onInvalidCredentials: () -> Unit,
        onSuccess: (String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val creds = biometricCryptoManager.loadDecryptedCredentials(container.id, cipher)
            if (creds == null) {
                withContext(Dispatchers.Main) { onInvalidCredentials() }
                return@launch
            }
            val uris = biometricCryptoManager.loadKeyfileUris(container.id)
            val keyfileData = uris.map { uriStr ->
                try { context.contentResolver.openInputStream(Uri.parse(uriStr))?.use { it.readBytes() } }
                catch (_: Exception) { null }
            }
            if (keyfileData.any { it == null }) {
                withContext(Dispatchers.Main) { onMissingKeyfiles() }
                return@launch
            }
            withContext(Dispatchers.Main) {
                mountContainer(
                    container                 = container,
                    password                  = creds.first,
                    keyfileData               = keyfileData.filterNotNull(),
                    pim                       = creds.second,
                    protectHiddenPassword     = null,
                    protectHiddenKeyfileData  = emptyList(),
                    protectHiddenPim          = 0,
                    onSuccess                 = onSuccess
                )
            }
        }
    }

    // ── Rename vault ───────────────────────────────────────────────────

    sealed interface RenameResult {
        data object Success : RenameResult
        data class Error(val message: String) : RenameResult
    }

    private val _renameResult = MutableStateFlow<RenameResult?>(null)
    val renameResult = _renameResult.asStateFlow()

    fun renameContainer(id: String, newName: String) {
        viewModelScope.launch {
            val container = repo.getContainerById(id)
            if (container == null) {
                _renameResult.value = RenameResult.Error("Container not found")
                return@launch
            }
            val success = when {
                container.safUri.isNotEmpty() -> {
                    try {
                        val uri = Uri.parse(container.safUri)
                        val newUri = android.provider.DocumentsContract.renameDocument(
                            context.contentResolver, uri, newName
                        )
                        if (newUri != null) repo.updateSafUri(id, newUri.toString())
                        repo.updateName(id, newName)
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
                container.path.isNotEmpty() -> {
                    val file = java.io.File(container.path)
                    val parent = file.parentFile
                    if (parent == null) {
                        false
                    } else {
                        val newFile = java.io.File(parent, newName)
                        if (!file.renameTo(newFile)) {
                            false
                        } else {
                            repo.updateContainerPath(id, newFile.absolutePath)
                            repo.updateName(id, newName)
                            true
                        }
                    }
                }
                else -> false
            }
            _renameResult.value = if (success) RenameResult.Success
                                  else RenameResult.Error("Failed to rename")
        }
    }

    fun clearRenameResult() { _renameResult.value = null }

    // ── Delete vault file ──────────────────────────────────────────────

    fun deleteVaultFile(id: String) {
        viewModelScope.launch {
            val handle = repo.getContainerHandle(id)
            if (handle != null) cryptoEngine.unmountContainer(handle)
            val container = repo.getContainerById(id)
            repo.deleteContainersById(setOf(id))
            when {
                container?.safUri?.isNotEmpty() == true ->
                    runCatching {
                        android.provider.DocumentsContract.deleteDocument(
                            context.contentResolver, Uri.parse(container.safUri)
                        )
                    }
                container?.path?.isNotEmpty() == true ->
                    java.io.File(container.path).delete()
            }
        }
    }

    private companion object {
        const val FIRST_PROMPT_AFTER_MS = 24L * 60 * 60 * 1000          // one day
        const val PROMPT_INTERVAL_MS    = 30L * 24 * 60 * 60 * 1000     // then monthly

        /** The two USB gate failures the user can act on, and they need different actions. */
        const val USB_NOT_CONNECTED =
            "Connect the USB drive holding this vault and try again."
        const val USB_WRONG_DEVICE =
            "Wrong USB device: the connected drive does not hold this vault."
    }
}
