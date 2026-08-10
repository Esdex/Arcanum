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

data class RestoreHeaderState(
    val password: String = "",
    val showPassword: Boolean = false,
    val pim: Int = 0,
    val keyfileData: List<ByteArray> = emptyList(),
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
    private val usbVolumes: zip.arcanum.usb.UsbVolumeManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow(RestoreHeaderState())
    val state = _state.asStateFlow()

    private var containerId: String = ""
    private var containerPath: String = ""
    private var safUri: String = ""
    private var usbSaltHash: String = ""

    fun init(id: String) {
        containerId = id
        viewModelScope.launch {
            val c = repo.getContainerById(id) ?: return@launch
            containerPath = c.path
            safUri        = c.safUri
            usbSaltHash   = c.usbSaltHash
        }
    }

    fun update(block: RestoreHeaderState.() -> RestoreHeaderState) =
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

    fun setBackupFile(uri: String, displayName: String) =
        _state.update { it.copy(backupUri = uri, backupFileName = displayName) }

    fun startRestore() {
        if (_state.value.isRunning) return
        if (repo.getContainerHandle(containerId) != null) {
            _state.update { it.copy(error = "Unmount the vault before restoring its header.") }
            return
        }
        val s = _state.value
        _state.update { it.copy(isRunning = true, error = null) }

        viewModelScope.launch {
            if (usbSaltHash.isNotEmpty()) {
                restoreFromUsb(s)
                return@launch
            }
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
                keyfileData = s.keyfileData,
                pim          = s.pim,
                fromExternal = s.fromExternal,
                backupFd     = backupPfd?.fd ?: -1
            )

            volumePfd.close(); backupPfd?.close()
            s.keyfileData.forEach { it.fill(0) }
            _state.update { it.copy(keyfileData = emptyList(), keyfileDisplayNames = emptyList()) }

            when (result) {
                is CryptoResult.Success -> _state.update { it.copy(isRunning = false, isSuccess = true) }
                is CryptoResult.Failure -> _state.update { it.copy(isRunning = false, error = result.error.name) }
            }
        }
    }

    /**
     * Restore for a USB-hosted vault. Opened read-write, because a restore rewrites the
     * volume's headers. The backup itself may still come from an external file, which
     * stays an ordinary descriptor - only the volume side changes.
     */
    private suspend fun restoreFromUsb(s: RestoreHeaderState) {
        val backupPfd = if (s.fromExternal) {
            try {
                context.contentResolver.openFileDescriptor(Uri.parse(s.backupUri), "r")
            } catch (e: Exception) {
                _state.update { it.copy(isRunning = false, error = "Failed to open backup: ${e.message}") }
                return
            }
        } else null

        val op = usbVolumes.withMatchingVolume(usbSaltHash, readOnly = false, refingerprint = true) { dev ->
            engine.restoreVolumeHeaderUsb(
                transport    = dev,
                deviceSize   = dev.sizeBytes,
                password     = s.password,
                keyfileData  = s.keyfileData,
                pim          = s.pim,
                fromExternal = s.fromExternal,
                backupFd     = backupPfd?.fd ?: -1
            )
        }
        backupPfd?.close()
        s.keyfileData.forEach { it.fill(0) }
        _state.update { it.copy(keyfileData = emptyList(), keyfileDisplayNames = emptyList()) }

        when (op) {
            is zip.arcanum.usb.UsbVolumeManager.VolumeOp.Done -> when (val r = op.value) {
                is CryptoResult.Success -> {
                    // The rewritten header carries a fresh salt, so the vault would stop
                    // recognising its own drive unless the fingerprint is re-pointed.
                    op.newSaltHash?.let { fresh ->
                        repo.updateUsbSaltHash(usbSaltHash, fresh)
                        usbSaltHash = fresh
                    }
                    _state.update { it.copy(isRunning = false, isSuccess = true) }
                }
                is CryptoResult.Failure -> _state.update { it.copy(isRunning = false, error = r.error.name) }
            }
            zip.arcanum.usb.UsbVolumeManager.VolumeOp.NoDrive ->
                _state.update { it.copy(isRunning = false, error = USB_NOT_CONNECTED) }
            is zip.arcanum.usb.UsbVolumeManager.VolumeOp.WrongVolume ->
                _state.update { it.copy(isRunning = false, error = USB_WRONG_DEVICE) }
            is zip.arcanum.usb.UsbVolumeManager.VolumeOp.Failed ->
                _state.update { it.copy(isRunning = false, error = op.reason) }
        }
    }

    private companion object {
        const val USB_NOT_CONNECTED = "Connect the USB drive holding this vault and try again."
        const val USB_WRONG_DEVICE  = "Wrong USB device: the connected drive does not hold this vault."
    }

    override fun onCleared() {
        super.onCleared()
        _state.value.keyfileData.forEach { it.fill(0) }
    }
}
