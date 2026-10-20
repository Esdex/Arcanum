package zip.arcanum.arcanum.gallery.tags

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import java.io.ByteArrayOutputStream

/**
 * Makes a picture fit to be a track's cover.
 *
 * A JPEG that is already small and carries nothing but the picture is stored as it is, the
 * way AIMP stores a cover. Anything else is drawn again as a JPEG no larger than [MAX_SIDE].
 * A photo straight off the phone carries EXIF - where it was taken, on what, and when - and a
 * cover goes wherever the song is copied, out of the vault too. Its orientation is applied
 * before it is dropped, because no player reads EXIF from a cover.
 */
object CoverImage {

    /** Larger than any player draws a cover. */
    const val MAX_SIDE = 1500

    /** A clean JPEG over this is drawn again anyway: the player reads its tags from the start of the file. */
    const val MAX_BYTES = 1024 * 1024

    private const val QUALITY = 90

    /** The cover to store for [bytes], or null if they are not a picture this phone can decode. */
    fun prepare(bytes: ByteArray, orientation: Int): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        if (isCleanJpeg(bytes) && maxOf(w, h) <= MAX_SIDE && bytes.size <= MAX_BYTES) return bytes

        // Decoded at the smallest power-of-two step that still covers MAX_SIDE, then scaled
        // the rest of the way: a 50-megapixel photo is never held at full size.
        var sample = 1
        while (maxOf(w, h) / (sample * 2) >= MAX_SIDE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = minOf(1f, MAX_SIDE.toFloat() / maxOf(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postScale(scale, scale)
            when (orientation) {
                2 -> postScale(-1f, 1f)
                3 -> postRotate(180f)
                4 -> postScale(1f, -1f)
                5 -> { postRotate(90f); postScale(-1f, 1f) }
                6 -> postRotate(90f)
                7 -> { postRotate(270f); postScale(-1f, 1f) }
                8 -> postRotate(270f)
            }
        }
        val shaped = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        // A JPEG has no transparency, and what was transparent would come out black.
        val flat = Bitmap.createBitmap(shaped.width, shaped.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(shaped, 0f, 0f, null)
        }
        val out = ByteArrayOutputStream()
        if (!flat.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)) return null
        return out.toByteArray()
    }

    /**
     * Whether [bytes] are a JPEG with nothing in it but the picture: no APP1 segment, which is
     * where EXIF and XMP live, and no comment. Segments are walked up to the start of the scan,
     * where the image data begins and markers end.
     */
    internal fun isCleanJpeg(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return false
        var i = 2
        while (i + 4 <= bytes.size) {
            if (bytes[i] != 0xFF.toByte()) return false
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }               // fill byte
            if (marker == 0xDA) return true                     // start of scan
            if (marker == 0xE1 || marker == 0xFE) return false  // APP1 (EXIF, XMP), comment
            val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (length < 2) return false
            i += 2 + length
        }
        return false
    }
}
