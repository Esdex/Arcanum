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
import zip.arcanum.crypto.KeyfileGenerator
import javax.inject.Inject

private const val ENTROPY_REQUIRED = 500

data class ChangeKeyfileState(
    val currentStep: Int = 1,
    val totalSteps: Int = 4,
    // Step 1 - credentials
    val password: String = "",
    val pim: Int = 0,
    val oldKeyfileData: List<ByteArray> = emptyList(),
    val oldKeyfileDisplayNames: List<String> = emptyList(),
    // Step 2 - new keyfiles
    val addKeyfilesEnabled: Boolean = true,
    val newKeyfileData: List<ByteArray> = emptyList(),
    val newKeyfileDisplayNames: List<String> = emptyList(),
    // Step 3 - entropy
    val entropyProgress: Float = 0f,
    // Step 4 - result
    val isRunning: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null,
    /** Failure of the inline keyfile generator, kept apart from [error]: that
     *  one gates startChange() and is only rendered on the result step, so a
     *  generation failure there would silently block the whole operation. */
    val keyfileError: String? = null
)

@HiltViewModel
class ChangeKeyfileViewModel @Inject constructor(
    private val repo: ContainerRepository,
    private val changeKeyfileParams: ChangeKeyfileParams,
    private val keyfileGenerator: KeyfileGenerator,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(ChangeKeyfileState())
    val state = _state.asStateFlow()

    private var containerId: String = ""
    private var containerPath: String = ""
    private var safUri: String = ""
    // PRF is always the existing volume's hash — user cannot change it (VeraCrypt: enablePkcs5Prf=false)
    private var containerHashAlgorithm: HashAlgorithm = HashAlgorithm.SHA512

    private val collectedEntropy: ByteArray = ByteArray(ENTROPY_REQUIRED * 2)
    private var entropyIndex: Int = 0

    fun init(id: String) {
        containerId = id
        viewModelScope.launch {
            val c = repo.getContainerById(id) ?: return@launch
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

    fun addOldKeyfile(bytes: ByteArray, displayName: String) =
        _state.update { it.copy(
            oldKeyfileData        = it.oldKeyfileData + bytes,
            oldKeyfileDisplayNames = it.oldKeyfileDisplayNames + displayName
        ) }

    fun removeOldKeyfile(index: Int) {
        val paths = _state.value.oldKeyfileData.toMutableList()
        val names = _state.value.oldKeyfileDisplayNames.toMutableList()
        if (index in paths.indices) {
            paths[index].fill(0)
            paths.removeAt(index); names.removeAt(index)
        }
        _state.update { it.copy(oldKeyfileData = paths, oldKeyfileDisplayNames = names) }
    }

    // ── New Keyfiles ──────────────────────────────────────────────────────

    fun addNewKeyfile(bytes: ByteArray, displayName: String) =
        _state.update { it.copy(
            newKeyfileData        = it.newKeyfileData + bytes,
            newKeyfileDisplayNames = it.newKeyfileDisplayNames + displayName
        ) }

    /**
     * Generates a fresh keyfile into the folder the user just picked and adds it
     * to the NEW keyfile list. Only offered for the new set: the old one has to
     * match what the volume was created with, where a random file is useless.
     */
    fun generateNewKeyfile(treeUri: Uri) {
        _state.update { it.copy(keyfileError = null) }
        viewModelScope.launch {
            keyfileGenerator.generateOne(treeUri, DEFAULT_GENERATED_KEYFILE_NAME).fold(
                onSuccess = { generated ->
                    val cached = FileUtils.readKeyfileBytes(context, generated.uri)
                    if (cached == null) {
                        _state.update { it.copy(keyfileError = "Keyfile created but could not be read back") }
                        return@fold
                    }
                    addNewKeyfile(cached.first, generated.displayName)
                },
                onFailure = { e ->
                    _state.update { it.copy(keyfileError = e.message ?: "Failed to generate keyfile") }
                }
            )
        }
    }

    fun removeNewKeyfile(index: Int) {
        val paths = _state.value.newKeyfileData.toMutableList()
        val names = _state.value.newKeyfileDisplayNames.toMutableList()
        if (index in paths.indices) {
            paths[index].fill(0)
            paths.removeAt(index); names.removeAt(index)
        }
        _state.update { it.copy(newKeyfileData = paths, newKeyfileDisplayNames = names) }
    }

    fun toggleAddKeyfiles(enabled: Boolean) {
        if (!enabled) {
            _state.value.newKeyfileData.forEach { it.fill(0) }
            _state.update { it.copy(
                addKeyfilesEnabled     = false,
                newKeyfileData        = emptyList(),
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
        if (repo.getContainerHandle(containerId) != null) {
            _state.update { it.copy(error = "Unmount the vault before changing its keyfiles") }
            return
        }
        val s = _state.value
        _state.update { it.copy(isRunning = true, error = null, currentStep = 4) }

        val pfd = if (safUri.isNotEmpty())
            context.contentResolver.openFileDescriptor(Uri.parse(safUri), "rw")
        else null

        val effectiveNewKeyfiles = if (s.addKeyfilesEnabled) s.newKeyfileData else emptyList()

        changeKeyfileParams.set(ChangeKeyfileParams.Params(
            path             = containerPath,
            safFd            = pfd?.fd ?: -1,
            safPfd           = pfd,
            password         = s.password,
            // Copies - see the note in CreateContainerViewModel: the service
            // outlives this ViewModel and both zero what they hold.
            oldKeyfileData  = s.oldKeyfileData.map { it.copyOf() },
            pim              = s.pim,
            newKeyfileData  = effectiveNewKeyfiles.map { it.copyOf() },
            newHashAlgorithm = containerHashAlgorithm.ordinal,
            extraEntropy     = collectedEntropy.copyOf(entropyIndex)
        ))

        _state.update { it.copy(
            oldKeyfileData = emptyList(), oldKeyfileDisplayNames = emptyList(),
            newKeyfileData = emptyList(), newKeyfileDisplayNames = emptyList()
        ) }

        try {
            context.startForegroundService(
                Intent(context, ChangeKeyfileService::class.java)
            )
        } catch (e: Exception) {
            val residual = changeKeyfileParams.take()
            residual?.safPfd?.close()
            residual?.oldKeyfileData?.forEach { it.fill(0) }
            residual?.newKeyfileData?.forEach { it.fill(0) }
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
        s.oldKeyfileData.forEach { it.fill(0) }
        s.newKeyfileData.forEach { it.fill(0) }
        collectedEntropy.fill(0)
    }
}
