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
import zip.arcanum.crypto.VeraCryptEngine
import java.time.Instant
import java.time.ZoneId
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
    private val engine: VeraCryptEngine
) : ViewModel() {

    enum class MediaFilter { ALL, PHOTOS, VIDEOS }

    data class UiState(
        val isScanning: Boolean = false,
        val scanProgress: Int = 0,
        val scanTotal: Int = 0,
        val currentScanPath: String = "",
        val groupedMedia: Map<String, List<MediaFileEntity>> = emptyMap(),
        val allMedia: List<MediaFileEntity> = emptyList(),
        val selectedFilter: MediaFilter = MediaFilter.ALL,
        val searchQuery: String = "",
        val isSearchActive: Boolean = false,
        val isEmpty: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private val _thumbnails = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val thumbnails = _thumbnails.asStateFlow()

    val preloadState = thumbnailPreloader.state

    // Max 4 concurrent JNI reads; all other requests queue inside the semaphore
    private val thumbnailSemaphore = Semaphore(4)

    // FIFO-bounded in-memory cache — keeps memory predictable (80 × ~250 KB ≈ 20 MB)
    private val thumbnailMap = LinkedHashMap<String, Bitmap>(MAX_THUMBNAILS + 1, 0.75f, false)

    // Guards duplicate requests; entries are removed when the bitmap is evicted from thumbnailMap
    private val loadingThumbnails = mutableSetOf<String>()

    // Files that had at least one failed decode attempt (used to detect the second failure)
    private val retriedThumbnails = mutableSetOf<String>()

    // Files that failed decode twice; never retried until clearThumbnailState() (e.g., rescan)
    private val failedThumbnails = mutableSetOf<String>()

    private var currentContainerId: String? = null
    private val _filter = MutableStateFlow(MediaFilter.ALL)
    private val _allFiles = MutableStateFlow<List<MediaFileEntity>>(emptyList())

    fun loadForContainer(containerId: String) {
        if (currentContainerId == containerId) return
        currentContainerId = containerId
        clearThumbnailState()

        viewModelScope.launch {
            combine(
                mediaFileDao.getMediaForContainer(containerId),
                _filter
            ) { files, filter -> Pair(files, filter) }.collect { (files, filter) ->
                _allFiles.value = files
                val filtered = applyFilter(files, filter)
                val grouped = groupByMonth(filtered)
                _uiState.update {
                    it.copy(
                        allMedia     = files,
                        groupedMedia = grouped,
                        isEmpty      = files.isEmpty() && !it.isScanning
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
        // Reset in-memory state so previously-failed files retry; keep disk cache intact
        // (thumbnails are keyed by containerId+relativePath and remain valid across rescans)
        clearThumbnailState()
        viewModelScope.launch {
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
        val filtered = applyFilter(_allFiles.value, filter)
        _uiState.update { it.copy(groupedMedia = groupByMonth(filtered)) }
    }

    fun setSearchQuery(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
        val files = if (query.isBlank()) _allFiles.value
        else _allFiles.value.filter { it.fileName.contains(query, ignoreCase = true) }
        val filtered = applyFilter(files, _filter.value)
        _uiState.update { it.copy(groupedMedia = groupByMonth(filtered)) }
    }

    fun setSearchActive(active: Boolean) {
        _uiState.update { it.copy(isSearchActive = active) }
        if (!active) setSearchQuery("")
    }

    fun requestThumbnail(file: MediaFileEntity) {
        if (file.id in failedThumbnails) return      // permanently undecodable this session
        if (!loadingThumbnails.add(file.id)) return  // already queued or loaded
        val containerId = currentContainerId
        val handle = if (containerId != null) repo.getContainerHandle(containerId) else null
        if (containerId == null || handle == null) {
            loadingThumbnails.remove(file.id)  // container not ready; allow retry
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            thumbnailSemaphore.withPermit {
                val bitmap = thumbnailManager.getThumbnail(engine, handle, file)
                withContext(Dispatchers.Main) {
                    if (bitmap != null) {
                        putThumbnail(file.id, bitmap)
                    } else {
                        // Allow one retry (transient OOM / memory pressure can cause false negatives).
                        // After two failures the file is permanently blocked until rescan.
                        loadingThumbnails.remove(file.id)
                        if (!retriedThumbnails.add(file.id)) {
                            failedThumbnails.add(file.id)
                        }
                    }
                }
            }
        }
    }

    // Adds bitmap to the bounded map; evicts oldest entry (and unblocks its re-request) when full.
    // Also removes evicted entries from _thumbnails so the Bitmap is actually eligible for GC.
    private fun putThumbnail(fileId: String, bitmap: Bitmap) {
        val evicted = mutableListOf<String>()
        while (thumbnailMap.size >= MAX_THUMBNAILS) {
            val oldest = thumbnailMap.keys.iterator().next()
            thumbnailMap.remove(oldest)
            loadingThumbnails.remove(oldest)  // allow re-request when item scrolls back into view
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

    fun toggleFavorite(file: MediaFileEntity) {
        viewModelScope.launch {
            mediaFileDao.updateMediaFile(file.copy(isFavorite = !file.isFavorite))
        }
    }

    fun deleteFile(file: MediaFileEntity) {
        val containerId = currentContainerId ?: return
        val handle = repo.getContainerHandle(containerId) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val deleted = runCatching {
                engine.nativeDeleteFile(handle, file.relativePath) == VeraCryptEngine.ERR_OK
            }.getOrDefault(false)
            if (deleted) {
                mediaFileDao.deleteMediaFile(file)
                thumbnailManager.clearCache(containerId)
            }
        }
    }

    fun getHandle(): Long? = currentContainerId?.let { repo.getContainerHandle(it) }

    private fun applyFilter(files: List<MediaFileEntity>, filter: MediaFilter) = when (filter) {
        MediaFilter.ALL    -> files.filter { it.fileType != MediaFileType.AUDIO }
        MediaFilter.PHOTOS -> files.filter { it.fileType == MediaFileType.IMAGE }
        MediaFilter.VIDEOS -> files.filter { it.fileType == MediaFileType.VIDEO }
    }

    private fun groupByMonth(files: List<MediaFileEntity>): Map<String, List<MediaFileEntity>> =
        files.sortedByDescending { it.dateCreated }.groupBy { file ->
            val zdt = Instant.ofEpochMilli(file.dateCreated).atZone(ZoneId.systemDefault())
            "${zdt.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${zdt.year}"
        }

    companion object {
        private const val MAX_THUMBNAILS = 80  // 80 × ~250 KB ≈ 20 MB cap
    }
}
