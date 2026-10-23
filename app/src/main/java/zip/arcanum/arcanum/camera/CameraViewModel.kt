package zip.arcanum.arcanum.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.gallery.ExifReader
import zip.arcanum.arcanum.gallery.MediaScanner
import zip.arcanum.arcanum.gallery.ThumbnailManager
import zip.arcanum.core.navigation.Screen
import zip.arcanum.crypto.VeraCryptEngine
import java.util.Date
import javax.inject.Inject

/**
 * Puts the photos taken on the camera screen into the vault (#145).
 *
 * A photo arrives as the JPEG bytes the camera made, in memory, and goes from there into the
 * folder the camera was opened from. It is never a file outside the vault, not even for a
 * moment: the camera hands it to this app and to no other. On the way it loses everything
 * the camera wrote about the phone - see [PhotoMetadata].
 */
@HiltViewModel
class CameraViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val engine: VeraCryptEngine,
    private val repo: ContainerRepository,
    private val scanner: MediaScanner,
    private val thumbnails: ThumbnailManager,
    private val exifReader: ExifReader
) : ViewModel() {

    data class State(
        /** The last photo kept, small, the right way up. */
        val lastShot: Bitmap? = null,
        /** How many photos this visit has put in the folder. */
        val saved: Int = 0,
        /** The last photo could not be written. */
        val saveFailed: Boolean = false,
        /** The vault closed while the camera was open. */
        val vaultClosed: Boolean = false
    )

    val containerId: String = savedStateHandle[Screen.Camera.ARG_CONTAINER] ?: ""
    private val dir: String = "/" + (savedStateHandle.get<String>(Screen.Camera.ARG_DIR) ?: "").trim('/')

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    /**
     * Writes outlive the screen: leaving the camera straight after the shutter must not cut a
     * photo short. One at a time, because two photos in the same second would otherwise race
     * for the same name.
     */
    private val saver = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveLock = Mutex()

    fun save(jpeg: ByteArray) {
        saver.launch {
            saveLock.withLock {
                val h = repo.getContainerHandle(containerId)
                if (h == null) {
                    _state.update { it.copy(vaultClosed = true) }
                    return@withLock
                }
                val ok = runCatching { write(h, jpeg) }.getOrDefault(false)
                _state.update {
                    if (ok) it.copy(lastShot = preview(jpeg) ?: it.lastShot, saved = it.saved + 1, saveFailed = false)
                    else it.copy(saveFailed = true)
                }
            }
        }
    }

    fun captureFailed() = _state.update { it.copy(saveFailed = true) }

    fun dismissSaveFailed() = _state.update { it.copy(saveFailed = false) }

    private suspend fun write(h: Long, shot: ByteArray): Boolean {
        val takenAt = Date()
        // The camera's EXIF goes and a bare one takes its place; a photo that cannot be
        // cleaned is not kept rather than kept with the phone's make and model in it.
        val jpeg = PhotoMetadata.keepOnly(shot, exifReader.readOrientation(shot), takenAt) ?: return false
        val taken = engine.listFilesOrNull(h, dir)?.map { it.name }?.toSet() ?: return false
        val name = photoName(takenAt, taken)
        val path = if (dir == "/") "/$name" else "$dir/$name"
        var at = 0
        while (at < jpeg.size) {
            // Whole blocks every time but the last: the ext4 driver appends only on a block boundary.
            val end = minOf(at + CHUNK, jpeg.size)
            if (engine.writeFile(h, path, jpeg.copyOfRange(at, end), at.toLong()) != VeraCryptEngine.ERR_OK) {
                runCatching { engine.deleteFile(h, path) }
                return false
            }
            at = end
        }
        // Into the gallery straight away, like an imported photo.
        scanner.indexFile(h, containerId, path, jpeg.size.toLong())
        thumbnails.notifyFilesImported(containerId)
        return true
    }

    /** The photo small enough for the corner of the screen, turned the way its EXIF says. */
    private fun preview(jpeg: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PREVIEW_SIDE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val matrix = Matrix()
        when (exifReader.readOrientation(jpeg)) {
            2 -> matrix.postScale(-1f, 1f)
            3 -> matrix.postRotate(180f)
            4 -> matrix.postScale(1f, -1f)
            5 -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.postRotate(90f)
            7 -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            8 -> matrix.postRotate(270f)
            else -> return bmp
        }
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }.getOrNull()

    private companion object {
        const val CHUNK = 512 * 1024
        const val PREVIEW_SIDE = 256
    }
}
