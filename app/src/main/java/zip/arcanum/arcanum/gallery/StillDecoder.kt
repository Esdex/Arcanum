package zip.arcanum.arcanum.gallery

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import zip.arcanum.core.utils.MediaExtensions
import zip.arcanum.crypto.VeraCryptEngine
import java.nio.ByteBuffer

/**
 * Decoding for the picture formats that cannot be read a piece at a time.
 *
 * Almost everything goes through BitmapFactory reading a stream, which is how a photo is
 * decoded without ever holding the whole encrypted file in memory. HEIF is the exception:
 * its decoder asks the source for its total size before it starts and refuses a plain
 * stream - `HeifDecoderImpl: getSize: not supported!` in the log - so every .heic came back
 * as nothing. The gallery showed no thumbnail for one and the viewer a broken picture,
 * while the editor, which happens to hand the decoder an array, opened the same file.
 *
 * These formats are read whole and decoded by ImageDecoder, which also turns the picture
 * the right way up on its own: unlike BitmapFactory it applies the orientation, so callers
 * must not apply it a second time.
 */
internal object StillDecoder {

    /** Beyond this a picture is left undecoded rather than held whole in memory. */
    const val MAX_BYTES = 64L * 1024 * 1024

    private val WHOLE_FILE_FORMATS = setOf("heic", "heif")

    fun needsWholeFile(fileName: String): Boolean = MediaExtensions.of(fileName) in WHOLE_FILE_FORMATS

    /** Decodes [bytes], no larger than [maxDimension] on a side. Null if the platform refuses. */
    fun decode(bytes: ByteArray, maxDimension: Int): Bitmap? = try {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            var sample = 1
            while (info.size.width / sample > maxDimension || info.size.height / sample > maxDimension) {
                sample *= 2
            }
            if (sample > 1) decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: Throwable) {
        null
    }
}

/**
 * The whole file in one array, sized up front.
 *
 * One JNI call cannot do it: both filesystem drivers refuse a read over 16 MB and hand back
 * an empty array, which is why anything larger has to arrive in the stream's chunks. They go
 * into a buffer of exactly the file's size rather than a growing one, which for a large file
 * would otherwise peak at several times its length.
 *
 * Null when the file is bigger than [limit], or when it ends before its recorded size.
 */
internal fun VeraCryptEngine.readWholeFile(
    handle: Long,
    path: String,
    size: Long,
    limit: Long
): ByteArray? {
    if (size <= 0 || size > limit) return null
    return try {
        val out = ByteArray(size.toInt())
        val stream = NativeFileInputStream(this, handle, path, size)
        var off = 0
        while (off < out.size) {
            val n = stream.read(out, off, out.size - off)
            if (n <= 0) break
            off += n
        }
        if (off == out.size) out else null
    } catch (_: Throwable) {
        null
    }
}
