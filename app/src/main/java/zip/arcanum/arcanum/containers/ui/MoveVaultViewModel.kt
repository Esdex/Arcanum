package zip.arcanum.arcanum.containers.ui

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
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
import java.util.UUID
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
            val destDir   = defaultDestDir()
            _storageInfo.value = StorageInfo(
                containerName        = container.name,
                containerSize        = container.size,
                destinationFreeBytes = destDir.usableSpace
            )
        }
    }

    fun refreshFreeSpace(includeInBackup: Boolean, destTreeUri: Uri?) {
        viewModelScope.launch(Dispatchers.IO) {
            val current = _storageInfo.value ?: return@launch
            val destDir = when {
                toApp -> if (includeInBackup) context.filesDir else context.noBackupFilesDir
                else  -> existingAncestor(context.getExternalFilesDir(null) ?: context.filesDir)
            }
            _storageInfo.value = current.copy(destinationFreeBytes = destDir.usableSpace)
        }
    }

    fun startMove(includeInBackup: Boolean, destTreeUri: Uri? = null) {
        viewModelScope.launch {
            _state.value = State.Moving(0f, 0, 0)
            try {
                val container = repo.getContainerById(containerId)
                    ?: throw Exception("Container not found")

                val total: Long
                val sourceIsSaf = container.safUri.isNotEmpty()

                if (sourceIsSaf) {
                    val statSize = context.contentResolver.openFileDescriptor(
                        Uri.parse(container.safUri),
                        "r"
                    )?.use { it.statSize } ?: -1L
                    total = statSize.takeIf { it >= 0L }
                        ?: container.size.takeIf { it > 0L }
                        ?: throw Exception("Cannot determine source file size")
                } else {
                    val sourceFile = File(container.path)
                    if (!sourceFile.exists()) throw Exception("Source file not found:\n${container.path}")
                    total = sourceFile.length()
                }

                withContext(Dispatchers.IO) {
                    if (toApp) {
                        // Moving TO app-private storage
                        val base = if (includeInBackup) context.filesDir else context.noBackupFilesDir
                        val destDir = File(base, "vaults").also { it.mkdirs() }
                        val destFile = resolveUniqueFile(destDir, container.name)
                        val tempFile = resolveUniqueFile(destDir, ".${destFile.name}.move.${UUID.randomUUID()}.tmp")

                        val input = if (sourceIsSaf) {
                            context.contentResolver.openInputStream(Uri.parse(container.safUri))
                                ?: throw Exception("Cannot open source file")
                        } else {
                            File(container.path).inputStream()
                        }
                        try {
                            input.use { ins ->
                                tempFile.outputStream().use { out ->
                                    copyStreamWithProgress(ins, out, total)
                                }
                            }
                            if (tempFile.length() != total) {
                                throw Exception("Destination size verification failed")
                            }
                            if (!tempFile.renameTo(destFile)) {
                                throw Exception("Cannot publish destination file")
                            }
                        } catch (t: Throwable) {
                            tempFile.delete()
                            throw t
                        }

                        repo.updateContainerPath(containerId, destFile.absolutePath)
                        repo.updateSafUri(containerId, "")
                        if (!deleteSource(container.path, container.safUri, sourceIsSaf)) {
                            throw Exception("Vault moved, but old source could not be deleted")
                        }
                    } else {
                        // Moving TO external storage via SAF tree
                        val destDir = destTreeUri?.let { DocumentFile.fromTreeUri(context, it) }
                            ?: DocumentFile.fromFile(
                                (context.getExternalFilesDir(null) ?: context.filesDir).also { it.mkdirs() }
                            )
                        val finalName = resolveUniqueDocumentName(destDir, container.name)
                        val tempName = ".${finalName}.move.${UUID.randomUUID()}.tmp"
                        val tempDoc = destDir.createFile("application/octet-stream", tempName)
                            ?: throw Exception("Cannot create destination file")

                        val output = context.contentResolver.openOutputStream(tempDoc.uri)
                            ?: throw Exception("Cannot open destination stream")

                        try {
                            output.use { out ->
                                val input = if (sourceIsSaf) {
                                    context.contentResolver.openInputStream(Uri.parse(container.safUri))
                                    ?: throw Exception("Cannot open source file")
                                } else {
                                    File(container.path).inputStream()
                                }
                                input.use { ins ->
                                    copyStreamWithProgress(ins, out, total)
                                }
                            }
                            if (documentLength(tempDoc.uri) != total) {
                                throw Exception("Destination size verification failed")
                            }
                            if (!tempDoc.renameTo(finalName)) {
                                throw Exception("Cannot publish destination file")
                            }
                        } catch (t: Throwable) {
                            runCatching { tempDoc.delete() }
                            throw t
                        }

                        if (destTreeUri != null) {
                            runCatching {
                                context.contentResolver.takePersistableUriPermission(
                                    destTreeUri,
                                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                )
                            }
                        }
                        val finalDoc = destDir.findFile(finalName) ?: tempDoc
                        repo.updateContainerPath(containerId, "")
                        repo.updateSafUri(containerId, finalDoc.uri.toString())
                        if (!deleteSource(container.path, container.safUri, sourceIsSaf)) {
                            throw Exception("Vault moved, but old source could not be deleted")
                        }
                    }
                }
                _state.value = State.Success
            } catch (e: Exception) {
                _state.value = State.Failure(e.message ?: "Unknown error")
            }
        }
    }

    fun reset() { _state.value = State.Idle }

    private fun defaultDestDir(): File =
        if (toApp) context.noBackupFilesDir
        else existingAncestor(context.getExternalFilesDir(null) ?: context.filesDir)

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

    private fun resolveUniqueDocumentName(dir: DocumentFile, name: String): String {
        if (dir.findFile(name) == null) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot >= 0) name.substring(0, dot) else name
        val ext = if (dot >= 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$base($n)$ext"
            if (dir.findFile(candidate) == null) return candidate
            n++
        }
    }

    private fun documentLength(uri: Uri): Long =
        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L

    private fun deleteSource(path: String, safUri: String, sourceIsSaf: Boolean): Boolean =
        if (sourceIsSaf) {
            runCatching {
                DocumentsContract.deleteDocument(context.contentResolver, Uri.parse(safUri))
            }.getOrDefault(false)
        } else {
            File(path).delete()
        }

    private fun copyStreamWithProgress(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        total: Long
    ) {
        val buf  = ByteArray(256 * 1024)
        var done = 0L
        var read: Int
        while (input.read(buf).also { read = it } != -1) {
            output.write(buf, 0, read)
            done += read
            if (total >= 0L && done > total) throw Exception("Source size changed during copy")
            _state.value = State.Moving(
                progress   = if (total > 0) done.toFloat() / total else 0f,
                bytesDone  = done,
                totalBytes = total
            )
        }
        output.flush()
        if (done != total) throw Exception("Source copy ended before expected size")
    }
}
