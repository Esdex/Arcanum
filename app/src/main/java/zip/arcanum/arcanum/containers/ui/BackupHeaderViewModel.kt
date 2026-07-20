package zip.arcanum.arcanum.containers.ui

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.core.utils.FileUtils
import zip.arcanum.crypto.CryptoResult
import zip.arcanum.crypto.VeraCryptEngine
import java.io.File
import javax.inject.Inject

data class BackupHeaderState(
    val password: String = "",
    val showPassword: Boolean = false,
    val pim: Int = 0,
    val keyfileData: List<ByteArray> = emptyList(),
    val keyfileDisplayNames: List<String> = emptyList(),
    val outputUri: String = "",
    val outputFileName: String = "",
    val isRunning: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class BackupHeaderViewModel @Inject constructor(
    private val repo: ContainerRepository,
    private val engine: VeraCryptEngine,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(BackupHeaderState())
    val state = _state.asStateFlow()

    private var containerId: String = ""
    private var containerPath: String = ""
    private var safUri: String = ""

    fun init(id: String) {
        containerId = id
        viewModelScope.launch {
            val c = repo.getContainerById(id) ?: return@launch
            containerPath = c.path
            safUri        = c.safUri
        }
    }

    fun update(block: BackupHeaderState.() -> BackupHeaderState) =
        _state.update { it.block() }

    fun addKeyfile(bytes: ByteArray, displayName: String) =
        _state.update { it.copy(
            keyfileData        = it.keyfileData + bytes,
            keyfileDisplayNames = it.keyfileDisplayNames + displayName
        ) }

    fun removeKeyfile(index: Int) {
        val paths = _state.value.keyfileData.toMutableList()
        val names = _state.value.keyfileDisplayNames.toMutableList()
        if (index in paths.indices) {
            paths[index].fill(0)
            paths.removeAt(index); names.removeAt(index)
        }
        _state.update { it.copy(keyfileData = paths, keyfileDisplayNames = names) }
    }

    fun setOutputFile(uri: String, displayName: String) =
        _state.update { it.copy(outputUri = uri, outputFileName = displayName) }

    fun startBackup() {
        if (_state.value.isRunning) return
        if (repo.getContainerHandle(containerId) != null) {
            _state.update { it.copy(error = "Unmount the vault before backing up its header.") }
            return
        }
        val s = _state.value
        _state.update { it.copy(isRunning = true, error = null) }

        viewModelScope.launch {
            val volumePfd: ParcelFileDescriptor? = try {
                if (safUri.isNotEmpty())
                    context.contentResolver.openFileDescriptor(Uri.parse(safUri), "r")
                else
                    ParcelFileDescriptor.open(File(containerPath), ParcelFileDescriptor.MODE_READ_ONLY)
            } catch (e: Exception) {
                _state.update { it.copy(isRunning = false, error = "Failed to open volume: ${e.message}") }
                return@launch
            }

            val outputPfd: ParcelFileDescriptor? = try {
                context.contentResolver.openFileDescriptor(Uri.parse(s.outputUri), "rw")
            } catch (e: Exception) {
                volumePfd?.close()
                _state.update { it.copy(isRunning = false, error = "Failed to open output file: ${e.message}") }
                return@launch
            }

            if (volumePfd == null || outputPfd == null) {
                volumePfd?.close(); outputPfd?.close()
                _state.update { it.copy(isRunning = false, error = "Failed to open files.") }
                return@launch
            }

            val result: CryptoResult<Unit> = engine.backupVolumeHeaderFd(
                volumeFd     = volumePfd.fd,
                password     = s.password,
                keyfileData = s.keyfileData,
                pim          = s.pim,
                outputFd     = outputPfd.fd
            )

            volumePfd.close(); outputPfd.close()
            s.keyfileData.forEach { it.fill(0) }
            _state.update { it.copy(keyfileData = emptyList(), keyfileDisplayNames = emptyList()) }

            when (result) {
                is CryptoResult.Success -> _state.update { it.copy(isRunning = false, isSuccess = true) }
                is CryptoResult.Failure -> _state.update { it.copy(isRunning = false, error = result.error.name) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        _state.value.keyfileData.forEach { it.fill(0) }
    }
}
