package zip.arcanum.arcanum.containers.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.containers.service.ChangeKeyfileParams
import zip.arcanum.arcanum.containers.service.ChangeKeyfileService
import zip.arcanum.core.utils.FileUtils
import javax.inject.Inject

private const val ENTROPY_REQUIRED = 500

data class ChangeKeyfileState(
    val currentStep: Int = 1,
    val totalSteps: Int = 4,
    // Step 1 - credentials
    val password: String = "",
    val pim: Int = 0,
    val oldKeyfilePaths: List<String> = emptyList(),
    val oldKeyfileDisplayNames: List<String> = emptyList(),
    // Step 2 - new keyfiles
    val addKeyfilesEnabled: Boolean = true,
    val newKeyfilePaths: List<String> = emptyList(),
    val newKeyfileDisplayNames: List<String> = emptyList(),
    // Step 3 - entropy
    val entropyProgress: Float = 0f,
    // Step 4 - result
    val isRunning: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ChangeKeyfileViewModel @Inject constructor(
    private val repo: ContainerRepository,
    private val changeKeyfileParams: ChangeKeyfileParams,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(ChangeKeyfileState())
    val state = _state.asStateFlow()

    private var containerPath: String = ""
    private var safUri: String = ""
    // PRF is always the existing volume's hash — user cannot change it (VeraCrypt: enablePkcs5Prf=false)
    private var containerHashAlgorithm: HashAlgorithm = HashAlgorithm.SHA512

    private val collectedEntropy: ByteArray = ByteArray(ENTROPY_REQUIRED * 2)
    private var entropyIndex: Int = 0

    fun init(containerId: String) {
        viewModelScope.launch {
            val c = repo.getContainerById(containerId) ?: return@launch
            containerPath          = c.path
            safUri                 = c.safUri
            containerHashAlgorithm = HashAlgorithm.entries.firstOrNull { it.displayName == c.prf }
                ?: HashAlgorithm.SHA512
        }
    }

    fun update(block: ChangeKeyfileState.() -> ChangeKeyfileState) =
        _state.update { it.block() }

    fun nextStep() = _state.update { it.copy(currentStep = (it.currentStep + 1).coerceAtMost(it.totalSteps)) }
    fun prevStep() = _state.update { it.copy(currentStep = (it.currentStep - 1).coerceAtLeast(1)) }

    // ── Old Keyfiles ──────────────────────────────────────────────────────

    fun addOldKeyfile(cachedPath: String, displayName: String) =
        _state.update { it.copy(
            oldKeyfilePaths        = it.oldKeyfilePaths + cachedPath,
            oldKeyfileDisplayNames = it.oldKeyfileDisplayNames + displayName
        ) }

    fun removeOldKeyfile(index: Int) {
        val paths = _state.value.oldKeyfilePaths.toMutableList()
        val names = _state.value.oldKeyfileDisplayNames.toMutableList()
        if (index in paths.indices) {
            FileUtils.secureZeroAndDelete(java.io.File(paths[index]))
            paths.removeAt(index); names.removeAt(index)
        }
        _state.update { it.copy(oldKeyfilePaths = paths, oldKeyfileDisplayNames = names) }
    }

    // ── New Keyfiles ──────────────────────────────────────────────────────

    fun addNewKeyfile(cachedPath: String, displayName: String) =
        _state.update { it.copy(
            newKeyfilePaths        = it.newKeyfilePaths + cachedPath,
            newKeyfileDisplayNames = it.newKeyfileDisplayNames + displayName
        ) }

    fun removeNewKeyfile(index: Int) {
        val paths = _state.value.newKeyfilePaths.toMutableList()
        val names = _state.value.newKeyfileDisplayNames.toMutableList()
        if (index in paths.indices) {
            FileUtils.secureZeroAndDelete(java.io.File(paths[index]))
            paths.removeAt(index); names.removeAt(index)
        }
        _state.update { it.copy(newKeyfilePaths = paths, newKeyfileDisplayNames = names) }
    }

    fun toggleAddKeyfiles(enabled: Boolean) {
        if (!enabled) {
            _state.value.newKeyfilePaths.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
            _state.update { it.copy(
                addKeyfilesEnabled     = false,
                newKeyfilePaths        = emptyList(),
                newKeyfileDisplayNames = emptyList()
            ) }
        } else {
            _state.update { it.copy(addKeyfilesEnabled = true) }
        }
    }

    // ── Entropy ───────────────────────────────────────────────────────────

    fun addEntropyPoint(x: Int, y: Int) {
        if (entropyIndex >= ENTROPY_REQUIRED * 2) return
        collectedEntropy[entropyIndex++] = x.toByte()
        collectedEntropy[entropyIndex++] = y.toByte()
        val progress = (entropyIndex / 2f / ENTROPY_REQUIRED).coerceAtMost(1f)
        _state.update { it.copy(entropyProgress = progress) }
    }

    // ── Start ─────────────────────────────────────────────────────────────

    fun startChange() {
        if (_state.value.isRunning) return
        val s = _state.value
        _state.update { it.copy(isRunning = true, error = null, currentStep = 4) }

        val pfd = if (safUri.isNotEmpty())
            context.contentResolver.openFileDescriptor(Uri.parse(safUri), "rw")
        else null

        val effectiveNewKeyfiles = if (s.addKeyfilesEnabled) s.newKeyfilePaths else emptyList()

        changeKeyfileParams.set(ChangeKeyfileParams.Params(
            path             = containerPath,
            safFd            = pfd?.fd ?: -1,
            safPfd           = pfd,
            password         = s.password,
            oldKeyfilePaths  = s.oldKeyfilePaths,
            pim              = s.pim,
            newKeyfilePaths  = effectiveNewKeyfiles,
            newHashAlgorithm = containerHashAlgorithm.ordinal,
            extraEntropy     = collectedEntropy.copyOf(entropyIndex)
        ))

        _state.update { it.copy(
            oldKeyfilePaths = emptyList(), oldKeyfileDisplayNames = emptyList(),
            newKeyfilePaths = emptyList(), newKeyfileDisplayNames = emptyList()
        ) }

        try {
            context.startForegroundService(
                Intent(context, ChangeKeyfileService::class.java)
            )
        } catch (e: Exception) {
            val residual = changeKeyfileParams.take()
            residual?.safPfd?.close()
            residual?.oldKeyfilePaths?.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
            residual?.newKeyfilePaths?.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
            residual?.extraEntropy?.fill(0)
            _state.update { it.copy(isRunning = false, error = e.message ?: "Failed to start service") }
            return
        }

        viewModelScope.launch {
            ChangeKeyfileService.state.collect { svcState ->
                when (svcState) {
                    is ChangeKeyfileService.State.Success -> {
                        _state.update { it.copy(isRunning = false, isSuccess = true) }
                        ChangeKeyfileService.reset()
                    }
                    is ChangeKeyfileService.State.Failure -> {
                        _state.update { it.copy(isRunning = false, error = svcState.error) }
                        ChangeKeyfileService.reset()
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        val s = _state.value
        s.oldKeyfilePaths.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
        s.newKeyfilePaths.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
        collectedEntropy.fill(0)
    }
}
