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
import zip.arcanum.core.security.AppPreferences
import zip.arcanum.crypto.VeraCryptEngine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
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
    private val prefs: AppPreferences
) : ViewModel() {

    enum class MediaFilter { ALL, PHOTOS, VIDEOS }

    data class DayGroup(
        val date: LocalDate,
        val displayDate: String,
        val photos: List<MediaFileEntity>
    )

    data class MonthGroup(
        val month: String,
        val days: List<DayGroup>
    )

    data class UiState(
        val isScanning: Boolean = false,
        val scanProgress: Int = 0,
        val scanTotal: Int = 0,
        val currentScanPath: String = "",
        val monthGroups: List<MonthGroup> = emptyList(),
        val allMedia: List<MediaFileEntity> = emptyList(),
        val selectedFilter: MediaFilter = MediaFilter.ALL,
        val searchQuery: String = "",
        val isSearchActive: Boolean = false,
        val isEmpty: Boolean = false,
        val pendingNotification: InAppNotification? = null,
        val showDeleteConfirm: Boolean = false,
        val isReadOnly: Boolean = false
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
    private val _allFiles = MutableStateFlow<List<MediaFileEntity>>(emptyList())

    init {
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
        val filtered = applyFilter(files, _filter.value)
        val groups = groupByMonthAndDay(filtered)
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

        viewModelScope.launch(Dispatchers.Default) {
            combine(
                mediaFileDao.getMediaForContainer(containerId),
                _filter
            ) { files, filter -> Pair(files, filter) }.collect { (files, filter) ->
                _allFiles.value = files
                val filtered = applyFilter(files, filter)
                val groups = groupByMonthAndDay(filtered)
                _uiState.update {
                    it.copy(
                        allMedia    = files,
                        monthGroups = groups,
                        isEmpty     = files.isEmpty() && !it.isScanning
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

    fun setFilter(filter: MediaFilter) {
        _filter.value = filter
        _uiState.update { it.copy(selectedFilter = filter) }
        viewModelScope.launch(Dispatchers.Default) {
            val filtered = applyFilter(_allFiles.value, filter)
            val groups = groupByMonthAndDay(filtered)
            _uiState.update { it.copy(monthGroups = groups) }
        }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        viewModelScope.launch(Dispatchers.Default) {
            val files = if (query.isBlank()) _allFiles.value
                        else _allFiles.value.filter { it.fileName.contains(query, ignoreCase = true) }
            val filtered = applyFilter(files, _filter.value)
            val groups = groupByMonthAndDay(filtered)
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
                _uiState.update { it.copy(pendingNotification = if (count > 0) InAppNotification.FilesDeleted(count) else null) }
            }
        }
    }

    fun clearNotification() {
        _uiState.update { it.copy(pendingNotification = null) }
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

    private fun groupByMonthAndDay(files: List<MediaFileEntity>): List<MonthGroup> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val yesterday = today.minusDays(1)
        val dayFmt = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())

        return files
            .sortedByDescending { it.dateCreated }
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
