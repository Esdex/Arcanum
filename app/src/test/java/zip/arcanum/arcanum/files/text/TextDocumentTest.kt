package zip.arcanum.arcanum.files.text

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

/**
 * What the editor is not allowed to change about a file it saves (#109).
 *
 * All four of these are invisible on screen - the encoding, the byte-order mark, the way lines
 * end, and whether the last one ends at all - and all four break something when they change
 * behind someone's back. They were verified once by hand, byte for byte, on a device; this is
 * what keeps them verified.
 */
class TextDocumentTest {

    private fun doc(bytes: ByteArray) = TextDocument.decode(bytes)

    @Test
    fun `an unedited file comes back byte for byte`() {
        val samples = listOf(
            "plain\nlines\n",
            "no trailing newline",
            "windows\r\nlines\r\n",
            "﻿with a bom\n"
        )
        for (text in samples) {
            val original = text.toByteArray()
            val d = doc(original)
            assertNotNull(text, d)
            assertArrayEquals(text, original, d!!.encode(d.text))
        }
    }

    @Test
    fun `CRLF survives an edit`() {
        val d = doc("first\r\nsecond\r\n".toByteArray())!!
        val out = String(d.encode(d.text + "third\n"))
        assertEquals("first\r\nsecond\r\nthird\r\n", out)
        assertFalse("no lone LF may be left", Regex("(?<!\r)\n").containsMatchIn(out))
    }

    @Test
    fun `a file without a final newline does not gain one`() {
        val d = doc("one line".toByteArray())!!
        assertFalse(d.trailingNewline)
        assertEquals("one line and more", String(d.encode("one line and more")))
    }

    @Test
    fun `a file with a final newline keeps it`() {
        val d = doc("one line\n".toByteArray())!!
        assertTrue(d.trailingNewline)
        assertEquals("edited\n", String(d.encode("edited")))
    }

    @Test
    fun `mixed line endings are reported and then unified`() {
        val d = doc("crlf\r\nlf\ncrlf\r\n".toByteArray())!!
        assertTrue("the editor must be able to say so", d.mixedNewlines)
        assertEquals("crlf\r\nlf\r\ncrlf\r\n", String(d.encode(d.text)))
    }

    @Test
    fun `a byte order mark is kept where it was`() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val d = doc(bom + "text\n".toByteArray())!!
        assertEquals("text\n", d.text)
        assertArrayEquals(bom + "text\n".toByteArray(), d.encode(d.text))
    }

    @Test
    fun `a file that is not UTF-8 round-trips unchanged`() {
        val cp1251 = Charset.forName("windows-1251")
        val original = "Этот файл в 1251\n".toByteArray(cp1251)
        val d = doc(original)!!
        assertTrue("it must know it did not understand the file", d.decodedAsFallback)
        assertArrayEquals(original, d.encode(d.text))
    }

    @Test
    fun `characters the file cannot hold are refused rather than written as question marks`() {
        val cp1251 = Charset.forName("windows-1251")
        val d = doc("текст\n".toByteArray(cp1251))!!
        // ASCII fits in Latin-1 and is allowed; anything else is not, and this is the case
        // that would otherwise be saved as "????" with a report of success.
        assertTrue(d.canEncode(d.text + "123"))
        assertFalse(d.canEncode(d.text + "новая строка"))
        // Converting is the way out, and then it holds.
        assertTrue(d.asUtf8().canEncode(d.text + "новая строка"))
    }

    @Test
    fun `binary is not offered to the editor`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
                              0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D)
        assertNull(doc(png))
    }

    @Test
    fun `an empty file is text`() {
        val d = doc(ByteArray(0))
        assertNotNull(d)
        assertEquals("", d!!.text)
        assertArrayEquals(ByteArray(0), d.encode(""))
    }
}
