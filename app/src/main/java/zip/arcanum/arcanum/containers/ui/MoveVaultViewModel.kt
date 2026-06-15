package zip.arcanum.arcanum.containers.ui

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.core.navigation.Screen
import java.io.File
import javax.inject.Inject

@HiltViewModel
class MoveVaultViewModel @Inject constructor(
    private val repo: ContainerRepository,
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    sealed interface State {
        object Idle    : State
        data class Moving(val progress: Float, val bytesDone: Long, val totalBytes: Long) : State
        object Success : State
        data class Failure(val message: String) : State
    }

    data class StorageInfo(
        val containerName: String,
        val containerSize: Long,
        val destinationFreeBytes: Long
    )

    private val containerId: String = checkNotNull(savedStateHandle[Screen.MoveVault.ARG_ID])
    val toApp: Boolean = savedStateHandle.get<Boolean>(Screen.MoveVault.ARG_TO_APP) ?: true

    private val _state       = MutableStateFlow<State>(State.Idle)
    val state = _state.asStateFlow()

    private val _storageInfo = MutableStateFlow<StorageInfo?>(null)
    val storageInfo = _storageInfo.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val container = repo.getContainerById(containerId) ?: return@launch
            val destDir   = defaultDestDir(includeInBackup = false)
            _storageInfo.value = StorageInfo(
                containerName        = container.name,
                containerSize        = container.size,
                destinationFreeBytes = destDir.usableSpace
            )
        }
    }

    fun refreshFreeSpace(includeInBackup: Boolean, customDir: String?) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _storageInfo.value ?: return@launch
            val destDir = when {
                toApp -> if (includeInBackup) context.filesDir else context.noBackupFilesDir
                else  -> existingAncestor(
                    customDir?.takeIf { it.isNotBlank() }?.let { File(it) }
                        ?: (context.getExternalFilesDir(null) ?: context.filesDir)
                )
            }
            _storageInfo.value = current.copy(destinationFreeBytes = destDir.usableSpace)
        }
    }

    fun startMove(includeInBackup: Boolean, customDestDir: String? = null) {
        viewModelScope.launch {
            _state.value = State.Moving(0f, 0, 0)
            try {
                val container = repo.getContainerById(containerId)
                    ?: throw Exception("Container not found")

                val sourceFile = File(container.path)
                if (!sourceFile.exists()) throw Exception("Source file not found:\n${container.path}")

                val destDir = when {
                    toApp -> {
                        val base = if (includeInBackup) context.filesDir else context.noBackupFilesDir
                        File(base, "vaults").also { it.mkdirs() }
                    }
                    else  -> {
                        val dir = File(customDestDir ?: context.getExternalFilesDir(null)!!.absolutePath)
                        dir.mkdirs()
                        dir
                    }
                }

                val destFile = resolveUniqueFile(destDir, sourceFile.name)
                val total    = sourceFile.length()

                withContext(Dispatchers.IO) {
                    copyWithProgress(sourceFile, destFile, total) { done ->
                        _state.value = State.Moving(
                            progress   = if (total > 0) done.toFloat() / total else 0f,
                            bytesDone  = done,
                            totalBytes = total
                        )
                    }
                    repo.updateContainerPath(containerId, destFile.absolutePath)
                    sourceFile.delete()
                }
                _state.value = State.Success
            } catch (e: Exception) {
                _state.value = State.Failure(e.message ?: "Unknown error")
            }
        }
    }

    fun reset() { _state.value = State.Idle }

    private fun defaultDestDir(includeInBackup: Boolean): File =
        if (toApp) {
            if (includeInBackup) context.filesDir else context.noBackupFilesDir
        } else {
            existingAncestor(context.getExternalFilesDir(null) ?: context.filesDir)
        }

    // Walk up to the nearest existing ancestor so usableSpace returns a real value
    private fun existingAncestor(f: File): File {
        var d = f
        while (!d.exists() && d.parentFile != null) d = d.parentFile!!
        return d
    }

    private fun resolveUniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot  = name.lastIndexOf('.')
        val base = if (dot >= 0) name.substring(0, dot) else name
        val ext  = if (dot >= 0) name.substring(dot)    else ""
        var n = 1
        while (candidate.exists()) { candidate = File(dir, "$base($n)$ext"); n++ }
        return candidate
    }

    private fun copyWithProgress(src: File, dst: File, total: Long, onProgress: (Long) -> Unit) {
        val buf  = ByteArray(256 * 1024)
        var done = 0L
        src.inputStream().use { input ->
            dst.outputStream().use { output ->
                var read: Int
                while (input.read(buf).also { read = it } != -1) {
                    output.write(buf, 0, read)
                    done += read
                    onProgress(done)
                }
            }
        }
    }
}
