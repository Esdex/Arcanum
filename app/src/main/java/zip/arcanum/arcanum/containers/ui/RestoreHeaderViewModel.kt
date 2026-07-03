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
import zip.arcanum.core.security.VaultPasswordPolicy
import zip.arcanum.core.utils.FileUtils
import zip.arcanum.crypto.CryptoResult
import zip.arcanum.crypto.VeraCryptEngine
import java.io.File
import javax.inject.Inject

data class RestoreHeaderState(
    val password: String = "",
    val showPassword: Boolean = false,
    val pim: Int = 0,
    val keyfilePaths: List<String> = emptyList(),
    val keyfileDisplayNames: List<String> = emptyList(),
    val fromExternal: Boolean = false,
    val backupUri: String = "",
    val backupFileName: String = "",
    val isRunning: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class RestoreHeaderViewModel @Inject constructor(
    private val repo: ContainerRepository,
    private val engine: VeraCryptEngine,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(RestoreHeaderState())
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

    fun update(block: RestoreHeaderState.() -> RestoreHeaderState) =
        _state.update { it.block() }

    fun addKeyfile(cachedPath: String, displayName: String) =
        _state.update { it.copy(
            keyfilePaths        = it.keyfilePaths + cachedPath,
            keyfileDisplayNames = it.keyfileDisplayNames + displayName
        ) }

    fun removeKeyfile(index: Int) {
        val paths = _state.value.keyfilePaths.toMutableList()
        val names = _state.value.keyfileDisplayNames.toMutableList()
        if (index in paths.indices) {
            FileUtils.secureZeroAndDelete(File(paths[index]))
            paths.removeAt(index); names.removeAt(index)
        }
        _state.update { it.copy(keyfilePaths = paths, keyfileDisplayNames = names) }
    }

    fun setBackupFile(uri: String, displayName: String) =
        _state.update { it.copy(backupUri = uri, backupFileName = displayName) }

    fun startRestore() {
        if (_state.value.isRunning) return
        if (repo.getContainerHandle(containerId) != null) {
            _state.update { it.copy(error = "Unmount the vault before restoring its header.") }
            return
        }
        val s = _state.value
        if (!VaultPasswordPolicy.isWithinVeraCryptLimit(s.password)) {
            _state.update { it.copy(error = VaultPasswordPolicy.violationMessage()) }
            return
        }
        _state.update { it.copy(isRunning = true, error = null) }

        viewModelScope.launch {
            val volumePfd: ParcelFileDescriptor? = try {
                if (safUri.isNotEmpty())
                    context.contentResolver.openFileDescriptor(Uri.parse(safUri), "rw")
                else
                    ParcelFileDescriptor.open(File(containerPath), ParcelFileDescriptor.MODE_READ_WRITE)
            } catch (e: Exception) {
                _state.update { it.copy(isRunning = false, error = "Failed to open volume: ${e.message}") }
                return@launch
            }

            val backupPfd: ParcelFileDescriptor? = if (s.fromExternal) {
                try {
                    context.contentResolver.openFileDescriptor(Uri.parse(s.backupUri), "r")
                } catch (e: Exception) {
                    volumePfd?.close()
                    _state.update { it.copy(isRunning = false, error = "Failed to open backup file: ${e.message}") }
                    return@launch
                }
            } else null

            if (volumePfd == null) {
                backupPfd?.close()
                _state.update { it.copy(isRunning = false, error = "Failed to open volume.") }
                return@launch
            }

            val result: CryptoResult<Unit> = engine.restoreVolumeHeaderFd(
                volumeFd     = volumePfd.fd,
                password     = s.password,
                keyfilePaths = s.keyfilePaths,
                pim          = s.pim,
                fromExternal = s.fromExternal,
                backupFd     = backupPfd?.fd ?: -1
            )

            volumePfd.close(); backupPfd?.close()
            s.keyfilePaths.forEach { FileUtils.secureZeroAndDelete(File(it)) }
            _state.update { it.copy(keyfilePaths = emptyList(), keyfileDisplayNames = emptyList()) }

            when (result) {
                is CryptoResult.Success -> _state.update { it.copy(isRunning = false, isSuccess = true) }
                is CryptoResult.Failure -> _state.update { it.copy(isRunning = false, error = result.error.name) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        _state.value.keyfilePaths.forEach { FileUtils.secureZeroAndDelete(File(it)) }
    }
}
