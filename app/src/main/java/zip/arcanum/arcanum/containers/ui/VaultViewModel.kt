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
import zip.arcanum.billing.BillingManagerInterface
import zip.arcanum.BuildConfig
import zip.arcanum.core.database.entities.ContainerEntity
import zip.arcanum.core.security.AppPreferences
import zip.arcanum.core.security.BiometricAuth
import zip.arcanum.core.security.BiometricCryptoManager
import zip.arcanum.crypto.CryptoError
import zip.arcanum.crypto.CryptoResult
import zip.arcanum.crypto.VeraCryptEngine
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

    fun markUpdateSeen() {
        viewModelScope.launch { prefs.setLastSeenVersionCode(BuildConfig.VERSION_CODE) }
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
        data class Error(val message: String) : MountState
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
        onSuccess: (containerId: String) -> Unit
    ) {
        mountJob = viewModelScope.launch {
            _mountState.value = MountState.Loading
            mountLogger.start()
            mountLogger.log("Container: ${container.name}")
            val pfd: ParcelFileDescriptor? = if (container.safUri.isNotEmpty()) {
                mountLogger.log("Source: SAF URI (${container.safUri.takeLast(40)})")
                context.contentResolver.openFileDescriptor(Uri.parse(container.safUri), "rw")
            } else {
                mountLogger.log("Source: ${container.path}")
                null
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
                val result = if (pfd != null) {
                    cryptoEngine.mountContainerFd(
                        fd = pfd.fd, password = password,
                        keyfileData = keyfileData, pim = pim,
                        algorithm = algorithm, hashAlgorithm = hashAlgorithm,
                        protectHiddenPassword = protectHiddenPassword,
                        protectHiddenKeyfileData = protectHiddenKeyfileData,
                        protectHiddenPim = protectHiddenPim,
                        mountProgressListener = progressListener
                    )
                } else {
                    cryptoEngine.mountContainer(
                        path = container.path, password = password,
                        keyfileData = keyfileData, pim = pim,
                        algorithm = algorithm, hashAlgorithm = hashAlgorithm,
                        protectHiddenPassword = protectHiddenPassword,
                        protectHiddenKeyfileData = protectHiddenKeyfileData,
                        protectHiddenPim = protectHiddenPim,
                        mountProgressListener = progressListener
                    )
                }
                when (result) {
                    is CryptoResult.Success -> {
                        mountLogger.log("Header decrypted successfully.")
                        val isHidden  = cryptoEngine.getVolumeType(result.value) == 1
                        val hasHidden = !protectHiddenPassword.isNullOrBlank()
                        val dataSize  = cryptoEngine.nativeGetDataSize(result.value).coerceAtLeast(0L)
                        val algId  = cryptoEngine.nativeGetAlgorithmId(result.value)
                        val hashId = cryptoEngine.nativeGetHashId(result.value)
                        val fsType = cryptoEngine.nativeGetFilesystem(result.value)
                        if (algId  >= 0) mountLogger.log("Cipher: ${VeraCryptEngine.algorithmIdToString(algId)}")
                        if (hashId >= 0) mountLogger.log("PRF: ${VeraCryptEngine.hashIdToString(hashId)}")
                        mountLogger.log("Mounting FatFs virtual filesystem...")
                        repo.mountContainer(container.id, result.value, pim,
                            isHidden = isHidden, hasHidden = hasHidden,
                            dataSize = dataSize, parcelFd = pfd)
                        pfdConsumed = true
                        if (algId  >= 0) repo.updateAlgorithm(container.id, VeraCryptEngine.algorithmIdToString(algId))
                        if (hashId >= 0) repo.updatePrf(container.id, VeraCryptEngine.hashIdToString(hashId))
                        if (fsType >= 0) repo.updateFilesystem(container.id, VeraCryptEngine.filesystemIdToString(fsType))
                        mountLogger.log("Mount successful.")
                        lastMountTimeMillis = System.currentTimeMillis()
                        _mountState.value = MountState.Idle
                        onSuccess(container.id)
                    }
                    is CryptoResult.Failure -> {
                        mountLogger.log("ERROR: ${result.error.name}")
                        _mountState.value = when (result.error) {
                            CryptoError.IO_ERROR -> MountState.Error("Cannot open container file")
                            else -> MountState.Error("Wrong password")
                        }
                    }
                }
            } finally {
                if (!pfdConsumed) pfd?.close()
            }
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

    fun clearAddVaultResult() { _addVaultResult.value = null }

    // ── Bulk delete ────────────────────────────────────────────────────

    fun unmountContainer(id: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val handle = repo.getContainerHandle(id)
            if (handle != null) cryptoEngine.unmountContainer(handle)
            repo.unmountContainer(id)
            onDone()
        }
    }

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

    fun unmountContainersOnStop(isLocked: Boolean) {
        // Skip background unmounting if a container was just mounted — ProcessLifecycleOwner.onStop
        // can fire during the mount animation or navigation transition, causing the freshly-mounted
        // container to be unmounted immediately. Screen-off (isLocked=true) is not affected.
        if (!isLocked && System.currentTimeMillis() - lastMountTimeMillis < 3_000L) return
        viewModelScope.launch {
            repo.getAllContainersRaw().first().filter { it.isMounted }.forEach { c ->
                if (c.unmountOnBackground || (isLocked && c.unmountOnLock)) {
                    val handle = repo.getContainerHandle(c.id)
                    if (handle != null) cryptoEngine.unmountContainer(handle)
                    repo.unmountContainer(c.id)
                }
            }
        }
    }

    fun removeFromList(id: String) {
        viewModelScope.launch {
            val handle = repo.getContainerHandle(id)
            if (handle != null) cryptoEngine.unmountContainer(handle)
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
}
