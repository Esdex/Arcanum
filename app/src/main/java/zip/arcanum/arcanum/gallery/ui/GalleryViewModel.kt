package zip.arcanum.arcanum.gallery.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import zip.arcanum.arcanum.gallery.MediaScanner
import zip.arcanum.arcanum.gallery.ThumbnailManager
import zip.arcanum.arcanum.gallery.ThumbnailPreloader
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.core.database.dao.MediaFileDao
import zip.arcanum.core.database.entities.MediaFileEntity
import zip.arcanum.core.database.entities.MediaFileType
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.notifications.NotificationCenter
import zip.arcanum.core.security.AppPreferences
import zip.arcanum.crypto.VeraCryptEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import kotlin.random.Random
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import javax.inject.Inject

@HiltViewModel
class GalleryViewModel @Inject constructor(
    private val mediaScanner: MediaScanner,
    private val thumbnailManager: ThumbnailManager,
    private val thumbnailPreloader: ThumbnailPreloader,
    private val mediaFileDao: MediaFileDao,
    private val repo: ContainerRepository,
    private val engine: VeraCryptEngine,
    private val prefs: AppPreferences,
    private val notifications: NotificationCenter
) : ViewModel() {

    enum class MediaFilter { ALL, PHOTOS, VIDEOS }

    /**
     * The Gallery's own sort. DATE is the timeline it has always shown - grouped by month
     * and day - and it is the only one of these that groups: the headers say "March 2026"
     * and carry the select-all checkboxes, which mean nothing once the list is ordered by
     * name or size (#122, #151).
     */
    enum class SortBy { NAME, DATE, SIZE, TYPE, RANDOM }

    data class DayGroup(
        val date: LocalDate,
        val displayDate: String,
        val photos: List<MediaFileEntity>
    )

    data class MonthGroup(
        val month: String,
        val days: List<DayGroup>
    )

    /**
     * One folder of the vault that holds media, as the folder sheet shows it: a few of its
     * newest files for the tile, its name, and how many are in it.
     *
     * Derived from the index rather than from the filesystem - every media file already
     * carries the path it lives at, so the folders are a grouping of what the gallery is
     * showing anyway. Nothing is scanned, and a folder appears the moment something lands
     * in it.
     */
    data class MediaFolder(
        val path: String,
        val name: String,
        val count: Int,
        val covers: List<MediaFileEntity>
    )

    data class UiState(
        val isScanning: Boolean = false,
        val scanProgress: Int = 0,
        val scanTotal: Int = 0,
        val currentScanPath: String = "",
        val monthGroups: List<MonthGroup> = emptyList(),
        val allMedia: List<MediaFileEntity> = emptyList(),
        val selectedFilter: MediaFilter = MediaFilter.ALL,
        val sortBy: SortBy = SortBy.DATE,
        val sortAscending: Boolean = false,
        val randomSeed: Long = 1L,
        val showOptionsSheet: Boolean = false,
        val searchQuery: String = "",
        val isSearchActive: Boolean = false,
        val isEmpty: Boolean = false,
        val showDeleteConfirm: Boolean = false,
        val isReadOnly: Boolean = false,
        val folders: List<MediaFolder> = emptyList(),
        /** Folders whose name matches the search box, shown above the results. */
        val matchingFolders: List<MediaFolder> = emptyList(),
        /** Which folders the grid is limited to. Empty means all of them. */
        val folderFilter: Set<String> = emptySet(),
        val showFolderSheet: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _thumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val thumbnails = _thumbnails.asStateFlow()

    // Separate flow so selection changes don't recompose month/day headers
    private val _selectedIds = MutableStateFlow<Set<String>>(emptySet())
    val selectedIds = _selectedIds.asStateFlow()

    val preloadState = thumbnailPreloader.state

    val showResyncButton = prefs.galleryResyncButton.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = false
    )

    private val thumbnailSemaphore = Semaphore(4)
    private val thumbnailMap = LinkedHashMap<String, Bitmap>(MAX_THUMBNAILS + 1, 0.75f, false)
    private val loadingThumbnails = mutableSetOf<String>()
    private val retriedThumbnails = mutableSetOf<String>()
    private val failedThumbnails = mutableSetOf<String>()

    private var currentContainerId: String? = null
    private var scanJob: Job? = null
    private val _filter = MutableStateFlow(MediaFilter.ALL)

    /*
     * Deliberately not remembered between sessions. A folder can be moved or emptied while
     * the vault is closed, and a filter restored onto a folder that is no longer there shows
     * an empty gallery with nothing to explain it. It lasts as long as the vault is open,
     * which is as long as the folders it names are known to be real.
     */
    private val _folderFilter = MutableStateFlow<Set<String>>(emptySet())

    /** What the root folder is called in the sheet - the vault's own name. */
    private var vaultName: String = ""

    private val _allFiles = MutableStateFlow<List<MediaFileEntity>>(emptyList())

    init {
        // The stored sort, applied before anything is shown. It outlives unmounting, the
        // process and the app; it is one setting for the whole app rather than one per vault.
        viewModelScope.launch {
            val by  = runCatching { SortBy.valueOf(prefs.gallerySortBy.first()) }.getOrDefault(SortBy.DATE)
            val asc  = prefs.gallerySortAscending.first()
            val seed = prefs.galleryRandomSeed.first()
            _uiState.update { it.copy(sortBy = by, sortAscending = asc, randomSeed = seed) }
            // Rebuild only if there is already something to rebuild. This used to push the
            // result unconditionally, and since the media list is empty this early, it raced
            // the container load and blanked a grid that had just been filled.
            if (_allFiles.value.isNotEmpty()) {
                val groups = withContext(Dispatchers.Default) { visible(_allFiles.value) }
                _uiState.update { it.copy(monthGroups = groups) }
            }
        }
        viewModelScope.launch {
            thumbnailManager.invalidatedIds.collect { fileId -> evictThumbnail(fileId) }
        }
        viewModelScope.launch {
            thumbnailManager.importedContainerIds.collect { containerId ->
                if (containerId == currentContainerId) refreshMedia(containerId)
            }
        }
        viewModelScope.launch {
            thumbnailManager.deletedContainerIds.collect { containerId ->
                if (containerId == currentContainerId) refreshMedia(containerId)
            }
        }
    }

    private suspend fun refreshMedia(containerId: String) {
        val files = mediaFileDao.getAllForContainerOnce(containerId)
        _allFiles.value = files
        val groups = visible(files)
        _uiState.update {
            it.copy(allMedia = files, monthGroups = groups, isEmpty = files.isEmpty() && !it.isScanning)
        }
    }

    private fun evictThumbnail(fileId: String) {
        thumbnailMap.remove(fileId)
        loadingThumbnails.remove(fileId)
        retriedThumbnails.remove(fileId)
        failedThumbnails.remove(fileId)
        _thumbnails.update { it - fileId }
    }

    fun loadForContainer(containerId: String) {
        _uiState.update { it.copy(isReadOnly = repo.isContainerReadOnly(containerId)) }
        if (currentContainerId == containerId) return
        currentContainerId = containerId
        clearThumbnailState()
        // A vault that has just been closed and another opened must not inherit a filter
        // naming folders of the one before it.
        _folderFilter.value = emptySet()
        viewModelScope.launch {
            vaultName = repo.getContainerById(containerId)?.name.orEmpty()
        }

        viewModelScope.launch(Dispatchers.Default) {
            combine(
                mediaFileDao.getMediaForContainer(containerId),
                _filter,
                _folderFilter
            ) { files, filter, folders -> Triple(files, filter, folders) }
                .collect { (files, filter, folders) ->
                    _allFiles.value = files
                    val groups = visible(files, filter = filter, folders = folders)
                    val allFolders = foldersOf(files)
                    _uiState.update {
                        it.copy(
                            allMedia     = files,
                            monthGroups  = groups,
                            isEmpty      = files.isEmpty() && !it.isScanning,
                            folders      = allFolders,
                            matchingFolders = foldersMatching(allFolders, it.searchQuery),
                            /* A folder that has lost its last file stops existing, and a
                               filter naming it would quietly show nothing. */
                            folderFilter = folders.intersect(files.map { f -> folderOf(f.relativePath) }.toSet())
                        )
                    }
                }
        }

        viewModelScope.launch {
            val isEmpty = mediaFileDao.getMediaForContainer(containerId).first().isEmpty()
            if (isEmpty) scanContainer(containerId)
        }
    }

    fun scanContainer(containerId: String) {
        val handle = repo.getContainerHandle(containerId) ?: return
        scanJob?.cancel()
        clearThumbnailState()
        scanJob = viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, isEmpty = false) }
            mediaScanner.scanContainer(handle, containerId).collect { progress ->
                _uiState.update {
                    it.copy(
                        isScanning      = !progress.isComplete,
                        scanProgress    = progress.scannedFiles,
                        scanTotal       = progress.totalFound,
                        currentScanPath = progress.currentPath,
                        isEmpty         = progress.isComplete && progress.mediaFiles.isEmpty()
                    )
                }
            }
        }
    }

    /**
     * The view options live in the state rather than in the screen because the Gallery does
     * not own its top bar inside a vault - NavGraph draws that one - and both bars have to be
     * able to open the same sheet.
     */
    /**
     * Called when the Gallery tab comes to the front. A random order that never changes is
     * just an arbitrary order; the point of it is that the same collection greets you
     * differently each time you come back to it (#122). Reshuffles only when the random sort
     * is the one selected, so it costs nothing otherwise.
     */
    fun onGalleryShown() {
        if (_uiState.value.sortBy != SortBy.RANDOM) return
        val seed = System.nanoTime()
        _uiState.update { it.copy(randomSeed = seed) }
        viewModelScope.launch { prefs.setGalleryRandomSeed(seed) }
        viewModelScope.launch(Dispatchers.Default) {
            val groups = visible(_allFiles.value)
            _uiState.update { it.copy(monthGroups = groups) }
        }
    }

    fun setOptionsSheet(open: Boolean) = _uiState.update { it.copy(showOptionsSheet = open) }

    fun setSortBy(sortBy: SortBy) {
        if (sortBy == _uiState.value.sortBy) return
        val seed = if (sortBy == SortBy.RANDOM) System.nanoTime() else _uiState.value.randomSeed
        _uiState.update { it.copy(sortBy = sortBy, randomSeed = seed) }
        if (sortBy == SortBy.RANDOM) viewModelScope.launch { prefs.setGalleryRandomSeed(seed) }
        persistSortAndRebuild()
    }

    fun toggleSortDirection() {
        _uiState.update { it.copy(sortAscending = !it.sortAscending) }
        persistSortAndRebuild()
    }

    private fun persistSortAndRebuild() {
        val s = _uiState.value
        viewModelScope.launch { prefs.setGallerySort(s.sortBy.name, s.sortAscending) }
        viewModelScope.launch(Dispatchers.Default) {
            val groups = visible(_allFiles.value)
            _uiState.update { it.copy(monthGroups = groups) }
        }
    }

    fun setFilter(filter: MediaFilter) {
        _filter.value = filter
        _uiState.update { it.copy(selectedFilter = filter) }
        viewModelScope.launch(Dispatchers.Default) {
            val groups = visible(_allFiles.value, filter = filter)
            _uiState.update { it.copy(monthGroups = groups) }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update {
            it.copy(searchQuery = query, matchingFolders = foldersMatching(it.folders, query))
        }
        viewModelScope.launch(Dispatchers.Default) {
            val groups = visible(_allFiles.value, query = query)
            _uiState.update { it.copy(monthGroups = groups) }
        }
    }

    fun setSearchActive(active: Boolean) {
        _uiState.update { it.copy(isSearchActive = active) }
        if (!active) setSearchQuery("")
    }

    // ── Selection ─────────────────────────────────────────────────────────────

    fun togglePhotoSelection(file: MediaFileEntity) {
        _selectedIds.update { current ->
            if (file.id in current) current - file.id else current + file.id
        }
    }

    fun toggleDaySelection(dayGroup: DayGroup) {
        val dayIds = dayGroup.photos.map { it.id }.toSet()
        _selectedIds.update { current ->
            if (dayIds.all { it in current }) current - dayIds else current + dayIds
        }
    }

    fun toggleMonthSelection(monthGroup: MonthGroup) {
        val monthIds = monthGroup.days.flatMap { it.photos }.map { it.id }.toSet()
        _selectedIds.update { current ->
            if (monthIds.all { it in current }) current - monthIds else current + monthIds
        }
    }

    fun clearSelection() {
        _selectedIds.value = emptySet()
    }

    fun requestDeleteSelected() {
        if (_uiState.value.isReadOnly || _selectedIds.value.isEmpty()) return
        _uiState.update { it.copy(showDeleteConfirm = true) }
    }

    fun dismissDeleteConfirm() {
        _uiState.update { it.copy(showDeleteConfirm = false) }
    }

    fun confirmDeleteSelected() {
        val containerId = currentContainerId ?: return
        val handle = repo.getContainerHandle(containerId) ?: return
        val toDelete = _selectedIds.value.toSet()
        val files = _allFiles.value.filter { it.id in toDelete }
        _uiState.update { it.copy(showDeleteConfirm = false) }
        viewModelScope.launch(Dispatchers.IO) {
            var count = 0
            files.forEach { file ->
                val rc = runCatching { engine.deleteFile(handle, file.relativePath) }.getOrDefault(VeraCryptEngine.ERR_FS)
                if (rc == VeraCryptEngine.ERR_OK) {
                    mediaFileDao.deleteMediaFile(file)
                    thumbnailManager.deleteFileCacheEntry(file.containerId, file.relativePath)
                    count++
                }
            }
            withContext(Dispatchers.Main) {
                toDelete.forEach { evictThumbnail(it) }
                _selectedIds.value = emptySet()
                if (count > 0) notifications.notify(InAppNotification.FilesDeleted(count))
            }
        }
    }

    // ── Thumbnails ────────────────────────────────────────────────────────────

    fun requestThumbnail(file: MediaFileEntity) {
        if (file.id in failedThumbnails) return
        if (!loadingThumbnails.add(file.id)) return
        val containerId = currentContainerId
        val handle = if (containerId != null) repo.getContainerHandle(containerId) else null
        if (containerId == null || handle == null) {
            loadingThumbnails.remove(file.id)
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            thumbnailSemaphore.withPermit {
                val bitmap = thumbnailManager.getThumbnail(engine, handle, file)
                withContext(Dispatchers.Main) {
                    if (bitmap != null) {
                        putThumbnail(file.id, bitmap)
                    } else {
                        loadingThumbnails.remove(file.id)
                        if (!retriedThumbnails.add(file.id)) {
                            failedThumbnails.add(file.id)
                        }
                    }
                }
            }
        }
    }

    private fun putThumbnail(fileId: String, bitmap: Bitmap) {
        val evicted = mutableListOf<String>()
        while (thumbnailMap.size >= MAX_THUMBNAILS) {
            val oldest = thumbnailMap.keys.iterator().next()
            thumbnailMap.remove(oldest)
            loadingThumbnails.remove(oldest)
            evicted.add(oldest)
        }
        thumbnailMap[fileId] = bitmap
        if (evicted.isEmpty()) {
            _thumbnails.value = _thumbnails.value + (fileId to bitmap)
        } else {
            val updated = _thumbnails.value.toMutableMap()
            evicted.forEach { updated.remove(it) }
            updated[fileId] = bitmap
            _thumbnails.value = updated
        }
    }

    private fun clearThumbnailState() {
        loadingThumbnails.clear()
        retriedThumbnails.clear()
        failedThumbnails.clear()
        thumbnailMap.clear()
        _thumbnails.value = emptyMap()
    }


    fun deleteFile(file: MediaFileEntity) {
        val containerId = currentContainerId ?: return
        val handle = repo.getContainerHandle(containerId) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val rc = runCatching { engine.deleteFile(handle, file.relativePath) }.getOrDefault(VeraCryptEngine.ERR_FS)
            if (rc == VeraCryptEngine.ERR_OK) {
                mediaFileDao.deleteMediaFile(file)
                thumbnailManager.deleteFileCacheEntry(file.containerId, file.relativePath)
                withContext(Dispatchers.Main) { evictThumbnail(file.id) }
            }
        }
    }

    fun getHandle(): Long? = currentContainerId?.let { repo.getContainerHandle(it) }

    // ── Grouping ──────────────────────────────────────────────────────────────

    private fun applyFilter(files: List<MediaFileEntity>, filter: MediaFilter) = when (filter) {
        MediaFilter.ALL    -> files.filter { it.fileType != MediaFileType.AUDIO }
        MediaFilter.PHOTOS -> files.filter { it.fileType == MediaFileType.IMAGE }
        MediaFilter.VIDEOS -> files.filter { it.fileType == MediaFileType.VIDEO }
    }

    /**
     * Everything the grid shows, in one place: the media filter, the search box and the sort.
     * The four callers used to each do their own subset, which is how changing the filter
     * quietly dropped an active search query.
     */
    private fun visible(
        files: List<MediaFileEntity>,
        filter: MediaFilter = _filter.value,
        query: String = _uiState.value.searchQuery,
        sortBy: SortBy = _uiState.value.sortBy,
        ascending: Boolean = _uiState.value.sortAscending,
        folders: Set<String> = _folderFilter.value
    ): List<MonthGroup> {
        var result = applyFilter(files, filter)
        /* A folder means that folder, not the tree under it: every folder with media of its
           own is a row of its own in the sheet, so following the tree would show one file
           under two different names. */
        if (folders.isNotEmpty()) result = result.filter { folderOf(it.relativePath) in folders }
        /* A folder's name answers the search box too (#123): a gallery that throws every
           folder together is exactly where "Camera" is the thing you know and the file
           names are the thing you do not. The rows above the grid name the folders that
           matched; this is what puts their contents in it. */
        if (query.isNotBlank()) result = result.filter {
            it.fileName.contains(query, ignoreCase = true) ||
                folderNameOf(it.relativePath).contains(query, ignoreCase = true)
        }

        if (sortBy == SortBy.RANDOM) {
            // Seeded, so the same seed always gives the same arrangement - which is what lets
            // the swipe in the viewer walk the grid's order instead of one of its own. The
            // list is sorted by id first so the shuffle starts from a fixed arrangement:
            // shuffling an already-varying order would vary with it.
            val shuffled = result.sortedBy { it.id }.shuffled(Random(_uiState.value.randomSeed))
            return if (shuffled.isEmpty()) emptyList()
                   else listOf(MonthGroup("", listOf(DayGroup(LocalDate.MIN, "", shuffled))))
        }
        if (sortBy == SortBy.DATE && !ascending) return groupByMonthAndDay(result)

        val comparator: Comparator<MediaFileEntity> = when (sortBy) {
            SortBy.NAME -> compareBy { it.fileName.lowercase() }
            SortBy.SIZE -> compareBy { it.size }
            SortBy.TYPE -> compareBy { it.fileName.substringAfterLast('.', "").lowercase() }
            SortBy.DATE -> compareBy { it.dateCreated }
            SortBy.RANDOM -> compareBy { it.id }      // unreachable, handled above
        }
        val ordered = if (ascending) result.sortedWith(comparator) else result.sortedWith(comparator.reversed())
        if (sortBy == SortBy.DATE) return groupByMonthAndDay(ordered, alreadyOrdered = true)

        // One unnamed group so the grid keeps its existing shape - rows of three, selection,
        // thumbnail loading - while the screen leaves the headers out for a flat order.
        if (ordered.isEmpty()) return emptyList()
        return listOf(
            MonthGroup(
                month = "",
                days  = listOf(DayGroup(date = LocalDate.MIN, displayDate = "", photos = ordered))
            )
        )
    }

    /** The directory part of a media file's path inside the vault; "/" for the root. */
    private fun folderOf(relativePath: String): String =
        relativePath.substringBeforeLast('/', "").ifEmpty { "/" }

    /**
     * What that folder is called on screen - the last segment, or the vault's own name at
     * the root. Searching matches this and not the whole path, so a query does not sweep in
     * every folder that happens to sit under a matching parent: a folder here means that
     * folder, the same rule the filter itself follows.
     */
    private fun folderNameOf(relativePath: String): String =
        folderOf(relativePath).let { if (it == "/") vaultName else it.substringAfterLast('/') }

    /**
     * Every folder holding media, newest first inside each one so the tile shows what was
     * added last. The root of the vault is a folder like any other and takes the vault's own
     * name, because "/" means nothing to anybody.
     */
    private fun foldersOf(files: List<MediaFileEntity>): List<MediaFolder> =
        files.groupBy { folderOf(it.relativePath) }
            .map { (path, inIt) ->
                MediaFolder(
                    path   = path,
                    name   = if (path == "/") vaultName else path.substringAfterLast('/'),
                    count  = inIt.size,
                    covers = inIt.sortedByDescending { it.dateCreated }.take(4)
                )
            }
            .sortedBy { it.name.lowercase() }

    fun setFolderSheet(open: Boolean) = _uiState.update { it.copy(showFolderSheet = open) }

    /** Ticking the last folder off is the same as asking for all of them. */
    fun toggleFolder(path: String) {
        _folderFilter.update { if (path in it) it - path else it + path }
    }

    fun showAllFolders() {
        _folderFilter.value = emptySet()
    }

    /** Folders a search query names. Empty while the box is empty, so the rows only appear
     *  once something has been typed. */
    private fun foldersMatching(folders: List<MediaFolder>, query: String): List<MediaFolder> =
        if (query.isBlank()) emptyList()
        else folders.filter { it.name.contains(query, ignoreCase = true) }

    /**
     * Tapping a folder in the search results: show that folder and nothing else, and leave
     * the search behind rather than layering one narrowing on top of another.
     *
     * The grid is rebuilt here rather than left to the [_folderFilter] collector, because
     * asking for the folder already filtered on does not change that flow's value and would
     * emit nothing - the query would go from the box while the grid still hid what it named.
     */
    fun showOnlyFolder(path: String) {
        _uiState.update {
            it.copy(searchQuery = "", isSearchActive = false, matchingFolders = emptyList())
        }
        _folderFilter.value = setOf(path)
        viewModelScope.launch(Dispatchers.Default) {
            val groups = visible(_allFiles.value, query = "", folders = setOf(path))
            _uiState.update { it.copy(monthGroups = groups) }
        }
    }

    private fun groupByMonthAndDay(files: List<MediaFileEntity>, alreadyOrdered: Boolean = false): List<MonthGroup> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())

        return (if (alreadyOrdered) files else files.sortedByDescending { it.dateCreated })
            .groupBy { file ->
                val zdt = Instant.ofEpochMilli(file.dateCreated).atZone(zone)
                "${zdt.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${zdt.year}"
            }
            .map { (monthLabel, monthFiles) ->
                val days = monthFiles
                    .groupBy { file ->
                        Instant.ofEpochMilli(file.dateCreated).atZone(zone).toLocalDate()
                    }
                    .map { (date, dayFiles) ->
                        val displayDate = when (date) {
                            today     -> "Today"
                            yesterday -> "Yesterday"
                            else      -> date.format(dayFmt)
                        }
                        DayGroup(date = date, displayDate = displayDate, photos = dayFiles)
                    }
                    .sortedByDescending { it.date }
                MonthGroup(month = monthLabel, days = days)
            }
    }

    companion object {
        private const val MAX_THUMBNAILS = 80
    }
}
