package zip.arcanum.arcanum.gallery

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.CRC32
import kotlin.math.abs

/**
 * Carries EXIF from the picture that was edited into the file that replaces it.
 *
 * `Bitmap.compress` writes pixels and nothing else, so every edit used to come out stripped:
 * the date the photo was taken and the place it was taken went with it. In the app that was
 * invisible, because the date also lives in the media index, but the file itself left the
 * vault empty of both.
 *
 * Two ways in, depending on what the original was:
 *
 * - a JPEG carries its EXIF as a TIFF block inside an APP1 segment, which is lifted whole,
 *   keeping camera, lens and exposure along with the date;
 * - anything else (a HEIC, where the block sits inside the ISO container) gets a small block
 *   built from what the app could read out of it: the date and the location.
 *
 * Either way the block is retargeted before it is written - see [retarget].
 */
object ExifTransfer {

    private const val MAX_TIFF_IN_APP1 = 0xFFFF - 2 - 6      // what one APP1 segment can hold
    private val EXIF_DATE_FMT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

    // ── Lifting ───────────────────────────────────────────────────────────

    /**
     * The TIFF block of the first *Exif* APP1 in a JPEG, or null when it has none.
     *
     * The first APP1 is not necessarily the one: a Nothing Phone writes dozens of APP1
     * segments holding depth maps, and only one of them starts with "Exif".
     */
    fun tiffFromJpeg(bytes: ByteArray): ByteArray? {
        if (bytes.size < 4 || !bytes.isJpeg()) return null
        var i = 2
        while (i + 4 <= bytes.size) {
            if (bytes[i] != 0xFF.toByte()) return null
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker == 0xDA || marker == 0xD9) return null   // image data: no headers left
            val segLen = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (segLen < 2 || i + 2 + segLen > bytes.size) return null
            if (marker == 0xE1 && segLen > 8 && bytes.hasAscii(i + 4, "Exif") && bytes[i + 8].toInt() == 0) {
                return bytes.copyOfRange(i + 10, i + 2 + segLen)
            }
            i += 2 + segLen
        }
        return null
    }

    /**
     * A small TIFF block holding a date and a location, for originals whose own block
     * cannot be lifted. Null when there is nothing worth writing.
     */
    fun buildTiff(dateMillis: Long?, lat: Double?, lng: Double?): ByteArray? {
        val hasDate = dateMillis != null && dateMillis > 0L
        val hasGps  = lat != null && lng != null
        if (!hasDate && !hasGps) return null

        // Layout: header, IFD0, Exif SubIFD, GPS IFD, then the values they point at.
        val ifd0Entries = 1 + (if (hasDate) 1 else 0) + (if (hasGps) 1 else 0)   // orientation + pointers
        val ifd0Off  = 8
        val subOff   = ifd0Off + 2 + ifd0Entries * 12 + 4
        val subSize  = if (hasDate) 2 + 12 + 4 else 0
        val gpsOff   = subOff + subSize
        val gpsSize  = if (hasGps) 2 + 4 * 12 + 4 else 0
        var dataOff  = gpsOff + gpsSize

        val dateAt = if (hasDate) dataOff.also { dataOff += 20 } else 0
        val latAt  = if (hasGps)  dataOff.also { dataOff += 24 } else 0
        val lngAt  = if (hasGps)  dataOff.also { dataOff += 24 } else 0

        val out = ByteArray(dataOff)
        out[0] = 'I'.code.toByte(); out[1] = 'I'.code.toByte()
        writeShort(out, 2, 42, true)
        writeInt(out, 4, ifd0Off, true)

        var p = ifd0Off
        writeShort(out, p, ifd0Entries, true); p += 2
        // Ascending tag order is required of an IFD.
        p = putEntry(out, p, 0x0112, 3, 1, 1)                       // Orientation: upright
        if (hasDate) p = putEntry(out, p, 0x8769, 4, 1, subOff)      // Exif SubIFD pointer
        if (hasGps)  p = putEntry(out, p, 0x8825, 4, 1, gpsOff)      // GPS IFD pointer
        writeInt(out, p, 0, true)                                    // no IFD1

        if (hasDate) {
            var q = subOff
            writeShort(out, q, 1, true); q += 2
            q = putEntry(out, q, 0x9003, 2, 20, dateAt)              // DateTimeOriginal
            writeInt(out, q, 0, true)
            val text = EXIF_DATE_FMT.format(Date(dateMillis!!)).toByteArray(Charsets.US_ASCII)
            text.copyInto(out, dateAt, 0, minOf(19, text.size))
            out[dateAt + 19] = 0
        }

        if (hasGps) {
            var q = gpsOff
            writeShort(out, q, 4, true); q += 2
            q = putAscii2(out, q, 0x0001, if (lat!! >= 0) 'N' else 'S')
            q = putEntry(out, q, 0x0002, 5, 3, latAt)
            q = putAscii2(out, q, 0x0003, if (lng!! >= 0) 'E' else 'W')
            q = putEntry(out, q, 0x0004, 5, 3, lngAt)
            writeInt(out, q, 0, true)
            writeDms(out, latAt, abs(lat))
            writeDms(out, lngAt, abs(lng!!))
        }
        return out
    }

    /**
     * Makes a block describe the file about to be written rather than the one it came from:
     * the pixels are already turned the right way up, a crop may have changed their number,
     * and the thumbnail inside is a picture of the photo before the edit.
     *
     * Every change is in place, so nothing an offset points at moves.
     */
    fun retarget(tiff: ByteArray, width: Int, height: Int): ByteArray {
        val out = tiff.copyOf()
        val le = when {
            out.size < 8                                              -> return out
            out[0] == 'I'.code.toByte() && out[1] == 'I'.code.toByte() -> true
            out[0] == 'M'.code.toByte() && out[1] == 'M'.code.toByte() -> false
            else                                                      -> return out
        }
        val ifd0 = readInt(out, 4, le)
        if (ifd0 < 8 || ifd0 + 2 > out.size) return out
        val count = readShort(out, ifd0, le)
        var subIfd = -1
        for (e in 0 until count) {
            val entry = ifd0 + 2 + e * 12
            if (entry + 12 > out.size) return out
            when (readShort(out, entry, le)) {
                0x0112 -> writeValue(out, entry, le, 1)
                0x8769 -> subIfd = readInt(out, entry + 8, le)
            }
        }
        // Unlink IFD1: what it holds is a thumbnail of the picture before the edit.
        val nextIfd = ifd0 + 2 + count * 12
        if (nextIfd + 4 <= out.size) writeInt(out, nextIfd, 0, le)

        if (subIfd >= 8 && subIfd + 2 <= out.size) {
            val subCount = readShort(out, subIfd, le)
            for (e in 0 until subCount) {
                val entry = subIfd + 2 + e * 12
                if (entry + 12 > out.size) break
                when (readShort(out, entry, le)) {
                    0xA002 -> writeValue(out, entry, le, width)
                    0xA003 -> writeValue(out, entry, le, height)
                }
            }
        }
        return out
    }

    // ── Writing it back into a container ──────────────────────────────────

    /** [jpeg] with an Exif APP1 right after the start marker. */
    fun intoJpeg(jpeg: ByteArray, tiff: ByteArray): ByteArray {
        if (!jpeg.isJpeg() || tiff.size > MAX_TIFF_IN_APP1) return jpeg
        val segLen = 2 + 6 + tiff.size
        val out = ByteArray(jpeg.size + 2 + segLen)
        var p = 0
        out[p++] = 0xFF.toByte(); out[p++] = 0xD8.toByte()
        out[p++] = 0xFF.toByte(); out[p++] = 0xE1.toByte()
        writeShort(out, p, segLen, false); p += 2
        "Exif".toByteArray(Charsets.US_ASCII).copyInto(out, p); p += 4
        out[p++] = 0; out[p++] = 0
        tiff.copyInto(out, p); p += tiff.size
        jpeg.copyInto(out, p, 2, jpeg.size)
        return out
    }

    /** [png] with an eXIf chunk before its end marker. The chunk holds the raw TIFF block. */
    fun intoPng(png: ByteArray, tiff: ByteArray): ByteArray {
        if (png.size < 8 || !png.hasAscii(1, "PNG")) return png
        val iend = lastChunkStart(png, "IEND") ?: return png
        val chunk = ByteArray(12 + tiff.size)
        writeInt(chunk, 0, tiff.size, false)
        "eXIf".toByteArray(Charsets.US_ASCII).copyInto(chunk, 4)
        tiff.copyInto(chunk, 8)
        val crc = CRC32().apply { update(chunk, 4, 4 + tiff.size) }.value
        writeInt(chunk, 8 + tiff.size, crc.toInt(), false)

        val out = ByteArray(png.size + chunk.size)
        png.copyInto(out, 0, 0, iend)
        chunk.copyInto(out, iend)
        png.copyInto(out, iend + chunk.size, iend, png.size)
        return out
    }

    /**
     * [webp] with an EXIF chunk. A WebP can only carry one in the extended format, so a
     * plain file is wrapped in a VP8X header first; a file that already has one keeps it,
     * with the EXIF flag set. Returns the input untouched if the result does not walk
     * cleanly - a photo without its date beats a photo no reader will open.
     */
    fun intoWebp(webp: ByteArray, tiff: ByteArray, width: Int, height: Int): ByteArray {
        if (webp.size < 16 || !webp.hasAscii(0, "RIFF") || !webp.hasAscii(8, "WEBP")) return webp
        if (width <= 0 || height <= 0 || width > 1 shl 24 || height > 1 shl 24) return webp

        var vp8x: ByteArray? = null
        val body = ArrayList<ByteArray>()
        var i = 12
        while (i + 8 <= webp.size) {
            val id  = String(webp, i, 4, Charsets.US_ASCII)
            val len = readInt(webp, i + 4, true)
            if (len < 0 || i + 8 + len > webp.size) return webp
            val padded = len + (len and 1)
            val end = minOf(i + 8 + padded, webp.size)
            val chunk = webp.copyOfRange(i, end)
            when (id) {
                "VP8X" -> vp8x = chunk
                "EXIF" -> Unit                       // a stale one is replaced, not kept
                else   -> body.add(chunk)
            }
            i += 8 + padded
        }
        if (body.isEmpty()) return webp

        val header = vp8x?.copyOf() ?: ByteArray(18).also {
            "VP8X".toByteArray(Charsets.US_ASCII).copyInto(it, 0)
            writeInt(it, 4, 10, true)
            writeInt24(it, 12, width - 1)
            writeInt24(it, 15, height - 1)
        }
        if (header.size < 9) return webp
        header[8] = (header[8].toInt() or 0x08).toByte()          // this file has EXIF

        val exif = ByteArray(8 + tiff.size + (tiff.size and 1))
        "EXIF".toByteArray(Charsets.US_ASCII).copyInto(exif, 0)
        writeInt(exif, 4, tiff.size, true)
        tiff.copyInto(exif, 8)

        val payload = header.size + body.sumOf { it.size } + exif.size
        val out = ByteArray(12 + payload)
        "RIFF".toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        writeInt(out, 4, 4 + payload, true)
        "WEBP".toByteArray(Charsets.US_ASCII).copyInto(out, 8)
        var p = 12
        header.copyInto(out, p); p += header.size
        for (c in body) { c.copyInto(out, p); p += c.size }
        exif.copyInto(out, p)
        return if (webpWalks(out)) out else webp
    }

    /** Whether a RIFF file's chunks account for exactly the length its header claims. */
    private fun webpWalks(bytes: ByteArray): Boolean {
        if (bytes.size < 12 || readInt(bytes, 4, true) != bytes.size - 8) return false
        var i = 12
        while (i + 8 <= bytes.size) {
            val len = readInt(bytes, i + 4, true)
            if (len < 0) return false
            i += 8 + len + (len and 1)
        }
        return i == bytes.size
    }

    // ── Bytes ─────────────────────────────────────────────────────────────

    private fun ByteArray.isJpeg() =
        size >= 2 && this[0] == 0xFF.toByte() && this[1] == 0xD8.toByte()

    private fun ByteArray.hasAscii(at: Int, text: String): Boolean {
        if (at < 0 || at + text.length > size) return false
        for (i in text.indices) if (this[at + i].toInt() != text[i].code) return false
        return true
    }

    /** Offset of the named PNG chunk's length field, walking the chunks properly. */
    private fun lastChunkStart(png: ByteArray, id: String): Int? {
        var i = 8
        while (i + 12 <= png.size) {
            val len = readInt(png, i, false)
            if (len < 0 || i + 12 + len > png.size) return null
            if (png.hasAscii(i + 4, id)) return i
            i += 12 + len
        }
        return null
    }

    // An IFD entry: tag, type, count, and either the value itself or where it lives.
    private fun putEntry(b: ByteArray, at: Int, tag: Int, type: Int, count: Int, value: Int): Int {
        writeShort(b, at, tag, true)
        writeShort(b, at + 2, type, true)
        writeInt(b, at + 4, count, true)
        if (type == 3 && count == 1) writeShort(b, at + 8, value, true) else writeInt(b, at + 8, value, true)
        return at + 12
    }

    // A two-character ASCII value fits in the entry itself.
    private fun putAscii2(b: ByteArray, at: Int, tag: Int, c: Char): Int {
        writeShort(b, at, tag, true)
        writeShort(b, at + 2, 2, true)
        writeInt(b, at + 4, 2, true)
        b[at + 8] = c.code.toByte()
        return at + 12
    }

    /** Writes a number into an entry's own value field, keeping the type it was declared as. */
    private fun writeValue(b: ByteArray, entry: Int, le: Boolean, value: Int) {
        val type  = readShort(b, entry + 2, le)
        val count = readInt(b, entry + 4, le)
        if (count != 1) return
        when (type) {
            3 -> { writeShort(b, entry + 8, value, le); b[entry + 10] = 0; b[entry + 11] = 0 }
            4 -> writeInt(b, entry + 8, value, le)
        }
    }

    // Degrees, minutes and seconds as three rationals.
    private fun writeDms(b: ByteArray, at: Int, decimal: Double) {
        val deg = decimal.toInt()
        val minFloat = (decimal - deg) * 60.0
        val min = minFloat.toInt()
        val sec = ((minFloat - min) * 60.0 * 10_000.0).toInt()
        writeInt(b, at,      deg, true); writeInt(b, at + 4,  1, true)
        writeInt(b, at + 8,  min, true); writeInt(b, at + 12, 1, true)
        writeInt(b, at + 16, sec, true); writeInt(b, at + 20, 10_000, true)
    }

    private fun readShort(b: ByteArray, off: Int, le: Boolean): Int {
        val a = b[off].toInt() and 0xFF; val c = b[off + 1].toInt() and 0xFF
        return if (le) a or (c shl 8) else (a shl 8) or c
    }

    private fun readInt(b: ByteArray, off: Int, le: Boolean): Int {
        val b0 = b[off].toInt() and 0xFF; val b1 = b[off + 1].toInt() and 0xFF
        val b2 = b[off + 2].toInt() and 0xFF; val b3 = b[off + 3].toInt() and 0xFF
        return if (le) b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
               else (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun writeShort(b: ByteArray, off: Int, v: Int, le: Boolean) {
        if (le) { b[off] = (v and 0xFF).toByte(); b[off + 1] = ((v shr 8) and 0xFF).toByte() }
        else    { b[off] = ((v shr 8) and 0xFF).toByte(); b[off + 1] = (v and 0xFF).toByte() }
    }

    private fun writeInt(b: ByteArray, off: Int, v: Int, le: Boolean) {
        if (le) {
            b[off]     = (v and 0xFF).toByte();          b[off + 1] = ((v shr 8) and 0xFF).toByte()
            b[off + 2] = ((v shr 16) and 0xFF).toByte(); b[off + 3] = ((v shr 24) and 0xFF).toByte()
        } else {
            b[off]     = ((v shr 24) and 0xFF).toByte(); b[off + 1] = ((v shr 16) and 0xFF).toByte()
            b[off + 2] = ((v shr 8) and 0xFF).toByte();  b[off + 3] = (v and 0xFF).toByte()
        }
    }

    private fun writeInt24(b: ByteArray, off: Int, v: Int) {
        b[off]     = (v and 0xFF).toByte()
        b[off + 1] = ((v shr 8) and 0xFF).toByte()
        b[off + 2] = ((v shr 16) and 0xFF).toByte()
    }
}
