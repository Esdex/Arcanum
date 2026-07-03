package zip.arcanum.arcanum.files.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.containers.domain.Container
import zip.arcanum.arcanum.files.domain.ClipboardItem
import zip.arcanum.arcanum.files.domain.FileClipboard
import zip.arcanum.arcanum.files.domain.PreparedShareFiles
import zip.arcanum.arcanum.files.domain.VaultFileTransfer
import zip.arcanum.arcanum.files.domain.VaultTransferItem
import zip.arcanum.arcanum.gallery.AudioPlayerQueue
import zip.arcanum.arcanum.gallery.MediaScanner
import zip.arcanum.arcanum.gallery.MediaViewerQueue
import zip.arcanum.arcanum.gallery.ThumbnailManager
import zip.arcanum.core.database.dao.MediaFileDao
import zip.arcanum.core.lifecycle.ExternalActivityGuard
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.security.AppPreferences
import zip.arcanum.core.utils.FileUtils
import zip.arcanum.crypto.NativeFileInfo
import zip.arcanum.crypto.VeraCryptEngine
import java.io.File
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import kotlin.coroutines.coroutineContext

private val Context.fileManagerPrefs: DataStore<Preferences> by preferencesDataStore("file_manager_prefs")

private val AUDIO_EXTENSIONS_VM = setOf("mp3", "m4a", "aac", "ogg", "flac", "wav", "opus")
private val MEDIA_EXTENSIONS_VM = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif",
    "mp4", "mkv", "avi", "mov", "m4v", "webm", "3gp"
)
private const val MEDIASTORE_DATE_TAKEN_COLUMN = "datetaken"
private const val TIMESTAMP_SECONDS_THRESHOLD = 10_000_000_000L

@HiltViewModel
class FileManagerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: VeraCryptEngine,
    private val clipboard: FileClipboard,
    private val repo: ContainerRepository,
    private val audioQueue: AudioPlayerQueue,
    private val mediaQueue: MediaViewerQueue,
    private val appPrefs: AppPreferences,
    private val mediaScanner: MediaScanner,
    private val thumbnailManager: ThumbnailManager,
    private val mediaFileDao: MediaFileDao,
    private val externalActivityGuard: ExternalActivityGuard
) : ViewModel() {

    enum class ViewMode { LIST, GRID }
    enum class SortBy { NAME, DATE, SIZE, TYPE }

    private enum class ImportWriteResult { IMPORTED, HIDDEN_PROTECTED, FAILED }

    private data class ImportStats(
        var count: Int = 0,
        var deleteSkipped: Int = 0,
        var hiddenProtected: Boolean = false,
        var importFailed: Boolean = false
    )

    data class VaultStorageUsage(
        val usedBytes: Long,
        val totalBytes: Long
    ) {
        val percent: Int
            get() = if (totalBytes <= 0L) 0 else ((usedBytes * 100.0) / totalBytes).toInt().coerceIn(0, 100)
    }

    data class PendingImportedSourceDeletion(
        val uriStrings: List<String>,
        val count: Int
    )

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
        val showGridFileNames: Boolean = false,
        val selectedItems: Set<String> = emptySet(),
        val isSelectionMode: Boolean = false,
        val searchQuery: String = "",
        val isSearchActive: Boolean = false,
        val isSearchRecursive: Boolean = false,
        val clipboardCount: Int = 0,
        val error: String? = null,
        val pendingNotification: InAppNotification? = null,
        val tempFileToOpen: Pair<File, String>? = null,
        val sharePayload: PreparedShareFiles? = null,
        val isOperationInProgress: Boolean = false,
        val operationMessage: String? = null,
        val folderItemCounts: Map<String, Int> = emptyMap(),
        val storageUsage: VaultStorageUsage? = null,
        val pendingImportedSourceDeletion: PendingImportedSourceDeletion? = null
    )

    private val _state = MutableStateFlow(FileManagerState())
    val state = _state.asStateFlow()

    private val _mediaThumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val mediaThumbnails = _mediaThumbnails.asStateFlow()

    val mountedContainers: kotlinx.coroutines.flow.StateFlow<List<Container>> = repo.getAllContainers()
        .map { list -> list.filter { it.isMounted } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _tempFiles = mutableListOf<File>()
    private val thumbnailSemaphore = Semaphore(THUMBNAIL_PARALLELISM)
    private val thumbnailMap = LinkedHashMap<String, Bitmap>(MAX_FILE_THUMBNAILS + 1, 0.75f, true)
    private val loadingThumbnails = mutableSetOf<String>()
    private val failedThumbnails = mutableSetOf<String>()
    private val loadingFolderCounts = mutableSetOf<String>()
    private val folderCountCache = mutableMapOf<String, Int>()
    private var storageUsageJob: Job? = null
    private var lastStorageUsageContainerId: String? = null
    private var lastStorageUsageAtMs = 0L
    private var initialized = false
    private var sensitiveStateRevision = 0L

    private val prefs = context.fileManagerPrefs

    companion object {
        private val SORT_BY_KEY      = stringPreferencesKey("sort_by")
        private val SORT_ASC_KEY     = booleanPreferencesKey("sort_asc")
        private val SHOW_HIDDEN_KEY  = booleanPreferencesKey("show_hidden")
        private val FOLDERS_FIRST_KEY = booleanPreferencesKey("folders_first")
        private val VIEW_MODE_KEY    = stringPreferencesKey("view_mode")
        private val SHOW_GRID_FILE_NAMES_KEY = booleanPreferencesKey("show_grid_file_names")
        private const val THUMBNAIL_PARALLELISM = 2
        private const val MAX_FILE_THUMBNAILS = 96
        private const val MAX_FAILED_THUMBNAILS = 256
        private const val STORAGE_USAGE_REFRESH_MS = 30_000L
    }

    fun initialize(containerId: String) {
        if (initialized && _state.value.containerId == containerId) return
        if (_state.value.containerId != containerId) {
            clearThumbnailState()
            invalidateFolderCounts()
        }
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
                    viewMode     = runCatching { ViewMode.valueOf(p[VIEW_MODE_KEY] ?: "LIST") }.getOrDefault(ViewMode.LIST),
                    showGridFileNames = p[SHOW_GRID_FILE_NAMES_KEY] ?: false
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
        if (clearSensitiveStateIfUnmounted()) return
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

    fun toggleShowGridFileNames() {
        _state.update { it.copy(showGridFileNames = !it.showGridFileNames) }
        savePrefs()
    }

    fun beginExternalActivity() {
        externalActivityGuard.begin()
    }

    fun finishExternalActivity() {
        externalActivityGuard.end()
    }

    fun clearSensitiveStateIfUnmounted(): Boolean {
        val containerId = _state.value.containerId
        if (containerId.isBlank() || repo.getContainerHandle(containerId) != null) return false
        lockSensitiveState()
        return true
    }

    fun clearSensitiveStateBeforeStopIfNeeded(isLocked: Boolean, containers: List<Container>) {
        val containerId = _state.value.containerId
        val container = containers.firstOrNull { it.id == containerId } ?: return
        if (!isLocked && externalActivityGuard.isActive && !container.unmountOnBackground) return
        if (container.unmountOnBackground || (isLocked && container.unmountOnLock)) {
            lockSensitiveState()
        }
    }

    fun requestThumbnail(file: NativeFileInfo) {
        if (!isVisualMedia(file)) return
        val key = file.path
        if (key in _mediaThumbnails.value) {
            thumbnailMap[key]
            return
        }
        if (key in loadingThumbnails || key in failedThumbnails) return
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: return
        loadingThumbnails += key

        viewModelScope.launch(Dispatchers.IO) {
            val bitmap = thumbnailSemaphore.withPermit {
                runCatching {
                    val media = mediaScanner.indexVisualFile(handle, s.containerId, file) ?: return@runCatching null
                    thumbnailManager.getThumbnail(engine, handle, media)
                }.getOrNull()
            }
            withContext(Dispatchers.Main) {
                loadingThumbnails -= key
                val stillMounted = repo.getContainerHandle(s.containerId) == handle &&
                    _state.value.containerId == s.containerId &&
                    _state.value.error == null
                if (!stillMounted) {
                    return@withContext
                }
                if (bitmap != null) putThumbnail(key, bitmap) else markThumbnailFailed(key)
            }
        }
    }

    fun requestFolderItemCount(file: NativeFileInfo) {
        if (!file.isDirectory) return
        val key = file.path
        if (key in _state.value.folderItemCounts || key in loadingFolderCounts) return
        folderCountCache[key]?.let { cached ->
            _state.update { it.copy(folderItemCounts = it.folderItemCounts + (key to cached)) }
            return
        }
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: return
        loadingFolderCounts += key
        viewModelScope.launch(Dispatchers.IO) {
            val count = runCatching { engine.nativeListFiles(handle, file.path).size }.getOrNull()
            withContext(Dispatchers.Main) {
                loadingFolderCounts -= key
                val stillMounted = repo.getContainerHandle(s.containerId) == handle &&
                    _state.value.containerId == s.containerId &&
                    _state.value.error == null
                if (!stillMounted || count == null) return@withContext
                folderCountCache[key] = count
                _state.update { it.copy(folderItemCounts = it.folderItemCounts + (key to count)) }
            }
        }
    }

    fun openVisualFile(file: NativeFileInfo, onResult: (String?) -> Unit) {
        if (!isVisualMedia(file)) {
            onResult(null)
            return
        }
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: run {
            onResult(null)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val visualFiles = s.files
                .filter { isVisualMedia(it) }
                .ifEmpty { listOf(file) }

            val orderedMedia = runCatching {
                mediaScanner.indexVisualFiles(handle, s.containerId, visualFiles)
            }.getOrDefault(emptyList())
            val media = orderedMedia.firstOrNull { it.relativePath == file.path }
                ?: runCatching { mediaScanner.indexVisualFile(handle, s.containerId, file) }.getOrNull()

            if (media != null) {
                val orderedIds = orderedMedia.map { it.id }.let { ids ->
                    if (media.id in ids) ids else ids + media.id
                }
                mediaQueue.setMediaOrder(s.containerId, orderedIds)
            }
            withContext(Dispatchers.Main) { onResult(media?.id) }
        }
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
                    val currentSourceHandle = repo.getContainerHandle(item.sourceContainerId)
                    if (currentSourceHandle == null || currentSourceHandle != item.sourceHandle) continue
                    val initialDestPath = if (currentPath == "/") "/${item.fileName}" else "$currentPath/${item.fileName}"
                    val destPath = resolvePasteDestinationPath(
                        sourceContainerId = item.sourceContainerId,
                        sourcePath = item.sourcePath,
                        destContainerId = destContainerId,
                        initialDestPath = initialDestPath,
                        fileName = item.fileName,
                        isCut = isCut
                    ) ?: continue
                    _state.update { it.copy(operationMessage = "${if (isCut) "Moving" else "Copying"} ${item.fileName}…") }
                    val sourceInfo = getContainerFileInfo(item.sourceHandle, item.sourcePath)
                    if (item.isDirectory) {
                        val ok = copyDirectoryRecursive(
                            item.sourceHandle,
                            item.sourcePath,
                            destHandle,
                            destPath,
                            sourceInfo?.lastModified ?: 0L
                        )
                        if (ok && isCut) {
                            val deleted = deleteContainerItemRecursive(item.sourceHandle, item.sourcePath, true)
                            if (!deleted) continue
                        }
                        if (ok) count++
                    } else {
                        val size = sourceInfo?.size ?: continue
                        var offset = 0L
                        var writeOk = true
                        while (offset < size) {
                            val toRead = minOf(chunkSize.toLong(), size - offset).toInt()
                            val chunk = engine.nativeReadFile(item.sourceHandle, item.sourcePath, offset, toRead) ?: run { writeOk = false; break }
                            val rc = engine.nativeWriteFile(destHandle, destPath, chunk, offset)
                            if (rc != VeraCryptEngine.ERR_OK) { writeOk = false; break }
                            offset += chunk.size
                        }
                        if (offset != size) writeOk = false
                        if (!writeOk) runCatching { engine.nativeDeleteFile(destHandle, destPath) }
                        if (writeOk) {
                            setContainerModifiedTime(destHandle, destPath, sourceInfo?.lastModified ?: System.currentTimeMillis())
                            if (isCut) {
                                val deleted = runCatching {
                                    engine.nativeDeleteFile(item.sourceHandle, item.sourcePath) == VeraCryptEngine.ERR_OK
                                }.getOrDefault(false)
                                if (!deleted) continue
                            }
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
                        val ok = copyDirectoryRecursive(sourceHandle, item.path, destHandle, destItemPath, item.lastModified)
                        if (ok) count++
                    } else {
                        var offset = 0L
                        var writeOk = true
                        while (offset < item.size) {
                            val toRead = minOf(chunkSize.toLong(), item.size - offset).toInt()
                            val chunk = engine.nativeReadFile(sourceHandle, item.path, offset, toRead) ?: run { writeOk = false; break }
                            val rc = engine.nativeWriteFile(destHandle, destItemPath, chunk, offset)
                            if (rc != VeraCryptEngine.ERR_OK) { writeOk = false; break }
                            offset += chunk.size
                        }
                        if (offset != item.size) writeOk = false
                        if (!writeOk) runCatching { engine.nativeDeleteFile(destHandle, destItemPath) }
                        if (writeOk) {
                            setContainerModifiedTime(destHandle, destItemPath, item.lastModified)
                            count++
                        }
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
                    item.isDirectory -> moveDirectoryRecursive(sourceHandle, item.path, destHandle, destItemPath, item.lastModified)
                    else -> moveFile(sourceHandle, item.path, destHandle, destItemPath, item.size, item.lastModified)
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
            val deletedMediaPaths = mutableListOf<String>()
            for (file in toDelete) {
                runCatching {
                    val deleted = deleteContainerItemRecursive(handle, file.path, file.isDirectory)
                    if (!deleted) return@runCatching
                    count++
                    mediaFileDao.deleteMediaByContainerPathPrefix(s.containerId, file.path)
                    deletedMediaPaths += file.path
                }
            }
            exitSelectionMode()
            refreshNow()
            if (deletedMediaPaths.isNotEmpty()) {
                audioQueue.clearForContainer(s.containerId)
                mediaQueue.clearForContainer(s.containerId)
            }
            withContext(Dispatchers.Main) { removeThumbnails(deletedMediaPaths) }
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

    fun createTextFile(rawName: String, onCreated: (path: String?, name: String?) -> Unit) {
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: run {
            onCreated(null, null)
            return
        }
        val safeName = normalizeTextFileName(rawName)
        if (safeName.isBlank()) {
            _state.update { it.copy(pendingNotification = InAppNotification.FileCreateFailed) }
            onCreated(null, null)
            return
        }
        val filePath = buildDestinationPath(s.currentPath, safeName)

        viewModelScope.launch(Dispatchers.IO) {
            val exists = runCatching {
                engine.nativeListFiles(handle, s.currentPath).any { it.name == safeName }
            }.getOrDefault(false)
            if (exists) {
                _state.update { it.copy(pendingNotification = InAppNotification.FileAlreadyExists(safeName)) }
                withContext(Dispatchers.Main) { onCreated(null, null) }
                return@launch
            }

            val rc = runCatching {
                engine.nativeWriteFile(handle, filePath, ByteArray(0), 0L)
            }.getOrDefault(VeraCryptEngine.ERR_FS)
            val created = rc == VeraCryptEngine.ERR_OK
            if (created) {
                setContainerModifiedTime(handle, filePath, System.currentTimeMillis())
                refreshNow()
                _state.update { it.copy(pendingNotification = InAppNotification.TextFileCreated(safeName)) }
            } else {
                _state.update { it.copy(
                    pendingNotification = if (rc == VeraCryptEngine.ERR_HIDDEN_BOUNDARY) {
                        InAppNotification.HiddenVolumeWriteProtection
                    } else {
                        InAppNotification.FileCreateFailed
                    }
                ) }
            }
            withContext(Dispatchers.Main) {
                if (created) onCreated(filePath, safeName) else onCreated(null, null)
            }
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
                if (isVisualMedia(file)) {
                    mediaFileDao.getMediaByContainerPath(s.containerId, file.path)?.let { media ->
                        mediaFileDao.updateMediaFile(
                            media.copy(
                                relativePath = newPath,
                                fileName = finalName,
                                size = file.size,
                                dateModified = System.currentTimeMillis()
                            )
                        )
                    }
                }
                refreshNow()
                withContext(Dispatchers.Main) { moveThumbnail(file.path, newPath) }
                _state.update { it.copy(pendingNotification = InAppNotification.FileRenamed(finalName)) }
            }
            withContext(Dispatchers.Main) { onResult(success) }
        }
    }

    fun importFiles(context: Context, uris: List<android.net.Uri>) {
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val deleteAfterImport = appPrefs.deleteImportedFiles.first()
            _state.update { it.copy(isOperationInProgress = true) }
            val stats = ImportStats()
            val successfulSourceUris = mutableListOf<Uri>()
            for (uri in uris) {
                if (stats.hiddenProtected || stats.importFailed) break
                try {
                    val rawName = getFileNameFromUri(context, uri) ?: continue
                    val name = sanitizePathName(rawName).ifEmpty { continue }
                    val destPath = buildDestinationPath(s.currentPath, name)
                    val sourceModifiedAt = getSourceModifiedTime(context, uri)
                    _state.update { it.copy(operationMessage = "Importing $name…") }
                    val inputStream = context.contentResolver.openInputStream(uri) ?: continue
                    when (importStreamAtomically(handle, destPath, inputStream)) {
                        ImportWriteResult.IMPORTED -> {
                            setContainerModifiedTime(handle, destPath, sourceModifiedAt)
                            mediaFileDao.deleteMediaByContainerPathPrefix(s.containerId, destPath)
                            if (isVisualMediaName(name)) {
                                withContext(Dispatchers.Main) { removeThumbnails(listOf(destPath)) }
                            }
                            stats.count++
                            if (deleteAfterImport) successfulSourceUris += uri
                        }
                        ImportWriteResult.HIDDEN_PROTECTED -> stats.hiddenProtected = true
                        ImportWriteResult.FAILED -> stats.importFailed = true
                    }
                } catch (_: Exception) {
                    stats.importFailed = true
                }
            }
            val partialFailure = stats.hiddenProtected || stats.importFailed
            if (deleteAfterImport && successfulSourceUris.isNotEmpty() && !partialFailure) {
                stats.deleteSkipped += deleteSourceUris(context, successfulSourceUris)
            }
            refreshNow()
            _state.update { it.copy(
                isOperationInProgress = false,
                operationMessage      = null,
                pendingImportedSourceDeletion = if (deleteAfterImport && partialFailure && successfulSourceUris.isNotEmpty()) {
                    PendingImportedSourceDeletion(successfulSourceUris.map { uri -> uri.toString() }, successfulSourceUris.size)
                } else null,
                pendingNotification   = when {
                    stats.hiddenProtected -> InAppNotification.HiddenVolumeWriteProtection
                    stats.importFailed    -> InAppNotification.ImportFailed
                    stats.deleteSkipped > 0 -> InAppNotification.ImportedSourceDeleteSkipped(stats.deleteSkipped)
                    stats.count > 0       -> InAppNotification.FilesImported(stats.count)
                    else            -> null
                }
            ) }
        }
    }

    fun importFolder(context: Context, treeUri: Uri) {
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val root = DocumentFile.fromTreeUri(context, treeUri) ?: run {
                _state.update { it.copy(pendingNotification = InAppNotification.ImportFailed) }
                return@launch
            }
            val rootName = sanitizePathName(root.name).ifEmpty { "Imported folder" }
            val destRoot = buildDestinationPath(s.currentPath, rootName)
            val deleteAfterImport = appPrefs.deleteImportedFiles.first()
            val stats = ImportStats()
            val successfulSourceUris = mutableListOf<Uri>()

            _state.update {
                it.copy(isOperationInProgress = true, operationMessage = "Importing $rootName…")
            }
            runCatching { engine.nativeCreateDirectory(handle, destRoot) }
            importDocumentDirectoryRecursive(
                context = context,
                handle = handle,
                sourceDir = root,
                destDirPath = destRoot,
                successfulSourceUris = successfulSourceUris,
                stats = stats
            )
            setContainerModifiedTime(handle, destRoot, root.lastModified())

            val partialFailure = stats.hiddenProtected || stats.importFailed
            if (deleteAfterImport && successfulSourceUris.isNotEmpty() && !partialFailure) {
                stats.deleteSkipped += deleteSourceUris(context, successfulSourceUris)
            }
            refreshNow()
            _state.update { it.copy(
                isOperationInProgress = false,
                operationMessage      = null,
                pendingImportedSourceDeletion = if (deleteAfterImport && partialFailure && successfulSourceUris.isNotEmpty()) {
                    PendingImportedSourceDeletion(successfulSourceUris.map { uri -> uri.toString() }, successfulSourceUris.size)
                } else null,
                pendingNotification   = when {
                    stats.hiddenProtected -> InAppNotification.HiddenVolumeWriteProtection
                    stats.importFailed    -> InAppNotification.ImportFailed
                    stats.deleteSkipped > 0 -> InAppNotification.ImportedSourceDeleteSkipped(stats.deleteSkipped)
                    stats.count > 0       -> InAppNotification.FilesImported(stats.count)
                    else                  -> InAppNotification.FolderImported(rootName)
                }
            ) }
        }
    }

    fun importCapturedPhoto(photoFile: File) {
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isOperationInProgress = true, operationMessage = "Importing ${photoFile.name}…") }
            if (!waitForStableImageFile(photoFile)) {
                runCatching { FileUtils.secureZeroAndDelete(photoFile) }.onFailure { photoFile.delete() }
                _state.update { it.copy(
                    isOperationInProgress = false,
                    operationMessage      = null,
                    pendingNotification   = InAppNotification.ImportFailed
                ) }
                return@launch
            }
            val destPath = buildDestinationPath(s.currentPath, photoFile.name)
            val result = runCatching {
                importStreamAtomically(handle, destPath, photoFile.inputStream())
            }.getOrDefault(ImportWriteResult.FAILED)
            if (result == ImportWriteResult.IMPORTED) {
                setContainerModifiedTime(handle, destPath, System.currentTimeMillis())
                mediaFileDao.deleteMediaByContainerPathPrefix(s.containerId, destPath)
                if (isVisualMediaName(photoFile.name)) {
                    withContext(Dispatchers.Main) { removeThumbnails(listOf(destPath)) }
                }
            }
            runCatching { FileUtils.secureZeroAndDelete(photoFile) }.onFailure { photoFile.delete() }
            refreshNow()
            _state.update { it.copy(
                isOperationInProgress = false,
                operationMessage      = null,
                pendingNotification   = when (result) {
                    ImportWriteResult.IMPORTED -> InAppNotification.FilesImported(1)
                    ImportWriteResult.HIDDEN_PROTECTED -> InAppNotification.HiddenVolumeWriteProtection
                    ImportWriteResult.FAILED -> InAppNotification.ImportFailed
                }
            ) }
        }
    }

    fun prepareShareSelected(context: Context) {
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: return
        val selected = s.selectedItems.mapNotNull { path -> s.files.find { it.path == path } }
        prepareShareItems(context, handle, selected, exitSelection = true)
    }

    fun prepareShareFile(context: Context, file: NativeFileInfo) {
        val handle = repo.getContainerHandle(_state.value.containerId) ?: return
        prepareShareItems(context, handle, listOf(file), exitSelection = false)
    }

    private fun prepareShareItems(
        context: Context,
        handle: Long,
        files: List<NativeFileInfo>,
        exitSelection: Boolean
    ) {
        if (files.isEmpty()) return
        if (files.any { it.isDirectory }) {
            _state.update { it.copy(pendingNotification = InAppNotification.ShareFoldersUnsupported) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isOperationInProgress = true, operationMessage = "Preparing share…") }
            val payload = VaultFileTransfer.prepareShareFiles(
                context = context,
                engine = engine,
                handle = handle,
                items = files.map(VaultFileTransfer::fromNative)
            )
            if (exitSelection) exitSelectionMode()
            _state.update { it.copy(
                isOperationInProgress = false,
                operationMessage      = null,
                sharePayload          = payload,
                pendingNotification   = if (payload == null) InAppNotification.ShareFailed else null
            ) }
        }
    }

    fun exportSelected(context: Context, treeUri: android.net.Uri) {
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: return
        val toExport = s.selectedItems.mapNotNull { path -> s.files.find { it.path == path } }
        exportItems(context, treeUri, handle, toExport, exitSelectionAfter = true)
    }

    fun exportSingle(context: Context, file: NativeFileInfo, treeUri: android.net.Uri) {
        val handle = repo.getContainerHandle(_state.value.containerId) ?: return
        exportItems(context, treeUri, handle, listOf(file), exitSelectionAfter = false)
    }

    fun exportSelectedToDefault(context: Context) {
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: return
        val toExport = s.selectedItems.mapNotNull { path -> s.files.find { it.path == path } }
        exportItemsToDefault(context, handle, toExport, exitSelectionAfter = true)
    }

    fun exportSingleToDefault(context: Context, file: NativeFileInfo) {
        val handle = repo.getContainerHandle(_state.value.containerId) ?: return
        exportItemsToDefault(context, handle, listOf(file), exitSelectionAfter = false)
    }

    private fun exportItems(
        context: Context,
        treeUri: android.net.Uri,
        handle: Long,
        toExport: List<NativeFileInfo>,
        exitSelectionAfter: Boolean
	    ) {
	        viewModelScope.launch(Dispatchers.IO) {
	            val exportContainerId = _state.value.containerId
	            val deleteAfterExport = appPrefs.deleteExportedFiles.first()
	            _state.update { it.copy(isOperationInProgress = true) }
            var count = 0
            var deleteSkipped = 0
            val rootDir = VaultFileTransfer.documentTreeRoot(context, treeUri)
            if (rootDir == null) {
                _state.update { it.copy(
                    isOperationInProgress = false,
                    operationMessage      = null,
                    pendingNotification   = InAppNotification.ExportFailed
                ) }
                return@launch
            }
            for (file in toExport) {
                try {
                    _state.update { it.copy(operationMessage = "Exporting ${file.name}…") }
                    val exported = VaultFileTransfer.exportItemToDirectory(
                        context = context,
                        engine = engine,
                        handle = handle,
                        item = VaultFileTransfer.fromNative(file),
                        parent = rootDir
                    )
                    if (exported) {
                        count++
	                        if (deleteAfterExport) {
	                            val deleted = deleteContainerItemRecursive(handle, file.path, file.isDirectory)
	                            if (deleted) {
	                                if (isVisualMedia(file)) mediaFileDao.deleteMediaByContainerPath(exportContainerId, file.path)
	                            } else {
	                                deleteSkipped++
	                            }
                        }
                    }
                } catch (_: Exception) { }
            }
            if (exitSelectionAfter) exitSelectionMode()
            if (deleteAfterExport && count > 0) refreshNow()
	            if (deleteAfterExport) {
	                val deletedPaths = toExport
	                    .filter { isVisualMedia(it) }
	                    .filter { mediaFileDao.getMediaByContainerPath(exportContainerId, it.path) == null }
	                    .map { it.path }
                withContext(Dispatchers.Main) { removeThumbnails(deletedPaths) }
            }
            _state.update { it.copy(
                isOperationInProgress = false,
                operationMessage      = null,
                pendingNotification   = when {
                    deleteSkipped > 0 -> InAppNotification.ExportedSourceDeleteSkipped(deleteSkipped)
                    count > 0 -> InAppNotification.FilesExported(count)
                    else -> InAppNotification.ExportFailed
                }
            ) }
        }
    }

    private fun exportItemsToDefault(
        context: Context,
        handle: Long,
        toExport: List<NativeFileInfo>,
        exitSelectionAfter: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val exportContainerId = _state.value.containerId
            val deleteAfterExport = appPrefs.deleteExportedFiles.first()
            _state.update { it.copy(isOperationInProgress = true) }
            var count = 0
            var deleteSkipped = 0
            for (file in toExport) {
                try {
                    _state.update { it.copy(operationMessage = "Exporting ${file.name}…") }
                    val exported = VaultFileTransfer.exportItemToDefaultFolder(
                        context = context,
                        engine = engine,
                        handle = handle,
                        item = VaultFileTransfer.fromNative(file)
                    )
                    if (exported) {
                        count++
                        if (deleteAfterExport) {
                            val deleted = deleteContainerItemRecursive(handle, file.path, file.isDirectory)
                            if (deleted) {
                                if (isVisualMedia(file)) mediaFileDao.deleteMediaByContainerPath(exportContainerId, file.path)
                            } else {
                                deleteSkipped++
                            }
                        }
                    }
                } catch (_: Exception) { }
            }
            if (exitSelectionAfter) exitSelectionMode()
            if (deleteAfterExport && count > 0) refreshNow()
            if (deleteAfterExport) {
                val deletedPaths = toExport
                    .filter { isVisualMedia(it) }
                    .filter { mediaFileDao.getMediaByContainerPath(exportContainerId, it.path) == null }
                    .map { it.path }
                withContext(Dispatchers.Main) { removeThumbnails(deletedPaths) }
            }
            _state.update { it.copy(
                isOperationInProgress = false,
                operationMessage      = null,
                pendingNotification   = when {
                    deleteSkipped > 0 -> InAppNotification.ExportedSourceDeleteSkipped(deleteSkipped)
                    count > 0 -> InAppNotification.FilesExported(count)
                    else -> InAppNotification.ExportFailed
                }
            ) }
        }
    }

    fun prepareOpenWithExternalApp(context: Context, file: NativeFileInfo) {
        if (file.isDirectory) return
        val handle = repo.getContainerHandle(_state.value.containerId) ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isOperationInProgress = true, operationMessage = "Preparing ${file.name}…") }
            try {
                val tempDir = File(context.cacheDir, "arcanum_temp/${System.currentTimeMillis()}_${UUID.randomUUID()}").also { it.mkdirs() }
                val safeName = sanitizePathName(file.name).ifEmpty { "file" }
                val tempFile = File(tempDir, safeName)
                if (!VaultFileTransfer.copyVaultFileToFile(engine, handle, file.path, file.size, tempFile)) {
                    tempFile.delete()
                    _state.update { it.copy(isOperationInProgress = false, operationMessage = null) }
                    return@launch
                }
                _tempFiles.add(tempFile)
                _state.update { it.copy(
                    isOperationInProgress = false,
                    operationMessage      = null,
                    tempFileToOpen        = Pair(tempFile, VaultFileTransfer.mimeType(file.name))
                ) }
            } catch (_: Exception) {
                _state.update { it.copy(isOperationInProgress = false, operationMessage = null) }
            }
        }
    }

    fun clearTempFileToOpen() {
        _state.update { it.copy(tempFileToOpen = null) }
    }

    fun clearSharePayload() {
        _state.update { it.copy(sharePayload = null) }
    }

    fun dismissPendingImportedSourceDeletion() {
        _state.update { it.copy(pendingImportedSourceDeletion = null) }
    }

    fun deletePendingImportedSources(context: Context) {
        val pending = _state.value.pendingImportedSourceDeletion ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val skipped = deleteSourceUris(context, pending.uriStrings.map(Uri::parse))
            _state.update {
                it.copy(
                    pendingImportedSourceDeletion = null,
                    pendingNotification = if (skipped > 0) {
                        InAppNotification.ImportedSourceDeleteSkipped(skipped)
                    } else {
                        InAppNotification.FilesImported(pending.count)
                    }
                )
            }
        }
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
        storageUsageJob?.cancel()
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

    private suspend fun importDocumentDirectoryRecursive(
        context: Context,
        handle: Long,
        sourceDir: DocumentFile,
        destDirPath: String,
        successfulSourceUris: MutableList<Uri>,
        stats: ImportStats
    ) {
        val children = runCatching {
            sourceDir.listFiles().sortedBy { it.name?.lowercase() ?: "" }
        }.getOrDefault(emptyList())

        for (child in children) {
            if (stats.hiddenProtected || stats.importFailed) return
            val name = sanitizePathName(child.name)
            if (name.isBlank()) continue
            val destPath = joinContainerPath(destDirPath, name)

            if (child.isDirectory) {
                _state.update { it.copy(operationMessage = "Importing $name…") }
                runCatching { engine.nativeCreateDirectory(handle, destPath) }
                importDocumentDirectoryRecursive(
                    context = context,
                    handle = handle,
                    sourceDir = child,
                    destDirPath = destPath,
                    successfulSourceUris = successfulSourceUris,
                    stats = stats
                )
                setContainerModifiedTime(handle, destPath, child.lastModified())
            } else if (child.isFile) {
                _state.update { it.copy(operationMessage = "Importing $name…") }
                val result = runCatching {
                    context.contentResolver.openInputStream(child.uri)?.use { input ->
                        importStreamAtomically(handle, destPath, input)
                    } ?: ImportWriteResult.FAILED
                }.getOrDefault(ImportWriteResult.FAILED)

                when (result) {
                    ImportWriteResult.IMPORTED -> {
                        setContainerModifiedTime(handle, destPath, child.lastModified())
                        mediaFileDao.deleteMediaByContainerPathPrefix(_state.value.containerId, destPath)
                        if (isVisualMediaName(name)) {
                            withContext(Dispatchers.Main) { removeThumbnails(listOf(destPath)) }
                        }
                        stats.count++
                        successfulSourceUris += child.uri
                    }
                    ImportWriteResult.HIDDEN_PROTECTED -> stats.hiddenProtected = true
                    ImportWriteResult.FAILED -> stats.importFailed = true
                }
            }
        }
    }

    private fun importStreamAtomically(
        handle: Long,
        destPath: String,
        inputStream: InputStream
    ): ImportWriteResult {
        val tempPath = importTempPath(destPath)
        val result = writeStreamToContainer(handle, tempPath, inputStream)
        if (result != ImportWriteResult.IMPORTED) return result
        return commitImportedTempFile(handle, tempPath, destPath)
    }

    private fun writeStreamToContainer(
        handle: Long,
        destPath: String,
        inputStream: InputStream
    ): ImportWriteResult {
        val chunkSize = 1 * 1024 * 1024
        return try {
            inputStream.use { input ->
                var offset = 0L
                val buffer = ByteArray(chunkSize)
                while (true) {
                    val read = input.read(buffer)
                    if (read == -1) break
                    val rc = engine.nativeWriteFile(handle, destPath, buffer.copyOf(read), offset)
                    when (rc) {
                        VeraCryptEngine.ERR_OK -> offset += read
                        VeraCryptEngine.ERR_HIDDEN_BOUNDARY -> {
                            runCatching { engine.nativeDeleteFile(handle, destPath) }
                            return ImportWriteResult.HIDDEN_PROTECTED
                        }
                        else -> {
                            runCatching { engine.nativeDeleteFile(handle, destPath) }
                            return ImportWriteResult.FAILED
                        }
                    }
                }
                if (offset == 0L) {
                    val rc = engine.nativeWriteFile(handle, destPath, ByteArray(0), 0L)
                    if (rc != VeraCryptEngine.ERR_OK) {
                        runCatching { engine.nativeDeleteFile(handle, destPath) }
                        return if (rc == VeraCryptEngine.ERR_HIDDEN_BOUNDARY) {
                            ImportWriteResult.HIDDEN_PROTECTED
                        } else {
                            ImportWriteResult.FAILED
                        }
                    }
                }
            }
            ImportWriteResult.IMPORTED
        } catch (_: Exception) {
            runCatching { engine.nativeDeleteFile(handle, destPath) }
            ImportWriteResult.FAILED
        }
    }

    private fun commitImportedTempFile(
        handle: Long,
        tempPath: String,
        destPath: String
    ): ImportWriteResult {
        val existing = getContainerFileInfo(handle, destPath)
        val backupPath = if (existing != null) importBackupPath(destPath) else null
        return try {
            if (backupPath != null) {
                val backupRc = engine.nativeRenameFile(handle, destPath, backupPath)
                if (backupRc != VeraCryptEngine.ERR_OK) {
                    runCatching { engine.nativeDeleteFile(handle, tempPath) }
                    return ImportWriteResult.FAILED
                }
            }
            val renameRc = engine.nativeRenameFile(handle, tempPath, destPath)
            if (renameRc == VeraCryptEngine.ERR_OK) {
                if (backupPath != null) runCatching { engine.nativeDeleteFile(handle, backupPath) }
                ImportWriteResult.IMPORTED
            } else {
                runCatching { engine.nativeDeleteFile(handle, tempPath) }
                if (backupPath != null) {
                    runCatching { engine.nativeRenameFile(handle, backupPath, destPath) }
                }
                if (renameRc == VeraCryptEngine.ERR_HIDDEN_BOUNDARY) {
                    ImportWriteResult.HIDDEN_PROTECTED
                } else {
                    ImportWriteResult.FAILED
                }
            }
        } catch (_: Exception) {
            runCatching { engine.nativeDeleteFile(handle, tempPath) }
            if (backupPath != null) {
                runCatching { engine.nativeRenameFile(handle, backupPath, destPath) }
            }
            ImportWriteResult.FAILED
        }
    }

    private suspend fun moveFile(
        srcHandle: Long, srcPath: String,
        destHandle: Long, destPath: String,
        fileSize: Long,
        modifiedAt: Long
    ): Boolean {
        val chunkSize = 1 * 1024 * 1024
        return try {
            var offset = 0L
            while (offset < fileSize) {
                val toRead = minOf(chunkSize.toLong(), fileSize - offset).toInt()
                val chunk = engine.nativeReadFile(srcHandle, srcPath, offset, toRead) ?: return false
                val rc = engine.nativeWriteFile(destHandle, destPath, chunk, offset)
                if (rc != VeraCryptEngine.ERR_OK) {
                    runCatching { engine.nativeDeleteFile(destHandle, destPath) }
                    return false
                }
                offset += chunk.size
            }
            if (offset != fileSize) {
                runCatching { engine.nativeDeleteFile(destHandle, destPath) }
                return false
            }
            setContainerModifiedTime(destHandle, destPath, modifiedAt)
            runCatching { engine.nativeDeleteFile(srcHandle, srcPath) == VeraCryptEngine.ERR_OK }
                .getOrDefault(false)
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
                    val ok = exportFileToUri(context, handle, entryPath, entry.size, docUri, chunkSize)
                    if (!ok) {
                        runCatching { DocumentsContract.deleteDocument(context.contentResolver, docUri) }
                        allOk = false
                    }
                }
            } catch (_: Exception) { allOk = false }
        }
        return allOk
    }

    private fun exportFileToUri(
        context: Context,
        handle: Long,
        srcPath: String,
        fileSize: Long,
        docUri: Uri,
        chunkSize: Int
    ): Boolean {
        return try {
            var offset = 0L
            val output = context.contentResolver.openOutputStream(docUri) ?: return false
            output.use { out ->
                while (offset < fileSize) {
                    val toRead = minOf(chunkSize.toLong(), fileSize - offset).toInt()
                    val chunk = engine.nativeReadFile(handle, srcPath, offset, toRead) ?: return false
                    if (chunk.isEmpty()) return false
                    out.write(chunk)
                    offset += chunk.size
                }
            }
            offset == fileSize
        } catch (_: Exception) { false }
    }

    private fun deleteSourceUri(context: Context, uri: Uri): Boolean =
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }.getOrDefault(false) ||
            runCatching { context.contentResolver.delete(uri, null, null) > 0 }.getOrDefault(false)

    private fun deleteSourceUris(context: Context, uris: List<Uri>): Int =
        uris.count { uri -> !deleteSourceUri(context, uri) }

    private fun deleteContainerItemRecursive(
        handle: Long,
        path: String,
        isDirectory: Boolean
    ): Boolean {
        if (!isDirectory) {
            return runCatching { engine.nativeDeleteFile(handle, path) == VeraCryptEngine.ERR_OK }
                .getOrDefault(false)
        }

        val entries = runCatching { engine.nativeListFiles(handle, path).toList() }.getOrNull()
            ?: return false
        var allDeleted = true
        for (entry in entries) {
            val childPath = if (path == "/") "/${entry.name}" else "$path/${entry.name}"
            val deleted = deleteContainerItemRecursive(handle, childPath, entry.isDirectory)
            if (!deleted) allDeleted = false
        }
        return allDeleted &&
            runCatching { engine.nativeDeleteDirectory(handle, path) == VeraCryptEngine.ERR_OK }
                .getOrDefault(false)
    }

    private suspend fun copyDirectoryRecursive(
        srcHandle: Long, srcPath: String,
        destHandle: Long, destPath: String,
        modifiedAt: Long = 0L
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
                    copyDirectoryRecursive(srcHandle, srcEntry, destHandle, destEntry, entry.lastModified)
                } else {
                    var offset = 0L
                    var ok = true
                    while (offset < entry.size) {
                        val toRead = minOf(chunkSize.toLong(), entry.size - offset).toInt()
                        val chunk = engine.nativeReadFile(srcHandle, srcEntry, offset, toRead)
                            ?: run { ok = false; break }
                        val rc = engine.nativeWriteFile(destHandle, destEntry, chunk, offset)
                        if (rc != VeraCryptEngine.ERR_OK) { ok = false; break }
                        offset += chunk.size
                    }
                    if (offset != entry.size) ok = false
                    if (!ok) runCatching { engine.nativeDeleteFile(destHandle, destEntry) }
                    if (ok) setContainerModifiedTime(destHandle, destEntry, entry.lastModified)
                    ok
                }
                if (!copied) allCopied = false
            }
            if (allCopied) setContainerModifiedTime(destHandle, destPath, modifiedAt)
            allCopied
        } catch (_: Exception) { false }
    }

    private suspend fun moveDirectoryRecursive(
        srcHandle: Long, srcPath: String,
        destHandle: Long, destPath: String,
        modifiedAt: Long = 0L
    ): Boolean {
        return try {
            runCatching { engine.nativeCreateDirectory(destHandle, destPath) }
            val entries = engine.nativeListFiles(srcHandle, srcPath).toList()
            var allMoved = true
            for (entry in entries) {
                val srcEntry  = if (srcPath  == "/") "/${entry.name}" else "$srcPath/${entry.name}"
                val destEntry = if (destPath == "/") "/${entry.name}" else "$destPath/${entry.name}"
                val moved = if (entry.isDirectory) {
                    moveDirectoryRecursive(srcHandle, srcEntry, destHandle, destEntry, entry.lastModified)
                } else {
                    moveFile(srcHandle, srcEntry, destHandle, destEntry, entry.size, entry.lastModified)
                }
                if (!moved) allMoved = false
            }
            if (allMoved) {
                setContainerModifiedTime(destHandle, destPath, modifiedAt)
                val deleted = runCatching {
                    engine.nativeDeleteDirectory(srcHandle, srcPath) == VeraCryptEngine.ERR_OK
                }.getOrDefault(false)
                if (!deleted) return false
            }
            allMoved
        } catch (_: Exception) { false }
    }

    fun refreshCurrentDirectory() {
        loadDirectory(_state.value.currentPath)
    }

    // Inline (suspending) refresh — use this inside operation coroutines so
    // the listing completes before the notification is set.
    private suspend fun refreshNow() {
        invalidateFolderCounts()
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: run {
            lockSensitiveState()
            return
        }
        val revision = sensitiveStateRevision
        runCatching {
            val rawFiles = engine.nativeListFiles(handle, s.currentPath).toList()
            _state.update { st ->
                if (revision != sensitiveStateRevision || repo.getContainerHandle(s.containerId) != handle) {
                    return@update st
                }
                val files = applyFiltersAndSort(rawFiles, st.sortBy, st.sortAscending, st.showHidden, st.foldersFirst, st.searchQuery)
                st.copy(rawFiles = rawFiles, files = files)
            }
        }
        refreshStorageUsageAsync(force = true)
    }

    private fun loadDirectory(path: String) {
        val containerId = _state.value.containerId
        val handle = repo.getContainerHandle(containerId) ?: run {
            lockSensitiveState()
            return
        }
        val revision = sensitiveStateRevision
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isLoading = true, error = null) }
            try {
                val rawFiles = engine.nativeListFiles(handle, path).toList()
                _state.update { s ->
                    if (revision != sensitiveStateRevision || repo.getContainerHandle(containerId) != handle) {
                        return@update s
                    }
                    val files = applyFiltersAndSort(rawFiles, s.sortBy, s.sortAscending, s.showHidden, s.foldersFirst, s.searchQuery)
                    s.copy(
                        rawFiles        = rawFiles,
                        files           = files,
                        isLoading       = false,
                        currentPath     = path,
                        pathSegments    = buildPathSegments(path)
                    )
                }
                refreshStorageUsageAsync(force = false)
            } catch (_: Exception) {
                lockSensitiveState(error = "Failed to list directory")
            }
        }
    }

    private fun refreshStorageUsageAsync(force: Boolean = false) {
        val s = _state.value
        val containerId = s.containerId
        val handle = repo.getContainerHandle(s.containerId) ?: return
        val now = System.currentTimeMillis()
        val hasFreshCachedValue = _state.value.storageUsage != null &&
            lastStorageUsageContainerId == containerId &&
            now - lastStorageUsageAtMs < STORAGE_USAGE_REFRESH_MS
        if (!force && hasFreshCachedValue) return
        val activeJob = storageUsageJob
        if (activeJob?.isActive == true) {
            if (!force) return
            activeJob.cancel()
        }
        val revision = sensitiveStateRevision
        storageUsageJob = viewModelScope.launch(Dispatchers.IO) {
            val totalBytes = mountedContainers.value.firstOrNull { it.id == s.containerId }?.size
                ?: runCatching { engine.nativeGetDataSize(handle) }.getOrDefault(0L)
            val usedBytes = try {
                calculateDirectorySize(handle, "/")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch
            }
            withContext(Dispatchers.Main) {
                if (revision != sensitiveStateRevision || repo.getContainerHandle(s.containerId) != handle) {
                    return@withContext
                }
                lastStorageUsageContainerId = containerId
                lastStorageUsageAtMs = System.currentTimeMillis()
                _state.update {
                    it.copy(storageUsage = VaultStorageUsage(usedBytes = usedBytes, totalBytes = totalBytes.coerceAtLeast(0L)))
                }
            }
        }
    }

    private suspend fun calculateDirectorySize(handle: Long, path: String, depth: Int = 0): Long {
        coroutineContext.ensureActive()
        if (depth > 64) return 0L
        val entries = runCatching { engine.nativeListFiles(handle, path).toList() }.getOrDefault(emptyList())
        var total = 0L
        entries.forEach { entry ->
            coroutineContext.ensureActive()
            total += if (entry.isDirectory) {
                calculateDirectorySize(handle, entry.path, depth + 1)
            } else {
                entry.size.coerceAtLeast(0L)
            }
        }
        return total
    }

    private fun invalidateFolderCounts() {
        folderCountCache.clear()
        loadingFolderCounts.clear()
        _state.update { it.copy(folderItemCounts = emptyMap()) }
    }

    private fun lockSensitiveState(error: String = "Vault not mounted") {
        sensitiveStateRevision++
        clipboard.clear()
        audioQueue.clearForContainer(_state.value.containerId)
        mediaQueue.clearForContainer(_state.value.containerId)
        storageUsageJob?.cancel()
        lastStorageUsageContainerId = null
        lastStorageUsageAtMs = 0L
        clearThumbnailState()
        folderCountCache.clear()
        loadingFolderCounts.clear()
        _state.update {
            it.copy(
                currentPath           = "/",
                pathSegments          = listOf("/"),
                files                 = emptyList(),
                rawFiles              = emptyList(),
                selectedItems         = emptySet(),
                isSelectionMode       = false,
                clipboardCount        = 0,
                isSearchActive        = false,
                searchQuery           = "",
                sharePayload          = null,
                tempFileToOpen        = null,
                isLoading             = false,
                isOperationInProgress = false,
                operationMessage      = null,
                folderItemCounts      = emptyMap(),
                storageUsage          = null,
                pendingImportedSourceDeletion = null,
                error                 = error
            )
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

    private fun isVisualMedia(file: NativeFileInfo): Boolean =
        !file.isDirectory && file.name.substringAfterLast('.', "").lowercase() in MEDIA_EXTENSIONS_VM

    private fun isVisualMediaName(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in MEDIA_EXTENSIONS_VM

    private suspend fun waitForStableImageFile(file: File): Boolean {
        var lastLength = -1L
        var stableReads = 0
        repeat(12) {
            val length = file.length()
            if (length > 0L && length == lastLength) stableReads++ else stableReads = 0
            lastLength = length
            if (stableReads >= 2 && canDecodeImageBounds(file)) return true
            delay(120L)
        }
        return canDecodeImageBounds(file)
    }

    private fun canDecodeImageBounds(file: File): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            opts.outWidth > 0 && opts.outHeight > 0
        }.getOrDefault(false)
    }

    private fun setContainerModifiedTime(handle: Long, path: String, modifiedAt: Long) {
        if (modifiedAt <= 0L) return
        runCatching { engine.nativeSetModifiedTime(handle, path, modifiedAt) }
    }

    private fun getContainerFileInfo(handle: Long, path: String): NativeFileInfo? {
        val parent = parentPathOf(path)
        val name = path.substringAfterLast("/")
        return runCatching {
            engine.nativeListFiles(handle, parent).firstOrNull { it.path == path || it.name == name }
        }.getOrNull()
    }

    private fun parentPathOf(path: String): String {
        val parent = path.substringBeforeLast("/", "")
        return if (parent.isBlank()) "/" else parent
    }

    private fun putThumbnail(path: String, bitmap: Bitmap) {
        val evicted = mutableListOf<String>()
        while (thumbnailMap.size >= MAX_FILE_THUMBNAILS) {
            val oldest = thumbnailMap.keys.iterator().next()
            thumbnailMap.remove(oldest)
            loadingThumbnails.remove(oldest)
            failedThumbnails.remove(oldest)
            evicted += oldest
        }
        thumbnailMap[path] = bitmap
        val updated = _mediaThumbnails.value.toMutableMap()
        evicted.forEach { updated.remove(it) }
        updated[path] = bitmap
        _mediaThumbnails.value = updated
    }

    private fun markThumbnailFailed(path: String) {
        failedThumbnails += path
        while (failedThumbnails.size > MAX_FAILED_THUMBNAILS) {
            val oldest = failedThumbnails.iterator().next()
            failedThumbnails.remove(oldest)
        }
    }

    private fun removeThumbnails(paths: Collection<String>) {
        if (paths.isEmpty()) return
        val removeKeys = thumbnailMap.keys
            .filter { key -> paths.any { path -> key == path || key.startsWith("$path/") } }
            .toSet() + paths
        removeKeys.forEach {
            thumbnailMap.remove(it)
            loadingThumbnails.remove(it)
            failedThumbnails.remove(it)
        }
        _mediaThumbnails.value = _mediaThumbnails.value - removeKeys
    }

    private fun moveThumbnail(oldPath: String, newPath: String) {
        val bitmap = thumbnailMap.remove(oldPath)
        loadingThumbnails.remove(oldPath)
        failedThumbnails.remove(oldPath)
        val updated = _mediaThumbnails.value.toMutableMap()
        updated.remove(oldPath)
        if (bitmap != null) {
            thumbnailMap[newPath] = bitmap
            updated[newPath] = bitmap
        }
        _mediaThumbnails.value = updated
    }

    private fun clearThumbnailState() {
        thumbnailMap.clear()
        loadingThumbnails.clear()
        failedThumbnails.clear()
        _mediaThumbnails.value = emptyMap()
    }

    private fun buildPathSegments(path: String): List<String> {
        if (path == "/") return listOf("/")
        return listOf("/") + path.split("/").filter { it.isNotEmpty() }
    }

    private fun buildDestinationPath(currentPath: String, fileName: String): String =
        joinContainerPath(currentPath, fileName)

    private fun resolvePasteDestinationPath(
        sourceContainerId: String,
        sourcePath: String,
        destContainerId: String,
        initialDestPath: String,
        fileName: String,
        isCut: Boolean
    ): String? {
        if (sourceContainerId != destContainerId) return initialDestPath
        if (sourcePath == initialDestPath) {
            if (isCut) return null
            return uniqueSiblingPath(parentPathOf(initialDestPath), fileName)
        }
        if (initialDestPath.startsWith("$sourcePath/")) return null
        return initialDestPath
    }

    private fun joinContainerPath(parentPath: String, childName: String): String =
        if (parentPath == "/") "/$childName" else "$parentPath/$childName"

    private fun uniqueSiblingPath(parentPath: String, fileName: String): String {
        val dot = fileName.lastIndexOf('.').takeIf { it > 0 && it < fileName.lastIndex }
        val base = if (dot != null) fileName.substring(0, dot) else fileName
        val ext = if (dot != null) fileName.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = joinContainerPath(parentPath, "$base copy${if (n == 1) "" else " $n"}$ext")
            val handle = repo.getContainerHandle(_state.value.containerId)
            if (handle == null || getContainerFileInfo(handle, candidate) == null) return candidate
            n++
        }
    }

    private fun importTempPath(destPath: String): String {
        val parent = parentPathOf(destPath)
        val name = destPath.substringAfterLast('/').ifBlank { "file" }
        return joinContainerPath(parent, ".arcanum_import_${UUID.randomUUID()}_$name.tmp")
    }

    private fun importBackupPath(destPath: String): String {
        val parent = parentPathOf(destPath)
        val name = destPath.substringAfterLast('/').ifBlank { "file" }
        return joinContainerPath(parent, ".arcanum_import_backup_${UUID.randomUUID()}_$name")
    }

    private fun sanitizePathName(name: String?): String =
        FileUtils.sanitizeFatFileName(name, fallback = "")

    private fun normalizeTextFileName(rawName: String): String {
        val safe = sanitizePathName(rawName)
        if (safe.isBlank()) return ""
        return if (safe.substringAfterLast('.', "").equals("txt", ignoreCase = true)) {
            safe
        } else {
            "$safe.txt"
        }
    }

    private fun getFileNameFromUri(context: Context, uri: android.net.Uri): String? =
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) cursor.getString(idx) else null
        } ?: uri.lastPathSegment

    private fun getSourceModifiedTime(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") {
            val localTime = uri.path?.let { File(it).lastModified() } ?: 0L
            if (localTime > 0L) return localTime
        }

        val documentTime = runCatching {
            DocumentFile.fromSingleUri(context, uri)?.lastModified() ?: 0L
        }.getOrDefault(0L)
        if (documentTime > 0L) return normalizeTimestamp(documentTime, storedAsSeconds = false)

        return listOfNotNull(
            queryUriTimestamp(context, uri, DocumentsContract.Document.COLUMN_LAST_MODIFIED, storedAsSeconds = false),
            queryUriTimestamp(context, uri, MediaStore.MediaColumns.DATE_MODIFIED, storedAsSeconds = true),
            queryUriTimestamp(context, uri, MEDIASTORE_DATE_TAKEN_COLUMN, storedAsSeconds = false)
        ).firstOrNull { it > 0L } ?: System.currentTimeMillis()
    }

    private fun queryUriTimestamp(
        context: Context,
        uri: Uri,
        column: String,
        storedAsSeconds: Boolean
    ): Long? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(column)
                if (!cursor.moveToFirst() || idx < 0 || cursor.isNull(idx)) {
                    null
                } else {
                    normalizeTimestamp(cursor.getLong(idx), storedAsSeconds).takeIf { it > 0L }
                }
            }
        }.getOrNull()

    private fun normalizeTimestamp(value: Long, storedAsSeconds: Boolean): Long {
        if (value <= 0L) return 0L
        return if (storedAsSeconds || value < TIMESTAMP_SECONDS_THRESHOLD) {
            if (value <= Long.MAX_VALUE / 1000L) value * 1000L else 0L
        } else {
            value
        }
    }

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
                p[SHOW_GRID_FILE_NAMES_KEY] = s.showGridFileNames
            }
        }
    }

}
