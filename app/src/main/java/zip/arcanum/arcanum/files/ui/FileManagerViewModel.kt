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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.distinctUntilChanged
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
import zip.arcanum.arcanum.files.data.FileBrowserPrefs
import zip.arcanum.arcanum.files.data.fileManagerPrefs
import zip.arcanum.arcanum.files.domain.ClipboardItem
import zip.arcanum.arcanum.files.domain.FileClipboard
import zip.arcanum.arcanum.gallery.AudioPlayerQueue
import zip.arcanum.arcanum.gallery.MediaScanner
import zip.arcanum.arcanum.gallery.ThumbnailManager
import zip.arcanum.arcanum.saf.VaultDocumentsProvider
import zip.arcanum.R
import kotlinx.coroutines.coroutineScope
import zip.arcanum.core.utils.FileUtils
import zip.arcanum.core.utils.MediaExtensions
import zip.arcanum.core.database.dao.MediaFileDao
import zip.arcanum.core.security.IdleMonitor
import zip.arcanum.core.security.SessionState
import zip.arcanum.core.database.entities.MediaFileType
import zip.arcanum.core.notifications.ImportFailureReason
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.security.AppPreferences
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import zip.arcanum.crypto.VeraCryptEngine
import zip.arcanum.crypto.NativeFileInfo
import java.io.File
import javax.inject.Inject

@HiltViewModel
class FileManagerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: VeraCryptEngine,
    private val clipboard: FileClipboard,
    private val repo: ContainerRepository,
    private val audioQueue: AudioPlayerQueue,
    private val thumbnailManager: ThumbnailManager,
    private val mediaScanner: MediaScanner,
    private val mediaFileDao: MediaFileDao,
    private val appPrefs: AppPreferences,
    private val idleMonitor: IdleMonitor,
    private val sessionState: SessionState
) : ViewModel() {

    /*
     * A file picker is another app's activity, and its result arrives through a callback
     * on a ViewModel that outlives the navigation to the lock screen. So if the session
     * locked while the user was choosing, the work would start anyway and write into a
     * vault the interface has just declared closed - observed on 2026-08-29. The timer no
     * longer fires while a picker is open, which makes this rare rather than routine; it
     * is here because "rare" is not the same as "cannot happen", and the thing it prevents
     * is writing to a vault behind the user's back.
     */
    /*
     * Name conflicts during an import (#157).
     *
     * The question is asked once per operation, at the first collision, and the answer
     * covers the rest of the batch. Asking per file would mean a thousand taps on a
     * thousand-file folder, which is a feature nobody would use; being able to answer
     * differently for different files inside one import is deliberately not offered, and
     * the dialog says so.
     *
     * The check happens at the first collision rather than by scanning ahead: a flat list
     * of files could be checked up front with one listing, but a folder import discovers
     * its tree as it walks, and one mechanism that works for both beats two that each
     * work for one.
     *
     * Directories are not asked about. An imported folder whose name already exists merges
     * into it, which is what every file manager does and what makes the file-level question
     * the meaningful one.
     */
    private var conflictAnswer: CompletableDeferred<ConflictChoice>? = null
    private var conflictPolicy: ConflictChoice? = null

    /* Names already in each destination directory, read once per directory per operation
     * and kept up to date as files land, so a second file cannot collide with the first. */
    private val destinationNames = mutableMapOf<String, MutableSet<String>>()

    private fun beginConflictTracking() {
        conflictPolicy = null
        destinationNames.clear()
    }

    private fun namesIn(handle: Long, dir: String): MutableSet<String> =
        destinationNames.getOrPut(dir) {
            (engine.listFilesOrNull(handle, dir) ?: emptyArray())
                .map { it.name }
                .toMutableSet()
        }

    /**
     * The name to write `name` under in `dir`, or null when the user chose to skip it.
     * Suspends on the first collision of an operation while the answer is given.
     */
    private suspend fun nameToWrite(handle: Long, dir: String, name: String): String? {
        val taken = namesIn(handle, dir)
        if (name !in taken) {
            taken.add(name)
            return name
        }
        val choice = conflictPolicy ?: askAboutConflict(name)
        return when (choice) {
            ConflictChoice.SKIP      -> null
            ConflictChoice.REPLACE   -> name          /* the write truncates what is there */
            ConflictChoice.KEEP_BOTH -> freeName(name, taken).also { taken.add(it) }
        }
    }

    private suspend fun askAboutConflict(name: String): ConflictChoice {
        val answer = CompletableDeferred<ConflictChoice>()
        conflictAnswer = answer
        _state.update { it.copy(conflictPrompt = ConflictPrompt(name)) }
        val choice = answer.await()
        conflictAnswer = null
        conflictPolicy = choice
        _state.update { it.copy(conflictPrompt = null) }
        return choice
    }

    /** Called from the dialog. The answer applies to the whole of the current operation. */
    fun answerConflict(choice: ConflictChoice) {
        conflictAnswer?.complete(choice)
    }

    private fun refuseIfLocked(): Boolean {
        if (!sessionState.isLocked) return false
        _state.update { it.copy(pendingNotification = InAppNotification.OperationRefusedLocked) }
        return true
    }

    /*
     * A batch operation - import, export, paste, move, delete - is work the app is doing
     * for the user, and it has to be visible as such outside this screen: the idle clock
     * must not age out during one, and nothing may unmount the volume underneath it. See
     * IdleMonitor.
     *
     * It is learned from `isOperationInProgress` rather than by bracketing each operation
     * by hand, because that flag is already load-bearing for the progress overlay - every
     * operation must set it to be visible at all - so a new operation cannot be added and
     * forget this. Bracketing seven call sites by hand could.
     */
    private var markedBusy = false

    private fun mirrorOperationState(busy: Boolean) {
        if (busy && !markedBusy) {
            idleMonitor.operationStarted()
            markedBusy = true
        } else if (!busy && markedBusy) {
            idleMonitor.operationFinished()
            markedBusy = false
        }
    }

    override fun onCleared() {
        /* The screen can go while a batch is still running. Leaving the counter raised
         * would keep the vault unlocked for the life of the process, and an import left
         * waiting on a dialog nobody can answer any more would do the same. */
        conflictAnswer?.complete(ConflictChoice.SKIP)
        mirrorOperationState(false)
        super.onCleared()
    }


    /** Has the explanation before the photo-location request already been shown once (#149). */
    val mediaLocationPromptShown: StateFlow<Boolean> = appPrefs.mediaLocationPromptShown.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = true
    )

    fun markMediaLocationPromptShown() {
        viewModelScope.launch { appPrefs.setMediaLocationPromptShown(true) }
    }

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
        /* Whether the filesystem underneath can hold a second name for one file.
         * Only ext4 can; FAT and exFAT have nothing to offer but a copy, and a
         * menu entry that silently copied would be the opposite of the ask (#128). */
        val supportsLinks: Boolean = false,
        val error: String? = null,
        val pendingNotification: InAppNotification? = null,
        val isOperationInProgress: Boolean = false,
        val operationMessage: String? = null,
        /* Non-null while an import is stopped waiting for an answer about a name (#157). */
        val conflictPrompt: ConflictPrompt? = null,
        val importProgress: ImportProgress? = null,
        val isReadOnly: Boolean = false,
        val thumbnails: Map<String, android.graphics.Bitmap> = emptyMap()
    )

    /**
     * What the import indicator shows. Separate from [FileManagerState.operationMessage]
     * because an import is the one operation that can say how far along it is: it streams
     * in chunks, so the bytes written against the file's size are known as it goes.
     *
     * [total] is 0 when the number of files is not known ahead of time, which is the
     * folder import - it walks the tree as it goes rather than counting first.
     * [fraction] is null when the provider does not report a size, and the bar then falls
     * back to running without a position rather than inventing one.
     */
    /**
     * What to do about a name that is already taken in the destination (#157).
     *
     * Importing used to write straight over whatever was there, silently and with nothing
     * to undo it - while copying inside the vault made a numbered duplicate. One app, two
     * answers, and the quiet one destroyed data.
     */
    enum class ConflictChoice { SKIP, REPLACE, KEEP_BOTH }

    /** The name that collided, while the operation waits for an answer about it. */
    data class ConflictPrompt(val fileName: String)

    data class ImportProgress(
        val fileName: String,
        val index: Int,
        val total: Int,
        val fraction: Float?
    )

    private val _state = MutableStateFlow(FileManagerState())
    val state = _state.asStateFlow()

    /* Below _state on purpose: an init block runs in declaration order, and reading the
     * flow from above it collects a field that is still null. */
    init {
        viewModelScope.launch {
            _state.map { it.isOperationInProgress }
                .distinctUntilChanged()
                .collect { mirrorOperationState(it) }
        }
    }

    val mountedContainers: kotlinx.coroutines.flow.StateFlow<List<Container>> = repo.getAllContainers()
        .map { list -> list.filter { it.isMounted } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private var initialized = false
    private val loadingThumbnails = mutableSetOf<String>()
    private val thumbnailSemaphore = Semaphore(4)

    private val prefs = context.fileManagerPrefs

    companion object {
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
        _state.update {
            it.copy(containerId = containerId,
                    isReadOnly = repo.isContainerReadOnly(containerId))
        }
        /* The filesystem has to be read, so it cannot be part of the update above.
         * Links exist only on ext4 (#128). */
        viewModelScope.launch {
            val fs = runCatching { repo.getContainerById(containerId)?.filesystem }
                .getOrNull()
            _state.update { it.copy(supportsLinks = fs.equals("ext4", ignoreCase = true)) }
        }
        viewModelScope.launch {
            runCatching {
                val p = prefs.data.first()
                _state.update { it.copy(
                    sortBy       = runCatching { SortBy.valueOf(p[FileBrowserPrefs.SORT_BY_KEY] ?: "DATE") }.getOrDefault(SortBy.DATE),
                    sortAscending = p[FileBrowserPrefs.SORT_ASC_KEY] ?: false,
                    showHidden   = p[FileBrowserPrefs.SHOW_HIDDEN_KEY] ?: false,
                    foldersFirst = p[FileBrowserPrefs.FOLDERS_FIRST_KEY] ?: true,
                    viewMode     = runCatching { ViewMode.valueOf(p[FileBrowserPrefs.VIEW_MODE_KEY] ?: "LIST") }.getOrDefault(ViewMode.LIST)
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
                    isCut             = false,
                    kind                  = f.kind,
                    linkTarget            = f.linkTarget,
                    linkTargetIsDirectory = f.linkTargetIsDirectory,
                    linkBroken            = f.linkBroken
                )
            }
        }
        if (items.isEmpty()) return
        clipboard.copy(items)
        _state.update { it.copy(
            clipboardCount       = clipboard.count,
            isSelectionMode      = false,
            selectedItems        = emptySet(),
            pendingNotification  = InAppNotification.FilesCopied(items.size)
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
                    isCut             = true,
                    kind                  = f.kind,
                    linkTarget            = f.linkTarget,
                    linkTargetIsDirectory = f.linkTargetIsDirectory,
                    linkBroken            = f.linkBroken
                )
            }
        }
        if (items.isEmpty()) return
        clipboard.cut(items)
        _state.update { it.copy(
            clipboardCount       = clipboard.count,
            isSelectionMode      = false,
            selectedItems        = emptySet(),
            pendingNotification  = InAppNotification.FilesCut(items.size)
        ) }
    }

    /**
     * Gives each of `items` a second name in `destPath`, inside this same vault (#128).
     *
     * One vault only, and that is not a limitation to be lifted later: a link is a
     * name pointing at something in the same filesystem, and there is no such thing
     * as one that reaches into another container. What could be offered instead is a
     * copy, which takes the space again and stops being the same file — the two
     * things the feature exists to avoid.
     *
     * Which kind each one gets is decided natively from what is being linked: a file
     * gets a hard link, a folder a symbolic one. The user is shown one word.
     */
    fun createLinkAt(items: List<NativeFileInfo>, destPath: String) {
        if (_state.value.isReadOnly || items.isEmpty()) return
        val containerId = _state.value.containerId
        val handle = repo.getContainerHandle(containerId) ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isOperationInProgress = true,
                                    operationMessage = "Linking…") }
            var count = 0
            var failed = 0
            val taken = runCatching {
                engine.listFilesOrNull(handle, destPath)?.map { it.name }?.toMutableSet()
            }.getOrNull() ?: mutableSetOf()

            for (item in items) {
                val name = freeName(item.name, taken)
                taken.add(name)
                val linkPath = if (destPath == "/") "/$name" else "$destPath/$name"
                val rc = runCatching { engine.createLink(handle, linkPath, item.path) }
                    .getOrDefault(VeraCryptEngine.ERR_FS)
                if (rc == VeraCryptEngine.ERR_OK) count++ else failed++
            }

            /* What was linked decides what may be promised about it: a file gets
             * a hard link and cannot come apart, a folder gets a symbolic one and
             * can. Taken from the items rather than from the results, since a
             * failure does not change what was being asked for. */
            val dirs = items.count { it.isDirectory }
            val kind = when {
                dirs == 0            -> InAppNotification.LinkedKind.FILES
                dirs == items.size   -> InAppNotification.LinkedKind.FOLDERS
                else                 -> InAppNotification.LinkedKind.MIXED
            }

            clearSelection()
            loadDirectory(_state.value.currentPath)
            _state.update {
                it.copy(isOperationInProgress = false, operationMessage = null,
                        pendingNotification = when {
                            failed > 0 -> InAppNotification.FilesPasteFailed(
                                failed, failed + count)
                            count > 0  -> InAppNotification.FilesLinked(count, kind)
                            else       -> null
                        })
            }
        }
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
            // Three outcomes, not two. A paste that moved nothing because the items are
            // already here is fine; a paste where every item failed is not, and until now
            // both ended in silence and looked exactly like success (#129).
            var count   = 0
            var failed  = 0
            var skipped = 0
            val tally   = CarryTally()
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
                    if (intoOwnFolder && isCut) { skipped++; continue }

                    /* Pasting a folder into itself or into its own subtree (cut
                       /a, paste in /a or /a/b) would recurse into the copy it is
                       creating and then delete the original. Refuse instead. */
                    val srcDir = item.sourcePath.trimEnd('/')
                    val intoOwnSubtree = sameContainer && item.isDirectory &&
                        (currentPath == srcDir || currentPath.startsWith("$srcDir/"))
                    if (intoOwnSubtree) { skipped++; continue }

                    /* A copy into the same folder becomes a duplicate rather than
                       a write onto itself, so it behaves like every other file
                       manager instead of silently doing nothing. */
                    val destName = if (intoOwnFolder) freeName(item.fileName, takenNames)
                                   else item.fileName
                    takenNames.add(destName)

                    val destPath = if (currentPath == "/") "/$destName" else "$currentPath/$destName"
                    _state.update { it.copy(operationMessage = "${if (isCut) "Moving" else "Copying"} ${item.fileName}…") }

                    /*
                     * A move inside one vault is a rename: the entry changes folder and
                     * nothing is read or written. Move to has always done this; the
                     * clipboard copied every byte and deleted the original instead, which
                     * turned a link into an empty file and quietly split a file with two
                     * names into two separate files, each holding its own copy (#168).
                     */
                    if (sameContainer && isCut) {
                        val rc = runCatching {
                            engine.renameFile(item.sourceHandle, item.sourcePath, destPath)
                        }.getOrDefault(VeraCryptEngine.ERR_FS)
                        if (rc == VeraCryptEngine.ERR_OK) count++ else failed++
                        continue
                    }

                    val carried = carryOddEntry(
                        EntryKind(item), item.sourceHandle, item.sourcePath,
                        destHandle, destPath, sameContainer, isCut, tally
                    )
                    if (carried != null) {
                        when (carried) {
                            Carried.DONE        -> count++
                            Carried.FAILED      -> failed++
                            Carried.LEFT_BEHIND -> {}
                        }
                        continue
                    }

                    if (item.isDirectory) {
                        val ok = copyDirectoryRecursive(item.sourceHandle, item.sourcePath, destHandle, destPath, tally)
                        if (ok && isCut) runCatching { engine.deleteDirectory(item.sourceHandle, item.sourcePath) }
                        if (ok) count++ else failed++
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
                        } else failed++
                    }
                } catch (_: Exception) { failed++ }
            }
            clipboard.clear()
            refreshNow()
            val destDesc = if (currentPath == "/") "Root" else currentPath
            _state.update { it.copy(
                isOperationInProgress = false,
                operationMessage      = null,
                clipboardCount        = 0,
                pendingNotification   = when {
                    // Failure first: someone told "3 copied" and not told "2 were not"
                    // walks away believing all five arrived.
                    failed > 0  -> InAppNotification.FilesPasteFailed(failed, clipItems.size)
                    /* Reported even when nothing arrived: items that stayed behind are
                       the one outcome silence would misread as success (#168). */
                    count > 0 || tally.leftBehind > 0 ->
                        if (isCut) InAppNotification.FilesMoved(count, destDesc, tally.leftBehind)
                        else InAppNotification.FilesPasted(count, tally.leftBehind)
                    skipped > 0 -> InAppNotification.FilesAlreadyHere
                    else        -> null
                }
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
            // Three outcomes, not two. A paste that moved nothing because the items are
            // already here is fine; a paste where every item failed is not, and until now
            // both ended in silence and looked exactly like success (#129).
            var count   = 0
            var failed  = 0
            var skipped = 0
            val tally   = CarryTally()
            val chunkSize = 1 * 1024 * 1024

            for (item in toCopy) {
                val parentDir = item.path.substringBeforeLast("/").let { if (it.isEmpty()) "/" else it }
                if (destinationContainerId == s.containerId && parentDir == destinationPath) { skipped++; continue }

                try {
                    val destItemPath = if (destinationPath == "/") "/${item.name}" else "$destinationPath/${item.name}"
                    _state.update { it.copy(operationMessage = "Copying ${item.name}…") }

                    val carried = carryOddEntry(
                        EntryKind(item), sourceHandle, item.path,
                        destHandle, destItemPath,
                        sameVault = destinationContainerId == s.containerId,
                        isMove = false, tally = tally
                    )
                    if (carried != null) {
                        when (carried) {
                            Carried.DONE        -> count++
                            Carried.FAILED      -> failed++
                            Carried.LEFT_BEHIND -> {}
                        }
                        continue
                    }

                    if (item.isDirectory) {
                        val ok = copyDirectoryRecursive(sourceHandle, item.path, destHandle, destItemPath, tally)
                        if (ok) count++ else failed++
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
                        if (writeOk) count++ else failed++
                    }
                } catch (_: Exception) { failed++ }
            }

            exitSelectionMode()
            _state.update { it.copy(
                isOperationInProgress = false,
                operationMessage      = null,
                pendingNotification   = when {
                    failed > 0  -> InAppNotification.FilesPasteFailed(failed, toCopy.size)
                    count > 0 || tally.leftBehind > 0 ->
                        InAppNotification.FilesPasted(count, tally.leftBehind)
                    skipped > 0 -> InAppNotification.FilesAlreadyHere
                    else        -> null
                }
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
            // See paste(): a move that moves nothing has to say which kind of nothing.
            var count   = 0
            var failed  = 0
            var skipped = 0
            val tally   = CarryTally()

            for (item in toMove) {
                val parentDir = item.path.substringBeforeLast("/").let { if (it.isEmpty()) "/" else it }
                if (destinationContainerId == s.containerId && parentDir == destinationPath) { skipped++; continue }

                val destItemPath = if (destinationPath == "/") "/${item.name}" else "$destinationPath/${item.name}"
                _state.update { it.copy(operationMessage = "Moving ${item.name}…") }

                /* Into another vault a link cannot travel as a link and a special file
                   cannot travel at all, so both stay where they are rather than being
                   turned into something else on the way (#168). A move inside the vault
                   is a rename below and keeps everything it is. */
                val carried = carryOddEntry(
                    EntryKind(item), sourceHandle, item.path, destHandle, destItemPath,
                    sameVault = destinationContainerId == s.containerId,
                    isMove = true, tally = tally
                )
                if (carried != null) {
                    when (carried) {
                        Carried.DONE        -> count++
                        Carried.FAILED      -> failed++
                        Carried.LEFT_BEHIND -> {}
                    }
                    continue
                }

                val moved = when {
                    destinationContainerId == s.containerId -> {
                        val result = runCatching {
                            engine.renameFile(sourceHandle, item.path, destItemPath)
                        }.getOrDefault(VeraCryptEngine.ERR_FS)
                        result == VeraCryptEngine.ERR_OK
                    }
                    item.isDirectory -> moveDirectoryRecursive(sourceHandle, item.path, destHandle, destItemPath, tally)
                    else -> moveFile(sourceHandle, item.path, destHandle, destItemPath, item.size)
                }
                if (moved) count++ else failed++
            }

            exitSelectionMode()
            refreshNow()
            _state.update { it.copy(
                isOperationInProgress = false,
                operationMessage      = null,
                pendingNotification   = when {
                    failed > 0  -> InAppNotification.FilesPasteFailed(failed, toMove.size)
                    count > 0 || tally.leftBehind > 0 ->
                        InAppNotification.FilesMoved(count, destinationName, tally.leftBehind)
                    skipped > 0 -> InAppNotification.FilesAlreadyHere
                    else        -> null
                }
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

    // Follows a file rename into the Gallery: the same row keeps its stable id (so the
    // in-memory bitmap survives) but takes the new path and name, and its thumbnail cache
    // is moved to match. The @Update re-emits Room's Flow, so the gallery updates live.
    // A no-op when the renamed file is not indexed media (getByPath returns null).
    private suspend fun renameFileMedia(containerId: String, oldPath: String, newPath: String, newName: String) {
        val entity = mediaFileDao.getByPath(containerId, oldPath) ?: return
        mediaFileDao.updateMediaFile(entity.copy(relativePath = newPath, fileName = newName))
        thumbnailManager.renameFileCache(containerId, oldPath, newPath, entity.id)
    }

    // A renamed directory moves every media file beneath it. Only the path prefix changes;
    // each file keeps its own name. Mirrors cleanupDirectoryMedia, rewriting instead of deleting.
    private suspend fun renameDirectoryMedia(containerId: String, oldDir: String, newDir: String) {
        // Normalise trailing slashes so the prefix swap is exact whichever form the path
        // arrives in: "/a/b.jpg".substring("/a".length) is "/b.jpg", giving newDir + "/b.jpg".
        val cleanOld = oldDir.trimEnd('/')
        val cleanNew = newDir.trimEnd('/')
        val prefix = "$cleanOld/"
        mediaFileDao.getAllForContainerOnce(containerId)
            .filter { it.relativePath.startsWith(prefix) }
            .forEach { entity ->
                val newPath = cleanNew + entity.relativePath.substring(cleanOld.length)
                mediaFileDao.updateMediaFile(entity.copy(relativePath = newPath))
                thumbnailManager.renameFileCache(containerId, entity.relativePath, newPath, entity.id)
            }
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
                // The Gallery's Room row and the thumbnail cache are keyed by the file's
                // path, so a rename has to move them too - otherwise the media viewer shows
                // the old name and a placeholder until the container is remounted. The disk
                // rename already succeeded, so a hiccup syncing the index must not crash or
                // block the result - at worst it falls back to the old remount behaviour.
                runCatching {
                    if (file.isDirectory) renameDirectoryMedia(s.containerId, file.path, newPath)
                    else                  renameFileMedia(s.containerId, file.path, newPath, finalName)
                }
                refreshNow()
                _state.update { it.copy(pendingNotification = InAppNotification.FileRenamed(finalName)) }
            }
            withContext(Dispatchers.Main) { onResult(success) }
        }
    }

    fun importFiles(context: Context, uris: List<android.net.Uri>, deleteAfterImport: Boolean = false) {
        if (refuseIfLocked()) return
        val s = _state.value
        if (s.isReadOnly) {
            _state.update { it.copy(pendingNotification = InAppNotification.ReadOnlyError) }
            return
        }
        val handle = repo.getContainerHandle(s.containerId) ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isOperationInProgress = true) }
            beginConflictTracking()
            var count = 0
            var skipped = 0
            var hiddenProtected = false
            /* Null while everything is fine; set to the first failing native
               code so the banner can say what actually went wrong (#114). */
            var failureCode: Int? = null
            val chunkSize = 1 * 1024 * 1024
            val importedMedia = mutableListOf<Pair<String, Long>>()
            for ((index, uri) in uris.withIndex()) {
                if (hiddenProtected || failureCode != null) break
                try {
                    val rawName = getFileNameFromUri(context, uri) ?: continue
                    val original = File(rawName).name.ifEmpty { continue }
                    /* May wait here for an answer about a name already taken (#157). */
                    val name = nameToWrite(handle, s.currentPath, original)
                    if (name == null) { skipped++; continue }
                    val destPath = buildDestinationPath(s.currentPath, name)
                    val declaredSize = uriSize(context, uri)
                    val progress = ImportProgress(
                        fileName = name,
                        index    = index + 1,
                        total    = uris.size,
                        fraction = if (declaredSize > 0L) 0f else null
                    )
                    _state.update { it.copy(importProgress = progress) }
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
                                else -> {
                                    offset += read
                                    fileOk = true
                                    if (declaredSize > 0L) {
                                        val done_ = (offset.toFloat() / declaredSize).coerceIn(0f, 1f)
                                        _state.update {
                                            it.copy(importProgress = progress.copy(fraction = done_))
                                        }
                                    }
                                }
                            }
                        }
                        fileSize = offset
                    }
                    if (!hiddenProtected && failureCode == null) {
                        count++
                        if (fileOk) {
                            val srcTime = FileUtils.uriLastModified(context, uri)
                            if (srcTime > 0L)
                                runCatching { engine.setFileTime(handle, destPath, srcTime) }
                        }
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
                importProgress        = null,
                pendingNotification   = when {
                    hiddenProtected      -> InAppNotification.HiddenVolumeWriteProtection
                    failureCode != null  -> InAppNotification.ImportFailed(importFailureReason(failureCode))
                    count > 0            -> InAppNotification.FilesImported(count, skipped)
                    skipped > 0          -> InAppNotification.FilesImported(0, skipped)
                    else                 -> null
                }
            ) }
            if (importedMedia.isNotEmpty()) {
                indexAndThumbnail(handle, s.containerId, importedMedia)
            }
        }
    }

    fun importFolder(context: Context, treeUri: android.net.Uri, deleteAfterImport: Boolean = false) {
        if (refuseIfLocked()) return
        val s = _state.value
        if (s.isReadOnly) {
            _state.update { it.copy(pendingNotification = InAppNotification.ReadOnlyError) }
            return
        }
        val handle = repo.getContainerHandle(s.containerId) ?: return

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isOperationInProgress = true) }
            beginConflictTracking()

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
            val (count, skipped, hiddenProtected, failureCode) =
                importFolderRecursive(context, handle, treeUri, rootDocId, destPath, importedMedia)

            if (deleteAfterImport && !hiddenProtected && failureCode == null && count > 0)
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, rootDocUri) }

            refreshNow()
            _state.update { it.copy(
                isOperationInProgress = false,
                operationMessage      = null,
                importProgress        = null,
                pendingNotification   = when {
                    hiddenProtected      -> InAppNotification.HiddenVolumeWriteProtection
                    failureCode != null  -> InAppNotification.ImportFailed(importFailureReason(failureCode))
                    count > 0            -> InAppNotification.FilesImported(count, skipped)
                    skipped > 0          -> InAppNotification.FilesImported(0, skipped)
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
        VeraCryptEngine.ERR_DIR_FULL       -> ImportFailureReason.DIRECTORY_FULL
        VeraCryptEngine.ERR_NO_SPACE       -> ImportFailureReason.NO_SPACE
        VeraCryptEngine.ERR_TOO_FRAGMENTED -> ImportFailureReason.TOO_FRAGMENTED
        VeraCryptEngine.ERR_READ_ONLY      -> ImportFailureReason.READ_ONLY
        else                               -> ImportFailureReason.UNKNOWN
    }

    // Reduces an untrusted SAF display name to a bare filename, rejecting empty,
    // "." and ".." so it can never be used to climb out of the destination folder.
    private fun sanitizeEntryName(name: String): String? =
        File(name).name.takeUnless { it.isEmpty() || it == "." || it == ".." }

    /* Four things come back from a walk now, which is one more than a Triple should carry. */
    private data class FolderImport(
        val count: Int,
        val skipped: Int,
        val hiddenProtected: Boolean,
        val failureCode: Int?
    )

    private suspend fun importFolderRecursive(
        context: Context,
        handle: Long,
        treeUri: android.net.Uri,
        docId: String,
        destPath: String,
        importedMedia: MutableList<Pair<String, Long>> = mutableListOf()
    ): FolderImport {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection  = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            // Asked for here rather than queried per file: the listing already costs one
            // cursor, the size is what lets the bar show a position, and the modification
            // time is the date the copy has to be stamped back to (#154).
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        var count           = 0
        var skipped         = 0
        var hiddenProtected = false
        /* First failing native code, or null while everything succeeded. */
        var failureCode: Int? = null
        val chunkSize       = 1 * 1024 * 1024

        context.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idCol   = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val timeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

            while (cursor.moveToNext() && !hiddenProtected && failureCode == null) {
                val childDocId  = cursor.getString(idCol) ?: continue
                val childName   = cursor.getString(nameCol) ?: continue
                val childMime   = cursor.getString(mimeCol) ?: continue
                // Strip any path components a hostile DocumentsProvider might smuggle in
                // COLUMN_DISPLAY_NAME before using it as an in-vault path (defense-in-depth;
                // the single-file import path sanitizes the same way).
                val safeName    = sanitizeEntryName(childName) ?: continue

                try {
                    if (childMime == DocumentsContract.Document.MIME_TYPE_DIR) {
                        /* A folder that is already there is merged into, not asked about. */
                        val childDest = buildDestinationPath(destPath, safeName)
                        runCatching { engine.createDirectory(handle, childDest) }
                        val sub =
                            importFolderRecursive(context, handle, treeUri, childDocId, childDest, importedMedia)
                        count += sub.count
                        skipped += sub.skipped
                        if (sub.hiddenProtected) hiddenProtected = true
                        if (sub.failureCode != null) failureCode = sub.failureCode
                    } else {
                        /* May wait here for an answer about a name already taken (#157). */
                        val writeName = nameToWrite(handle, destPath, safeName)
                        if (writeName == null) { skipped++; continue }
                        val childDest = buildDestinationPath(destPath, writeName)
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocId)
                        val childSize = if (sizeCol >= 0 && !cursor.isNull(sizeCol))
                            cursor.getLong(sizeCol) else 0L
                        val childMtime = if (timeCol >= 0 && !cursor.isNull(timeCol))
                            cursor.getLong(timeCol) else 0L
                        // total 0: a folder import discovers its files as it walks, so there
                        // is no count to show - the name and the current file's bar are what
                        // it can honestly report.
                        val childProgress = ImportProgress(
                            fileName = safeName,
                            index    = 0,
                            total    = 0,
                            fraction = if (childSize > 0L) 0f else null
                        )
                        _state.update { it.copy(importProgress = childProgress) }
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
                                    else -> {
                                        offset += read
                                        if (childSize > 0L) {
                                            val done_ = (offset.toFloat() / childSize).coerceIn(0f, 1f)
                                            _state.update {
                                                it.copy(importProgress = childProgress.copy(fraction = done_))
                                            }
                                        }
                                    }
                                }
                            }
                            childFileSize = offset
                        }
                        if (!hiddenProtected && failureCode == null) {
                            count++
                            if (childMtime > 0L)
                                runCatching { engine.setFileTime(handle, childDest, childMtime) }
                            val ext = childName.substringAfterLast('.', "").lowercase()
                            if (ext in MediaExtensions.IMAGE || ext in MediaExtensions.VIDEO) {
                                importedMedia.add(Pair(childDest, childFileSize))
                            }
                        }
                    }
                } catch (_: Exception) { failureCode = VeraCryptEngine.ERR_FS }
            }
        }
        return FolderImport(count, skipped, hiddenProtected, failureCode)
    }

    /**
     * What one export produced. A count on its own cannot describe an export out of
     * an ext4 vault: a link lives inside the vault and there is nowhere outside to
     * put one, so some items land as something other than themselves and some
     * cannot land at all (#167).
     *
     * [exported] counts the picked items that produced something, as it always did -
     * a folder is one of them. [skipped] counts items at any depth there was nothing
     * to write for. [duplicates] counts files written out more than once, which is
     * what a second name for one file has to become outside the vault.
     */
    private class ExportTally {
        var exported = 0
        var skipped  = 0
        private val written    = HashSet<Long>()
        private val duplicated = HashSet<Long>()

        /** How many distinct files left as more than one copy, not how many extra writes. */
        val duplicates: Int get() = duplicated.size

        /** Once per file actually written, at any depth. Inode 0 means the filesystem
         *  cannot tell two entries apart - FAT and exFAT - so nothing is claimed there. */
        fun noteWritten(inode: Long) {
            if (inode > 0L && !written.add(inode)) duplicated.add(inode)
        }
    }

    fun exportSelected(context: Context, treeUri: android.net.Uri) {
        if (refuseIfLocked()) return
        val s = _state.value
        val handle = repo.getContainerHandle(s.containerId) ?: return
        val toExport = s.selectedItems.mapNotNull { path -> s.files.find { it.path == path } }

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isOperationInProgress = true) }
            val tally = ExportTally()
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val treeDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)
            for (file in toExport) {
                try {
                    _state.update { it.copy(operationMessage = "Exporting ${file.name}…") }
                    when {
                        /*
                         * Nothing behind it to write. Both used to be created outside as
                         * an empty file under the item's own name and counted as
                         * exported, which is the worst of the answers available: the
                         * user is told the item left the vault and what they have is
                         * zero bytes (#167).
                         */
                        file.isSpecial || (file.isSymlink && file.linkBroken) -> tally.skipped++

                        /*
                         * A folder, or a link to one. SAF has no notion of a link, so
                         * the only thing that can leave is what the link leads to,
                         * written out under the link's own name - which is what the
                         * user sees when they open it here.
                         */
                        file.opensAsDirectory -> {
                            val ok = exportDirectoryRecursive(
                                context, handle, file.path, treeDocUri, file.name,
                                tally, setOf(file.inode)
                            )
                            if (ok) tally.exported++
                        }

                        else -> {
                            val ok = exportOneFile(
                                context, handle, file.path, file.size, file.name, treeDocUri
                            )
                            if (ok) {
                                tally.noteWritten(file.inode)
                                tally.exported++
                            }
                        }
                    }
                } catch (_: Exception) { }
            }
            /* Debug builds say what the walk decided, so a device pass has something to
             * check the banner against - what a link became is invisible once it is
             * outside the vault. Counts only, never a name. */
            if (zip.arcanum.BuildConfig.DEBUG)
                android.util.Log.d("ArcanumExport", "exported=${tally.exported} " +
                    "skipped=${tally.skipped} duplicates=${tally.duplicates}")
            exitSelectionMode()
            _state.update { it.copy(
                isOperationInProgress = false,
                operationMessage      = null,
                /* Shown even when nothing landed: an export of one dead link used to end
                 * in silence, and silence after an operation reads as success. */
                pendingNotification   = if (tally.exported > 0 || tally.skipped > 0)
                    InAppNotification.FilesExported(tally.exported, tally.skipped, tally.duplicates)
                else null
            ) }
        }
    }

    fun clearPendingNotification() {
        _state.update { it.copy(pendingNotification = null) }
    }

    // ── Private helpers ───────────────────────────────────────────────────

    /** How many items a copy or a move had to leave where they were (#168). */
    private class CarryTally { var leftBehind = 0 }

    /** What carrying one entry came to. */
    private enum class Carried { DONE, LEFT_BEHIND, FAILED }

    /** The facts about an entry that decide how it can be carried, from either side. */
    private class EntryKind(
        val isSymlink: Boolean,
        val isSpecial: Boolean,
        val linkTarget: String?,
        val linkBroken: Boolean,
        val opensAsDirectory: Boolean
    ) {
        constructor(f: NativeFileInfo) : this(
            f.isSymlink, f.isSpecial, f.linkTarget, f.linkBroken, f.opensAsDirectory)
        constructor(i: ClipboardItem) : this(
            i.isSymlink, i.isSpecial, i.linkTarget, i.linkBroken, i.opensAsDirectory)
    }

    /**
     * Carries a link or a special file - the two things a plain read cannot move.
     * Returns null for an ordinary file or folder, which the caller carries its own way.
     *
     * One sentence decides all of it: a link stays a link inside the vault it lives in,
     * and cannot be one anywhere else. What used to happen instead was that a folder
     * link went down the file branch, read back empty, and landed as a 0 byte file under
     * the link's name - and a cut deleted the link afterwards (#168).
     *
     * - a special file (FIFO, socket, device node) is left where it is wherever it was
     *   going: nothing here can create one, and reading it gives nothing
     * - moved inside its own vault, it is a rename - the entry changes folder and not one
     *   byte is read or written, which is also the only form that keeps a file with two
     *   names one file
     * - copied inside its own vault, the link is written again with the same target, dead
     *   ones included: a link that leads nowhere copies as a link that leads nowhere
     * - copied into another vault, or onto FAT, it becomes what it leads to, which is the
     *   answer export gives for the same reason. A dead one has nothing to become and is
     *   left where it is
     * - MOVED where it cannot stay a link, it is left where it is rather than converted.
     *   Turning it into a copy and then deleting it is precisely the fault being fixed
     */
    private suspend fun carryOddEntry(
        entry: EntryKind,
        srcHandle: Long, srcPath: String,
        destHandle: Long, destPath: String,
        sameVault: Boolean,
        isMove: Boolean,
        tally: CarryTally
    ): Carried? {
        if (entry.isSpecial) { tally.leftBehind++; return Carried.LEFT_BEHIND }
        if (!entry.isSymlink) return null

        if (sameVault) {
            val rc = if (isMove) {
                runCatching { engine.renameFile(srcHandle, srcPath, destPath) }
                    .getOrDefault(VeraCryptEngine.ERR_FS)
            } else {
                val target = entry.linkTarget ?: return Carried.FAILED
                runCatching { engine.createSymlink(destHandle, destPath, target) }
                    .getOrDefault(VeraCryptEngine.ERR_FS)
            }
            return if (rc == VeraCryptEngine.ERR_OK) Carried.DONE else Carried.FAILED
        }

        if (isMove || entry.linkBroken) { tally.leftBehind++; return Carried.LEFT_BEHIND }

        if (entry.opensAsDirectory) {
            val ok = copyDirectoryRecursive(srcHandle, srcPath, destHandle, destPath, tally)
            return if (ok) Carried.DONE else Carried.FAILED
        }
        /* A link to a file, going somewhere that cannot hold a link: reading it follows
           it, so the caller's ordinary file copy already writes out the file itself. */
        return null
    }

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

    /**
     * Writes one file out through SAF. Shared by the picked items and by the walk, so
     * a link found inside a folder leaves exactly as one picked by hand does.
     *
     * [size] is the size of what will be read, which for a link is its target's rather
     * than the length of the path it holds.
     */
    private suspend fun exportOneFile(
        context: Context,
        handle: Long,
        srcPath: String,
        size: Long,
        name: String,
        parentDocUri: android.net.Uri
    ): Boolean {
        val docUri = DocumentsContract.createDocument(
            context.contentResolver, parentDocUri, getMimeType(name), name
        ) ?: return false
        val out = context.contentResolver.openOutputStream(docUri) ?: return false
        val chunkSize = 1 * 1024 * 1024
        out.use { stream ->
            var offset = 0L
            while (offset < size) {
                val chunk = engine.readFile(handle, srcPath, offset, chunkSize) ?: break
                stream.write(chunk)
                offset += chunk.size
                if (chunk.size < chunkSize) break
            }
        }
        return true
    }

    /**
     * [ancestors] holds the inodes of the folders on the way here, this one included.
     */
    private suspend fun exportDirectoryRecursive(
        context: Context,
        handle: Long,
        srcPath: String,
        parentDocUri: android.net.Uri,
        dirName: String,
        tally: ExportTally,
        ancestors: Set<Long>
    ): Boolean {
        val dirUri = DocumentsContract.createDocument(
            context.contentResolver, parentDocUri,
            DocumentsContract.Document.MIME_TYPE_DIR, dirName
        ) ?: return false
        val entries = runCatching { engine.listFilesOrNull(handle, srcPath)?.toList() }.getOrNull()
            ?: return false
        var allOk = true
        for (entry in entries) {
            val entryPath = if (srcPath == "/") "/${entry.name}" else "$srcPath/${entry.name}"
            try {
                when {
                    entry.isSpecial || (entry.isSymlink && entry.linkBroken) -> tally.skipped++

                    entry.opensAsDirectory -> {
                        /*
                         * A folder link is followed like any other folder, since that is
                         * the only way what it holds can leave the vault. Following one
                         * that leads back into a folder already on the way here would
                         * copy that folder into itself until the storage or the stack
                         * gives out; a ring has to revisit a folder it is already
                         * inside, so refusing exactly that is enough to stop every one.
                         */
                        if (entry.inode > 0L && entry.inode in ancestors) {
                            tally.skipped++
                        } else {
                            val ok = exportDirectoryRecursive(
                                context, handle, entryPath, dirUri, entry.name,
                                tally, ancestors + entry.inode
                            )
                            if (!ok) allOk = false
                        }
                    }

                    else -> {
                        val ok = exportOneFile(
                            context, handle, entryPath, entry.size, entry.name, dirUri
                        )
                        if (ok) tally.noteWritten(entry.inode) else allOk = false
                    }
                }
            } catch (_: Exception) { allOk = false }
        }
        return allOk
    }

    private suspend fun copyDirectoryRecursive(
        srcHandle: Long, srcPath: String,
        destHandle: Long, destPath: String,
        tally: CarryTally
    ): Boolean {
        return try {
            runCatching { engine.createDirectory(destHandle, destPath) }
            val entries = engine.listFilesOrNull(srcHandle, srcPath)?.toList()
                ?: return false
            var allCopied = true
            val chunkSize = 1 * 1024 * 1024
            val sameVault = srcHandle == destHandle
            for (entry in entries) {
                val srcEntry  = if (srcPath  == "/") "/${entry.name}" else "$srcPath/${entry.name}"
                val destEntry = if (destPath == "/") "/${entry.name}" else "$destPath/${entry.name}"
                /* A link inside a folder is carried exactly as one picked by hand is. */
                val carried = carryOddEntry(
                    EntryKind(entry), srcHandle, srcEntry, destHandle, destEntry,
                    sameVault, isMove = false, tally = tally
                )
                if (carried != null) {
                    /* Left behind is not a failure of the copy: nothing was lost and the
                       tally has counted it. */
                    if (carried == Carried.FAILED) allCopied = false
                    continue
                }
                val copied = if (entry.opensAsDirectory) {
                    copyDirectoryRecursive(srcHandle, srcEntry, destHandle, destEntry, tally)
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
        destHandle: Long, destPath: String,
        tally: CarryTally
    ): Boolean {
        return try {
            runCatching { engine.createDirectory(destHandle, destPath) }
            val entries = engine.listFilesOrNull(srcHandle, srcPath)?.toList()
                ?: return false
            var allMoved = true
            val sameVault = srcHandle == destHandle
            for (entry in entries) {
                val srcEntry  = if (srcPath  == "/") "/${entry.name}" else "$srcPath/${entry.name}"
                val destEntry = if (destPath == "/") "/${entry.name}" else "$destPath/${entry.name}"
                val carried = carryOddEntry(
                    EntryKind(entry), srcHandle, srcEntry, destHandle, destEntry,
                    sameVault, isMove = true, tally = tally
                )
                if (carried != null) {
                    /* Here, unlike a copy, a link left behind DOES stop the move counting
                       as complete - that is what keeps the source folder below from being
                       deleted with the link still inside it. */
                    if (carried != Carried.DONE) allMoved = false
                    continue
                }
                val moved = if (entry.opensAsDirectory) {
                    moveDirectoryRecursive(srcHandle, srcEntry, destHandle, destEntry, tally)
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

    /**
     * What the provider says the file is, in bytes, or 0 when it will not say. Only the
     * progress bar reads this, so an absent or nonsense size costs a bar without a
     * position rather than a wrong one - never a failed import.
     */
    private fun uriSize(context: Context, uri: android.net.Uri): Long =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (cursor.moveToFirst() && idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx)
                    else 0L
                } ?: 0L
        }.getOrDefault(0L)

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
                p[FileBrowserPrefs.SORT_BY_KEY]       = s.sortBy.name
                p[FileBrowserPrefs.SORT_ASC_KEY]      = s.sortAscending
                p[FileBrowserPrefs.SHOW_HIDDEN_KEY]   = s.showHidden
                p[FileBrowserPrefs.FOLDERS_FIRST_KEY] = s.foldersFirst
                p[FileBrowserPrefs.VIEW_MODE_KEY]     = s.viewMode.name
            }
        }
    }

}
