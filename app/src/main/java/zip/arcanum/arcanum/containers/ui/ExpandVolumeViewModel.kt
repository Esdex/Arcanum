package zip.arcanum.arcanum.containers.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.containers.service.ExpandVolumeParams
import zip.arcanum.arcanum.containers.service.ExpandVolumeService
import zip.arcanum.core.utils.FileUtils
import java.io.File
import javax.inject.Inject

enum class SizeUnit { MB, GB }

data class ExpandVolumeState(
    val password: String = "",
    val pim: Int = 0,
    val keyfileData: List<ByteArray> = emptyList(),
    val keyfileDisplayNames: List<String> = emptyList(),
    val newSizeInput: String = "",
    val sizeUnit: SizeUnit = SizeUnit.GB,
    val isReady: Boolean = false,   // M3: true once container info is loaded from DB
    // Session knowledge says this container holds a hidden volume — expansion is blocked
    // outright (it would overwrite the hidden volume's data and backup header).
    val blockedByHidden: Boolean = false,
    // User's answer to "does this container contain a hidden volume?" — VeraCrypt-style
    // mandatory question; null = unanswered, true = yes (blocked), false = no (may proceed).
    // Deliberately not persisted: see ContainerRepository.sessionHasHiddenVolume.
    val hiddenAnswer: Boolean? = null,
    val availableSpaceMb: Long = Long.MAX_VALUE,
    val isRunning: Boolean = false,
    val progress: Float = 0f,
    val speedMbps: Float = 0f,
    val isSuccess: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ExpandVolumeViewModel @Inject constructor(
    private val repo: ContainerRepository,
    private val expandVolumeParams: ExpandVolumeParams,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(ExpandVolumeState())
    val state = _state.asStateFlow()

    private var containerId: String = ""
    private var containerPath: String = ""
    private var safUri: String = ""
    private var pendingNewSizeBytes: Long = 0L
    var currentFileSizeBytes: Long = 0L
        private set

    // M2: single collector job, cancelled and replaced on every new expand
    private var collectJob: Job? = null

    fun init(id: String) {
        containerId = id
        viewModelScope.launch {
            val c = repo.getContainerById(id) ?: return@launch
            containerPath = c.path
            safUri        = c.safUri
            // Total file size = data area size + two header groups (262144 bytes)
            currentFileSizeBytes = c.size + 262144L
            val statPath = when {
                c.path.isNotBlank() -> File(c.path).parent ?: c.path
                c.safUri.isNotBlank() -> Environment.getExternalStorageDirectory().absolutePath
                else -> null
            }
            val avail = statPath?.let {
                try { StatFs(it).availableBytes / (1024L * 1024L) } catch (_: Exception) { Long.MAX_VALUE }
            } ?: Long.MAX_VALUE
            _state.update { it.copy(
                isReady          = true,
                availableSpaceMb = avail,
                blockedByHidden  = repo.isKnownToContainHiddenVolume(id)
            ) }
        }
    }

    fun update(block: ExpandVolumeState.() -> ExpandVolumeState) =
        _state.update { it.block() }

    fun addKeyfile(bytes: ByteArray, name: String) {
        _state.update { it.copy(
            keyfileData        = it.keyfileData + bytes,
            keyfileDisplayNames = it.keyfileDisplayNames + name
        ) }
    }

    fun removeKeyfile(index: Int) {
        val paths = _state.value.keyfileData.toMutableList()
        val names = _state.value.keyfileDisplayNames.toMutableList()
        if (index in paths.indices) {
            paths[index].fill(0)
            paths.removeAt(index)
            names.removeAt(index)
        }
        _state.update { it.copy(keyfileData = paths, keyfileDisplayNames = names) }
    }

    fun startExpand() {
        val current = _state.value
        if (current.isRunning) return
        if (!current.isReady) return  // M3: DB init not yet complete, size validation would be wrong

        // Hidden-volume guard: expansion overwrites the file tail (encrypted fill + new
        // backup header), destroying any hidden volume. Native detects only legacy
        // containers that carry field28; for new-format containers we rely on session
        // knowledge plus the user's explicit answer, so refuse unless the answer is "no".
        if (current.blockedByHidden || current.hiddenAnswer != false) {
            _state.update { it.copy(error = "expand_has_hidden") }
            return
        }

        val inputVal = current.newSizeInput.toLongOrNull() ?: 0L
        val newSizeBytes = when (current.sizeUnit) {
            SizeUnit.MB -> inputVal * 1024L * 1024L
            SizeUnit.GB -> inputVal * 1024L * 1024L * 1024L
        }

        if (newSizeBytes < currentFileSizeBytes + 65536L) {
            _state.update { it.copy(error = "expand_too_small") }
            return
        }
        if (newSizeBytes % 512 != 0L) {
            _state.update { it.copy(error = "expand_not_aligned") }
            return
        }

        _state.update { it.copy(isRunning = true, error = null, progress = 0f, speedMbps = 0f) }

        val pfd = if (safUri.isNotEmpty())
            context.contentResolver.openFileDescriptor(Uri.parse(safUri), "rw")
        else null

        pendingNewSizeBytes = newSizeBytes

        expandVolumeParams.set(ExpandVolumeParams.Params(
            containerId  = containerId,
            path         = containerPath,
            safFd        = pfd?.fd ?: -1,
            safPfd       = pfd,
            password     = current.password,
            // Copies - see the note in CreateContainerViewModel: the service
            // outlives this ViewModel and both zero what they hold.
            keyfileData = current.keyfileData.map { it.copyOf() },
            pim          = current.pim,
            newSizeBytes = newSizeBytes
        ))

        // M1: clear password + keyfile references from state as soon as they're handed to the service
        _state.update { it.copy(password = "", keyfileData = emptyList(), keyfileDisplayNames = emptyList()) }

        try {
            context.startForegroundService(Intent(context, ExpandVolumeService::class.java))
        } catch (e: Exception) {
            val residual = expandVolumeParams.take()
            residual?.safPfd?.close()
            residual?.keyfileData?.forEach { it.fill(0) }
            _state.update { it.copy(isRunning = false, error = e.message ?: "Failed to start service") }
            return
        }

        // M2: cancel any previous collector before launching a new one
        collectJob?.cancel()
        collectJob = viewModelScope.launch {
            ExpandVolumeService.state.collect { svcState ->
                when (svcState) {
                    is ExpandVolumeService.State.Running -> {
                        _state.update { it.copy(
                            progress  = svcState.progress,
                            speedMbps = svcState.speedMbps
                        ) }
                    }
                    is ExpandVolumeService.State.Success -> {
                        // M4: DB size update now happens in the service; only update in-memory value here
                        currentFileSizeBytes = pendingNewSizeBytes
                        _state.update { it.copy(isRunning = false, isSuccess = true) }
                        ExpandVolumeService.reset()
                    }
                    is ExpandVolumeService.State.Failure -> {
                        _state.update { it.copy(isRunning = false, error = svcState.error) }
                        ExpandVolumeService.reset()
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        _state.value.keyfileData.forEach { it.fill(0) }
        // M1: drop password reference from heap
        _state.update { it.copy(password = "") }
        // M5: release any params the service was killed before consuming (fd + keyfile copies)
        expandVolumeParams.clear()
    }
}
