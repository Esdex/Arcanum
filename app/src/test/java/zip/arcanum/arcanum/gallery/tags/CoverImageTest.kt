package zip.arcanum.arcanum.gallery.tags

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverImageTest {

    private fun segment(marker: Int, body: ByteArray) =
        byteArrayOf(0xFF.toByte(), marker.toByte(), ((body.size + 2) shr 8).toByte(), (body.size + 2).toByte()) + body

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val jfif = segment(0xE0, "JFIF\u0000".toByteArray() + ByteArray(9))
    private val table = segment(0xDB, ByteArray(65))
    // The scan's data may hold anything, an 0xFFE1 included: it is not a marker there.
    private val scan = segment(0xDA, ByteArray(10)) + byteArrayOf(1, 0xFF.toByte(), 0xE1.toByte(), 2, 0xFF.toByte(), 0xD9.toByte())

    @Test
    fun aJpegWithOnlyThePictureIsClean() {
        assertTrue(CoverImage.isCleanJpeg(soi + jfif + table + scan))
        // An ICC profile (APP2) is part of how the picture looks, not about where it was taken.
        assertTrue(CoverImage.isCleanJpeg(soi + jfif + segment(0xE2, ByteArray(30)) + scan))
    }

    @Test
    fun exifXmpOrACommentMakeItNotClean() {
        val exif = segment(0xE1, "Exif\u0000\u0000".toByteArray() + ByteArray(40))
        assertFalse(CoverImage.isCleanJpeg(soi + exif + table + scan))
        assertFalse(CoverImage.isCleanJpeg(soi + jfif + table + segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray()) + scan))
        assertFalse(CoverImage.isCleanJpeg(soi + jfif + segment(0xFE, "made by".toByteArray()) + scan))
    }

    @Test
    fun notAJpegOrACutOneIsNotClean() {
        assertFalse(CoverImage.isCleanJpeg(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())))
        assertFalse(CoverImage.isCleanJpeg(soi + jfif + table))   // no scan: cut short
        assertFalse(CoverImage.isCleanJpeg(soi + byteArrayOf(0, 0, 0, 0) + scan))
        // Well-formed segments behind something that is not a JPEG's start.
        assertFalse(CoverImage.isCleanJpeg(byteArrayOf(0, 0) + jfif + scan))
        // A byte that is not a marker where a marker has to be, with a length that would fit.
        assertFalse(CoverImage.isCleanJpeg(soi + byteArrayOf(0x12, 0x34, 0x00, 0x04, 1, 2) + scan))
    }
}
