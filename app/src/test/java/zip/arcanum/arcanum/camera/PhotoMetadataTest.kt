package zip.arcanum.arcanum.camera

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import zip.arcanum.arcanum.gallery.ExifReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Calendar

class PhotoMetadataTest {

    // ── A camera's JPEG, built byte by byte ───────────────────────────────

    private fun segment(marker: Int, body: ByteArray) =
        byteArrayOf(0xFF.toByte(), marker.toByte(), ((body.size + 2) shr 8).toByte(), (body.size + 2).toByte()) + body

    /** Big-endian EXIF, as this phone's camera writes it: make, model, orientation 6 and a GPS block. */
    private fun cameraExif(): ByteArray {
        val t = ByteArrayOutputStream()
        fun u16(v: Int) { t.write(v shr 8 and 0xFF); t.write(v and 0xFF) }
        fun u32(v: Int) { u16(v ushr 16); u16(v and 0xFFFF) }
        val make = "Nothing\u0000".toByteArray()
        val model = "A063\u0000".toByteArray()
        val ifd0 = 8
        val dataAt = ifd0 + 2 + 4 * 12 + 4
        val gpsAt = dataAt + make.size + model.size
        t.write("MM".toByteArray()); u16(0x2A); u32(ifd0)
        u16(4)
        u16(0x010F); u16(2); u32(make.size); u32(dataAt)                 // Make
        u16(0x0110); u16(2); u32(model.size); u32(dataAt + make.size)    // Model
        u16(0x0112); u16(3); u32(1); u16(6); u16(0)                     // Orientation
        u16(0x8825); u16(4); u32(1); u32(gpsAt)                         // GPS IFD
        u32(0)
        t.write(make); t.write(model)
        u16(1)
        u16(0x0001); u16(2); u32(2); t.write('N'.code); t.write(0); u16(0)   // GPSLatitudeRef "N"
        u32(0)
        return segment(0xE1, "Exif\u0000\u0000".toByteArray() + t.toByteArray())
    }

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val app0 = segment(0xE0, "JFIF\u0000".toByteArray() + byteArrayOf(1, 1, 0, 0, 1, 0, 1, 0, 0))
    private val xmp = segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>".toByteArray())
    private val icc = segment(0xE2, "ICC_PROFILE\u0000".toByteArray() + byteArrayOf(1, 1, 9, 9))
    private val mpf = segment(0xE2, "MPF\u0000".toByteArray() + ByteArray(8))
    private val photoshop = segment(0xED, "Photoshop 3.0\u0000".toByteArray() + ByteArray(4))
    private val comment = segment(0xFE, "shot on a Nothing Phone".toByteArray())
    private val dqt = segment(0xDB, ByteArray(65) { it.toByte() })
    private val sof = segment(0xC0, byteArrayOf(8, 0, 16, 0, 16, 1, 1, 0x11, 0))
    private val dht = segment(0xC4, ByteArray(29))
    // An FF in the scan is stuffed with 00, and a restart marker may sit in it; neither ends it.
    private val scan = segment(0xDA, byteArrayOf(1, 1, 0, 0, 0x3F, 0)) +
        byteArrayOf(0x12, 0xFF.toByte(), 0, 0x34, 0xFF.toByte(), 0xD0.toByte(), 0x56, 0xFF.toByte(), 0xD9.toByte())
    private val trailer = "appended after the image".toByteArray()

    private fun cameraJpeg() = soi + app0 + cameraExif() + xmp + icc + mpf + photoshop + comment + dqt + sof + dht + scan + trailer

    private val takenAt = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 23, 20, 5, 11) }.time

    private fun clean() = PhotoMetadata.keepOnly(cameraJpeg(), 6, takenAt)!!

    private fun ByteArray.contains(part: ByteArray) =
        (0..size - part.size).any { i -> part.indices.all { this[i + it] == part[it] } }

    // ── What goes and what stays ──────────────────────────────────────────

    @Test
    fun theFixtureHasWhatTheCameraWrites() {
        // Otherwise "gone" below would prove nothing.
        val md = ImageMetadataReader.readMetadata(ByteArrayInputStream(cameraJpeg()))
        val ifd0 = md.getFirstDirectoryOfType(ExifIFD0Directory::class.java)!!
        assertEquals("Nothing", ifd0.getString(ExifIFD0Directory.TAG_MAKE))
        assertEquals("A063", ifd0.getString(ExifIFD0Directory.TAG_MODEL))
        assertEquals("N", md.getFirstDirectoryOfType(GpsDirectory::class.java)!!.getString(GpsDirectory.TAG_LATITUDE_REF))
    }

    @Test
    fun theMakeTheModelAndTheLocationAreGone() {
        val md = ImageMetadataReader.readMetadata(ByteArrayInputStream(clean()))
        val ifd0 = md.getFirstDirectoryOfType(ExifIFD0Directory::class.java)!!
        assertFalse(ifd0.containsTag(ExifIFD0Directory.TAG_MAKE))
        assertFalse(ifd0.containsTag(ExifIFD0Directory.TAG_MODEL))
        assertNull(md.getFirstDirectoryOfType(GpsDirectory::class.java))
        assertFalse(clean().contains("Nothing".toByteArray()))
        assertFalse(clean().contains("A063".toByteArray()))
    }

    @Test
    fun theOrientationAndTheDateTakenAreKept() {
        val md = ImageMetadataReader.readMetadata(ByteArrayInputStream(clean()))
        assertEquals(6, md.getFirstDirectoryOfType(ExifIFD0Directory::class.java)!!.getInt(ExifIFD0Directory.TAG_ORIENTATION))
        assertEquals("2026:10:23 20:05:11",
            md.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)!!.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL))
        assertEquals("2026:10:23 20:05:11",
            md.getFirstDirectoryOfType(ExifIFD0Directory::class.java)!!.getString(ExifIFD0Directory.TAG_DATETIME))
        // and the gallery's own reader sees the same
        assertEquals(6, ExifReader().readOrientation(clean()))
    }

    @Test
    fun xmpCommentsAndOtherBlocksAreDroppedButTheColourInformationIsNot() {
        val c = clean()
        assertFalse(c.contains(xmp))
        assertFalse(c.contains(mpf))
        assertFalse(c.contains(photoshop))
        assertFalse(c.contains(comment))
        assertTrue(c.contains(app0))
        assertTrue(c.contains(icc))
    }

    @Test
    fun thePictureIsCopiedByteForByteAndEndsWhereTheImageEnds() {
        val c = clean()
        val picture = dqt + sof + dht + scan
        assertArrayEquals(picture, c.copyOfRange(c.size - picture.size, c.size))
        assertFalse(c.contains(trailer))
        // The new EXIF comes straight after the start of the image.
        assertEquals(0xE1, c[3].toInt() and 0xFF)
    }

    @Test
    fun whatIsNotAWholeJpegIsRefused() {
        assertNull(PhotoMetadata.keepOnly("not a picture".toByteArray(), 1, takenAt))
        assertNull(PhotoMetadata.keepOnly(soi + app0 + dqt, 1, takenAt))                       // no scan
        assertNull(PhotoMetadata.keepOnly(soi + app0 + dqt + sof + segment(0xDA, ByteArray(6)) + byteArrayOf(1, 2), 1, takenAt)) // no end
        assertNull(PhotoMetadata.keepOnly(soi + byteArrayOf(0xFF.toByte(), 0xE1.toByte(), 0x7F, 0x00) + dqt, 1, takenAt))   // runs past the end
        assertNull(PhotoMetadata.keepOnly(soi + byteArrayOf(0xFF.toByte(), 0xDB.toByte(), 0x7F, 0x00) + ByteArray(10), 1, takenAt)) // a kept one too
        // Well-formed segments behind something that is not the start of a JPEG.
        assertNull(PhotoMetadata.keepOnly(byteArrayOf(0, 0) + app0 + dqt + sof + dht + scan, 1, takenAt))
    }
}
