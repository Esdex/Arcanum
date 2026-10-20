package zip.arcanum.arcanum.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class ExifReaderTest {

    private fun segment(marker: Int, body: ByteArray) =
        byteArrayOf(0xFF.toByte(), marker.toByte(), ((body.size + 2) shr 8).toByte(), (body.size + 2).toByte()) + body

    /** An EXIF block holding one tag, the orientation, little-endian as phones write it. */
    private fun exif(orientation: Int) = segment(0xE1,
        "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 0x2A, 0, 8, 0, 0, 0) +       // header, IFD0 at 8
            byteArrayOf(1, 0) +                                                             // one entry
            byteArrayOf(0x12, 0x01, 3, 0, 1, 0, 0, 0, orientation.toByte(), 0, 0, 0) +      // 0x0112 SHORT x1
            byteArrayOf(0, 0, 0, 0)                                                         // no next IFD
    )

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val jfif = segment(0xE0, "JFIF\u0000".toByteArray() + ByteArray(9))
    private val rest = segment(0xDB, ByteArray(65)) + segment(0xDA, ByteArray(10)) + ByteArray(500) +
        byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    @Test
    fun theOrientationOfAJpegIsRead() {
        // It used to come back as 1 for every JPEG with EXIF: the EXIF block was cut out on
        // its own, without an end, and the library gave up on it - so a portrait photo from a
        // phone that stores it turned on its side lay on its side in the gallery.
        assertEquals(6, ExifReader().readOrientation(soi + jfif + exif(6) + rest))
        assertEquals(8, ExifReader().readOrientation(soi + exif(8) + rest))
    }

    @Test
    fun theFirstBytesOfAFileAreEnough() {
        // The gallery reads 64 KB, which ends in the middle of the picture.
        val whole = soi + jfif + exif(6) + rest
        assertEquals(6, ExifReader().readOrientation(whole.copyOf(whole.size - 300)))
    }

    @Test
    fun otherApp1BlocksBeforeTheExifAreSkipped() {
        // XMP, and the dozens of depth-map blocks a Nothing Phone writes, are APP1 as well.
        val xmp = segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x/>".toByteArray())
        assertEquals(3, ExifReader().readOrientation(soi + xmp + exif(3) + rest))
    }

    @Test
    fun noExifIsUpright() {
        assertEquals(1, ExifReader().readOrientation(soi + jfif + rest))
        assertEquals(1, ExifReader().readOrientation(ByteArray(0)))
    }
}
