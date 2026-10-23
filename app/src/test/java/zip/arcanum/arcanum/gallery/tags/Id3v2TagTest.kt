package zip.arcanum.arcanum.gallery.tags

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class Id3v2TagTest {

    // ── Text ──────────────────────────────────────────────────────────────

    @Test
    fun readsUtf16TextWithAndWithoutTheTerminatorAimpLeavesOff() {
        val t = Id3v2Tag.parse(tag(3, F("TIT2", text16("Тест", terminated = false)), F("TPE1", text16("CHMCL SØUP"))))!!
        assertEquals("Тест", t.title)
        assertEquals("CHMCL SØUP", t.artist)
    }

    @Test
    fun readsUtf8AndLatin1AndJoinsSeveralValues() {
        val t = Id3v2Tag.parse(tag(4, F("TPE1", text8("Nero", "Knife Party")), F("TALB", latin1("Crush"))))!!
        assertEquals("Nero; Knife Party", t.artist)
        assertEquals("Crush", t.album)
    }

    @Test
    fun theFullDateWinsOverTheYearAndTheTrackLosesItsTotal() {
        val both = Id3v2Tag.parse(tag(3, F("TYER", text16("2023")), F("TDRC", latin1("2023-01-27")), F("TRCK", text16("3/4"))))!!
        assertEquals("2023-01-27", both.year)
        assertEquals("3", both.trackNumber)
        assertEquals("2000", Id3v2Tag.parse(tag(3, F("TYER", text16("2000"))))!!.year)
    }

    @Test
    fun genreNumbersFromId3v1AreLookedUp() {
        assertEquals("Rock", Id3v2Tag.resolveGenre("(17)"))
        assertEquals("Pop", Id3v2Tag.resolveGenre("13"))
        assertEquals("Rock music", Id3v2Tag.resolveGenre("(17)Rock music"))
        assertEquals("Phonk", Id3v2Tag.resolveGenre("Phonk"))
        assertEquals("(250)", Id3v2Tag.resolveGenre("(250)"))
        // Winamp's extensions, found in the corpus as (127) and (135).
        assertEquals("Drum & Bass", Id3v2Tag.resolveGenre("(127)"))
        assertEquals("Beat", Id3v2Tag.resolveGenre("(135)"))
        assertEquals("Psybient", Id3v2Tag.resolveGenre("(191)"))
    }

    // ── Lyrics and pictures ───────────────────────────────────────────────

    @Test
    fun englishLyricsArePreferredAndTheDescriptionIsSkipped() {
        val t = Id3v2Tag.parse(tag(3,
            F("USLT", lyrics16("rus", "", "Строка один")),
            F("USLT", lyrics16("eng", "verse", "Line one\nСтрока два")),
            F("TEXT", text16("Автор"))
        ))!!
        assertEquals("Line one\nСтрока два", t.lyrics)
        assertEquals("Автор", t.lyricist)
    }

    @Test
    fun theFrontCoverIsPreferredAndItsBytesComeOutWhole() {
        val front = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 0, 2, 0, 0, 3)   // zeros in the image itself
        val back = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 9)
        val t = Id3v2Tag.parse(tag(3, F("APIC", picture(4, back)), F("APIC", picture(3, front, "Обложка", enc = 1))))!!
        assertArrayEquals(front, t.cover)
        assertArrayEquals(back, Id3v2Tag.parse(tag(3, F("APIC", picture(4, back))))!!.cover)
    }

    // ── Structure ─────────────────────────────────────────────────────────

    @Test
    fun framesItDoesNotShowAreKeptByteForByteAndThePaddingIsMeasured() {
        val popm = byteArrayOf(0, 0x80.toByte())
        val bytes = tag(3, F("TIT2", text16("A")), F("POPM", popm), padding = 6504)
        val t = Id3v2Tag.parse(bytes)!!
        assertEquals(listOf("TIT2", "POPM"), t.frames.map { it.id })
        assertArrayEquals(popm, t.frame("POPM")!!.body)
        assertEquals(6504, t.paddingSize)
        assertEquals(bytes.size - 4, t.totalSize)                       // the audio frame is not the tag's
        assertEquals(t.totalSize, Id3v2Tag.sizeFromHeader(bytes.copyOf(10)))
        assertTrue(t.rewritable)
    }

    @Test
    fun id3v24FrameSizesAreSyncsafeAndId3v23OnesAreNot() {
        // 200 bytes: 0x000000C8 plain, 0x00000148 syncsafe - reading one as the other loses the frame.
        val long = "x".repeat(199)
        assertEquals(long, Id3v2Tag.parse(tag(4, F("TIT2", text8(long)), F("TPE1", text8("after"))))!!.title)
        assertEquals("after", Id3v2Tag.parse(tag(4, F("TIT2", text8(long)), F("TPE1", text8("after"))))!!.artist)
        assertEquals("after", Id3v2Tag.parse(tag(3, F("TIT2", latin1(long)), F("TPE1", latin1("after"))))!!.artist)
    }

    @Test
    fun anUnsynchronisedTagIsReadButNotRewritten() {
        // Unsynchronised, the 0xFF of the BOM is followed by a 0x00 that is not part of the text.
        val plain = tag(3, F("TIT2", text16("Ok")))
        val body = plain.copyOfRange(10, plain.size - 4)
        val out = ByteArrayOutputStream()
        for (b in body) { out.write(b.toInt()); if (b == 0xFF.toByte()) out.write(0) }
        val unsync = out.toByteArray()
        val bytes = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0x80.toByte()) + syncsafe(unsync.size) + unsync
        val t = Id3v2Tag.parse(bytes)!!
        assertEquals("Ok", t.title)
        assertFalse(t.rewritable)
    }

    @Test
    fun anExtendedHeaderIsSkippedAndKeepsTheTagFromBeingRewritten() {
        val ext = be32(6) + ByteArray(6)                                   // v2.3: size excludes itself
        val t = Id3v2Tag.parse(tag(3, F("TIT2", text16("Ok")), flags = 0x40, extended = ext))!!
        assertEquals("Ok", t.title)
        assertFalse(t.rewritable)
    }

    @Test
    fun aCompressedFrameIsKeptButNeverShown() {
        val t = Id3v2Tag.parse(tag(3, F("TIT2", text16("hidden"), flags = 0x0080), F("TPE1", text16("shown"))))!!
        assertNull(t.title)
        assertEquals("shown", t.artist)
        assertEquals(2, t.frames.size)
        assertFalse(t.rewritable)
    }

    @Test
    fun notATagOrAnUnreadableOneIsNothing() {
        assertNull(Id3v2Tag.parse(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)))
        assertNull(Id3v2Tag.parse(tag(2, F("TT2", latin1("v2.2")))))      // three-letter frames: not read
        assertNull(Id3v2Tag.parse(tag(3, F("TIT2", text16("cut"))).copyOf(14)))
        assertEquals(0, Id3v2Tag.sizeFromHeader(ByteArray(10)))
    }

    // ── Malformed tags: never an exception, at worst nothing ──────────────

    @Test
    fun aFrameSizeNearTheLargestIntIsAnEndNotACrash() {
        // start + size would overflow and pass a naive bounds check.
        val ok = tag(3, F("TIT2", text16("Kept")))
        val bad = ok.copyOfRange(0, ok.size - 4) + "TPE1".toByteArray() + be32(0x7FFFFFF0) + byteArrayOf(0, 0, 1, 2, 3)
        val fixed = bad.copyOf().also { val n = bad.size - 10; syncsafe(n).copyInto(it, 6) }
        val t = Id3v2Tag.parse(fixed)!!
        assertEquals("Kept", t.title)
        assertFalse(t.rewritable)
    }

    @Test
    fun anExtendedHeaderThatDoesNotFitIsNoTag() {
        // The flag set over an empty body, and sizes that point before or past the tag.
        assertNull(Id3v2Tag.parse(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 4, 0, 0x40, 0, 0, 0, 0)))
        assertNull(Id3v2Tag.parse(tag(3, F("TIT2", text16("x")), flags = 0x40, extended = be32(-8) + ByteArray(4))))
        assertNull(Id3v2Tag.parse(tag(3, F("TIT2", text16("x")), flags = 0x40, extended = be32(10_000) + ByteArray(4))))
    }
}
