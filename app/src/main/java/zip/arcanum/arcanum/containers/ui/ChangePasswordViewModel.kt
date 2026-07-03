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
import zip.arcanum.arcanum.containers.service.ChangePasswordParams
import zip.arcanum.arcanum.containers.service.ChangePasswordService
import zip.arcanum.core.security.VaultPasswordPolicy
import zip.arcanum.core.utils.FileUtils
import javax.inject.Inject

private const val ENTROPY_REQUIRED = 500

enum class WipeMode(val passCount: Int) {
    PASS_1(1),
    PASS_3(3),
    PASS_7(7),
    PASS_35(35),
    PASS_256(256)
}

data class ChangePasswordState(
    val currentStep: Int = 1,
    val totalSteps: Int = 5,
    // Step 1 - current credentials
    val oldPassword: String = "",
    val oldPim: Int = 0,
    val oldKeyfilePaths: List<String> = emptyList(),
    val oldKeyfileDisplayNames: List<String> = emptyList(),
    // Step 2 - new credentials
    val newPassword: String = "",
    val newConfirmPassword: String = "",
    val newPim: Int = 0,
    val newKeyfilePaths: List<String> = emptyList(),
    val newKeyfileDisplayNames: List<String> = emptyList(),
    val newHashAlgorithm: HashAlgorithm = HashAlgorithm.SHA512,
    // Step 3 - entropy
    val entropyProgress: Float = 0f,
    // Step 4 - wipe mode
    val wipeMode: WipeMode = WipeMode.PASS_3,
    // Step 5 - result
    val isRunning: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ChangePasswordViewModel @Inject constructor(
    private val repo: ContainerRepository,
    private val changePasswordParams: ChangePasswordParams,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(ChangePasswordState())
    val state = _state.asStateFlow()

    private var containerId: String = ""
    private var containerPath: String = ""
    private var safUri: String = ""

    private val collectedEntropy: ByteArray = ByteArray(ENTROPY_REQUIRED * 2)
    private var entropyIndex: Int = 0

    fun addEntropyPoint(x: Int, y: Int) {
        if (entropyIndex >= ENTROPY_REQUIRED * 2) return
        collectedEntropy[entropyIndex++] = x.toByte()
        collectedEntropy[entropyIndex++] = y.toByte()
        val progress = (entropyIndex / 2f / ENTROPY_REQUIRED).coerceAtMost(1f)
        _state.update { it.copy(entropyProgress = progress) }
    }

    fun init(id: String) {
        containerId = id
        viewModelScope.launch {
            val c = repo.getContainerById(id) ?: return@launch
            containerPath = c.path
            safUri        = c.safUri
        }
    }

    fun update(block: ChangePasswordState.() -> ChangePasswordState) =
        _state.update { it.block() }

    fun nextStep() = _state.update { it.copy(currentStep = (it.currentStep + 1).coerceAtMost(it.totalSteps)) }
    fun prevStep() = _state.update { it.copy(currentStep = (it.currentStep - 1).coerceAtLeast(1)) }

    // ── Keyfiles ──────────────────────────────────────────────────────────

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

    // ── Start ─────────────────────────────────────────────────────────────

    fun startChange() {
        if (_state.value.isRunning) return
        if (repo.getContainerHandle(containerId) != null) {
            _state.update { it.copy(error = "Unmount the vault before changing its password") }
            return
        }
        val s = _state.value
        if (!VaultPasswordPolicy.isWithinVeraCryptLimit(s.oldPassword) ||
            !VaultPasswordPolicy.isWithinVeraCryptLimit(s.newPassword)
        ) {
            _state.update { it.copy(error = VaultPasswordPolicy.violationMessage()) }
            return
        }
        if (VaultPasswordPolicy.hasUnsafeLowPim(s.newPassword, s.newPim)) {
            _state.update { it.copy(error = VaultPasswordPolicy.lowPimViolationMessage()) }
            return
        }
        _state.update { it.copy(isRunning = true, error = null, currentStep = 5) }

        // Build SAF fd if needed
        val pfd = if (safUri.isNotEmpty())
            context.contentResolver.openFileDescriptor(Uri.parse(safUri), "rw")
        else null

        changePasswordParams.set(ChangePasswordParams.Params(
            path             = containerPath,
            safFd            = pfd?.fd ?: -1,
            safPfd           = pfd,
            oldPassword      = s.oldPassword,
            oldKeyfilePaths  = s.oldKeyfilePaths,
            oldPim           = s.oldPim,
            newPassword      = s.newPassword,
            newKeyfilePaths  = s.newKeyfilePaths,
            newHashAlgorithm = s.newHashAlgorithm.ordinal,
            newPim           = s.newPim,
            wipePassCount    = s.wipeMode.passCount,
            extraEntropy     = collectedEntropy.copyOf(entropyIndex)
        ))

        // Clear keyfile paths from state — service owns them now
        _state.update { it.copy(
            oldKeyfilePaths = emptyList(), oldKeyfileDisplayNames = emptyList(),
            newKeyfilePaths = emptyList(), newKeyfileDisplayNames = emptyList()
        ) }

        try {
            context.startForegroundService(
                Intent(context, ChangePasswordService::class.java)
            )
        } catch (e: Exception) {
            // Service failed to start — recover params and surface error
            val residual = changePasswordParams.take()
            residual?.safPfd?.close()
            residual?.oldKeyfilePaths?.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
            residual?.newKeyfilePaths?.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
            residual?.extraEntropy?.fill(0)
            _state.update { it.copy(isRunning = false, error = e.message ?: "Failed to start service") }
            return
        }

        // Observe service result
        viewModelScope.launch {
            ChangePasswordService.state.collect { svcState ->
                when (svcState) {
                    is ChangePasswordService.State.Success -> {
                        _state.update { it.copy(isRunning = false, isSuccess = true) }
                        ChangePasswordService.reset()
                    }
                    is ChangePasswordService.State.Failure -> {
                        _state.update { it.copy(isRunning = false, error = svcState.error) }
                        ChangePasswordService.reset()
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // If VM is cleared before service finishes, keyfiles are already with the service.
        // Clean up any residual keyfiles still in state (edge case: cleared before startChange).
        val s = _state.value
        s.oldKeyfilePaths.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
        s.newKeyfilePaths.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
        collectedEntropy.fill(0)
    }
}
