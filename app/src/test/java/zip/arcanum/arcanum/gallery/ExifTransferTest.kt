package zip.arcanum.arcanum.gallery

import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.Metadata
import com.drew.metadata.exif.ExifIFD0Directory
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.ExifThumbnailDirectory
import com.drew.metadata.exif.GpsDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

/**
 * The editor re-encodes a picture from pixels, so whatever EXIF the original had has to be
 * put back by hand (see ExifTransfer). These check the bytes that come out of that.
 *
 * The reader here is metadata-extractor, a different implementation from the writer under
 * test, so agreement between them means something. Every output is also written to
 * build/exif-out for a second opinion from outside the JVM.
 *
 * The fixture is a JPEG made by Pillow carrying orientation 6, a date and a location.
 */
class ExifTransferTest {

    private val outDir = File("build/exif-out").apply { mkdirs() }

    private fun resource(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    private fun keep(name: String, bytes: ByteArray): ByteArray {
        File(outDir, name).writeBytes(bytes)
        return bytes
    }

    private fun read(bytes: ByteArray): Metadata =
        ImageMetadataReader.readMetadata(ByteArrayInputStream(bytes))

    private fun dateOf(md: Metadata): String? =
        md.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            ?.getString(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)

    private fun latOf(md: Metadata): Double? =
        md.getFirstDirectoryOfType(GpsDirectory::class.java)?.geoLocation?.latitude

    private fun lngOf(md: Metadata): Double? =
        md.getFirstDirectoryOfType(GpsDirectory::class.java)?.geoLocation?.longitude

    private fun orientationOf(md: Metadata): Int? =
        md.getFirstDirectoryOfType(ExifIFD0Directory::class.java)
            ?.getInteger(ExifIFD0Directory.TAG_ORIENTATION)

    // ── Lifting and retargeting ───────────────────────────────────────────

    @Test
    fun `a jpeg's exif block is lifted whole`() {
        val tiff = ExifTransfer.tiffFromJpeg(resource("exif-fixture.jpg"))
        assertNotNull("the fixture's APP1 was not found", tiff)
        // A TIFF header, either way round. This fixture is big-endian, which is what puts
        // retarget's other byte order through its paces; buildTiff below writes the little.
        val order = String(tiff!!, 0, 2, Charsets.US_ASCII)
        assertTrue("not a TIFF header: $order", order == "II" || order == "MM")
        val magic = if (order == "II") tiff[2].toInt() else tiff[3].toInt()
        assertEquals(42, magic)
    }

    @Test
    fun `a file with no exif yields nothing to carry`() {
        assertNull(ExifTransfer.tiffFromJpeg(resource("plain.jpg")))
        assertNull(ExifTransfer.buildTiff(null, null, null))
    }

    @Test
    fun `retarget rights the orientation, resizes and drops the old thumbnail`() {
        val tiff  = ExifTransfer.tiffFromJpeg(resource("exif-fixture.jpg"))!!
        val ready = ExifTransfer.retarget(tiff, 321, 123)
        val md    = read(keep("retargeted.jpg", ExifTransfer.intoJpeg(resource("plain.jpg"), ready)))

        // The pixels come out of the editor already turned, so the tag must not turn them again.
        assertEquals(1, orientationOf(md))
        val sub = md.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)!!
        assertEquals(321, sub.getInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_WIDTH))
        assertEquals(123, sub.getInt(ExifSubIFDDirectory.TAG_EXIF_IMAGE_HEIGHT))
        // Everything else about the shot survives.
        assertEquals("2021:07:04 12:34:56", dateOf(md))
        assertEquals("Arcanum", md.getFirstDirectoryOfType(ExifIFD0Directory::class.java)!!
            .getString(ExifIFD0Directory.TAG_MAKE))
        assertEquals(48.858, latOf(md)!!, 0.001)
        assertEquals(2.294,  lngOf(md)!!, 0.001)
        // The thumbnail inside was a picture of the photo before the edit.
        assertNull(md.getFirstDirectoryOfType(ExifThumbnailDirectory::class.java))
    }

    // ── Containers ────────────────────────────────────────────────────────

    @Test
    fun `exif goes into a jpeg`() {
        val tiff = ExifTransfer.retarget(ExifTransfer.tiffFromJpeg(resource("exif-fixture.jpg"))!!, 640, 480)
        val out  = keep("carried.jpg", ExifTransfer.intoJpeg(resource("plain.jpg"), tiff))
        val md   = read(out)
        assertEquals("2021:07:04 12:34:56", dateOf(md))
        assertEquals(48.858, latOf(md)!!, 0.001)
        // The picture itself is still there, untouched, after the segment that was put in front of it.
        assertTrue(out.size > resource("plain.jpg").size)
    }

    @Test
    fun `exif goes into a png`() {
        val tiff = ExifTransfer.retarget(ExifTransfer.tiffFromJpeg(resource("exif-fixture.jpg"))!!, 640, 480)
        val md   = read(keep("carried.png", ExifTransfer.intoPng(resource("plain.png"), tiff)))
        assertEquals("2021:07:04 12:34:56", dateOf(md))
        assertEquals(2.294, lngOf(md)!!, 0.001)
    }

    @Test
    fun `exif goes into a plain webp, which has to become an extended one`() {
        val tiff = ExifTransfer.retarget(ExifTransfer.tiffFromJpeg(resource("exif-fixture.jpg"))!!, 640, 480)
        val out  = keep("carried.webp", ExifTransfer.intoWebp(resource("plain.webp"), tiff, 640, 480))
        assertEquals("VP8X", String(out, 12, 4, Charsets.US_ASCII))
        assertTrue("the EXIF flag is not set", (out[20].toInt() and 0x08) != 0)
        val md = read(out)
        assertEquals("2021:07:04 12:34:56", dateOf(md))
        assertEquals(48.858, latOf(md)!!, 0.001)
    }

    @Test
    fun `exif goes into a webp that is already extended`() {
        val tiff = ExifTransfer.retarget(ExifTransfer.tiffFromJpeg(resource("exif-fixture.jpg"))!!, 640, 480)
        val src  = resource("plain-alpha.webp")
        val out  = keep("carried-alpha.webp", ExifTransfer.intoWebp(src, tiff, 640, 480))
        // Its own header is kept, alpha flag and all, with one more flag set.
        assertEquals(src[20].toInt() or 0x08, out[20].toInt())
        assertEquals("2021:07:04 12:34:56", dateOf(read(out)))
    }

    @Test
    fun `a container that is not what it claims is left alone`() {
        val tiff = ExifTransfer.buildTiff(0L, 1.0, 2.0)!!
        val junk = ByteArray(32) { 0x7F }
        assertTrue(ExifTransfer.intoJpeg(junk, tiff).contentEquals(junk))
        assertTrue(ExifTransfer.intoPng(junk, tiff).contentEquals(junk))
        assertTrue(ExifTransfer.intoWebp(junk, tiff, 10, 10).contentEquals(junk))
    }

    // ── The rebuilt block, for originals whose own cannot be lifted ───────

    @Test
    fun `a built block carries the date and the place`() {
        // 2021-07-04 12:34:56 UTC, written as local time the way EXIF does.
        val millis = java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", java.util.Locale.US)
            .parse("2021:07:04 12:34:56")!!.time
        val tiff = ExifTransfer.buildTiff(millis, 48.8584, 2.2945)!!
        val md   = read(keep("built.jpg", ExifTransfer.intoJpeg(resource("plain.jpg"), tiff)))
        assertEquals("2021:07:04 12:34:56", dateOf(md))
        assertEquals(48.8584, latOf(md)!!, 0.0005)
        assertEquals(2.2945,  lngOf(md)!!, 0.0005)
        assertEquals(1, orientationOf(md))
    }

    @Test
    fun `a built block with a place but no date is still valid`() {
        val tiff = ExifTransfer.buildTiff(null, -33.8568, 151.2153)!!
        val md   = read(keep("built-gps-only.jpg", ExifTransfer.intoJpeg(resource("plain.jpg"), tiff)))
        assertNull(dateOf(md))
        assertEquals(-33.8568, latOf(md)!!, 0.0005)
        assertEquals(151.2153, lngOf(md)!!, 0.0005)
    }
}
