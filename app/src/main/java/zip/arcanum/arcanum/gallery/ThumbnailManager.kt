package zip.arcanum.arcanum.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaDataSource
import android.media.MediaMetadataRetriever
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import zip.arcanum.arcanum.gallery.ExifReader
import zip.arcanum.core.database.entities.MediaFileEntity
import zip.arcanum.core.database.entities.MediaFileType
import zip.arcanum.crypto.VeraCryptEngine
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Singleton
class ThumbnailManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exifReader: ExifReader
) {
    private val cacheRoot get() = File(context.cacheDir, "arcanum_thumbs")

    // Emits fileId whenever a specific file's disk cache is cleared (e.g. after overwrite).
    // GalleryViewModel collects this to evict the stale bitmap from its in-memory map.
    private val _invalidatedIds = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val invalidatedIds: SharedFlow<String> = _invalidatedIds.asSharedFlow()

    // Emits containerId after new media files are indexed (e.g. after import).
    // GalleryViewModel collects this to refresh its media list without waiting for Room Flow debounce.
    private val _importedContainerIds = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val importedContainerIds: SharedFlow<String> = _importedContainerIds.asSharedFlow()

    fun notifyFilesImported(containerId: String) {
        _importedContainerIds.tryEmit(containerId)
    }

    // Emits containerId after files are deleted (e.g. from Files tab).
    // GalleryViewModel collects this to refresh its media list immediately.
    private val _deletedContainerIds = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val deletedContainerIds: SharedFlow<String> = _deletedContainerIds.asSharedFlow()

    fun notifyFilesDeleted(containerId: String) {
        _deletedContainerIds.tryEmit(containerId)
    }

    // AES-256-GCM key from Android Keystore — mirrors PinManager's pattern
    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private fun cacheFile(containerId: String, filePath: String): File {
        val key = md5("$containerId:$filePath:v5")
        return File(cacheRoot, "$containerId/$key.enc")
    }

    suspend fun getThumbnail(
        engine: VeraCryptEngine,
        handle: Long,
        file: MediaFileEntity
    ): Bitmap? = getThumbnail(engine, handle, file.containerId, file.relativePath, file.fileType, file.size)

    suspend fun getThumbnail(
        engine: VeraCryptEngine,
        handle: Long,
        containerId: String,
        relativePath: String,
        type: MediaFileType,
        size: Long
    ): Bitmap? {
        val cached = cacheFile(containerId, relativePath)
        if (cached.exists() && cached.length() > 0L) {
            val bmp = readCacheFile(cached)
            if (bmp != null) return bmp
            cached.delete()  // Corrupted or wrong key — regenerate
        }
        return when (type) {
            MediaFileType.IMAGE -> generateImageThumbnail(engine, handle, relativePath, size, cached)
            MediaFileType.VIDEO -> generateVideoThumbnail(engine, handle, relativePath, size, cached)
            MediaFileType.AUDIO -> generateAudioThumbnail(engine, handle, relativePath, size, cached)
        }
    }

    private fun generateImageThumbnail(
        engine: VeraCryptEngine,
        handle: Long,
        relativePath: String,
        fileSize: Long,
        cacheFile: File
    ): Bitmap? { return try {
        val stream = NativeFileInputStream(engine, handle, relativePath, fileSize)

        // Pass 1: bounds-only scan. BitmapFactory stops as soon as it finds the SOF marker,
        // so this works even when huge APP1 blocks (e.g. Nothing Phone refocus data) push
        // SOF past 5 MB into the file. mark(0) lets us rewind for pass 2.
        stream.mark(0)
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream, null, opts)
        if (opts.outWidth <= 0) return null

        // Pass 2: full decode from the start at reduced resolution.
        stream.reset()
        opts.inSampleSize = calculateInSampleSize(opts, 256, 256)
        opts.inJustDecodeBounds = false
        val bitmap = BitmapFactory.decodeStream(stream, null, opts) ?: return null

        // Read EXIF orientation from the first 64 KB — always within the initial APP1.
        val exifBytes = engine.readFile(handle, relativePath, 0L, 65_536) ?: ByteArray(0)
        val exifOrientation = exifReader.readOrientation(exifBytes)

        // Orientations 5-8 swap width↔height. On Android 12+ with hardware-accelerated
        // JPEG decoding, BitmapFactory may already apply the rotation automatically.
        // Detect this: if raw stored dimensions are landscape but the decoded bitmap is
        // portrait, the decoder already rotated — applying again would double-rotate.
        val oriented = if (exifOrientation in 5..8
                           && opts.outWidth > opts.outHeight
                           && bitmap.height > bitmap.width) {
            bitmap
        } else {
            applyExifOrientation(bitmap, exifOrientation)
        }
        val thumb = centerCrop(oriented, 256)
        saveToCacheFile(thumb, cacheFile)
        thumb
    } catch (_: Throwable) {
        null
    } }

    private fun generateVideoThumbnail(
        engine: VeraCryptEngine,
        handle: Long,
        relativePath: String,
        fileSize: Long,
        cacheFile: File
    ): Bitmap? = try {
        // NativeMediaDataSource lets MediaMetadataRetriever seek anywhere on demand —
        // correctly handles MP4 moov at end-of-file without pre-reading the whole file.
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(NativeMediaDataSource(engine, handle, relativePath, fileSize))
            pickVideoFrame(retriever)?.let {
                val thumb = centerCrop(it, 256)
                saveToCacheFile(thumb, cacheFile)
                thumb
            }
        } finally {
            retriever.release()
        }
    } catch (_: Throwable) {
        null
    }

    /**
     * Picks the most informative frame available, cheapest source first (#111):
     *  1. An embedded cover image, when the muxer stored one — it is the frame the
     *     camera or editor already chose, and it costs no video decoding at all.
     *  2. The first frame, which is what most videos want anyway.
     *  3. Only when the first frame is a near-solid fill (black lead-in, white intro
     *     slate) do we pay for a second seek deeper into the file — and even then we
     *     keep whichever of the two frames carries more detail, so a false positive
     *     costs one seek rather than a worse thumbnail.
     */
    private fun pickVideoFrame(retriever: MediaMetadataRetriever): Bitmap? {
        retriever.embeddedPicture?.let { bytes ->
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return it }
        }

        val first = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: return null
        val firstDetail = frameDetail(first)
        if (firstDetail >= SOLID_FRAME_THRESHOLD) return first

        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: return first
        // 30% in clears long fades and intro slates. The 5 s cap keeps the seek near the
        // start of long videos, where 30% would land minutes deep — a slow read into the
        // middle of the container for a frame no more representative than an early one.
        val seekMs = minOf(durationMs * 30 / 100, 5_000L)
        if (seekMs <= 0L) return first

        val later = retriever.getFrameAtTime(
            seekMs * 1_000L,  // getFrameAtTime takes microseconds
            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
        ) ?: return first
        return if (frameDetail(later) > firstDetail) later else first
    }

    /**
     * Standard deviation of luma over an 8x8 reduction of the frame: a solid fill
     * scores ~0 while any real scene scores well above it. Scaling down first means
     * we inspect 64 averaged pixels instead of millions.
     */
    private fun frameDetail(frame: Bitmap): Double = try {
        val small = Bitmap.createScaledBitmap(frame, 8, 8, true)
        val pixels = IntArray(64)
        small.getPixels(pixels, 0, 8, 0, 0, 8, 8)
        if (small !== frame) small.recycle()
        val luma = pixels.map {
            0.299 * ((it shr 16) and 0xFF) +
            0.587 * ((it shr 8) and 0xFF) +
            0.114 * (it and 0xFF)
        }
        val mean = luma.average()
        sqrt(luma.sumOf { (it - mean) * (it - mean) } / luma.size)
    } catch (_: Throwable) {
        // Unreadable frame (e.g. a HARDWARE-config bitmap) — report it as detailed so
        // the caller keeps the frame it already has instead of losing the thumbnail.
        Double.MAX_VALUE
    }

    private fun generateAudioThumbnail(
        engine: VeraCryptEngine,
        handle: Long,
        relativePath: String,
        fileSize: Long,
        cacheFile: File
    ): Bitmap? = try {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(NativeMediaDataSource(engine, handle, relativePath, fileSize))
            // Returns null if no embedded art → UI will show the music note icon instead
            val artBytes = retriever.embeddedPicture
            artBytes?.let { bytes ->
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                val thumb = centerCrop(bitmap, 256)
                saveToCacheFile(thumb, cacheFile)
                thumb
            }
        } finally {
            retriever.release()
        }
    } catch (_: Throwable) {
        null
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
            else -> return bitmap  // 1 = normal / undefined
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun centerCrop(bitmap: Bitmap, size: Int): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val dim = minOf(w, h)
        val x = (w - dim) / 2
        val y = (h - dim) / 2
        val cropped = if (x == 0 && y == 0) bitmap
                      else Bitmap.createBitmap(bitmap, x, y, dim, dim)
        return if (cropped.width == size) cropped
               else Bitmap.createScaledBitmap(cropped, size, size, true)
    }

    private fun saveToCacheFile(bitmap: Bitmap, file: File) {
        try {
            file.parentFile?.mkdirs()
            val encFile = EncryptedFile.Builder(
                context, file, masterKey,
                EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
            encFile.openFileOutput().use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out))
                    throw IOException("compress returned false")
            }
        } catch (_: Exception) {
            file.delete()  // Prevent a partial/corrupted file from being used on next load
        }
    }

    private fun readCacheFile(file: File): Bitmap? = try {
        val encFile = EncryptedFile.Builder(
            context, file, masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()
        encFile.openFileInput().use { inp ->
            BitmapFactory.decodeStream(inp)
        }
    } catch (_: Exception) {
        null  // Decryption failure (wrong key, corrupt) → caller deletes and regenerates
    }

    private fun calculateInSampleSize(opts: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        var size = 1
        if (opts.outHeight > reqH || opts.outWidth > reqW) {
            val halfH = opts.outHeight / 2
            val halfW = opts.outWidth / 2
            while (halfH / size >= reqH && halfW / size >= reqW) size *= 2
        }
        return size
    }

    fun filterUncached(containerId: String, files: List<MediaFileEntity>): List<MediaFileEntity> =
        files.filter { file ->
            val f = cacheFile(containerId, file.relativePath)
            !f.exists() || f.length() == 0L
        }

    fun clearFileCache(containerId: String, filePath: String, fileId: String) {
        cacheFile(containerId, filePath).delete()
        _invalidatedIds.tryEmit(fileId)
    }

    // Moves a file's disk-cached thumbnail to follow a rename. The cache file is keyed
    // by the file's path, so without this the renamed file has no thumbnail and the old
    // one is orphaned. Moving keeps the thumbnail the gallery already has rather than
    // regenerating it; if the move cannot be done the stale entry is dropped so it is
    // regenerated on next request. The emit re-reads the in-memory copy under the new path.
    fun renameFileCache(containerId: String, oldPath: String, newPath: String, fileId: String) {
        val old = cacheFile(containerId, oldPath)
        val new = cacheFile(containerId, newPath)
        if (old.exists()) {
            new.parentFile?.mkdirs()
            new.delete()
            if (!old.renameTo(new)) old.delete()
        }
        _invalidatedIds.tryEmit(fileId)
    }

    fun deleteFileCacheEntry(containerId: String, filePath: String) {
        cacheFile(containerId, filePath).delete()
    }

    fun clearCache(containerId: String) {
        File(cacheRoot, containerId).deleteRecursively()
    }

    fun clearAllCache() {
        cacheRoot.deleteRecursively()
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        // Luma stddev (0-255 scale) below which a frame reads as a solid fill. Set low
        // on purpose: a dim-but-real scene misjudged here only costs an extra seek,
        // since pickVideoFrame keeps the more detailed of the two frames either way.
        const val SOLID_FRAME_THRESHOLD = 6.0
    }
}

/**
 * Seekable MediaDataSource backed by the native container JNI bridge.
 * MediaMetadataRetriever calls readAt() with arbitrary offsets, so it can locate
 * the moov atom at the end of an MP4 or embedded art anywhere in an audio file
 * without us having to pre-buffer the whole file.
 */
internal class NativeMediaDataSource(
    private val engine: VeraCryptEngine,
    private val handle: Long,
    private val filePath: String,
    private val fileSize: Long
) : MediaDataSource() {

    override fun readAt(position: Long, buffer: ByteArray?, offset: Int, size: Int): Int {
        if (buffer == null || position < 0 || position >= fileSize) return -1
        val toRead = minOf(size.toLong(), fileSize - position).toInt()
        val chunk = engine.readFile(handle, filePath, position, toRead) ?: return -1
        chunk.copyInto(buffer, offset, 0, chunk.size)
        return chunk.size
    }

    override fun getSize(): Long = fileSize

    override fun close() {}
}

/**
 * Sequential InputStream backed by the native container JNI bridge.
 * BitmapFactory reads the compressed image data chunk-by-chunk without
 * pre-buffering the whole file, so arbitrarily large images (50 MP+) decode
 * at a sampled resolution without OOM.
 */
internal class NativeFileInputStream(
    private val engine: VeraCryptEngine,
    private val handle: Long,
    private val filePath: String,
    private val fileSize: Long,
    private val chunkSize: Int = 256 * 1024
) : InputStream() {

    private var position: Long = 0   // file offset of the next byte to fetch from JNI
    private var bufStart: Long = 0   // file offset of buf[0]
    private var buf: ByteArray = ByteArray(0)
    private var bufPos: Int = 0
    private var markedPosition: Long = 0

    override fun read(): Int {
        if (bufPos >= buf.size && !fillBuffer()) return -1
        return buf[bufPos++].toInt() and 0xFF
    }

    override fun read(dest: ByteArray, off: Int, len: Int): Int {
        if (bufPos >= buf.size && !fillBuffer()) return -1
        val toRead = minOf(len, buf.size - bufPos)
        System.arraycopy(buf, bufPos, dest, off, toRead)
        bufPos += toRead
        return toRead
    }

    // BitmapFactory calls mark/reset to detect the image format before decoding.
    // We support it by tracking the marked file offset and re-seeking via JNI on reset().
    override fun markSupported() = true

    override fun mark(readlimit: Int) {
        markedPosition = bufStart + bufPos
    }

    override fun reset() {
        val target = markedPosition
        if (target >= bufStart && target < bufStart + buf.size) {
            bufPos = (target - bufStart).toInt()
        } else {
            position = target
            bufStart = target
            buf = ByteArray(0)
            bufPos = 0
        }
    }

    private fun fillBuffer(): Boolean {
        if (position >= fileSize) return false
        val toRead = minOf(chunkSize.toLong(), fileSize - position).toInt()
        val chunk = engine.readFile(handle, filePath, position, toRead) ?: return false
        bufStart = position
        buf = chunk
        bufPos = 0
        position += chunk.size
        return chunk.isNotEmpty()
    }

    override fun close() {}
}
