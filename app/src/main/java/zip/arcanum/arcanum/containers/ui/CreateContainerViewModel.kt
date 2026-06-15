package zip.arcanum.arcanum.containers.ui

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.containers.service.ContainerCreationService
import zip.arcanum.core.premium.PremiumManager
import zip.arcanum.crypto.CryptoResult
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject
import kotlin.math.roundToInt

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
enum class AlgorithmSpeed { FAST, MEDIUM, SLOW }
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
    NTFS(
        displayName = "NTFS",
        maxFileSize = "16 TB",
        description = "Best for Windows-only use",
        info        = "NTFS is Windows' native filesystem. On macOS it is read-only by default. On Linux it requires the ntfs-3g driver, which is usually pre-installed."
    ),
    EXT2(
        displayName = "Ext2",
        maxFileSize = "2 TB",
        description = "Best for Linux-only use",
        info        = "Ext2 is a Linux filesystem. It is not supported on Windows or macOS without third-party software. Choose this only if you use Linux exclusively."
    )
}

data class CreateContainerState(
    val currentStep: Int = 1,
    val totalSteps: Int = 10,
    val volumeType: VolumeType = VolumeType.STANDARD,
    val location: StorageLocation = StorageLocation.APP_STORAGE,
    val filePath: String = "",
    val fileName: String = "vault.hc",
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
    val creationProgress: Float = 0f,
    val creationSpeed: String = "",
    val creationTimeRemaining: String = "",
    val isCreating: Boolean = false,
    val isCreated: Boolean = false,
    val error: String? = null,
    // ── Hidden volume fields ──────────────────────────────────────────
    val hiddenSizeMb: Long = 100L,
    val hiddenPassword: String = "",
    val hiddenConfirmPassword: String = "",
    val hiddenAlgorithm: CipherAlgorithm = CipherAlgorithm.AES,
    val hiddenHashAlgorithm: HashAlgorithm = HashAlgorithm.SHA512,
    val hiddenKeyfilePaths: List<String> = emptyList(),
    val hiddenKeyfileDisplayNames: List<String> = emptyList(),
    val hiddenPim: Int = 0,
    val hiddenEntropyPoints: Int = 0,
    val isHiddenCreated: Boolean = false
)

@HiltViewModel
class CreateContainerViewModel @Inject constructor(
    val premiumManager: PremiumManager,
    private val cryptoEngine: VeraCryptEngine,
    private val repo: ContainerRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(CreateContainerState())
    val state = _state.asStateFlow()

    private val _createdContainerId = MutableStateFlow<String?>(null)
    val createdContainerId = _createdContainerId.asStateFlow()

    private val entropyBuffer       = mutableListOf<Byte>()
    private val hiddenEntropyBuffer = mutableListOf<Byte>()

    val appStoragePath: String = context.filesDir.absolutePath

    init {
        _state.update { it.copy(filePath = context.filesDir.absolutePath) }
    }

    fun update(transform: CreateContainerState.() -> CreateContainerState) =
        _state.update { it.transform() }

    fun nextStep() = _state.update { it.copy(currentStep = (it.currentStep + 1).coerceAtMost(it.totalSteps)) }
    fun prevStep() = _state.update { it.copy(currentStep = (it.currentStep - 1).coerceAtLeast(1)) }

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
            java.io.File(paths[index]).delete()
            paths.removeAt(index)
            names.removeAt(index)
        }
        _state.update { it.copy(keyfilePaths = paths, keyfileDisplayNames = names) }
    }

    fun clearKeyfiles() {
        _state.value.keyfilePaths.forEach { java.io.File(it).delete() }
        _state.update { it.copy(keyfilePaths = emptyList(), keyfileDisplayNames = emptyList()) }
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
            java.io.File(paths[index]).delete()
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
        val fullPath = "${s.filePath.trimEnd('/')}/${s.fileName}"
        viewModelScope.launch {
            val result = cryptoEngine.createHiddenVolume(
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
            when (result) {
                is CryptoResult.Success -> _state.update { it.copy(
                    isCreating       = false,
                    isHiddenCreated  = true,
                    creationProgress = 1f,
                    currentStep      = 16
                ) }
                is CryptoResult.Failure -> _state.update { it.copy(
                    isCreating = false,
                    error      = "Hidden volume creation failed: ${result.error}"
                ) }
            }
            s.hiddenKeyfilePaths.forEach { java.io.File(it).delete() }
        }
    }

    fun startCreation() {
        val s = _state.value
        _state.update { it.copy(isCreating = true, creationProgress = 0f) }

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

        // Start the foreground service
        val fullPath = "${s.filePath.trimEnd('/')}/${s.fileName}"
        val intent = Intent(context, ContainerCreationService::class.java).apply {
            putExtra(ContainerCreationService.EXTRA_PATH,         fullPath)
            putExtra(ContainerCreationService.EXTRA_SIZE_BYTES,   s.sizeMb * 1024L * 1024L)
            putExtra(ContainerCreationService.EXTRA_PASSWORD,     s.password)
            putExtra(ContainerCreationService.EXTRA_ALGORITHM,    s.algorithm.ordinal)
            putExtra(ContainerCreationService.EXTRA_HASH_ALG,     s.hashAlgorithm.ordinal)
            putExtra(ContainerCreationService.EXTRA_FILESYSTEM,   s.filesystem.ordinal)
            putExtra(ContainerCreationService.EXTRA_QUICK_FORMAT, s.quickFormat)
            putExtra(ContainerCreationService.EXTRA_ENTROPY,      entropyBuffer.toByteArray())
            putExtra(ContainerCreationService.EXTRA_PIM,          s.pim)
            if (s.keyfilePaths.isNotEmpty())
                putStringArrayListExtra(ContainerCreationService.EXTRA_KEYFILE_PATHS, ArrayList(s.keyfilePaths))
        }
        context.startForegroundService(intent)
    }

    fun registerCreatedContainer() {
        if (_createdContainerId.value != null) return
        val s = _state.value
        val fullPath = "${s.filePath.trimEnd('/')}/${s.fileName}"
        viewModelScope.launch {
            val id = repo.addContainerFromPath(fullPath)
            _createdContainerId.value = id
        }
    }

    fun cancelCreation() {
        context.stopService(Intent(context, ContainerCreationService::class.java))
        val s = _state.value
        val fullPath = "${s.filePath.trimEnd('/')}/${s.fileName}"
        java.io.File(fullPath).delete()
        _state.update { it.copy(isCreating = false, currentStep = 1) }
    }

    override fun onCleared() {
        super.onCleared()
        _state.value.keyfilePaths.forEach { java.io.File(it).delete() }
        _state.value.hiddenKeyfilePaths.forEach { java.io.File(it).delete() }
    }

    private fun formatTime(secs: Long): String = when {
        secs < 60 -> "~$secs seconds"
        else      -> "~${secs / 60} min ${secs % 60} sec"
    }
}
