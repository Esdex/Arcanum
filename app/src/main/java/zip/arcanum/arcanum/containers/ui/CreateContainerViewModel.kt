package zip.arcanum.arcanum.containers.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.containers.service.ContainerCreationParams
import zip.arcanum.arcanum.containers.service.ContainerCreationService
import zip.arcanum.core.utils.FileUtils
import zip.arcanum.crypto.CryptoResult
import zip.arcanum.crypto.KeyfileGenerator
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject
import kotlin.math.roundToInt

/** Base name for keyfiles made by the inline "generate new keyfile" action.
 *  A clash is resolved by the storage provider, never by overwriting. */
internal const val DEFAULT_GENERATED_KEYFILE_NAME = "arcanum-keyfile"

enum class VolumeType { STANDARD, HIDDEN }
enum class StorageLocation { APP_STORAGE, INTERNAL_STORAGE }
enum class CipherAlgorithm(val displayName: String, val description: String, val speed: AlgorithmSpeed) {
    AES("AES", "Best performance on modern hardware", AlgorithmSpeed.FAST),
    SERPENT("Serpent", "Conservative security margin", AlgorithmSpeed.MEDIUM),
    TWOFISH("Twofish", "Flexible key schedule design", AlgorithmSpeed.MEDIUM),
    CAMELLIA("Camellia", "ISO/IEC certified cipher", AlgorithmSpeed.MEDIUM),
    KUZNYECHIK("Kuznyechik", "Russian GOST standard", AlgorithmSpeed.MEDIUM),
    AES_TWOFISH("AES-Twofish", "Cascade: AES + Twofish", AlgorithmSpeed.SLOW),
    AES_TWOFISH_SERPENT("AES-Twofish-Serpent", "Cascade: three ciphers", AlgorithmSpeed.SLOW),
    SERPENT_AES("Serpent-AES", "Cascade: Serpent + AES", AlgorithmSpeed.SLOW),
    SERPENT_TWOFISH_AES("Serpent-Twofish-AES", "Cascade: three ciphers", AlgorithmSpeed.SLOW),
    TWOFISH_SERPENT("Twofish-Serpent", "Cascade: Twofish + Serpent", AlgorithmSpeed.SLOW),
    CAMELLIA_KUZNYECHIK("Camellia-Kuznyechik", "Cascade: two standards", AlgorithmSpeed.SLOW),
    CAMELLIA_SERPENT("Camellia-Serpent", "Cascade: Camellia + Serpent", AlgorithmSpeed.SLOW),
    KUZNYECHIK_AES("Kuznyechik-AES", "Cascade: GOST + AES", AlgorithmSpeed.SLOW),
    KUZNYECHIK_SERPENT_CAMELLIA("Kuznyechik-Serpent-Camellia", "Cascade: three ciphers", AlgorithmSpeed.SLOW),
    KUZNYECHIK_TWOFISH("Kuznyechik-Twofish", "Cascade: GOST + Twofish", AlgorithmSpeed.SLOW)
}
enum class AlgorithmSpeed { FAST, MEDIUM, SLOW, EXTREMELY_SLOW, PARANOIA }
enum class HashAlgorithm(val displayName: String) {
    SHA512("SHA-512"),
    SHA256("SHA-256"),
    WHIRLPOOL("Whirlpool"),
    STREEBOG("Streebog"),
    BLAKE2S("BLAKE2s-256")
}
enum class FilesystemType(
    val displayName: String,
    val maxFileSize: String,
    val description: String,
    val info: String
) {
    FAT32(
        displayName = "FAT",
        maxFileSize = "4 GB",
        description = "Recommended for most users",
        info        = "FAT is the most compatible filesystem. It works on Windows, macOS, and Linux without any additional software.\n\nLimitation: individual files cannot exceed 4 GB."
    ),
    EXFAT(
        displayName = "exFAT",
        maxFileSize = "16 EB",
        description = "Best for files larger than 4 GB (videos, disk images)",
        info        = "exFAT is a modern filesystem designed for flash storage. It supports files larger than 4 GB and works on all major operating systems."
    ),
}

data class CreateContainerState(
    val currentStep: Int = 1,
    val totalSteps: Int = 10,
    val volumeType: VolumeType = VolumeType.STANDARD,
    val location: StorageLocation = StorageLocation.INTERNAL_STORAGE,
    val filePath: String = "",
    val fileName: String = "vault.hc",
    val safUri: String = "",
    val includeInBackup: Boolean = false,
    val algorithm: CipherAlgorithm = CipherAlgorithm.AES,
    val hashAlgorithm: HashAlgorithm = HashAlgorithm.SHA512,
    val sizeMb: Long = 1024L,
    val password: String = "",
    val confirmPassword: String = "",
    val keyfilePaths: List<String> = emptyList(),
    val keyfileDisplayNames: List<String> = emptyList(),
    val quickFormat: Boolean = true,
    val filesystem: FilesystemType = FilesystemType.FAT32,
    val pim: Int = 0,
    val entropyPoints: Int = 0,
    /** Failure of the inline keyfile generator, kept apart from [error]: that
     *  one is only rendered on the hidden-volume creation screen, so a
     *  generation failure set there would never reach the user. */
    val keyfileError: String? = null,
    val creationProgress: Float = 0f,
    val creationSpeed: String = "",
    val creationTimeRemaining: String = "",
    val isCreating: Boolean = false,
    val isCreated: Boolean = false,
    val error: String? = null,
    // ── Hidden volume fields ──────────────────────────────────────────
    val hiddenSizeMb: Long = 0L,
    val hiddenPassword: String = "",
    val hiddenConfirmPassword: String = "",
    val hiddenAlgorithm: CipherAlgorithm = CipherAlgorithm.AES,
    val hiddenHashAlgorithm: HashAlgorithm = HashAlgorithm.SHA512,
    val hiddenKeyfilePaths: List<String> = emptyList(),
    val hiddenKeyfileDisplayNames: List<String> = emptyList(),
    val hiddenPim: Int = 0,
    val hiddenEntropyPoints: Int = 0,
    val isHiddenCreated: Boolean = false,
    val isExternalSd: Boolean = false
)

@HiltViewModel
class CreateContainerViewModel @Inject constructor(
    private val cryptoEngine: VeraCryptEngine,
    private val repo: ContainerRepository,
    private val creationParams: ContainerCreationParams,
    private val keyfileGenerator: KeyfileGenerator,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(CreateContainerState())
    val state = _state.asStateFlow()

    private val _createdContainerId = MutableStateFlow<String?>(null)
    val createdContainerId = _createdContainerId.asStateFlow()

    private val entropyBuffer         = mutableListOf<Byte>()
    private val hiddenEntropyBuffer   = mutableListOf<Byte>()
    private var safParcelFd: ParcelFileDescriptor? = null
    private val registrationStarted  = AtomicBoolean(false)

    val appStoragePath: String = context.noBackupFilesDir.absolutePath
    val appStoragePathWithBackup: String = context.filesDir.absolutePath

    init {
        _state.update { it.copy(filePath = context.noBackupFilesDir.absolutePath) }
    }

    fun update(transform: CreateContainerState.() -> CreateContainerState) =
        _state.update { it.transform() }

    fun nextStep() = _state.update { it.copy(currentStep = (it.currentStep + 1).coerceAtMost(it.totalSteps)) }
    fun prevStep() = _state.update { it.copy(currentStep = (it.currentStep - 1).coerceAtLeast(1)) }

    fun setSafUri(uri: Uri) {
        safParcelFd?.close()
        safParcelFd = null
        val normalizedUri = zip.arcanum.core.utils.FileUtils.normalizeSafUri(uri)
        val uriString = normalizedUri.toString()
        val displayName = context.contentResolver.query(
            normalizedUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: _state.value.fileName
        safParcelFd = context.contentResolver.openFileDescriptor(normalizedUri, "rw")
        _state.update { it.copy(safUri = uriString, fileName = displayName, isExternalSd = isRemovableSafUri(normalizedUri)) }
    }

    fun clearSafUri() {
        deletePendingSafFile()
        _state.update { it.copy(
            safUri      = "",
            isExternalSd = false,
            filePath    = if (it.includeInBackup) context.filesDir.absolutePath else context.noBackupFilesDir.absolutePath
        ) }
    }

    private fun isRemovableSafUri(uri: Uri): Boolean {
        if (uri.authority != "com.android.externalstorage.documents") return false
        val docId = runCatching { android.provider.DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return false
        return docId.substringBefore(":") != "primary"
    }

    // Call this BEFORE launching the file creator picker so the old 0-byte file is gone
    // by the time the picker opens — prevents the OS from appending " (1)" to the name.
    fun deletePendingSafFile() {
        val oldSafUri = _state.value.safUri
        safParcelFd?.close()
        safParcelFd = null
        if (oldSafUri.isNotEmpty()) {
            runCatching {
                android.provider.DocumentsContract.deleteDocument(
                    context.contentResolver, android.net.Uri.parse(oldSafUri)
                )
            }
            _state.update { it.copy(safUri = "") }
        }
    }

    fun setVolumeType(type: VolumeType) {
        _state.update { it.copy(
            volumeType = type,
            totalSteps = if (type == VolumeType.HIDDEN) 16 else 10
        ) }
    }

    fun addKeyfile(cachedPath: String, displayName: String) {
        _state.update { it.copy(
            keyfilePaths       = it.keyfilePaths + cachedPath,
            keyfileDisplayNames = it.keyfileDisplayNames + displayName
        ) }
    }

    fun removeKeyfile(index: Int) {
        val paths = _state.value.keyfilePaths.toMutableList()
        val names = _state.value.keyfileDisplayNames.toMutableList()
        if (index in paths.indices) {
            FileUtils.secureZeroAndDelete(java.io.File(paths[index]))
            paths.removeAt(index)
            names.removeAt(index)
        }
        _state.update { it.copy(keyfilePaths = paths, keyfileDisplayNames = names) }
    }

    fun clearKeyfiles() {
        _state.value.keyfilePaths.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
        _state.update { it.copy(keyfilePaths = emptyList(), keyfileDisplayNames = emptyList()) }
    }

    /**
     * Generates a fresh keyfile into the folder the user just picked and adds it
     * to the outer volume's list, so a vault can be created with a keyfile
     * without leaving the wizard.
     *
     * The default 64 bytes is the whole keyfile pool, so this is as strong as
     * anything the standalone generator produces; the wizard collects its own
     * entropy later, and plain urandom is already a CSPRNG. Use [hidden] for
     * the hidden-volume list.
     */
    fun generateKeyfile(treeUri: Uri, hidden: Boolean = false) {
        _state.update { it.copy(keyfileError = null) }
        viewModelScope.launch {
            val result = keyfileGenerator.generateOne(treeUri, DEFAULT_GENERATED_KEYFILE_NAME)
            result.fold(
                onSuccess = { generated ->
                    // Mirror the picked-keyfile path: creation consumes cached
                    // files, so the generated one is copied in the same way.
                    val cached = FileUtils.copyUriToCache(context, generated.uri)
                    if (cached == null) {
                        _state.update { it.copy(keyfileError = "Keyfile created but could not be read back") }
                        return@fold
                    }
                    if (hidden) addHiddenKeyfile(cached.first, generated.displayName)
                    else        addKeyfile(cached.first, generated.displayName)
                },
                onFailure = { e ->
                    _state.update { it.copy(keyfileError = e.message ?: "Failed to generate keyfile") }
                }
            )
        }
    }

    fun addEntropyPoint(x: Int, y: Int) {
        entropyBuffer.add(x.toByte())
        entropyBuffer.add(y.toByte())
        _state.update { it.copy(entropyPoints = it.entropyPoints + 1) }
    }

    fun addHiddenKeyfile(cachedPath: String, displayName: String) {
        _state.update { it.copy(
            hiddenKeyfilePaths        = it.hiddenKeyfilePaths + cachedPath,
            hiddenKeyfileDisplayNames = it.hiddenKeyfileDisplayNames + displayName
        ) }
    }

    fun removeHiddenKeyfile(index: Int) {
        val paths = _state.value.hiddenKeyfilePaths.toMutableList()
        val names = _state.value.hiddenKeyfileDisplayNames.toMutableList()
        if (index in paths.indices) {
            FileUtils.secureZeroAndDelete(java.io.File(paths[index]))
            paths.removeAt(index)
            names.removeAt(index)
        }
        _state.update { it.copy(hiddenKeyfilePaths = paths, hiddenKeyfileDisplayNames = names) }
    }

    fun addHiddenEntropyPoint(x: Int, y: Int) {
        hiddenEntropyBuffer.add(x.toByte())
        hiddenEntropyBuffer.add(y.toByte())
        _state.update { it.copy(hiddenEntropyPoints = it.hiddenEntropyPoints + 1) }
    }

    fun startHiddenCreation() {
        val s = _state.value
        _state.update { it.copy(isCreating = true, creationProgress = 0f, error = null) }
        val fullPath = if (s.safUri.isEmpty()) "${s.filePath.trimEnd('/')}/${s.fileName}" else ""
        viewModelScope.launch {
            val pfd = safParcelFd
            val result = try {
                if (pfd != null) {
                    cryptoEngine.createHiddenVolumeFd(
                        fd                  = pfd.fd,
                        hiddenSizeBytes     = s.hiddenSizeMb * 1024L * 1024L,
                        outerPassword       = s.password,
                        outerKeyfilePaths   = s.keyfilePaths,
                        outerPim            = s.pim,
                        hiddenPassword      = s.hiddenPassword,
                        hiddenKeyfilePaths  = s.hiddenKeyfilePaths,
                        hiddenPim           = s.hiddenPim,
                        hiddenAlgorithm     = s.hiddenAlgorithm.ordinal,
                        hiddenHashAlgorithm = s.hiddenHashAlgorithm.ordinal,
                        quickFormat         = true,
                        entropyBytes        = hiddenEntropyBuffer.toByteArray(),
                        progressListener    = null
                    )
                } else {
                    cryptoEngine.createHiddenVolume(
                        path                = fullPath,
                        hiddenSizeBytes     = s.hiddenSizeMb * 1024L * 1024L,
                        outerPassword       = s.password,
                        outerKeyfilePaths   = s.keyfilePaths,
                        outerPim            = s.pim,
                        hiddenPassword      = s.hiddenPassword,
                        hiddenKeyfilePaths  = s.hiddenKeyfilePaths,
                        hiddenPim           = s.hiddenPim,
                        hiddenAlgorithm     = s.hiddenAlgorithm.ordinal,
                        hiddenHashAlgorithm = s.hiddenHashAlgorithm.ordinal,
                        quickFormat         = true,
                        entropyBytes        = hiddenEntropyBuffer.toByteArray(),
                        progressListener    = null
                    )
                }
            } finally {
                // Always delete keyfile copies regardless of success, failure, or exception.
                // Outer keyfiles were preserved by the service (preserveKeyfiles=true) - delete here.
                s.keyfilePaths.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
                s.hiddenKeyfilePaths.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
                _state.update { it.copy(
                    keyfilePaths              = emptyList(),
                    keyfileDisplayNames       = emptyList(),
                    hiddenKeyfilePaths        = emptyList(),
                    hiddenKeyfileDisplayNames = emptyList()
                ) }
            }
            when (result) {
                is CryptoResult.Success -> {
                    // Session-only knowledge for the expand-volume guard; never persisted
                    // (would defeat plausible deniability).
                    _createdContainerId.value?.let { repo.markSessionHasHiddenVolume(it) }
                    _state.update { it.copy(
                        isCreating       = false,
                        isHiddenCreated  = true,
                        creationProgress = 1f,
                        currentStep      = 16
                    ) }
                }
                is CryptoResult.Failure -> _state.update { it.copy(
                    isCreating = false,
                    error      = "Hidden volume creation failed: ${result.error}"
                ) }
            }
        }
    }

    fun startCreation() {
        val s = _state.value
        _state.update { it.copy(isCreating = true, creationProgress = 0f) }

        // Clear any stale completed state from a previous run before subscribing.
        // ContainerCreationService.progress is a StateFlow (companion object), so a new
        // subscriber would immediately replay the last emitted value — which could be
        // isComplete=true from the previous container creation, triggering a spurious
        // registerCreatedContainer() call before the new file exists.
        ContainerCreationService.resetProgress()

        // Observe service progress
        viewModelScope.launch {
            ContainerCreationService.progress
                .filterNotNull()
                .collect { p ->
                    val secsLeft = if (p.speedMbps > 0f && p.totalBytes > 0L) {
                        val remMb = (p.totalBytes - p.bytesWritten) / 1_048_576f
                        (remMb / p.speedMbps).roundToInt().toLong()
                    } else 0L
                    _state.update { it.copy(
                        creationProgress      = p.fraction,
                        creationSpeed         = if (p.speedMbps > 0f) "${p.speedMbps.roundToInt()} MB/s" else "",
                        creationTimeRemaining = if (secsLeft > 0) formatTime(secsLeft) else "",
                        isCreating            = !p.isComplete,
                        isCreated             = p.isComplete && p.error == null,
                        currentStep           = if (p.isComplete && p.error == null) 10 else it.currentStep,
                        error                 = p.error
                    ) }
                }
        }

        // Pass all params (including password) through an in-process singleton — never via Intent.
        val fullPath = if (s.safUri.isEmpty()) "${s.filePath.trimEnd('/')}/${s.fileName}" else ""
        // Dup the SAF pfd so the service owns an independent fd — decouples service lifetime
        // from the ViewModel's pfd, eliminating the raw-fd race on ViewModel.onCleared().
        val servicePfd = safParcelFd?.dup()
        creationParams.set(ContainerCreationParams.Params(
            path             = fullPath,
            sizeBytes        = s.sizeMb * 1024L * 1024L,
            password         = s.password,
            algorithm        = s.algorithm.ordinal,
            hashAlgorithm    = s.hashAlgorithm.ordinal,
            filesystem       = s.filesystem.ordinal,
            quickFormat      = s.quickFormat,
            entropyBytes     = entropyBuffer.toByteArray(),
            keyfilePaths     = s.keyfilePaths,
            pim              = s.pim,
            safFd            = servicePfd?.fd ?: -1,
            safPfd           = servicePfd,
            preserveKeyfiles = s.volumeType == VolumeType.HIDDEN
        ))
        context.startForegroundService(Intent(context, ContainerCreationService::class.java))
    }

    fun registerCreatedContainer() {
        if (!registrationStarted.compareAndSet(false, true)) return
        val s = _state.value
        viewModelScope.launch {
            val id = if (s.safUri.isNotEmpty()) {
                val actualSize = context.contentResolver
                    .openFileDescriptor(android.net.Uri.parse(s.safUri), "r")
                    ?.use { it.statSize }
                repo.addContainerFromPath(
                    path   = "",
                    safUri = s.safUri,
                    name   = s.fileName.ifBlank { null },
                    size   = actualSize
                ).also {
                    // close the pfd only after the container is registered
                    safParcelFd?.close()
                    safParcelFd = null
                }
            } else {
                val fullPath = "${s.filePath.trimEnd('/')}/${s.fileName}"
                repo.addContainerFromPath(fullPath)
            }
            _createdContainerId.value = id
        }
    }

    fun cancelCreation() {
        context.stopService(Intent(context, ContainerCreationService::class.java))
        val s = _state.value
        safParcelFd?.close()
        safParcelFd = null
        s.keyfilePaths.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
        s.hiddenKeyfilePaths.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
        if (s.safUri.isEmpty()) {
            val fullPath = "${s.filePath.trimEnd('/')}/${s.fileName}"
            java.io.File(fullPath).delete()
        } else {
            runCatching {
                android.provider.DocumentsContract.deleteDocument(
                    context.contentResolver, android.net.Uri.parse(s.safUri)
                )
            }
        }
        _state.update { it.copy(
            isCreating                = false,
            currentStep               = 1,
            safUri                    = "",
            keyfilePaths              = emptyList(),
            keyfileDisplayNames       = emptyList(),
            hiddenKeyfilePaths        = emptyList(),
            hiddenKeyfileDisplayNames = emptyList()
        ) }
    }

    override fun onCleared() {
        super.onCleared()
        val s = _state.value
        s.keyfilePaths.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
        s.hiddenKeyfilePaths.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
        safParcelFd?.close()
        safParcelFd = null
        // The SAF picker creates a 0-byte placeholder the moment the user picks a location.
        // Delete it if the wizard was abandoned or creation never completed.
        if (s.safUri.isNotEmpty() && !s.isCreated && !s.isHiddenCreated) {
            runCatching {
                android.provider.DocumentsContract.deleteDocument(
                    context.contentResolver, android.net.Uri.parse(s.safUri)
                )
            }
        }
    }

    private fun formatTime(secs: Long): String = when {
        secs < 60 -> "~$secs seconds"
        else      -> "~${secs / 60} min ${secs % 60} sec"
    }
}
