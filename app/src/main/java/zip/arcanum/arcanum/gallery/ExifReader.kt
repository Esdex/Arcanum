package zip.arcanum.arcanum.gallery

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import com.drew.metadata.jpeg.JpegDirectory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.round

data class ExifTag(val directory: String, val name: String, val value: String)

data class MediaExifData(
    val dateTimeOriginal: Long? = null,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
    val megapixels: Float? = null,
    val cameraMake: String? = null,
    val cameraModel: String? = null,
    val fNumber: String? = null,
    val exposureTime: String? = null,
    val iso: Int? = null,
    val focalLength: String? = null,
    val flash: String? = null,
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
    val gpsAltitude: Double? = null,
    val allTags: List<ExifTag> = emptyList()
)

@Singleton
class ExifReader @Inject constructor() {

    private val EXIF_DATE_FMT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

    fun readDate(bytes: ByteArray): Long = try {
        val metadata = ImageMetadataReader.readMetadata(ByteArrayInputStream(limitedExifBytes(bytes)))
        val exifSub  = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
        val exif0    = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
        val dateStr  = exifSub?.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
            ?: exif0?.getString(ExifIFD0Directory.TAG_DATETIME)
        dateStr?.let { runCatching { EXIF_DATE_FMT.parse(it)?.time }.getOrNull() } ?: 0L
    } catch (_: Exception) { 0L }

    fun readOrientation(bytes: ByteArray): Int = try {
        ImageMetadataReader.readMetadata(ByteArrayInputStream(limitedExifBytes(bytes)))
            .getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            ?.getInteger(ExifIFD0Directory.TAG_ORIENTATION) ?: 1
    } catch (_: Exception) { 1 }

    suspend fun readExif(bytes: ByteArray): MediaExifData = withContext(Dispatchers.IO) {
        try {
            val metadata = ImageMetadataReader.readMetadata(ByteArrayInputStream(limitedExifBytes(bytes)))

            val exif0   = metadata.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            val exifSub = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            val gps     = metadata.getFirstDirectoryOfType(GpsDirectory::class.java)
            val jpeg    = metadata.getFirstDirectoryOfType(JpegDirectory::class.java)

            val exifFmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
            val dateTimeOriginal = (exifSub?.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
                ?: exif0?.getString(ExifIFD0Directory.TAG_DATETIME))
                ?.let { runCatching { exifFmt.parse(it)?.time }.getOrNull() }

            val w = jpeg?.getInteger(JpegDirectory.TAG_IMAGE_WIDTH)
                ?: exifSub?.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH)
                ?: exif0?.getInteger(ExifIFD0Directory.TAG_IMAGE_WIDTH)
            val h = jpeg?.getInteger(JpegDirectory.TAG_IMAGE_HEIGHT)
                ?: exifSub?.getInteger(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT)
                ?: exif0?.getInteger(ExifIFD0Directory.TAG_IMAGE_HEIGHT)
            val megapixels = if (w != null && h != null && w > 0 && h > 0)
                (w.toLong() * h.toLong()).toFloat() / 1_000_000f else null

            val cameraMake  = exif0?.getString(ExifIFD0Directory.TAG_MAKE)?.trim()
            val rawModel    = exif0?.getString(ExifIFD0Directory.TAG_MODEL)?.trim()
            val cameraModel = if (rawModel != null && cameraMake != null &&
                rawModel.startsWith(cameraMake, ignoreCase = true))
                rawModel.substring(cameraMake.length).trim() else rawModel

            val fNumberR = exifSub?.getRational(ExifSubIFDDirectory.TAG_FNUMBER)
            val fNumber  = fNumberR?.let { "f/%.2f".format(it.toDouble()) }

            val etR = exifSub?.getRational(ExifSubIFDDirectory.TAG_EXPOSURE_TIME)
            val exposureTime = etR?.let {
                val d = it.toDouble()
                if (d > 0 && d < 1.0) "1/${round(1.0 / d).toLong()}" else "%.4f".format(d)
            }

            val iso = exifSub?.getInteger(ExifSubIFDDirectory.TAG_ISO_EQUIVALENT)

            val flR = exifSub?.getRational(ExifSubIFDDirectory.TAG_FOCAL_LENGTH)
            val focalLength = flR?.let { "%.2fmm".format(it.toDouble()) }

            val flash = exifSub?.getDescription(ExifSubIFDDirectory.TAG_FLASH)

            var lat: Double? = null
            var lng: Double? = null
            var alt: Double? = null
            if (gps != null) {
                try {
                    val geoLoc = gps.geoLocation
                    if (geoLoc != null) {
                        lat = geoLoc.latitude
                        lng = geoLoc.longitude
                    }
                    alt = gps.getRational(GpsDirectory.TAG_ALTITUDE)?.toDouble()
                } catch (_: Exception) {}
            }

            val allTags = mutableListOf<ExifTag>()
            tagLoop@ for (dir in metadata.directories) {
                for (tag in dir.tags) {
                    val value = tag.description
                    if (!value.isNullOrBlank()) {
                        allTags.add(ExifTag(dir.name, tag.tagName, value.take(MAX_EXIF_TAG_VALUE_CHARS)))
                        if (allTags.size >= MAX_EXIF_TAGS) break@tagLoop
                    }
                }
            }

            MediaExifData(
                dateTimeOriginal = dateTimeOriginal,
                widthPx          = w,
                heightPx         = h,
                megapixels       = megapixels,
                cameraMake       = cameraMake,
                cameraModel      = cameraModel,
                fNumber          = fNumber,
                exposureTime     = exposureTime,
                iso              = iso,
                focalLength      = focalLength,
                flash            = flash,
                gpsLatitude      = lat,
                gpsLongitude     = lng,
                gpsAltitude      = alt,
                allTags          = allTags
            )
        } catch (_: Exception) {
            MediaExifData()
        }
    }

    private fun limitedExifBytes(bytes: ByteArray): ByteArray =
        if (bytes.size > MAX_EXIF_PARSE_BYTES) bytes.copyOf(MAX_EXIF_PARSE_BYTES) else bytes

    private companion object {
        const val MAX_EXIF_PARSE_BYTES = 512 * 1024
        const val MAX_EXIF_TAGS = 256
        const val MAX_EXIF_TAG_VALUE_CHARS = 512
    }
}

// ── In-place JPEG EXIF patcher ────────────────────────────────────────────
// Reads only the APP1 segment, modifies EXIF tags in-place, returns
// (app1OffsetInFile, modifiedApp1Bytes) so the caller can write only those
// bytes back without touching the rest of the file.

object ExifJpegPatcher {

    private val EXIF_DATE_FMT = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)

    // Returns (startOffset, modifiedBytes) or null if APP1/EXIF not found.
    fun patchDateTime(fileHead: ByteArray, newDateMillis: Long): Pair<Int, ByteArray>? {
        val (app1Start, segLen) = findApp1(fileHead) ?: return null
        val app1Total = 2 + segLen
        if (app1Start + app1Total > fileHead.size) return null
        val app1 = fileHead.copyOfRange(app1Start, app1Start + app1Total)
        val tiffStart = 10  // 2 marker + 2 length + 6 "Exif\0\0"
        if (!isTiffHeaderValid(app1, tiffStart)) return null
        val isLE = app1[tiffStart] == 'I'.code.toByte()
        val newDateStr = EXIF_DATE_FMT.format(Date(newDateMillis))
        val newDateBytes = newDateStr.toByteArray(Charsets.US_ASCII)
        val ifd0Off = readInt(app1, tiffStart + 4, isLE)
        if (ifd0Off < 0) return null
        val subIfdOff = patchIfdDateTimes(app1, tiffStart, isLE, ifd0Off, newDateBytes)
        if (subIfdOff > 0) patchIfdDateTimes(app1, tiffStart, isLE, subIfdOff, newDateBytes)
        return Pair(app1Start, app1)
    }

    fun patchGps(fileHead: ByteArray, lat: Double, lng: Double): Pair<Int, ByteArray>? {
        if (!lat.isFinite() || !lng.isFinite() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        val (app1Start, segLen) = findApp1(fileHead) ?: return null
        val app1Total = 2 + segLen
        if (app1Start + app1Total > fileHead.size) return null
        val app1 = fileHead.copyOfRange(app1Start, app1Start + app1Total)
        val tiffStart = 10
        if (!isTiffHeaderValid(app1, tiffStart)) return null
        val isLE = app1[tiffStart] == 'I'.code.toByte()
        val ifd0Off = readInt(app1, tiffStart + 4, isLE)
        if (ifd0Off < 0) return null
        val absIfd0 = tiffStart + ifd0Off
        if (absIfd0 + 2 > app1.size) return null
        val count = readShort(app1, absIfd0, isLE)
        if (count !in 0..MAX_IFD_ENTRIES) return null
        for (e in 0 until count) {
            val entry = absIfd0 + 2 + e * 12
            if (entry + 12 > app1.size) break
            val tag  = readShort(app1, entry, isLE)
            val type = readShort(app1, entry + 2, isLE)
            if (tag == 0x8825 && type == 4) {
                val gpsIfdOff = readInt(app1, entry + 8, isLE)
                patchGpsIfd(app1, tiffStart, isLE, gpsIfdOff, lat, lng)
                break
            }
        }
        return Pair(app1Start, app1)
    }

    private fun findApp1(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 4 || bytes[0] != 0xFF.toByte() || bytes[1] != 0xD8.toByte()) return null
        var i = 2
        while (i < bytes.size - 3) {
            if (bytes[i] != 0xFF.toByte()) return null
            val marker = bytes[i + 1].toInt() and 0xFF
            val segLen = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (segLen < 2) return null
            val next = i + 2 + segLen
            if (next <= i || next > bytes.size) return null
            when (marker) {
                0xE1 -> {
                    if (i + 10 > bytes.size) return null
                    val hasExif = bytes[i + 4] == 'E'.code.toByte() &&
                        bytes[i + 5] == 'x'.code.toByte() &&
                        bytes[i + 6] == 'i'.code.toByte() &&
                        bytes[i + 7] == 'f'.code.toByte() &&
                        bytes[i + 8] == 0.toByte() &&
                        bytes[i + 9] == 0.toByte()
                    if (hasExif) return Pair(i, segLen)
                    i = next
                }
                0xDA, 0xD9 -> return null
                else -> i = next
            }
        }
        return null
    }

    private fun isTiffHeaderValid(app1: ByteArray, tiffStart: Int): Boolean {
        if (tiffStart < 0 || tiffStart + 8 > app1.size) return false
        val little = app1[tiffStart] == 'I'.code.toByte() && app1[tiffStart + 1] == 'I'.code.toByte() &&
            app1[tiffStart + 2] == 42.toByte() && app1[tiffStart + 3] == 0.toByte()
        val big = app1[tiffStart] == 'M'.code.toByte() && app1[tiffStart + 1] == 'M'.code.toByte() &&
            app1[tiffStart + 2] == 0.toByte() && app1[tiffStart + 3] == 42.toByte()
        return little || big
    }

    // Returns ExifSubIFD pointer offset (> 0) if found, else -1
    private fun patchIfdDateTimes(
        app1: ByteArray, tiffStart: Int, isLE: Boolean,
        ifdRelOffset: Int, newDateBytes: ByteArray
    ): Int {
        if (ifdRelOffset < 0) return -1
        val absIfd = tiffStart + ifdRelOffset
        if (absIfd + 2 > app1.size) return -1
        val count = readShort(app1, absIfd, isLE)
        if (count !in 0..MAX_IFD_ENTRIES) return -1
        var subIfd = -1
        for (e in 0 until count) {
            val entry = absIfd + 2 + e * 12
            if (entry + 12 > app1.size) break
            val tag   = readShort(app1, entry, isLE)
            val type  = readShort(app1, entry + 2, isLE)
            val cnt   = readInt(app1, entry + 4, isLE)
            if ((tag == 0x0132 || tag == 0x9003 || tag == 0x9004) && type == 2 && cnt == 20) {
                val valOff = readInt(app1, entry + 8, isLE)
                if (valOff < 0) continue
                val absVal = tiffStart + valOff
                if (absVal >= 0 && absVal + 20 <= app1.size) {
                    newDateBytes.copyInto(app1, absVal, 0, minOf(19, newDateBytes.size))
                    app1[absVal + 19] = 0
                }
            }
            if (tag == 0x8769 && type == 4) subIfd = readInt(app1, entry + 8, isLE)
        }
        return subIfd
    }

    private fun patchGpsIfd(
        app1: ByteArray, tiffStart: Int, isLE: Boolean, gpsIfdRelOff: Int,
        lat: Double, lng: Double
    ) {
        if (gpsIfdRelOff < 0) return
        val absGps = tiffStart + gpsIfdRelOff
        if (absGps + 2 > app1.size) return
        val count = readShort(app1, absGps, isLE)
        if (count !in 0..MAX_IFD_ENTRIES) return
        for (e in 0 until count) {
            val entry = absGps + 2 + e * 12
            if (entry + 12 > app1.size) break
            val tag   = readShort(app1, entry, isLE)
            val type  = readShort(app1, entry + 2, isLE)
            val cnt   = readInt(app1, entry + 4, isLE)
            when {
                tag == 0x0001 && type == 2 && cnt == 2 -> {
                    app1[entry + 8]  = (if (lat >= 0) 'N' else 'S').code.toByte()
                    app1[entry + 9]  = 0; app1[entry + 10] = 0; app1[entry + 11] = 0
                }
                tag == 0x0002 && type == 5 && cnt == 3 -> {
                    val absVal = tiffStart + readInt(app1, entry + 8, isLE)
                    if (absVal >= 0 && absVal + 24 <= app1.size) writeDms(app1, absVal, isLE, abs(lat))
                }
                tag == 0x0003 && type == 2 && cnt == 2 -> {
                    app1[entry + 8]  = (if (lng >= 0) 'E' else 'W').code.toByte()
                    app1[entry + 9]  = 0; app1[entry + 10] = 0; app1[entry + 11] = 0
                }
                tag == 0x0004 && type == 5 && cnt == 3 -> {
                    val absVal = tiffStart + readInt(app1, entry + 8, isLE)
                    if (absVal >= 0 && absVal + 24 <= app1.size) writeDms(app1, absVal, isLE, abs(lng))
                }
            }
        }
    }

    // Writes degrees/minutes/seconds as 3 rationals at the given offset
    private fun writeDms(bytes: ByteArray, offset: Int, isLE: Boolean, decimal: Double) {
        val deg = decimal.toInt()
        val minFloat = (decimal - deg) * 60.0
        val min = minFloat.toInt()
        val secNumerator = ((minFloat - min) * 60.0 * 10_000.0).toInt()
        writeInt(bytes, offset,      deg, isLE);  writeInt(bytes, offset + 4,  1, isLE)
        writeInt(bytes, offset + 8,  min, isLE);  writeInt(bytes, offset + 12, 1, isLE)
        writeInt(bytes, offset + 16, secNumerator, isLE); writeInt(bytes, offset + 20, 10_000, isLE)
    }

    private fun readShort(b: ByteArray, off: Int, le: Boolean): Int {
        if (off < 0 || off + 1 >= b.size) return 0
        val a = b[off].toInt() and 0xFF; val c = b[off + 1].toInt() and 0xFF
        return if (le) a or (c shl 8) else (a shl 8) or c
    }

    private fun readInt(b: ByteArray, off: Int, le: Boolean): Int {
        if (off < 0 || off + 3 >= b.size) return -1
        val b0 = b[off].toInt() and 0xFF; val b1 = b[off + 1].toInt() and 0xFF
        val b2 = b[off + 2].toInt() and 0xFF; val b3 = b[off + 3].toInt() and 0xFF
        return if (le) b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        else (b0 shl 24) or (b1 shl 16) or (b2 shl 8) or b3
    }

    private fun writeInt(b: ByteArray, off: Int, v: Int, le: Boolean) {
        if (off < 0 || off + 3 >= b.size) return
        if (le) {
            b[off]     = (v and 0xFF).toByte(); b[off + 1] = ((v shr 8) and 0xFF).toByte()
            b[off + 2] = ((v shr 16) and 0xFF).toByte(); b[off + 3] = ((v shr 24) and 0xFF).toByte()
        } else {
            b[off]     = ((v shr 24) and 0xFF).toByte(); b[off + 1] = ((v shr 16) and 0xFF).toByte()
            b[off + 2] = ((v shr 8) and 0xFF).toByte(); b[off + 3] = (v and 0xFF).toByte()
        }
    }

    private const val MAX_IFD_ENTRIES = 512
}
