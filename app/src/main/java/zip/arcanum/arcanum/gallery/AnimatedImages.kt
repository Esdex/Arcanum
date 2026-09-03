package zip.arcanum.arcanum.gallery

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import zip.arcanum.core.utils.MediaExtensions
import java.nio.ByteBuffer

/**
 * Decoding for the image formats that hold more than one frame: GIF, and the animated form
 * of WebP. Everything else keeps going through BitmapFactory, which only ever hands back
 * the first frame (#159).
 *
 * The platform decoder does the work - minSdk is 29 and ImageDecoder has been there since
 * 28 - so no image library is pulled in for this.
 *
 * ImageDecoder can only read a ByteBuffer here (its other sources are files and content
 * URIs, and a vault has neither), so the whole file has to be in memory before it can be
 * decoded. That is what MAX_BYTES bounds.
 */
object AnimatedImages {

    /**
     * Above this, the file is shown as a still first frame instead. The cap is on the
     * encoded bytes, which are held whole while decoding; the decoded frames are bounded
     * separately by MAX_DIMENSION.
     */
    const val MAX_BYTES = 48L * 1024 * 1024

    /** No frame is decoded larger than this on a side - the same bound the still path uses. */
    private const val MAX_DIMENSION = 4096

    /** Enough to hold a WebP extended-format header, which is where its ANIM flag lives. */
    const val HEADER_BYTES = 21

    private val CANDIDATES = setOf("gif", "webp")

    /** Whether the name and size are worth reading a header for at all. */
    fun mayAnimate(fileName: String, size: Long): Boolean =
        size in 1..MAX_BYTES && MediaExtensions.of(fileName) in CANDIDATES

    /**
     * Whether [head] - the first HEADER_BYTES of the file - says this one can animate.
     *
     * A WebP is answered exactly: only the extended format carries animation, and it
     * announces it in a flag. A GIF cannot be answered without walking its blocks to look
     * for a second image descriptor, which means reading the whole file, so it is answered
     * "maybe" and the decoder settles it. That costs nothing: a single-frame GIF comes back
     * as a plain bitmap from the same decode, and the caller keeps it.
     */
    fun headerAnimates(fileName: String, head: ByteArray?): Boolean =
        when (MediaExtensions.of(fileName)) {
            "gif"  -> true
            "webp" -> head != null && webpAnimates(head)
            else   -> false
        }

    // RIFF....WEBPVP8X, then a 4-byte chunk size, then the flags byte: 0x02 is ANIMATION.
    private fun webpAnimates(head: ByteArray): Boolean =
        head.size >= HEADER_BYTES &&
        head.hasAscii(0, "RIFF") && head.hasAscii(8, "WEBP") && head.hasAscii(12, "VP8X") &&
        (head[20].toInt() and 0x02) != 0

    private fun ByteArray.hasAscii(at: Int, text: String): Boolean {
        if (at + text.length > size) return false
        for (i in text.indices) if (this[at + i].toInt() != text[i].code) return false
        return true
    }

    /**
     * Decodes [bytes] into an AnimatedImageDrawable when they animate, and into a
     * BitmapDrawable when they do not. Null on anything the decoder refuses, which leaves
     * the caller to fall back to the still path.
     *
     * Software allocation on purpose: a hardware bitmap cannot be read back, and a
     * single-frame result is handed on to code that treats it as an ordinary bitmap.
     */
    fun decode(bytes: ByteArray): Drawable? = try {
        ImageDecoder.decodeDrawable(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            var sample = 1
            while (info.size.width / sample > MAX_DIMENSION || info.size.height / sample > MAX_DIMENSION) {
                sample *= 2
            }
            if (sample > 1) decoder.setTargetSampleSize(sample)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: Throwable) {
        null
    }

    /** Loops forever rather than freezing on the last frame of a GIF that declares one pass. */
    fun playForever(drawable: Drawable) {
        if (drawable is AnimatedImageDrawable) {
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable.start()
        }
    }

    fun stop(drawable: Drawable?) {
        if (drawable is AnimatedImageDrawable && drawable.isRunning) drawable.stop()
    }
}
