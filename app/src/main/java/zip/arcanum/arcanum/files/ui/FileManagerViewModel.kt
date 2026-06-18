package zip.arcanum.arcanum.files.ui

import android.content.Context
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.containers.domain.Container
import zip.arcanum.arcanum.files.domain.ClipboardItem
import zip.arcanum.arcanum.files.domain.FileClipboard
import zip.arcanum.arcanum.gallery.AudioPlayerQueue
import zip.arcanum.arcanum.gallery.MediaViewerQueue
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.utils.FileUtils
import zip.arcanum.crypto.VeraCryptEngine
import zip.arcanum.crypto.NativeFileInfo
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

private val Context.fileManagerPrefs: DataStore<Preferences> by preferencesDataStore("file_manager_prefs")

private val AUDIO_EXTENSIONS_VM = setOf("mp3", "m4a", "aac", "ogg", "flac", "wav", "opus")
private val MEDIA_EXTENSIONS_VM = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif",
    "mp4", "mkv", "avi", "mov", "m4v", "webm", "3gp"
)

@HiltViewModel
class FileManagerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: VeraCryptEngine,
    private val clipboard: FileClipboard,
    private val repo: ContainerRepository,
    private val audioQueue: AudioPlayerQueue,
    private val mediaQueue: MediaViewerQueue
) : ViewModel() {

    enum class ViewMode { LIST, GRID }
    enum class SortBy { NAME, DATE, SIZE, TYPE }

    data class FileManagerState(
        val containerId: String = "",
        val currentPath: String = "/",
        val pathSegments: List<String> = listOf("/"),
        val files: List<NativeFileInfo> = emptyList(),
        val rawFiles: List<NativeFileInfo> = emptyList(),
        val isLoading: Boolean = false,
        val viewMode: ViewMode = ViewMode.LIST,
        val sortBy: SortBy = SortBy.DATE,
        val sortAscending: Boolean = false,
        val showHidden: Boolean = false,
        val foldersFirst: Boolean = true,
        val selectedItems: Set<String> = emptySet(),
        val isSelectionMode: Boolean = false,
        val searchQuery: String = "",
        val isSearchActive: Boolean = false,
        val isSearchRecursive: Boolean = false,
        val clipboardCount: Int = 0,
        val error: String? = null,
        val pendingNotification: InAppNotification? = null,
        val tempFileToOpen: Pair<File, String>? = null,
        val isOperationInProgress: Boolean = false,
        val operationMessage: String? = null
    )

    private val _state = MutableStateFlow(FileManagerState())
    val state = _state.asStateFlow()

    val mountedContainers: kotlinx.coroutines.flow.StateFlow<List<Container>> = repo.getAllContainers()
        .map { list -> list.filter { it.isMounted } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _tempFiles = mutableListOf<File>()
    private var initialized = false

    private val prefs = context.fileManagerPrefs

    companion object {
        private val SORT_BY_KEY      = stringPreferencesKey("sort_by")
        private val SORT_ASC_KEY     = booleanPreferencesKey("sort_asc")
        private val SHOW_HIDDEN_KEY  = booleanPreferencesKey("show_hidden")
        private val FOLDERS_FIRST_KEY = booleanPreferencesKey("folders_first")
        private val VIEW_MODE_KEY    = stringPreferencesKey("view_mode")
    }

    fun initialize(containerId: String) {
        if (initialized && _state.value.containerId == containerId) return
        initialized = true
        _state.update { it.copy(containerId = containerId) }
        viewModelScope.launch {
            runCatching {
                val p = prefs.data.first()
                _state.update { it.copy(
                    sortBy       = runCatching { SortBy.valueOf(p[SORT_BY_KEY] ?: "DATE") }.getOrDefault(SortBy.DATE),
                    sortAscending = p[SORT_ASC_KEY] ?: false,
                    showHidden   = p[SHOW_HIDDEN_KEY] ?: false,
                    foldersFirst = p[FOLDERS_FIRST_KEY] ?: true,
                    viewMode     = runCatching { ViewMode.valueOf(p[VIEW_MODE_KEY] ?: "LIST") }.getOrDefault(ViewMode.LIST)
                ) }
            }
            loadDirectory("/")
        }
    }

    fun setMediaQueue(clickedFile: NativeFileInfo) {
        val mediaFiles = _state.value.files.filter {
            !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in MEDIA_EXTENSIONS_VM
        }
        val index = mediaFiles.indexOfFirst { it.path == clickedFile.path }.coerceAtLeast(0)
        mediaQueue.set(_state.value.containerId, mediaFiles, index)
    }

    fun setAudioQueue(clickedFile: NativeFileInfo) {
        val audioFiles = _state.value.files.filter {
            !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS_VM
        }
        val index = audioFiles.indexOfFirst { it.path == clickedFile.path }.coerceAtLeast(0)
        audioQueue.set(_state.value.containerId, audioFiles, index)
    }

    // ── Navigation ────────────────────────────────────────────────────────

    fun navigateTo(path: String) {
        _state.update { it.copy(
            selectedItems   = emptySet(),
            isSelectionMode = false,
            isSearchActive  = false,
            searchQuery     = ""
        ) }
        loadDirectory(path)
    }

    fun navigateUp() {
        val current = _state.value.currentPath
        if (current == "/") return
        val parent = current.substringBeforeLast("/").ifEmpty { "/" }
        navigateTo(parent)
    }

    fun navigateToSegment(segmentIndex: Int) {
        val path = pathForSegmentIndex(segmentIndex)
        navigateTo(path)
    }

    fun navigateToRoot() = navigateTo("/")

    private fun pathForSegmentIndex(index: Int): String {
        val segments = _state.value.pathSegments
        if (index == 0) return "/"
        return "/" + segments.drop(1).take(index).joinToString("/")
    }

    // ── View mode & sorting ───────────────────────────────────────────────

    fun toggleViewMode() {
        _state.update { it.copy(viewMode = if (it.viewMode == ViewMode.LIST) ViewMode.GRID else ViewMode.LIST) }
        savePrefs()
    }

    fun setSortBy(sortBy: SortBy) {
        _state.update { s ->
            val files = applyFiltersAndSort(s.rawFiles, sortBy, s.sortAscending, s.showHidden, s.foldersFirst, s.searchQuery)
            s.copy(sortBy = sortBy, files = files)
        }
        savePrefs()
    }

    fun toggleSortDirection() {
        _state.update { s ->
            val asc = !s.sortAscending
            val files = applyFiltersAndSort(s.rawFiles, s.sortBy, asc, s.showHidden, s.foldersFirst, s.searchQuery)
            s.copy(sortAscending = asc, files = files)
        }
        savePrefs()
    }

    fun toggleFoldersFirst() {
        _state.update { s ->
            val ff = !s.foldersFirst
            val files = applyFiltersAndSort(s.rawFiles, s.sortBy, s.sortAscending, s.showHidden, ff, s.searchQuery)
            s.copy(foldersFirst = ff, files = files)
        }
        savePrefs()
    }

    fun toggleShowHidden() {
        _state.update { s ->
            val sh = !s.showHidden
            val files = applyFiltersAndSort(s.rawFiles, s.sortBy, s.sortAscending, sh, s.foldersFirst, s.searchQuery)
            s.copy(showHidden = sh, files = files)
        }
        savePrefs()
    }

    // ── Search ────────────────────────────────────────────────────────────

    fun toggleSearch() {
        val active = !_state.value.isSearchActive
        _state.update { s ->
            val files = if (!active) applyFiltersAndSort(s.rawFiles, s.sortBy, s.sortAscending, s.showHidden, s.foldersFirst, "")
                        else s.files
            s.copy(isSearchActive = active, searchQuery = if (!active) "" else s.searchQuery, files = files)
        }
    }

    fun setSearchActive(active: Boolean) {
        if (!active) {
            _state.update { s ->
                val files = applyFiltersAndSort(s.rawFiles, s.sortBy, s.sortAscending, s.showHidden, s.foldersFirst, "")
                s.copy(isSearchActive = false, searchQuery = "", files = files)
            }
        } else {
            _state.update { it.copy(isSearchActive = true) }
        }
    }

    fun setSearchQuery(query: String) {
        _state.update { s ->
            val files = applyFiltersAndSort(s.rawFiles, s.sortBy, s.sortAscending, s.showHidden, s.foldersFirst, query)
            s.copy(searchQuery = query, files = files)
        }
    }

    fun toggleSearchRecursive() {
        _state.update { it.copy(isSearchRecursive = !it.isSearchRecursive) }
    }

    // ── Selection ─────────────────────────────────────────────────────────

    fun enterSelectionMode(initialPath: String) {
        _state.update { it.copy(isSelectionMode = true, selectedItems = setOf(initialPath)) }
    }

    fun exitSelectionMode() {
        _state.update { it.copy(isSelectionMode = false, selectedItems = emptySet()) }
    }

    fun toggleSelection(path: String) {
        _state.update { s ->
            val newSet = if (path in s.selectedItems) s.selectedItems - path else s.selectedItems + path
            val stillInSelection = newSet.isNotEmpty()
            s.copy(selectedItems = newSet, isSelectionMode = stillInSelection)
        }
    }

    fun selectAll() {
        _state.update { s ->
            s.copy(selectedItems = s.files.map { it.path }.toSet())
        }
    }

    fun clearSelection() = exitSelectionMode()

    // ── Clipboard ─────────────────────────────────────────────────────────

    fun copySelected() {
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: return
        val items = s.selectedItems.mapNotNull { path ->
            s.files.find { it.path == path }?.let { f ->
                ClipboardItem(
                    sourceContainerId = s.containerId,
                    sourceHandle      = handle,
                    sourcePath        = f.path,
                    fileName          = f.name,
                    isDirectory       = f.isDirectory,
                    isCut             = false
                )
            }
        }
        clipboard.copy(items)
        _state.update { it.copy(
            clipboardCount  = clipboard.count,
            isSelectionMode = false,
            selectedItems   = emptySet()
        ) }
    }

    fun cutSelected() {
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: return
        val items = s.selectedItems.mapNotNull { path ->
            s.files.find { it.path == path }?.let { f ->
                ClipboardItem(
                    sourceContainerId = s.containerId,
                    sourceHandle      = handle,
                    sourcePath        = f.path,
                    fileName          = f.name,
                    isDirectory       = f.isDirectory,
                    isCut             = true
                )
            }
        }
        clipboard.cut(items)
        _state.update { it.copy(
            clipboardCount  = clipboard.count,
            isSelectionMode = false,
            selectedItems   = emptySet()
        ) }
    }

    fun paste() {
        val destContainerId = _state.value.containerId
        val destHandle = repo.getContainerHandle(destContainerId) ?: return
        val clipItems = clipboard.items
        if (clipItems.isEmpty()) return
        val currentPath = _state.value.currentPath
        val isCut = clipboard.isCut

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isOperationInProgress = true, operationMessage = "Pasting…") }
            var count = 0
            val chunkSize = 1 * 1024 * 1024
            for (item in clipItems) {
                try {
                    val destPath = if (currentPath == "/") "/${item.fileName}" else "$currentPath/${item.fileName}"
                    _state.update { it.copy(operationMessage = "${if (isCut) "Moving" else "Copying"} ${item.fileName}…") }
                    if (item.isDirectory) {
                        val ok = copyDirectoryRecursive(item.sourceHandle, item.sourcePath, destHandle, destPath)
                        if (ok && isCut) runCatching { engine.nativeDeleteDirectory(item.sourceHandle, item.sourcePath) }
                        if (ok) count++
                    } else {
                        var offset = 0L
                        while (true) {
                            val chunk = engine.nativeReadFile(item.sourceHandle, item.sourcePath, offset, chunkSize) ?: break
                            engine.nativeWriteFile(destHandle, destPath, chunk, offset)
                            offset += chunk.size
                            if (chunk.size < chunkSize) break
                        }
                        if (isCut) runCatching { engine.nativeDeleteFile(item.sourceHandle, item.sourcePath) }
                        count++
                    }
                } catch (_: Exception) { }
            }
            clipboard.clear()
            refreshNow()
            val destDesc = if (currentPath == "/") "Root" else currentPath
            _state.update { it.copy(
                isOperationInProgress = false,
                operationMessage      = null,
                clipboardCount        = 0,
                pendingNotification   = if (count > 0) {
                    if (isCut) InAppNotification.FilesMoved(count, destDesc)
                    else InAppNotification.FilesPasted(count)
                } else null
            ) }
        }
    }

    fun copyToDestination(
        destinationContainerId: String,
        destinationPath: String,
        destinationName: String
    ) {
        val s = _state.value
        val sourceHandle = repo.getContainerHandle(s.containerId) ?: return
        val destHandle = repo.getContainerHandle(destinationContainerId) ?: return
        val toCopy = s.selectedItems.mapNotNull { path -> s.files.find { it.path == path } }

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isOperationInProgress = true, operationMessage = "Copying…") }
            var count = 0
            val chunkSize = 1 * 1024 * 1024

            for (item in toCopy) {
                val parentDir = item.path.substringBeforeLast("/").let { if (it.isEmpty()) "/" else it }
                if (destinationContainerId == s.containerId && parentDir == destinationPath) continue

                try {
                    val destItemPath = if (destinationPath == "/") "/${item.name}" else "$destinationPath/${item.name}"
                    _state.update { it.copy(operationMessage = "Copying ${item.name}…") }
                    if (item.isDirectory) {
                        val ok = copyDirectoryRecursive(sourceHandle, item.path, destHandle, destItemPath)
                        if (ok) count++
                    } else {
                        var offset = 0L
                        while (offset < item.size) {
                            val chunk = engine.nativeReadFile(sourceHandle, item.path, offset, chunkSize) ?: break
                            engine.nativeWriteFile(destHandle, destItemPath, chunk, offset)
                            offset += chunk.size
                            if (chunk.size < chunkSize) break
                        }
                        count++
                    }
                } catch (_: Exception) { }
            }

            exitSelectionMode()
            _state.update { it.copy(
                isOperationInProgress = false,
                operationMessage      = null,
                pendingNotification   = if (count > 0) InAppNotification.FilesPasted(count) else null
            ) }
        }
    }

    fun moveSelected(
        destinationContainerId: String,
        destinationPath: String,
        destinationName: String
    ) {
        val s = _state.value
        val sourceHandle = repo.getContainerHandle(s.containerId) ?: return
        val destHandle = repo.getContainerHandle(destinationContainerId) ?: return
        val toMove = s.selectedItems.mapNotNull { path -> s.files.find { it.path == path } }

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isOperationInProgress = true, operationMessage = "Moving…") }
            var count = 0

            for (item in toMove) {
                val parentDir = item.path.substringBeforeLast("/").let { if (it.isEmpty()) "/" else it }
                if (destinationContainerId == s.containerId && parentDir == destinationPath) continue

                val destItemPath = if (destinationPath == "/") "/${item.name}" else "$destinationPath/${item.name}"
                _state.update { it.copy(operationMessage = "Moving ${item.name}…") }

                val moved = when {
                    destinationContainerId == s.containerId -> {
                        val result = runCatching {
                            engine.nativeRenameFile(sourceHandle, item.path, destItemPath)
                        }.getOrDefault(VeraCryptEngine.ERR_FS)
                        result == VeraCryptEngine.ERR_OK
                    }
                    item.isDirectory -> moveDirectoryRecursive(sourceHandle, item.path, destHandle, destItemPath)
                    else -> moveFile(sourceHandle, item.path, destHandle, destItemPath, item.size)
                }
                if (moved) count++
            }

            exitSelectionMode()
            refreshNow()
            _state.update { it.copy(
                isOperationInProgress = false,
                operationMessage      = null,
                pendingNotification   = if (count > 0) InAppNotification.FilesMoved(count, destinationName) else null
            ) }
        }
    }

    fun listDirectoriesAt(containerId: String, path: String, onResult: (List<NativeFileInfo>) -> Unit) {
        val handle = repo.getContainerHandle(containerId) ?: run { onResult(emptyList()); return }
        viewModelScope.launch(Dispatchers.IO) {
            val dirs = runCatching {
                engine.nativeListFiles(handle, path).toList().filter { it.isDirectory }
            }.getOrDefault(emptyList())
            withContext(Dispatchers.Main) { onResult(dirs) }
        }
    }

    // ── File operations ───────────────────────────────────────────────────

    fun deleteSelected() {
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: return
        val toDelete = s.selectedItems.mapNotNull { path -> s.files.find { it.path == path } }

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isOperationInProgress = true) }
            var count = 0
            for (file in toDelete) {
                runCatching {
                    if (file.isDirectory) engine.nativeDeleteDirectory(handle, file.path)
                    else engine.nativeDeleteFile(handle, file.path)
                    count++
                }
            }
            exitSelectionMode()
            refreshNow()
            _state.update { it.copy(
                isOperationInProgress = false,
                pendingNotification   = if (count > 0) InAppNotification.FilesDeleted(count) else null
            ) }
        }
    }

    fun createFolder(name: String) {
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: return
        val folderPath = if (s.currentPath == "/") "/$name" else "${s.currentPath}/$name"

        viewModelScope.launch(Dispatchers.IO) {
            runCatching { engine.nativeCreateDirectory(handle, folderPath) }
            refreshNow()
            _state.update { it.copy(pendingNotification = InAppNotification.FolderCreated(name)) }
        }
    }

    fun renameFile(file: NativeFileInfo, newName: String, onResult: (Boolean) -> Unit) {
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: return
        val dir = file.path.substringBeforeLast("/", "")
        val ext = file.name.substringAfterLast(".", "")
        val finalName = when {
            newName.contains('.') -> newName
            ext.isNotEmpty()      -> "$newName.$ext"
            else                  -> newName
        }
        val newPath = if (dir.isEmpty() || dir == "") "/$finalName" else "$dir/$finalName"

        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { engine.nativeRenameFile(handle, file.path, newPath) }.getOrDefault(VeraCryptEngine.ERR_FS)
            val success = result == VeraCryptEngine.ERR_OK
            if (success) {
                refreshNow()
                _state.update { it.copy(pendingNotification = InAppNotification.FileRenamed(finalName)) }
            }
            withContext(Dispatchers.Main) { onResult(success) }
        }
    }

    fun importFiles(context: Context, uris: List<android.net.Uri>) {
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isOperationInProgress = true) }
            var count = 0
            val chunkSize = 1 * 1024 * 1024
            for (uri in uris) {
                try {
                    val rawName = getFileNameFromUri(context, uri) ?: continue
                    val name = File(rawName).name.ifEmpty { continue }
                    val destPath = buildDestinationPath(s.currentPath, name)
                    _state.update { it.copy(operationMessage = "Importing $name…") }
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        var offset = 0L
                        val buffer = ByteArray(chunkSize)
                        var read: Int
                        while (input.read(buffer).also { read = it } != -1) {
                            engine.nativeWriteFile(handle, destPath, buffer.copyOf(read), offset)
                            offset += read
                        }
                    }
                    count++
                } catch (_: Exception) { }
            }
            refreshNow()
            _state.update { it.copy(
                isOperationInProgress = false,
                operationMessage      = null,
                pendingNotification   = if (count > 0) InAppNotification.FilesImported(count) else null
            ) }
        }
    }

    fun exportSelected(context: Context, treeUri: android.net.Uri) {
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: return
        val toExport = s.selectedItems.mapNotNull { path -> s.files.find { it.path == path } }

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isOperationInProgress = true) }
            var count = 0
            val chunkSize = 1 * 1024 * 1024
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
            for (file in toExport) {
                try {
                    _state.update { it.copy(operationMessage = "Exporting ${file.name}…") }
                    if (file.isDirectory) {
                        val ok = exportDirectoryRecursive(context, handle, file.path, treeDocUri, file.name)
                        if (ok) count++
                    } else {
                        val docUri = DocumentsContract.createDocument(
                            context.contentResolver, treeDocUri,
                            getMimeType(file.name), file.name
                        ) ?: continue
                        context.contentResolver.openOutputStream(docUri)?.use { out ->
                            var offset = 0L
                            while (offset < file.size) {
                                val chunk = engine.nativeReadFile(handle, file.path, offset, chunkSize) ?: break
                                out.write(chunk)
                                offset += chunk.size
                                if (chunk.size < chunkSize) break
                            }
                        }
                        count++
                    }
                } catch (_: Exception) { }
            }
            exitSelectionMode()
            _state.update { it.copy(
                isOperationInProgress = false,
                operationMessage      = null,
                pendingNotification   = if (count > 0) InAppNotification.FilesExported(count) else null
            ) }
        }
    }

    fun prepareOpenWithExternalApp(context: Context, file: NativeFileInfo) {
        if (file.isDirectory) return
        val handle = repo.getContainerHandle(_state.value.containerId) ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isOperationInProgress = true, operationMessage = "Preparing ${file.name}…") }
            try {
                val tempDir = File(context.cacheDir, "arcanum_temp").also { it.mkdirs() }
                val safeName = File(file.name).name.ifEmpty { "file" }
                val tempFile = File(tempDir, safeName)
                val baos = ByteArrayOutputStream()
                val chunkSize = 1 * 1024 * 1024
                var offset = 0L
                while (offset < file.size) {
                    val chunk = engine.nativeReadFile(handle, file.path, offset, chunkSize) ?: break
                    baos.write(chunk)
                    offset += chunk.size
                    if (chunk.size < chunkSize) break
                }
                tempFile.writeBytes(baos.toByteArray())
                _tempFiles.add(tempFile)
                _state.update { it.copy(
                    isOperationInProgress = false,
                    operationMessage      = null,
                    tempFileToOpen        = Pair(tempFile, getMimeType(file.name))
                ) }
            } catch (_: Exception) {
                _state.update { it.copy(isOperationInProgress = false, operationMessage = null) }
            }
        }
    }

    fun clearTempFileToOpen() {
        _state.update { it.copy(tempFileToOpen = null) }
    }

    fun clearTempFiles(context: Context) {
        val iter = _tempFiles.iterator()
        while (iter.hasNext()) {
            runCatching { FileUtils.secureZeroAndDelete(iter.next()) }
            iter.remove()
        }
        runCatching {
            File(context.cacheDir, "arcanum_temp").listFiles()
                ?.forEach { FileUtils.secureZeroAndDelete(it) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        runCatching {
            File(context.cacheDir, "arcanum_temp").listFiles()
                ?.forEach { FileUtils.secureZeroAndDelete(it) }
        }
        _tempFiles.forEach { runCatching { FileUtils.secureZeroAndDelete(it) } }
        _tempFiles.clear()
    }

    fun clearPendingNotification() {
        _state.update { it.copy(pendingNotification = null) }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private suspend fun moveFile(
        srcHandle: Long, srcPath: String,
        destHandle: Long, destPath: String,
        fileSize: Long
    ): Boolean {
        val chunkSize = 1 * 1024 * 1024
        return try {
            var offset = 0L
            while (offset < fileSize) {
                val chunk = engine.nativeReadFile(srcHandle, srcPath, offset, chunkSize) ?: return false
                engine.nativeWriteFile(destHandle, destPath, chunk, offset)
                offset += chunk.size
                if (chunk.size < chunkSize) break
            }
            runCatching { engine.nativeDeleteFile(srcHandle, srcPath) }
            true
        } catch (_: Exception) { false }
    }

    private suspend fun exportDirectoryRecursive(
        context: Context,
        handle: Long,
        srcPath: String,
        parentDocUri: android.net.Uri,
        dirName: String
    ): Boolean {
        val dirUri = DocumentsContract.createDocument(
            context.contentResolver, parentDocUri,
            DocumentsContract.Document.MIME_TYPE_DIR, dirName
        ) ?: return false
        val entries = runCatching { engine.nativeListFiles(handle, srcPath).toList() }.getOrDefault(emptyList())
        val chunkSize = 1 * 1024 * 1024
        var allOk = true
        for (entry in entries) {
            val entryPath = if (srcPath == "/") "/${entry.name}" else "$srcPath/${entry.name}"
            try {
                if (entry.isDirectory) {
                    val ok = exportDirectoryRecursive(context, handle, entryPath, dirUri, entry.name)
                    if (!ok) allOk = false
                } else {
                    val docUri = DocumentsContract.createDocument(
                        context.contentResolver, dirUri,
                        getMimeType(entry.name), entry.name
                    ) ?: run { allOk = false; continue }
                    context.contentResolver.openOutputStream(docUri)?.use { out ->
                        var offset = 0L
                        while (offset < entry.size) {
                            val chunk = engine.nativeReadFile(handle, entryPath, offset, chunkSize) ?: break
                            out.write(chunk)
                            offset += chunk.size
                            if (chunk.size < chunkSize) break
                        }
                    }
                }
            } catch (_: Exception) { allOk = false }
        }
        return allOk
    }

    private suspend fun copyDirectoryRecursive(
        srcHandle: Long, srcPath: String,
        destHandle: Long, destPath: String
    ): Boolean {
        return try {
            runCatching { engine.nativeCreateDirectory(destHandle, destPath) }
            val entries = engine.nativeListFiles(srcHandle, srcPath).toList()
            var allCopied = true
            val chunkSize = 1 * 1024 * 1024
            for (entry in entries) {
                val srcEntry  = if (srcPath  == "/") "/${entry.name}" else "$srcPath/${entry.name}"
                val destEntry = if (destPath == "/") "/${entry.name}" else "$destPath/${entry.name}"
                val copied = if (entry.isDirectory) {
                    copyDirectoryRecursive(srcHandle, srcEntry, destHandle, destEntry)
                } else {
                    var offset = 0L
                    var ok = true
                    while (offset < entry.size) {
                        val chunk = engine.nativeReadFile(srcHandle, srcEntry, offset, chunkSize)
                            ?: run { ok = false; break }
                        engine.nativeWriteFile(destHandle, destEntry, chunk, offset)
                        offset += chunk.size
                        if (chunk.size < chunkSize) break
                    }
                    ok
                }
                if (!copied) allCopied = false
            }
            allCopied
        } catch (_: Exception) { false }
    }

    private suspend fun moveDirectoryRecursive(
        srcHandle: Long, srcPath: String,
        destHandle: Long, destPath: String
    ): Boolean {
        return try {
            runCatching { engine.nativeCreateDirectory(destHandle, destPath) }
            val entries = engine.nativeListFiles(srcHandle, srcPath).toList()
            var allMoved = true
            for (entry in entries) {
                val srcEntry  = if (srcPath  == "/") "/${entry.name}" else "$srcPath/${entry.name}"
                val destEntry = if (destPath == "/") "/${entry.name}" else "$destPath/${entry.name}"
                val moved = if (entry.isDirectory) {
                    moveDirectoryRecursive(srcHandle, srcEntry, destHandle, destEntry)
                } else {
                    moveFile(srcHandle, srcEntry, destHandle, destEntry, entry.size)
                }
                if (!moved) allMoved = false
            }
            if (allMoved) runCatching { engine.nativeDeleteDirectory(srcHandle, srcPath) }
            allMoved
        } catch (_: Exception) { false }
    }

    fun refreshCurrentDirectory() {
        loadDirectory(_state.value.currentPath)
    }

    // Inline (suspending) refresh — use this inside operation coroutines so
    // the listing completes before the notification is set.
    private suspend fun refreshNow() {
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: return
        runCatching {
            val rawFiles = engine.nativeListFiles(handle, s.currentPath).toList()
            _state.update { st ->
                val files = applyFiltersAndSort(rawFiles, st.sortBy, st.sortAscending, st.showHidden, st.foldersFirst, st.searchQuery)
                st.copy(rawFiles = rawFiles, files = files)
            }
        }
    }

    private fun loadDirectory(path: String) {
        val containerId = _state.value.containerId
        val handle = repo.getContainerHandle(containerId) ?: run {
            _state.update { it.copy(isLoading = false, error = "Vault not mounted") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val rawFiles = engine.nativeListFiles(handle, path).toList()
                _state.update { s ->
                    val files = applyFiltersAndSort(rawFiles, s.sortBy, s.sortAscending, s.showHidden, s.foldersFirst, s.searchQuery)
                    s.copy(
                        rawFiles        = rawFiles,
                        files           = files,
                        isLoading       = false,
                        currentPath     = path,
                        pathSegments    = buildPathSegments(path)
                    )
                }
            } catch (_: Exception) {
                _state.update { it.copy(isLoading = false, error = "Failed to list directory") }
            }
        }
    }

    private fun applyFiltersAndSort(
        rawFiles: List<NativeFileInfo>,
        sortBy: SortBy,
        ascending: Boolean,
        showHidden: Boolean,
        foldersFirst: Boolean,
        query: String
    ): List<NativeFileInfo> {
        var result = rawFiles
        if (!showHidden) result = result.filter { !it.name.startsWith(".") }
        if (query.isNotBlank()) result = result.filter { it.name.contains(query, ignoreCase = true) }
        val comparator: Comparator<NativeFileInfo> = when (sortBy) {
            SortBy.NAME -> compareBy { it.name.lowercase() }
            SortBy.DATE -> compareBy { it.lastModified }
            SortBy.SIZE -> compareBy { it.size }
            SortBy.TYPE -> compareBy { it.name.substringAfterLast('.', "").lowercase() }
        }
        result = if (ascending) result.sortedWith(comparator) else result.sortedWith(comparator.reversed())
        return if (foldersFirst) result.sortedWith(compareByDescending { it.isDirectory }) else result
    }

    private fun buildPathSegments(path: String): List<String> {
        if (path == "/") return listOf("/")
        return listOf("/") + path.split("/").filter { it.isNotEmpty() }
    }

    private fun buildDestinationPath(currentPath: String, fileName: String): String =
        if (currentPath == "/") "/$fileName" else "$currentPath/$fileName"

    private fun getFileNameFromUri(context: Context, uri: android.net.Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        } ?: uri.lastPathSegment

    fun getMimeType(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "jpg", "jpeg" -> "image/jpeg"
            "png"         -> "image/png"
            "gif"         -> "image/gif"
            "webp"        -> "image/webp"
            "bmp"         -> "image/bmp"
            "mp4", "m4v"  -> "video/mp4"
            "mkv"         -> "video/x-matroska"
            "avi"         -> "video/avi"
            "mov"         -> "video/quicktime"
            "mp3"         -> "audio/mpeg"
            "m4a", "aac"  -> "audio/aac"
            "ogg"         -> "audio/ogg"
            "flac"        -> "audio/flac"
            "wav"         -> "audio/wav"
            "pdf"         -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "txt", "md"   -> "text/plain"
            "zip"         -> "application/zip"
            "rar"         -> "application/x-rar-compressed"
            "7z"          -> "application/x-7z-compressed"
            "json"        -> "application/json"
            "xml"         -> "application/xml"
            "html", "htm" -> "text/html"
            "apk"         -> "application/vnd.android.package-archive"
            else          -> "application/octet-stream"
        }
    }

    fun formatFileSize(bytes: Long): String = when {
        bytes < 1024L        -> "$bytes B"
        bytes < 1024L * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
    }

    private fun savePrefs() {
        viewModelScope.launch {
            val s = _state.value
            prefs.edit { p ->
                p[SORT_BY_KEY]       = s.sortBy.name
                p[SORT_ASC_KEY]      = s.sortAscending
                p[SHOW_HIDDEN_KEY]   = s.showHidden
                p[FOLDERS_FIRST_KEY] = s.foldersFirst
                p[VIEW_MODE_KEY]     = s.viewMode.name
            }
        }
    }

}
