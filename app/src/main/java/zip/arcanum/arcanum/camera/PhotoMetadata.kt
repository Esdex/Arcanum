package zip.arcanum.arcanum.camera

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Leaves a photo from the in-app camera only the metadata the gallery needs (#145).
 *
 * The camera writes the phone's make and model into every JPEG, and the exposure, the lens and
 * a thumbnail with them: things a photo carries out of the vault the moment it is shared, and
 * that say which phone took it. What is kept is which way up it goes and when it was taken.
 * Someone who wants the rest has the phone's own camera; this one is for photos that say
 * nothing about where they came from.
 *
 * The picture itself is not touched: the tables, the frame and the scan are copied byte for
 * byte, and only the segments around them change.
 */
internal object PhotoMetadata {

    /**
     * [jpeg] with its EXIF replaced by one holding [orientation] and [takenAt] alone, XMP,
     * comments and every other application segment dropped. Null when [jpeg] is not a JPEG
     * this can walk; the caller does not save it then, rather than save it unstripped.
     *
     * Kept: APP0 (JFIF), an APP2 that is an ICC profile and APP14 (Adobe) - they say how the
     * colours are to be read, not anything about the photo. The image ends at the first
     * end-of-image marker after the scan, which is where it ends in the baseline, single-scan
     * JPEG a camera makes; anything appended after it is dropped with the rest.
     */
    fun keepOnly(jpeg: ByteArray, orientation: Int, takenAt: Date): ByteArray? {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null
        val out = ByteArrayOutputStream(jpeg.size)
        out.write(jpeg, 0, 2)
        out.write(exifSegment(orientation, takenAt))
        var i = 2
        while (i + 4 <= jpeg.size) {
            if (jpeg[i] != 0xFF.toByte()) return null
            val marker = jpeg[i + 1].toInt() and 0xFF
            if (marker == 0xFF) { i++; continue }                    // fill byte
            val length = ((jpeg[i + 2].toInt() and 0xFF) shl 8) or (jpeg[i + 3].toInt() and 0xFF)
            if (length < 2 || i + 2 + length > jpeg.size) return null
            if (marker == 0xDA) {
                val end = endOfImage(jpeg, i + 2 + length) ?: return null
                out.write(jpeg, i, end - i)
                return out.toByteArray()
            }
            if (keeps(marker, jpeg, i + 4, length - 2)) out.write(jpeg, i, 2 + length)
            i += 2 + length
        }
        return null                                                  // no scan: not a whole JPEG
    }

    /** APP0 (JFIF) is not an application segment of this kind and stays with the tables. */
    private fun keeps(marker: Int, b: ByteArray, at: Int, size: Int): Boolean = when (marker) {
        0xEE -> true
        0xE2 -> size >= ICC.size && b.copyOfRange(at, at + ICC.size).contentEquals(ICC)
        in 0xE1..0xEF, 0xFE -> false
        else -> true
    }

    /** Just past the first FF D9 from [from] on: in scan data an FF is always followed by 00 or a restart marker. */
    private fun endOfImage(b: ByteArray, from: Int): Int? {
        var i = from
        while (i + 1 < b.size) {
            if (b[i] == 0xFF.toByte() && b[i + 1] == 0xD9.toByte()) return i + 2
            i++
        }
        return null
    }

    /**
     * APP1 with a little-endian TIFF: IFD0 holds the orientation, the date and a pointer to the
     * Exif IFD, which holds the date the photo was taken - the one the gallery sorts by.
     */
    private fun exifSegment(orientation: Int, takenAt: Date): ByteArray {
        val date = (SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(takenAt) + "\u0000")
            .toByteArray(Charsets.US_ASCII)                          // 20 bytes
        val tiff = ByteArrayOutputStream()
        fun u16(v: Int) { tiff.write(v and 0xFF); tiff.write(v shr 8 and 0xFF) }
        fun u32(v: Int) { u16(v and 0xFFFF); u16(v ushr 16) }
        fun entry(tag: Int, type: Int, count: Int, value: Int) { u16(tag); u16(type); u32(count); u32(value) }

        val ifd0 = 8
        val ifd0Size = 2 + 3 * 12 + 4
        val dateAt = ifd0 + ifd0Size
        val exifIfd = dateAt + date.size
        val exifIfdSize = 2 + 12 + 4
        val originalAt = exifIfd + exifIfdSize

        tiff.write(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0)); u32(ifd0)
        u16(3)                                                       // tags in ascending order
        entry(0x0112, 3, 1, orientation.coerceIn(1, 8))              // Orientation, SHORT
        entry(0x0132, 2, date.size, dateAt)                          // DateTime, ASCII
        entry(0x8769, 4, 1, exifIfd)                                 // Exif IFD, LONG
        u32(0)                                                       // no IFD1: no thumbnail
        tiff.write(date)
        u16(1)
        entry(0x9003, 2, date.size, originalAt)                      // DateTimeOriginal
        u32(0)
        tiff.write(date)

        val payload = EXIF_HEADER + tiff.toByteArray()
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private val EXIF_HEADER = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
    private val ICC = "ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)
}
