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
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThumbnailManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val exifReader: ExifReader
) {
    private val cacheRoot get() = File(context.cacheDir, "arcanum_thumbs")

    // AES-256-GCM key from Android Keystore — mirrors PinManager's pattern
    private val masterKey by lazy {
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
    }

    private fun cacheFile(containerId: String, filePath: String): File {
        val key = md5("$containerId:$filePath:v3")
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
    ): Bitmap? {
        // Pass 1: read first 512 KB to detect format and get dimensions from header only
        val headerBytes = engine.nativeReadFile(handle, relativePath, 0L, 512 * 1024) ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(headerBytes, 0, headerBytes.size, opts)
        if (opts.outWidth <= 0) return null

        // Pass 2: read the COMPLETE file so BitmapFactory gets a full stream.
        // Truncated JPEG data causes BitmapFactory to return a non-null but partially black bitmap.
        val fileCap = minOf(fileSize, 20L * 1024 * 1024).toInt()
        val bytes = if (fileSize <= headerBytes.size.toLong()) headerBytes
                    else engine.nativeReadFile(handle, relativePath, 0L, fileCap) ?: return null

        opts.inSampleSize = calculateInSampleSize(opts, 256, 256)
        opts.inJustDecodeBounds = false
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val oriented = applyExifOrientation(bitmap, exifReader.readOrientation(bytes))
        val thumb = centerCrop(oriented, 256)
        saveToCacheFile(thumb, cacheFile)
        return thumb
    }

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
        retriever.setDataSource(NativeMediaDataSource(engine, handle, relativePath, fileSize))
        val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        retriever.release()
        frame?.let {
            val thumb = centerCrop(it, 256)
            saveToCacheFile(thumb, cacheFile)
            thumb
        }
    } catch (_: Exception) {
        null
    }

    private fun generateAudioThumbnail(
        engine: VeraCryptEngine,
        handle: Long,
        relativePath: String,
        fileSize: Long,
        cacheFile: File
    ): Bitmap? = try {
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(NativeMediaDataSource(engine, handle, relativePath, fileSize))
        val artBytes = retriever.embeddedPicture
        retriever.release()
        // Returns null if no embedded art → UI will show the music note icon instead
        artBytes?.let { bytes ->
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val thumb = centerCrop(bitmap, 256)
            saveToCacheFile(thumb, cacheFile)
            thumb
        }
    } catch (_: Exception) {
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
        val chunk = engine.nativeReadFile(handle, filePath, position, toRead) ?: return -1
        chunk.copyInto(buffer, offset, 0, chunk.size)
        return chunk.size
    }

    override fun getSize(): Long = fileSize

    override fun close() {}
}
