package zip.arcanum.arcanum.gallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.gallery.ExifJpegPatcher
import zip.arcanum.arcanum.gallery.ExifReader
import zip.arcanum.arcanum.gallery.MediaExifData
import zip.arcanum.arcanum.gallery.NativeFileInputStream
import zip.arcanum.arcanum.gallery.ThumbnailManager
import zip.arcanum.core.database.dao.MediaFileDao
import zip.arcanum.core.database.entities.MediaFileEntity
import zip.arcanum.core.database.entities.MediaFileType
import zip.arcanum.core.navigation.Screen
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.security.IdleMonitor
import zip.arcanum.crypto.VeraCryptEngine
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
    private val idleMonitor: IdleMonitor
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
        val isLoading: Boolean = true,
        val error: String? = null,
        val showBars: Boolean = true,
        val showInfoSheet: Boolean = false,
        val exportDone: Boolean = false,
        val exifData: MediaExifData? = null,
        val isExifLoading: Boolean = false,
        val pendingNotification: InAppNotification? = null,
        val isReadOnly: Boolean = false
    )

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
        return if (folderScope) {
            val dir = parentDir(file.relativePath)
            all.filter { parentDir(it.relativePath) == dir }
        } else all
    }

    // Loads the bitmap for a single image file. Returns null on error. Must be called on IO dispatcher.
    private fun loadBitmapForFile(file: MediaFileEntity, handle: Long): Bitmap? { return try {
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

            for (idx in order) {
                ensureActive()
                val file = siblings[idx]
                if (file.fileType != MediaFileType.IMAGE) {
                    if (idx == centerIndex) _uiState.update { it.copy(isLoading = false) }
                    continue
                }
                if (_uiState.value.bitmapCache.containsKey(file.id)) {
                    if (idx == centerIndex) _uiState.update { it.copy(isLoading = false) }
                    continue
                }
                val bitmap = loadBitmapForFile(file, handle)
                _uiState.update { state ->
                    state.copy(
                        bitmapCache = if (bitmap != null) state.bitmapCache + (file.id to bitmap)
                                      else state.bitmapCache,
                        isLoading   = if (idx == centerIndex) false else state.isLoading
                    )
                }
            }

            // Evict bitmaps outside the ±1 window
            val keepIds = (lo..hi).map { siblings[it].id }.toSet()
            _uiState.update { state ->
                val evicted = state.bitmapCache.filterKeys { it in keepIds }
                if (evicted.size == state.bitmapCache.size) state
                else state.copy(bitmapCache = evicted)
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
        val cached = _uiState.value.bitmapCache.containsKey(file.id)
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
            _uiState.update { it.copy(pendingNotification = InAppNotification.ReadOnlyError) }
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
                        _uiState.update { it.copy(pendingNotification = InAppNotification.ReadOnlyError) }
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
                              ?: MediaExifData(dateTimeOriginal = newDateMillis),
                pendingNotification = InAppNotification.DateUpdated
            ) }
        }
    }

    fun updateGps(lat: Double, lng: Double) {
        val file = _uiState.value.currentFile ?: return
        if (repo.isContainerReadOnly(file.containerId)) {
            _uiState.update { it.copy(pendingNotification = InAppNotification.ReadOnlyError) }
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
                        _uiState.update { it.copy(pendingNotification = InAppNotification.ReadOnlyError) }
                        return@launch
                    }
                }
            }
            _uiState.update { it.copy(
                exifData = _uiState.value.exifData?.copy(gpsLatitude = lat, gpsLongitude = lng)
                           ?: MediaExifData(gpsLatitude = lat, gpsLongitude = lng),
                pendingNotification = InAppNotification.DateUpdated
            ) }
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
                _uiState.update { it.copy(
                    currentFile = updated,
                    siblings    = siblings,
                    pendingNotification = InAppNotification.FileRenamed(finalName)
                ) }
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
                _uiState.update { it.copy(pendingNotification = InAppNotification.ReadOnlyError) }
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

    fun clearPendingNotification() { _uiState.update { it.copy(pendingNotification = null) } }

    fun getHandle(): Long? {
        val containerId = _uiState.value.currentFile?.containerId ?: return null
        return repo.getContainerHandle(containerId)
    }

    fun getHandleForContainer(id: String): Long? = repo.getContainerHandle(id)

    // Refresh the idle auto-lock baseline. Called periodically by the viewer while a video is
    // actively playing so watching (which produces no touch events) doesn't trip the idle timer.
    fun recordInteraction() = idleMonitor.recordInteraction()
}
