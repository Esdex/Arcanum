package zip.arcanum.arcanum.gallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zip.arcanum.core.security.AppPreferences
import zip.arcanum.crypto.NativeFileInfo
import zip.arcanum.arcanum.files.data.FileBrowserPrefs
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.gallery.AnimatedImages
import zip.arcanum.arcanum.gallery.ExifJpegPatcher
import zip.arcanum.arcanum.gallery.ExifReader
import zip.arcanum.arcanum.gallery.MediaExifData
import zip.arcanum.arcanum.gallery.NativeFileInputStream
import zip.arcanum.arcanum.gallery.StillDecoder
import zip.arcanum.arcanum.gallery.readWholeFile
import zip.arcanum.arcanum.gallery.ThumbnailManager
import zip.arcanum.core.database.dao.MediaFileDao
import zip.arcanum.core.database.entities.MediaFileEntity
import zip.arcanum.core.database.entities.MediaFileType
import zip.arcanum.core.navigation.Screen
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.notifications.NotificationCenter
import zip.arcanum.core.security.IdleMonitor
import zip.arcanum.crypto.VeraCryptEngine
import kotlin.random.Random
import javax.inject.Inject

@HiltViewModel
class PhotoViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val mediaFileDao: MediaFileDao,
    private val repo: ContainerRepository,
    val engine: VeraCryptEngine,
    private val exifReader: ExifReader,
    private val thumbnailManager: ThumbnailManager,
    private val idleMonitor: IdleMonitor,
    private val prefs: AppPreferences,
    private val notifications: NotificationCenter
) : ViewModel() {

    private val fileId: String = savedStateHandle[Screen.PhotoViewer.ARG] ?: ""

    // When true, the swipe siblings are limited to the opened file's folder (entry from the Files
    // browser); when false, they span all visual media in the vault (the Gallery timeline).
    private val folderScope: Boolean = savedStateHandle[Screen.PhotoViewer.ARG_FOLDER_SCOPE] ?: false

    data class UiState(
        val currentFile: MediaFileEntity? = null,
        val siblings: List<MediaFileEntity> = emptyList(),
        val currentIndex: Int = 0,
        val bitmapCache: Map<String, Bitmap> = emptyMap(),  // fileId -> Bitmap
        val animatedCache: Map<String, Drawable> = emptyMap(),  // fileId -> a GIF or animated WebP
        val isLoading: Boolean = true,
        val error: String? = null,
        val showBars: Boolean = true,
        val showInfoSheet: Boolean = false,
        val exportDone: Boolean = false,
        val exifData: MediaExifData? = null,
        val isExifLoading: Boolean = false,
        val isReadOnly: Boolean = false
    )

    /** Whether what is playing may be named outside the app - see AppPreferences. */
    val mediaSessionContent = prefs.mediaSessionContent
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

    private val _uiState = MutableStateFlow(UiState())
    val uiState = _uiState.asStateFlow()

    private var loadingJob: Job? = null

    init {
        viewModelScope.launch {
            val file = mediaFileDao.getMediaById(fileId) ?: run {
                _uiState.update { it.copy(isLoading = false, error = "File not found") }
                return@launch
            }
            val siblings = loadSiblings(file)
            val idx = siblings.indexOfFirst { it.id == fileId }.coerceAtLeast(0)
            _uiState.update {
                it.copy(
                    currentFile = file,
                    siblings    = siblings,
                    currentIndex = idx,
                    isReadOnly  = repo.isContainerReadOnly(file.containerId)
                )
            }
            loadBitmapRange(idx)
        }

    }

    // Parent directory of a container-relative path ("/a/b/c.jpg" -> "/a/b", "/c.jpg" -> "").
    private fun parentDir(path: String) = path.substringBeforeLast('/', "")

    // All visual media in the container, narrowed to [file]'s folder when folderScope is on.
    private suspend fun loadSiblings(file: MediaFileEntity): List<MediaFileEntity> {
        val all = mediaFileDao.getVisualMediaOnce(file.containerId)
        // Opened from the Gallery: follow the Gallery's order, which is its own setting.
        if (!folderScope) return orderLikeGallery(all)
        val dir = parentDir(file.relativePath)
        return orderLikeBrowser(file.containerId, dir, all.filter { parentDir(it.relativePath) == dir })
    }

    /**
     * Puts the vault's media in the order the Gallery is showing it. Its sort is a separate
     * setting from the browser's, so a swipe follows whichever list it was opened from
     * (#151). Dates come from the index here, not from the vault listing: the Gallery is a
     * timeline of when photos were taken, which is exactly what the index stores.
     */
    private suspend fun orderLikeGallery(media: List<MediaFileEntity>): List<MediaFileEntity> {
        if (media.size < 2) return media
        val by  = runCatching { prefs.gallerySortBy.first() }.getOrNull() ?: return media
        val asc = runCatching { prefs.gallerySortAscending.first() }.getOrDefault(false)
        if (by == "RANDOM") {
            // The same seed the grid used, so the swipe walks the arrangement on screen
            // rather than a second, unrelated shuffle (#122). Sorted by id first for the same
            // reason it is there: the shuffle has to start from a fixed order.
            val seed = runCatching { prefs.galleryRandomSeed.first() }.getOrDefault(1L)
            return media.sortedBy { it.id }.shuffled(Random(seed))
        }
        val comparator: Comparator<MediaFileEntity> = when (by) {
            "NAME" -> compareBy { it.fileName.lowercase() }
            "SIZE" -> compareBy { it.size }
            "TYPE" -> compareBy { it.fileName.substringAfterLast('.', "").lowercase() }
            else   -> compareBy { it.dateCreated }       // DATE, and anything unrecognised
        }
        return if (asc) media.sortedWith(comparator) else media.sortedWith(comparator.reversed())
    }

    /**
     * Puts a folder's media in the order the Files browser is showing it, so a swipe goes
     * where the list said it would. It used to follow the media index instead, which is
     * ordered by date taken and knew nothing of the sort the user had picked (#151).
     *
     * The order is computed from the vault's own listing rather than from the index,
     * because the two hold different dates: the index stores the date a photo was taken
     * when EXIF carries one, while the browser sorts on the file's modification time.
     * Sorting the same values the browser sorts is the only way the two can agree.
     *
     * Falls back to the index order whenever anything is missing - an unmounted vault, a
     * listing that fails - because a swipe in some order beats a viewer that opens empty.
     */
    private suspend fun orderLikeBrowser(
        containerId: String,
        dir: String,
        media: List<MediaFileEntity>
    ): List<MediaFileEntity> {
        if (media.size < 2) return media
        val handle  = repo.getContainerHandle(containerId) ?: return media
        val listing = runCatching { engine.listFilesOrNull(handle, dir.ifEmpty { "/" }) }
            .getOrNull() ?: return media
        val sort    = runCatching { FileBrowserPrefs.sortOnce(context) }.getOrNull() ?: return media

        // Mirrors FileManagerViewModel.applyFiltersAndSort; foldersFirst does not apply
        // here because a folder is never one of the swipe siblings.
        val comparator: Comparator<NativeFileInfo> = when (sort.by) {
            "NAME" -> compareBy { it.name.lowercase() }
            "SIZE" -> compareBy { it.size }
            "TYPE" -> compareBy { it.name.substringAfterLast('.', "").lowercase() }
            else   -> compareBy { it.lastModified }      // DATE, and anything unrecognised
        }
        val ordered = if (sort.ascending) listing.sortedWith(comparator)
                      else listing.sortedWith(comparator.reversed())

        val byName = media.associateBy { it.fileName }
        val result = ordered.mapNotNull { byName[it.name] }
        // Whatever the listing did not mention keeps its place at the end rather than
        // dropping out of the swipe entirely.
        val seen = result.mapTo(HashSet()) { it.id }
        return result + media.filter { it.id !in seen }
    }

    /** What a decode produced: one frame, or something that plays. */
    private sealed interface Decoded {
        data class Still(val bitmap: Bitmap) : Decoded
        data class Animated(val drawable: Drawable) : Decoded
    }

    /**
     * Reads one image out of the vault. With [allowAnimated], a GIF or an animated WebP
     * goes through ImageDecoder so that it plays (#159); everything else, and anything that
     * decoder refuses, goes through the BitmapFactory path below.
     *
     * Must be called on the IO dispatcher.
     */
    private fun loadImageForFile(file: MediaFileEntity, handle: Long, allowAnimated: Boolean): Decoded? {
        if (allowAnimated && AnimatedImages.mayAnimate(file.fileName, file.size)) {
            val head = engine.readFile(handle, file.relativePath, 0L, AnimatedImages.HEADER_BYTES)
            if (AnimatedImages.headerAnimates(file.fileName, head)) {
                val bytes = engine.readWholeFile(
                    handle, file.relativePath, file.size, AnimatedImages.MAX_BYTES
                )
                when (val drawable = bytes?.let { AnimatedImages.decode(it) }) {
                    is AnimatedImageDrawable -> return Decoded.Animated(drawable)
                    // A GIF with a single frame: keep the frame this decode already
                    // produced instead of reading and decoding the file a second time.
                    is BitmapDrawable        -> return Decoded.Still(drawable.bitmap)
                    else                     -> Unit
                }
            }
        }
        return loadBitmapForFile(file, handle)?.let { Decoded.Still(it) }
    }

    // Loads the bitmap for a single image file. Returns null on error. Must be called on IO dispatcher.
    private fun loadBitmapForFile(file: MediaFileEntity, handle: Long): Bitmap? { return try {
        /* HEIF will not decode from a stream, so it is read whole and left to ImageDecoder,
           which turns it the right way up itself - hence no orientation step here. */
        if (StillDecoder.needsWholeFile(file.fileName)) {
            val whole = engine.readWholeFile(handle, file.relativePath, file.size, StillDecoder.MAX_BYTES)
            return whole?.let { StillDecoder.decode(it, 4096) }
        }
        val stream = NativeFileInputStream(engine, handle, file.relativePath, file.size)
        stream.mark(0)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream, null, opts)
        if (opts.outWidth <= 0) return null
        stream.reset()
        opts.inSampleSize      = calculateInSampleSize(opts, 4096, 4096)
        opts.inJustDecodeBounds = false
        opts.inPreferredConfig  = Bitmap.Config.ARGB_8888
        val decoded = BitmapFactory.decodeStream(stream, null, opts) ?: return null
        val exifBytes = engine.readFile(handle, file.relativePath, 0L, 65_536) ?: ByteArray(0)
        applyExifOrientation(decoded, exifReader.readOrientation(exifBytes))
    } catch (_: Throwable) {
        null
    } }

    // Loads current page first, then ±1 neighbors. Cancels any prior load.
    private fun loadBitmapRange(centerIndex: Int) {
        val siblings = _uiState.value.siblings
        if (siblings.isEmpty() || centerIndex !in siblings.indices) return
        val centerFile = siblings[centerIndex]
        val handle = repo.getContainerHandle(centerFile.containerId) ?: run {
            _uiState.update { it.copy(isLoading = false, error = "Vault not mounted") }
            return
        }

        loadingJob?.cancel()
        loadingJob = viewModelScope.launch(Dispatchers.IO) {
            // Priority order: current first, then neighbors
            val lo = (centerIndex - 1).coerceAtLeast(0)
            val hi = (centerIndex + 1).coerceAtMost(siblings.lastIndex)
            val order = listOf(centerIndex) + (lo until centerIndex).toList() + ((centerIndex + 1)..hi).toList()
            // The one file allowed to hold a playing drawable.
            val keepAnimated = centerFile.id

            for (idx in order) {
                ensureActive()
                val file = siblings[idx]
                if (file.fileType != MediaFileType.IMAGE) {
                    if (idx == centerIndex) _uiState.update { it.copy(isLoading = false) }
                    continue
                }
                /* Only the page on screen is decoded as an animation. A neighbour would
                   not play anyway - it is stopped until it is swiped to - and the encoded
                   file stays in memory for as long as its drawable does, so preloading the
                   neighbours would hold three files near the cap at once. Measured on a
                   30 MB GIF: 69 MB of Java heap allocated while it was on screen. */
                val wantsAnimated = idx == centerIndex &&
                                    AnimatedImages.mayAnimate(file.fileName, file.size)
                // A drawable the eviction below is about to drop does not count as loaded:
                // its still frame is decoded here, before it goes, so the page never blinks.
                val haveAnimated  = file.id == keepAnimated &&
                                    _uiState.value.animatedCache.containsKey(file.id)
                val haveStill     = _uiState.value.bitmapCache.containsKey(file.id)
                if (haveAnimated || (haveStill && !wantsAnimated)) {
                    if (idx == centerIndex) _uiState.update { it.copy(isLoading = false) }
                    continue
                }
                val decoded = loadImageForFile(file, handle, wantsAnimated)
                _uiState.update { state ->
                    state.copy(
                        bitmapCache   = if (decoded is Decoded.Still)
                                            state.bitmapCache + (file.id to decoded.bitmap)
                                        else state.bitmapCache,
                        animatedCache = if (decoded is Decoded.Animated)
                                            state.animatedCache + (file.id to decoded.drawable)
                                        else state.animatedCache,
                        isLoading     = if (idx == centerIndex) false else state.isLoading
                    )
                }
            }

            // Evict what is outside the ±1 window
            val keepIds = (lo..hi).map { siblings[it].id }.toSet()
            // A dropped drawable is stopped first: while it runs it keeps posting its next
            // frame to the main looper, and nothing is left to draw it.
            _uiState.value.animatedCache
                .filterKeys { it != keepAnimated }
                .forEach { (_, drawable) -> AnimatedImages.stop(drawable) }
            _uiState.update { state ->
                val bitmaps  = state.bitmapCache.filterKeys { it in keepIds }
                val animated = state.animatedCache.filterKeys { it == keepAnimated }
                if (bitmaps.size == state.bitmapCache.size && animated.size == state.animatedCache.size) state
                else state.copy(bitmapCache = bitmaps, animatedCache = animated)
            }
        }
    }

    private fun calculateInSampleSize(opts: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        var size = 1
        while (opts.outWidth / size > reqW || opts.outHeight / size > reqH) size *= 2
        return size
    }

    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.postScale(-1f, 1f)
            3 -> matrix.postRotate(180f)
            4 -> matrix.postScale(1f, -1f)
            5 -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.postRotate(90f)
            7 -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            8 -> matrix.postRotate(270f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    fun navigateTo(index: Int) {
        val siblings = _uiState.value.siblings
        if (index < 0 || index >= siblings.size) return
        val file = siblings[index]
        val cached = _uiState.value.bitmapCache.containsKey(file.id) ||
                     _uiState.value.animatedCache.containsKey(file.id)
        val needsLoad = file.fileType == MediaFileType.IMAGE && !cached
        _uiState.update { it.copy(currentFile = file, currentIndex = index, isLoading = needsLoad, exifData = null) }
        loadBitmapRange(index)
    }

    fun toggleBars() { _uiState.update { it.copy(showBars = !it.showBars) } }
    fun toggleInfoSheet() { _uiState.update { it.copy(showInfoSheet = !it.showInfoSheet) } }

    fun loadExif() {
        val file = _uiState.value.currentFile ?: return
        if (file.fileType != MediaFileType.IMAGE) return
        val handle = repo.getContainerHandle(file.containerId) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isExifLoading = true) }
            val readSize = minOf(file.size, 512L * 1024).toInt()
            val bytes = engine.readFile(handle, file.relativePath, 0L, readSize)
            val exif = if (bytes != null) exifReader.readExif(bytes) else MediaExifData()
            _uiState.update { it.copy(exifData = exif, isExifLoading = false) }
        }
    }

    fun updateDescription(text: String) {
        val file = _uiState.value.currentFile ?: return
        viewModelScope.launch {
            val updated = file.copy(description = text)
            mediaFileDao.updateMediaFile(updated)
            _uiState.update { it.copy(currentFile = updated) }
        }
    }

    fun updateDateTime(newDateMillis: Long) {
        val file = _uiState.value.currentFile ?: return
        if (repo.isContainerReadOnly(file.containerId)) {
            notifications.notify(InAppNotification.ReadOnlyError)
            return
        }
        val handle = repo.getContainerHandle(file.containerId) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val headSize = minOf(file.size, 128L * 1024).toInt()
            val head = engine.readFile(handle, file.relativePath, 0L, headSize)
            if (head != null) {
                val patch = ExifJpegPatcher.patchDateTime(head, newDateMillis)
                if (patch != null) {
                    val (app1Offset, modifiedApp1) = patch
                    val rc = engine.writeFile(handle, file.relativePath, modifiedApp1, app1Offset.toLong())
                    if (rc != 0) {
                        notifications.notify(InAppNotification.ReadOnlyError)
                        return@launch
                    }
                }
            }
            val updated = file.copy(dateCreated = newDateMillis, dateModified = newDateMillis)
            mediaFileDao.updateMediaFile(updated)
            val siblings = loadSiblings(updated)
            val idx = siblings.indexOfFirst { it.id == file.id }.coerceAtLeast(0)
            _uiState.update { it.copy(
                currentFile = updated,
                siblings    = siblings,
                currentIndex = idx,
                exifData    = _uiState.value.exifData?.copy(dateTimeOriginal = newDateMillis)
                              ?: MediaExifData(dateTimeOriginal = newDateMillis)
            ) }
            notifications.notify(InAppNotification.DateUpdated)
        }
    }

    fun updateGps(lat: Double, lng: Double) {
        val file = _uiState.value.currentFile ?: return
        if (repo.isContainerReadOnly(file.containerId)) {
            notifications.notify(InAppNotification.ReadOnlyError)
            return
        }
        val handle = repo.getContainerHandle(file.containerId) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val headSize = minOf(file.size, 128L * 1024).toInt()
            val head = engine.readFile(handle, file.relativePath, 0L, headSize)
            if (head != null) {
                val patch = ExifJpegPatcher.patchGps(head, lat, lng)
                if (patch != null) {
                    val (app1Offset, modifiedApp1) = patch
                    val rc = engine.writeFile(handle, file.relativePath, modifiedApp1, app1Offset.toLong())
                    if (rc != 0) {
                        notifications.notify(InAppNotification.ReadOnlyError)
                        return@launch
                    }
                }
            }
            _uiState.update { it.copy(
                exifData = _uiState.value.exifData?.copy(gpsLatitude = lat, gpsLongitude = lng)
                           ?: MediaExifData(gpsLatitude = lat, gpsLongitude = lng)
            ) }
            notifications.notify(InAppNotification.DateUpdated)
        }
    }

    fun renameFile(newName: String, onResult: (Boolean) -> Unit) {
        val file = _uiState.value.currentFile ?: return
        val handle = repo.getContainerHandle(file.containerId) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val dir = file.relativePath.substringBeforeLast("/", "")
            val ext = file.fileName.substringAfterLast(".", "")
            val finalName = when {
                newName.contains('.') -> newName
                ext.isNotEmpty()      -> "$newName.$ext"
                else                  -> newName
            }
            val newPath = if (dir.isEmpty()) "/$finalName" else "$dir/$finalName"
            val result = try {
                engine.renameFile(handle, file.relativePath, newPath)
            } catch (_: Throwable) { VeraCryptEngine.ERR_FS }
            val success = result == VeraCryptEngine.ERR_OK
            if (success) {
                val updated = file.copy(fileName = finalName, relativePath = newPath)
                mediaFileDao.updateMediaFile(updated)
                val siblings = _uiState.value.siblings.map { if (it.id == file.id) updated else it }
                _uiState.update { it.copy(currentFile = updated, siblings = siblings) }
                notifications.notify(InAppNotification.FileRenamed(finalName))
            }
            withContext(Dispatchers.Main) { onResult(success) }
        }
    }

    fun deleteCurrentFile(onDone: () -> Unit) {
        val file = _uiState.value.currentFile ?: return
        val handle = repo.getContainerHandle(file.containerId)
        viewModelScope.launch(Dispatchers.IO) {
            val rc = if (handle != null) {
                try { engine.deleteFile(handle, file.relativePath) } catch (_: Exception) { VeraCryptEngine.ERR_FS }
            } else VeraCryptEngine.ERR_OK
            if (rc == VeraCryptEngine.ERR_OK) {
                mediaFileDao.deleteMediaFile(file)
                thumbnailManager.clearFileCache(file.containerId, file.relativePath, file.id)
                launch(Dispatchers.Main) { onDone() }
            } else {
                notifications.notify(InAppNotification.ReadOnlyError)
            }
        }
    }

    fun exportToUri(uri: android.net.Uri) {
        val file = _uiState.value.currentFile ?: return
        val handle = repo.getContainerHandle(file.containerId) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                var offset = 0L
                val chunkSize = 1024 * 1024
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    while (true) {
                        val chunk = engine.readFile(handle, file.relativePath, offset, chunkSize)
                            ?: break
                        out.write(chunk)
                        offset += chunk.size
                        if (chunk.size < chunkSize) break
                    }
                }
                _uiState.update { it.copy(exportDone = true) }
            } catch (_: Exception) {}
        }
    }

    fun clearExportDone() { _uiState.update { it.copy(exportDone = false) } }


    fun getHandle(): Long? {
        val containerId = _uiState.value.currentFile?.containerId ?: return null
        return repo.getContainerHandle(containerId)
    }

    fun getHandleForContainer(id: String): Long? = repo.getContainerHandle(id)

    override fun onCleared() {
        _uiState.value.animatedCache.values.forEach { AnimatedImages.stop(it) }
        super.onCleared()
    }

    // Refresh the idle auto-lock baseline. Called periodically by the viewer while a video is
    // actively playing so watching (which produces no touch events) doesn't trip the idle timer.
    fun recordInteraction() = idleMonitor.recordInteraction()
}
