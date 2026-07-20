package zip.arcanum.arcanum.files.ui

import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
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
import zip.arcanum.arcanum.gallery.MediaScanner
import zip.arcanum.arcanum.gallery.ThumbnailManager
import zip.arcanum.arcanum.saf.VaultDocumentsProvider
import zip.arcanum.R
import kotlinx.coroutines.coroutineScope
import zip.arcanum.core.utils.MediaExtensions
import zip.arcanum.core.database.dao.MediaFileDao
import zip.arcanum.core.database.entities.MediaFileType
import zip.arcanum.core.notifications.ImportFailureReason
import zip.arcanum.core.notifications.InAppNotification
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import zip.arcanum.crypto.VeraCryptEngine
import zip.arcanum.crypto.NativeFileInfo
import java.io.File
import javax.inject.Inject

private val Context.fileManagerPrefs: DataStore<Preferences> by preferencesDataStore("file_manager_prefs")

@HiltViewModel
class FileManagerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: VeraCryptEngine,
    private val clipboard: FileClipboard,
    private val repo: ContainerRepository,
    private val audioQueue: AudioPlayerQueue,
    private val thumbnailManager: ThumbnailManager,
    private val mediaScanner: MediaScanner,
    private val mediaFileDao: MediaFileDao
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
        val isOperationInProgress: Boolean = false,
        val operationMessage: String? = null,
        val isReadOnly: Boolean = false,
        val thumbnails: Map<String, android.graphics.Bitmap> = emptyMap()
    )

    private val _state = MutableStateFlow(FileManagerState())
    val state = _state.asStateFlow()

    val mountedContainers: kotlinx.coroutines.flow.StateFlow<List<Container>> = repo.getAllContainers()
        .map { list -> list.filter { it.isMounted } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var initialized = false
    private val loadingThumbnails = mutableSetOf<String>()
    private val thumbnailSemaphore = Semaphore(4)

    private val prefs = context.fileManagerPrefs

    companion object {
        private val SORT_BY_KEY      = stringPreferencesKey("sort_by")
        private val SORT_ASC_KEY     = booleanPreferencesKey("sort_asc")
        private val SHOW_HIDDEN_KEY  = booleanPreferencesKey("show_hidden")
        private val FOLDERS_FIRST_KEY = booleanPreferencesKey("folders_first")
        private val VIEW_MODE_KEY    = stringPreferencesKey("view_mode")
        private const val MAX_FM_THUMBNAILS = 80
    }

    fun requestThumbnail(file: NativeFileInfo) {
        if (file.isDirectory) return
        val ext = file.name.substringAfterLast('.', "").lowercase()
        val type = when (ext) {
            in MediaExtensions.IMAGE -> MediaFileType.IMAGE
            in MediaExtensions.VIDEO -> MediaFileType.VIDEO
            // ThumbnailManager has always known how to pull embedded cover art; the file
            // browser just never asked for it.
            in MediaExtensions.AUDIO -> MediaFileType.AUDIO
            else -> return
        }
        if (!loadingThumbnails.add(file.path)) return
        val containerId = _state.value.containerId
        val handle = repo.getContainerHandle(containerId) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            thumbnailSemaphore.withPermit {
                val bitmap = thumbnailManager.getThumbnail(engine, handle, containerId, file.path, type, file.size)
                withContext(Dispatchers.Main) {
                    if (bitmap != null) {
                        _state.update { s ->
                            val map = LinkedHashMap(s.thumbnails)
                            map[file.path] = bitmap
                            while (map.size > MAX_FM_THUMBNAILS) map.iterator().also { it.next(); it.remove() }
                            s.copy(thumbnails = map)
                        }
                    } else if (type != MediaFileType.AUDIO) {
                        // Allow a retry on a later pass: an image or video that yielded
                        // nothing usually hit a transient read failure. Audio is different -
                        // plenty of tracks simply carry no artwork, and that will not change,
                        // so retrying would reopen the extractor over encrypted storage for
                        // every art-less track on every scroll.
                        loadingThumbnails.remove(file.path)
                    }
                }
            }
        }
    }

    fun initialize(containerId: String) {
        if (initialized && _state.value.containerId == containerId) return
        initialized = true
        _state.update { it.copy(containerId = containerId, isReadOnly = repo.isContainerReadOnly(containerId)) }
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

    // Resolves a tapped media file to its Gallery DB id so the Files browser can open the shared
    // media viewer (MediaViewerScreen). Falls back to indexing the file on the spot if the gallery
    // scan hasn't reached it yet. Result is delivered on the main thread; null means unresolvable.
    fun openMediaFile(clickedFile: NativeFileInfo, onResult: (String?) -> Unit) {
        val cid = _state.value.containerId
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                mediaFileDao.getByPath(cid, clickedFile.path)?.id
                    ?: repo.getContainerHandle(cid)?.let { handle ->
                        mediaScanner.indexFile(handle, cid, clickedFile.path, clickedFile.size)?.id
                    }
            }
            onResult(id)
        }
    }

    /**
     * Outcome of asking to hand a file to another app (#103).
     */
    sealed interface OpenWithRequest {
        data class Ready(val chooser: Intent) : OpenWithRequest
        /** The vault's External app access is off, so the provider would refuse the URI. */
        data object NeedsExternalAccess : OpenWithRequest
        /** The vault is no longer mounted - nothing to hand over. */
        data object Unavailable : OpenWithRequest
    }

    fun requestOpenWith(file: NativeFileInfo, onResult: (OpenWithRequest) -> Unit) {
        val cid = _state.value.containerId
        viewModelScope.launch {
            if (!repo.isExternalAccessEnabled(cid)) {
                onResult(OpenWithRequest.NeedsExternalAccess)
            } else {
                onResult(buildOpenWithChooser(cid, file))
            }
        }
    }

    /**
     * Called only after the user has agreed to the prompt. Exposing a vault to other apps is
     * the user's decision to make, so this never runs as a silent side effect of Open with.
     */
    fun enableExternalAccessAndOpenWith(file: NativeFileInfo, onResult: (OpenWithRequest) -> Unit) {
        val cid = _state.value.containerId
        viewModelScope.launch {
            repo.updateExternalAccessEnabled(cid, true)
            onResult(buildOpenWithChooser(cid, file))
        }
    }

    /**
     * Hands out a SAF URI rather than a decrypted copy. VaultDocumentsProvider serves it through
     * a proxy file descriptor, so the other app reads and seeks straight out of the mounted vault
     * and no plaintext is ever written to disk - which is what #103 asks for over an export.
     */
    private fun buildOpenWithChooser(cid: String, file: NativeFileInfo): OpenWithRequest {
        if (repo.getContainerHandle(cid) == null) return OpenWithRequest.Unavailable

        val uri = DocumentsContract.buildDocumentUri(
            VaultDocumentsProvider.authority(context),
            VaultDocumentsProvider.documentId(cid, file.path)
        )
        // "*/*" rather than octet-stream for an unknown extension: octet-stream makes most
        // players decline the intent outright, leaving an empty chooser.
        val mime = getMimeType(file.name).takeIf { it != "application/octet-stream" } ?: "*/*"

        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return OpenWithRequest.Ready(
            Intent.createChooser(view, context.getString(R.string.files_action_open_with))
        )
    }

    fun setAudioQueue(clickedFile: NativeFileInfo) {
        val audioFiles = _state.value.files.filter {
            !it.isDirectory && it.name.substringAfterLast('.', "").lowercase() in MediaExtensions.AUDIO
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
        _state.update { it.copy(sortBy = sortBy) }
        viewModelScope.launch(Dispatchers.Default) {
            val s = _state.value
            val files = applyFiltersAndSort(s.rawFiles, sortBy, s.sortAscending, s.showHidden, s.foldersFirst, s.searchQuery)
            _state.update { it.copy(files = files) }
        }
        savePrefs()
    }

    fun toggleSortDirection() {
        val asc = !_state.value.sortAscending
        _state.update { it.copy(sortAscending = asc) }
        viewModelScope.launch(Dispatchers.Default) {
            val s = _state.value
            val files = applyFiltersAndSort(s.rawFiles, s.sortBy, asc, s.showHidden, s.foldersFirst, s.searchQuery)
            _state.update { it.copy(files = files) }
        }
        savePrefs()
    }

    fun toggleFoldersFirst() {
        val ff = !_state.value.foldersFirst
        _state.update { it.copy(foldersFirst = ff) }
        viewModelScope.launch(Dispatchers.Default) {
            val s = _state.value
            val files = applyFiltersAndSort(s.rawFiles, s.sortBy, s.sortAscending, s.showHidden, ff, s.searchQuery)
            _state.update { it.copy(files = files) }
        }
        savePrefs()
    }

    fun toggleShowHidden() {
        val sh = !_state.value.showHidden
        _state.update { it.copy(showHidden = sh) }
        viewModelScope.launch(Dispatchers.Default) {
            val s = _state.value
            val files = applyFiltersAndSort(s.rawFiles, s.sortBy, s.sortAscending, sh, s.foldersFirst, s.searchQuery)
            _state.update { it.copy(files = files) }
        }
        savePrefs()
    }

    // ── Search ────────────────────────────────────────────────────────────

    fun toggleSearch() {
        val active = !_state.value.isSearchActive
        _state.update { it.copy(isSearchActive = active, searchQuery = if (!active) "" else it.searchQuery) }
        if (!active) {
            viewModelScope.launch(Dispatchers.Default) {
                val s = _state.value
                val files = applyFiltersAndSort(s.rawFiles, s.sortBy, s.sortAscending, s.showHidden, s.foldersFirst, "")
                _state.update { it.copy(files = files) }
            }
        }
    }

    fun setSearchActive(active: Boolean) {
        if (!active) {
            _state.update { it.copy(isSearchActive = false, searchQuery = "") }
            viewModelScope.launch(Dispatchers.Default) {
                val s = _state.value
                val files = applyFiltersAndSort(s.rawFiles, s.sortBy, s.sortAscending, s.showHidden, s.foldersFirst, "")
                _state.update { it.copy(files = files) }
            }
        } else {
            _state.update { it.copy(isSearchActive = true) }
        }
    }

    fun setSearchQuery(query: String) {
        _state.update { it.copy(searchQuery = query) }
        viewModelScope.launch(Dispatchers.Default) {
            val s = _state.value
            val files = applyFiltersAndSort(s.rawFiles, s.sortBy, s.sortAscending, s.showHidden, s.foldersFirst, query)
            _state.update { it.copy(files = files) }
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
        if (s.isReadOnly) return
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
        if (_state.value.isReadOnly) return
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

            /* Names already in the destination, used to pick a free one when
               copying an item into the folder it already lives in. */
            val takenNames = runCatching {
                engine.listFilesOrNull(destHandle, currentPath)?.map { it.name }?.toMutableSet()
            }.getOrNull() ?: mutableSetOf()

            for (item in clipItems) {
                try {
                    val sameContainer = item.sourceContainerId == destContainerId
                    val sourceParent  = item.sourcePath.substringBeforeLast("/").ifEmpty { "/" }
                    val intoOwnFolder = sameContainer && sourceParent == currentPath

                    /* Pasting into the folder the item is already in (#113).
                       Source and destination paths are identical here, so the
                       copy below would write the item onto itself and the delete
                       that follows a cut would then destroy it outright. A move
                       to where it already is has nothing to do. */
                    if (intoOwnFolder && isCut) continue

                    /* Pasting a folder into itself or into its own subtree (cut
                       /a, paste in /a or /a/b) would recurse into the copy it is
                       creating and then delete the original. Refuse instead. */
                    val srcDir = item.sourcePath.trimEnd('/')
                    val intoOwnSubtree = sameContainer && item.isDirectory &&
                        (currentPath == srcDir || currentPath.startsWith("$srcDir/"))
                    if (intoOwnSubtree) continue

                    /* A copy into the same folder becomes a duplicate rather than
                       a write onto itself, so it behaves like every other file
                       manager instead of silently doing nothing. */
                    val destName = if (intoOwnFolder) freeName(item.fileName, takenNames)
                                   else item.fileName
                    takenNames.add(destName)

                    val destPath = if (currentPath == "/") "/$destName" else "$currentPath/$destName"
                    _state.update { it.copy(operationMessage = "${if (isCut) "Moving" else "Copying"} ${item.fileName}…") }
                    if (item.isDirectory) {
                        val ok = copyDirectoryRecursive(item.sourceHandle, item.sourcePath, destHandle, destPath)
                        if (ok && isCut) runCatching { engine.deleteDirectory(item.sourceHandle, item.sourcePath) }
                        if (ok) count++
                    } else {
                        var offset = 0L
                        var writeOk = true
                        while (true) {
                            val chunk = engine.readFile(item.sourceHandle, item.sourcePath, offset, chunkSize) ?: run { writeOk = false; break }
                            val rc = engine.writeFile(destHandle, destPath, chunk, offset)
                            if (rc != VeraCryptEngine.ERR_OK) { writeOk = false; break }
                            offset += chunk.size
                            if (chunk.size < chunkSize) break
                        }
                        if (!writeOk) runCatching { engine.deleteFile(destHandle, destPath) }
                        if (writeOk) {
                            if (isCut) runCatching { engine.deleteFile(item.sourceHandle, item.sourcePath) }
                            count++
                        }
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

    /**
     * First name of the form "base (n).ext" not already in [taken], starting at
     * "base (1).ext". Used when copying an item into the folder it already
     * lives in, where reusing the name would mean writing it onto itself.
     */
    private fun freeName(fileName: String, taken: Set<String>): String {
        if (fileName !in taken) return fileName
        val dot  = fileName.lastIndexOf('.')
        val base = if (dot > 0) fileName.substring(0, dot) else fileName
        val ext  = if (dot > 0) fileName.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (candidate !in taken) return candidate
            n++
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
                        var writeOk = true
                        while (offset < item.size) {
                            val chunk = engine.readFile(sourceHandle, item.path, offset, chunkSize) ?: run { writeOk = false; break }
                            val rc = engine.writeFile(destHandle, destItemPath, chunk, offset)
                            if (rc != VeraCryptEngine.ERR_OK) { writeOk = false; break }
                            offset += chunk.size
                            if (chunk.size < chunkSize) break
                        }
                        if (!writeOk) runCatching { engine.deleteFile(destHandle, destItemPath) }
                        if (writeOk) count++
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
                            engine.renameFile(sourceHandle, item.path, destItemPath)
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
                engine.listFilesOrNull(handle, path)?.toList()?.filter { it.isDirectory }
            }.getOrNull() ?: emptyList()
            withContext(Dispatchers.Main) { onResult(dirs) }
        }
    }

    // ── Post-import indexing & thumbnail generation ───────────────────────

    private suspend fun indexAndThumbnail(
        handle: Long,
        containerId: String,
        files: List<Pair<String, Long>>
    ) {
        // Phase 1: index all files into DB sequentially (fast — just EXIF read + insert).
        // Do this before thumbnail generation so Gallery's Room Flow sees them immediately.
        val entities = files.mapNotNull { (path, size) ->
            mediaScanner.indexFile(handle, containerId, path, size)
        }
        if (entities.isEmpty()) return

        // Explicitly notify GalleryViewModel in case Room Flow debounce hasn't fired yet.
        thumbnailManager.notifyFilesImported(containerId)

        // Phase 2: generate thumbnails concurrently (slow — image decode + cache write).
        // flatMapMerge bounds both coroutine count AND concurrency to 3; a raw forEach+launch
        // with a Semaphore would spawn entities.size coroutines immediately regardless of cap.
        entities.asFlow()
            .flatMapMerge(concurrency = 3) { entity ->
                flow {
                    thumbnailManager.getThumbnail(engine, handle, entity)
                    emit(Unit)
                }
            }
            .collect {}
    }

    // ── File operations ───────────────────────────────────────────────────

    fun deleteSelected() {
        val s = _state.value
        if (s.isReadOnly) return
        val handle = repo.getContainerHandle(s.containerId) ?: return
        val toDelete = s.selectedItems.mapNotNull { path -> s.files.find { it.path == path } }

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isOperationInProgress = true) }
            var count = 0
            for (file in toDelete) {
                val rc = runCatching {
                    if (file.isDirectory) engine.deleteDirectory(handle, file.path)
                    else engine.deleteFile(handle, file.path)
                }.getOrDefault(VeraCryptEngine.ERR_FS)
                if (rc == VeraCryptEngine.ERR_OK) {
                    if (file.isDirectory) cleanupDirectoryMedia(s.containerId, file.path)
                    else cleanupFileMedia(s.containerId, file.path)
                    count++
                }
            }
            exitSelectionMode()
            refreshNow()
            _state.update { it.copy(
                isOperationInProgress = false,
                pendingNotification   = if (count > 0) InAppNotification.FilesDeleted(count) else null
            ) }
            if (count > 0) thumbnailManager.notifyFilesDeleted(s.containerId)
        }
    }

    private suspend fun cleanupFileMedia(containerId: String, path: String) {
        // Get entity first for fileId (needed to evict bitmap from GalleryViewModel memory).
        val entity = mediaFileDao.getByPath(containerId, path)
        // Direct @Query DELETE works even if getByPath returned null (path format mismatch edge case).
        mediaFileDao.deleteByPath(containerId, path)
        if (entity != null) {
            thumbnailManager.clearFileCache(entity.containerId, entity.relativePath, entity.id)
        } else {
            thumbnailManager.deleteFileCacheEntry(containerId, path)
        }
    }

    private suspend fun cleanupDirectoryMedia(containerId: String, dirPath: String) {
        val prefix = if (dirPath.endsWith("/")) dirPath else "$dirPath/"
        // Evict bitmaps for any entity we can identify by path prefix.
        mediaFileDao.getAllForContainerOnce(containerId)
            .filter { it.relativePath.startsWith(prefix) }
            .forEach { entity ->
                thumbnailManager.clearFileCache(entity.containerId, entity.relativePath, entity.id)
            }
        // Direct DELETE triggers Room's InvalidationTracker for Gallery's Flow.
        mediaFileDao.deleteByPathPrefix(containerId, prefix)
    }

    fun createFolder(name: String) {
        val s = _state.value
        if (s.isReadOnly) {
            _state.update { it.copy(pendingNotification = InAppNotification.ReadOnlyError) }
            return
        }
        val handle = repo.getContainerHandle(s.containerId) ?: return
        val folderPath = if (s.currentPath == "/") "/$name" else "${s.currentPath}/$name"

        viewModelScope.launch(Dispatchers.IO) {
            val rc = runCatching { engine.createDirectory(handle, folderPath) }.getOrDefault(VeraCryptEngine.ERR_FS)
            refreshNow()
            _state.update {
                it.copy(pendingNotification = if (rc == VeraCryptEngine.ERR_OK) InAppNotification.FolderCreated(name) else InAppNotification.ReadOnlyError)
            }
        }
    }

    fun renameFile(file: NativeFileInfo, newName: String, onResult: (Boolean) -> Unit) {
        if (_state.value.isReadOnly) { onResult(false); return }
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: return
        val dir = file.path.substringBeforeLast("/", "")
        val ext = file.name.substringAfterLast(".", "")
        // Folders have no extension to preserve: re-appending one would turn
        // "photos.2026" renamed to "archive" into "archive.2026".
        val finalName = when {
            file.isDirectory      -> newName
            newName.contains('.') -> newName
            ext.isNotEmpty()      -> "$newName.$ext"
            else                  -> newName
        }
        val newPath = if (dir.isEmpty() || dir == "") "/$finalName" else "$dir/$finalName"

        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching { engine.renameFile(handle, file.path, newPath) }.getOrDefault(VeraCryptEngine.ERR_FS)
            val success = result == VeraCryptEngine.ERR_OK
            if (success) {
                refreshNow()
                _state.update { it.copy(pendingNotification = InAppNotification.FileRenamed(finalName)) }
            }
            withContext(Dispatchers.Main) { onResult(success) }
        }
    }

    fun importFiles(context: Context, uris: List<android.net.Uri>, deleteAfterImport: Boolean = false) {
        val s = _state.value
        if (s.isReadOnly) {
            _state.update { it.copy(pendingNotification = InAppNotification.ReadOnlyError) }
            return
        }
        val handle = repo.getContainerHandle(s.containerId) ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isOperationInProgress = true) }
            var count = 0
            var hiddenProtected = false
            /* Null while everything is fine; set to the first failing native
               code so the banner can say what actually went wrong (#114). */
            var failureCode: Int? = null
            val chunkSize = 1 * 1024 * 1024
            val importedMedia = mutableListOf<Pair<String, Long>>()
            for (uri in uris) {
                if (hiddenProtected || failureCode != null) break
                try {
                    val rawName = getFileNameFromUri(context, uri) ?: continue
                    val name = File(rawName).name.ifEmpty { continue }
                    val destPath = buildDestinationPath(s.currentPath, name)
                    _state.update { it.copy(operationMessage = "Importing $name…") }
                    var fileOk = false
                    var fileSize = 0L
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        var offset = 0L
                        val buffer = ByteArray(chunkSize)
                        var read: Int = 0
                        var done = false
                        while (!done && input.read(buffer).also { read = it } != -1) {
                            val rc = engine.writeFile(handle, destPath, buffer.copyOf(read), offset)
                            when {
                                rc == VeraCryptEngine.ERR_HIDDEN_BOUNDARY -> {
                                    hiddenProtected = true
                                    runCatching { engine.deleteFile(handle, destPath) }
                                    done = true
                                }
                                rc != VeraCryptEngine.ERR_OK -> {
                                    failureCode = rc
                                    runCatching { engine.deleteFile(handle, destPath) }
                                    done = true
                                }
                                else -> { offset += read; fileOk = true }
                            }
                        }
                        fileSize = offset
                    }
                    if (!hiddenProtected && failureCode == null) {
                        count++
                        val ext = name.substringAfterLast('.', "").lowercase()
                        if (ext in MediaExtensions.IMAGE || ext in MediaExtensions.VIDEO) {
                            importedMedia.add(Pair(destPath, fileSize))
                        }
                        if (deleteAfterImport && fileOk)
                            runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    }
                } catch (_: Exception) { }
            }
            refreshNow()
            _state.update { it.copy(
                isOperationInProgress = false,
                operationMessage      = null,
                pendingNotification   = when {
                    hiddenProtected      -> InAppNotification.HiddenVolumeWriteProtection
                    failureCode != null  -> InAppNotification.ImportFailed(importFailureReason(failureCode))
                    count > 0            -> InAppNotification.FilesImported(count)
                    else                 -> null
                }
            ) }
            if (importedMedia.isNotEmpty()) {
                indexAndThumbnail(handle, s.containerId, importedMedia)
            }
        }
    }

    fun importFolder(context: Context, treeUri: android.net.Uri, deleteAfterImport: Boolean = false) {
        val s = _state.value
        if (s.isReadOnly) {
            _state.update { it.copy(pendingNotification = InAppNotification.ReadOnlyError) }
            return
        }
        val handle = repo.getContainerHandle(s.containerId) ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isOperationInProgress = true) }

            val rootDocId  = DocumentsContract.getTreeDocumentId(treeUri)
            val rootDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId)
            val folderName = context.contentResolver.query(
                rootDocUri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                ?: rootDocId.substringAfterLast('/')

            val destPath = buildDestinationPath(s.currentPath, folderName)
            runCatching { engine.createDirectory(handle, destPath) }

            val importedMedia = mutableListOf<Pair<String, Long>>()
            val (count, hiddenProtected, failureCode) =
                importFolderRecursive(context, handle, treeUri, rootDocId, destPath, importedMedia)

            if (deleteAfterImport && !hiddenProtected && failureCode == null && count > 0)
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, rootDocUri) }

            refreshNow()
            _state.update { it.copy(
                isOperationInProgress = false,
                operationMessage      = null,
                pendingNotification   = when {
                    hiddenProtected      -> InAppNotification.HiddenVolumeWriteProtection
                    failureCode != null  -> InAppNotification.ImportFailed(importFailureReason(failureCode))
                    count > 0            -> InAppNotification.FilesImported(count)
                    else                 -> null
                }
            ) }
            if (importedMedia.isNotEmpty()) {
                indexAndThumbnail(handle, s.containerId, importedMedia)
            }
        }
    }

    /**
     * Turns the native write error into the reason the banner reports.
     *
     * Every failure used to be shown as "not enough space in the vault", which
     * is the one thing the code path could not actually distinguish (#114).
     * ERR_FILE stays UNKNOWN on purpose: it covers an unopenable path, a name
     * FatFs rejects and a stale handle, which have nothing useful in common to
     * tell the user.
     */
    private fun importFailureReason(code: Int): ImportFailureReason = when (code) {
        VeraCryptEngine.ERR_DIR_FULL  -> ImportFailureReason.DIRECTORY_FULL
        VeraCryptEngine.ERR_NO_SPACE  -> ImportFailureReason.NO_SPACE
        VeraCryptEngine.ERR_READ_ONLY -> ImportFailureReason.READ_ONLY
        else                          -> ImportFailureReason.UNKNOWN
    }

    // Reduces an untrusted SAF display name to a bare filename, rejecting empty,
    // "." and ".." so it can never be used to climb out of the destination folder.
    private fun sanitizeEntryName(name: String): String? =
        File(name).name.takeUnless { it.isEmpty() || it == "." || it == ".." }

    private suspend fun importFolderRecursive(
        context: Context,
        handle: Long,
        treeUri: android.net.Uri,
        docId: String,
        destPath: String,
        importedMedia: MutableList<Pair<String, Long>> = mutableListOf()
    ): Triple<Int, Boolean, Int?> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection  = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
        )
        var count           = 0
        var hiddenProtected = false
        /* First failing native code, or null while everything succeeded. */
        var failureCode: Int? = null
        val chunkSize       = 1 * 1024 * 1024

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol   = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)

            while (cursor.moveToNext() && !hiddenProtected && failureCode == null) {
                val childDocId  = cursor.getString(idCol) ?: continue
                val childName   = cursor.getString(nameCol) ?: continue
                val childMime   = cursor.getString(mimeCol) ?: continue
                // Strip any path components a hostile DocumentsProvider might smuggle in
                // COLUMN_DISPLAY_NAME before using it as an in-vault path (defense-in-depth;
                // the single-file import path sanitizes the same way).
                val safeName    = sanitizeEntryName(childName) ?: continue
                val childDest   = buildDestinationPath(destPath, safeName)

                try {
                    if (childMime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        runCatching { engine.createDirectory(handle, childDest) }
                        val (sub, subProtected, subFailure) =
                            importFolderRecursive(context, handle, treeUri, childDocId, childDest, importedMedia)
                        count += sub
                        if (subProtected) hiddenProtected = true
                        if (subFailure != null) failureCode = subFailure
                    } else {
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                        _state.update { it.copy(operationMessage = "Importing $childName…") }
                        var childFileSize = 0L
                        context.contentResolver.openInputStream(childUri)?.use { input ->
                            var offset = 0L
                            val buffer = ByteArray(chunkSize)
                            var read = 0
                            var done = false
                            while (!done && input.read(buffer).also { read = it } != -1) {
                                val rc = engine.writeFile(handle, childDest, buffer.copyOf(read), offset)
                                when {
                                    rc == VeraCryptEngine.ERR_HIDDEN_BOUNDARY -> {
                                        hiddenProtected = true
                                        runCatching { engine.deleteFile(handle, childDest) }
                                        done = true
                                    }
                                    rc != VeraCryptEngine.ERR_OK -> {
                                        failureCode = rc
                                        runCatching { engine.deleteFile(handle, childDest) }
                                        done = true
                                    }
                                    else -> { offset += read }
                                }
                            }
                            childFileSize = offset
                        }
                        if (!hiddenProtected && failureCode == null) {
                            count++
                            val ext = childName.substringAfterLast('.', "").lowercase()
                            if (ext in MediaExtensions.IMAGE || ext in MediaExtensions.VIDEO) {
                                importedMedia.add(Pair(childDest, childFileSize))
                            }
                        }
                    }
                } catch (_: Exception) { failureCode = VeraCryptEngine.ERR_FS }
            }
        }
        return Triple(count, hiddenProtected, failureCode)
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
                                val chunk = engine.readFile(handle, file.path, offset, chunkSize) ?: break
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
                val chunk = engine.readFile(srcHandle, srcPath, offset, chunkSize) ?: return false
                val rc = engine.writeFile(destHandle, destPath, chunk, offset)
                if (rc != VeraCryptEngine.ERR_OK) {
                    runCatching { engine.deleteFile(destHandle, destPath) }
                    return false
                }
                offset += chunk.size
                if (chunk.size < chunkSize) break
            }
            runCatching { engine.deleteFile(srcHandle, srcPath) }
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
        val entries = runCatching { engine.listFilesOrNull(handle, srcPath)?.toList() }.getOrNull()
            ?: return false
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
                            val chunk = engine.readFile(handle, entryPath, offset, chunkSize) ?: break
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
            runCatching { engine.createDirectory(destHandle, destPath) }
            val entries = engine.listFilesOrNull(srcHandle, srcPath)?.toList()
                ?: return false
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
                        val chunk = engine.readFile(srcHandle, srcEntry, offset, chunkSize)
                            ?: run { ok = false; break }
                        val rc = engine.writeFile(destHandle, destEntry, chunk, offset)
                        if (rc != VeraCryptEngine.ERR_OK) { ok = false; break }
                        offset += chunk.size
                        if (chunk.size < chunkSize) break
                    }
                    if (!ok) runCatching { engine.deleteFile(destHandle, destEntry) }
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
            runCatching { engine.createDirectory(destHandle, destPath) }
            val entries = engine.listFilesOrNull(srcHandle, srcPath)?.toList()
                ?: return false
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
            if (allMoved) runCatching { engine.deleteDirectory(srcHandle, srcPath) }
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
            val rawFiles = engine.listFilesOrNull(handle, s.currentPath)
                ?: return  // null = disk error, not empty dir
            _state.update { st ->
                val files = applyFiltersAndSort(rawFiles.toList(), st.sortBy, st.sortAscending, st.showHidden, st.foldersFirst, st.searchQuery)
                st.copy(rawFiles = rawFiles.toList(), files = files)
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
                val rawFiles = engine.listFilesOrNull(handle, path)
                if (rawFiles == null) {
                    _state.update { it.copy(isLoading = false, error = "Failed to list directory") }
                    return@launch
                }
                val rawList = rawFiles.toList()
                _state.update { s ->
                    val files = applyFiltersAndSort(rawList, s.sortBy, s.sortAscending, s.showHidden, s.foldersFirst, s.searchQuery)
                    s.copy(
                        rawFiles        = rawList,
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
