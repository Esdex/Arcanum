package zip.arcanum.arcanum.gallery.tags

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import zip.arcanum.arcanum.gallery.tags.Id3v2Writer.CoverEdit
import zip.arcanum.arcanum.gallery.tags.Id3v2Writer.Values

class Id3v2WriterTest {

    private fun read(bytes: ByteArray) = Id3v2Tag.parse(bytes)!!
    private fun edit(tag: ByteArray, cover: CoverEdit = CoverEdit.Keep, change: (Values) -> Values): Id3v2Writer.Rendered {
        val old = read(tag)
        return Id3v2Writer.render(old, change(Id3v2Writer.valuesOf(old)), cover)
    }
    private fun bodyOf(bytes: ByteArray, id: String) = read(bytes).frame(id)!!.body

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 7)
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A, 0, 1)

    // ── Nothing changed is nothing touched ────────────────────────────────

    @Test
    fun anUneditedTagComesOutByteForByte() {
        // Two artists as two values, a genre by number, a rating: each would read back the same
        // if rewritten, and none of them may be.
        val original = tag(4,
            F("TPE1", text8("Nero", "Knife Party")), F("TCON", latin1("(127)")),
            F("POPM", byteArrayOf(0, 0x80.toByte()), flags = 0x2000), F("TIT2", text8("Promises")),   // "discard if the audio changes"
            padding = 300
        )
        val r = edit(original) { it }
        assertTrue(r.inPlace)
        assertArrayEquals(original.copyOf(original.size - 4), r.bytes)   // less the audio after it
    }

    @Test
    fun anEditedFieldIsRewrittenInItsPlaceAndTheOthersAreNot() {
        val popm = byteArrayOf(0, 0x80.toByte())
        val original = tag(3, F("TPE1", text16("Old artist")), F("TIT2", text16("Old")), F("POPM", popm), padding = 500)
        val r = edit(original) { it.copy(title = "Новое") }
        val t = read(r.bytes)
        assertEquals(listOf("TPE1", "TIT2", "POPM"), t.frames.map { it.id })
        assertEquals("Новое", t.title)
        assertArrayEquals(text16("Old artist"), t.frame("TPE1")!!.body)
        assertArrayEquals(popm, t.frame("POPM")!!.body)
    }

    // ── Encodings and sizes ───────────────────────────────────────────────

    @Test
    fun id3v23TextIsUtf16WithItsMarkAndId3v24TextIsUtf8() {
        val v3 = edit(tag(3, padding = 100)) { it.copy(title = "Тест") }
        assertArrayEquals(text16("Тест", terminated = false), bodyOf(v3.bytes, "TIT2"))
        assertEquals(3, v3.bytes[3].toInt())
        val v4 = edit(tag(4, padding = 100)) { it.copy(title = "Тест") }
        assertArrayEquals(text8("Тест"), bodyOf(v4.bytes, "TIT2"))
        assertEquals(4, v4.bytes[3].toInt())
    }

    @Test
    fun frameSizesAreWrittenTheWayEachVersionReadsThem() {
        // 200 bytes of value: syncsafe and plain differ there, and the frame after it is lost
        // if the size is written the wrong way.
        val long = "x".repeat(200)
        for (major in listOf(3, 4)) {
            val r = edit(tag(major, F("TPE1", latin1("after")))) { it.copy(title = long) }
            val t = read(r.bytes)
            assertEquals(long, t.title)
            assertEquals("after", t.artist)
        }
    }

    // ── Where the tag goes ────────────────────────────────────────────────

    @Test
    fun aTagThatFitsTheOldOneTakesItsExactSize() {
        val original = tag(3, F("TIT2", text16("A")), padding = 1000)
        val r = edit(original) { it.copy(title = "A longer title", artist = "Someone") }
        assertTrue(r.inPlace)
        assertEquals(read(original).totalSize, r.bytes.size)
        assertEquals("Someone", read(r.bytes).artist)
    }

    @Test
    fun aTagThatOutgrowsTheOldOneGetsFreshPadding() {
        val original = tag(3, F("TIT2", text16("A")), padding = 10)
        val r = edit(original) { it.copy(lyrics = "line\n".repeat(100)) }
        assertFalse(r.inPlace)
        assertEquals(Id3v2Writer.NEW_PADDING, read(r.bytes).paddingSize)
    }

    @Test
    fun theRoomOfARemovedLargeCoverIsGivenBack() {
        val big = jpeg + ByteArray(Id3v2Writer.MAX_KEPT_PADDING + 1)
        val original = tag(3, F("TIT2", text16("A")), F("APIC", picture(3, big)))
        val r = edit(original, CoverEdit.Remove) { it }
        assertFalse(r.inPlace)
        assertTrue(r.bytes.size < 10_000)
        assertNull(read(r.bytes).cover)
        // Smaller than that, the old room is kept and the audio does not move.
        val small = tag(3, F("TIT2", text16("A")), F("APIC", picture(3, jpeg + ByteArray(50_000))))
        assertTrue(edit(small, CoverEdit.Remove) { it }.inPlace)
    }

    @Test
    fun aFooterIsDroppedAndItsRoomGoesToThePadding() {
        val withFooter = tag(4, F("TIT2", text8("A")), padding = 0, flags = 0x10)
        val old = read(withFooter)
        assertEquals(withFooter.size - 4 + 10, old.totalSize)               // the footer is counted
        val r = Id3v2Writer.render(old, Id3v2Writer.valuesOf(old).copy(title = "B"))
        assertTrue(r.inPlace)
        assertEquals(old.totalSize, r.bytes.size)
        assertEquals(0, r.bytes[5].toInt())
    }

    @Test
    fun aFileWithoutATagGetsAnId3v23One() {
        val r = Id3v2Writer.render(null, Values(title = "T", artist = "A", year = "1999", trackNumber = "2", lyrics = "la"))
        assertFalse(r.inPlace)
        val t = read(r.bytes)
        assertEquals(3, t.majorVersion)
        assertEquals(listOf("TIT2", "TPE1", "TYER", "TRCK", "USLT"), t.frames.map { it.id })
        assertEquals("la", t.lyrics)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aTagItCannotRewriteIsRefused() {
        val compressed = tag(3, F("TIT2", text16("x"), flags = 0x0080))
        Id3v2Writer.render(read(compressed), Values(title = "y"))
    }

    // ── Fields with rules of their own ────────────────────────────────────

    @Test
    fun aClearedFieldLosesItsFrameAndDuplicatesGoWithIt() {
        val original = tag(3, F("TIT2", text16("one")), F("TALB", text16("Album")), F("TIT2", text16("two")), padding = 50)
        val t = read(edit(original) { it.copy(title = "  ", album = "New") }.bytes)
        assertEquals(listOf("TALB"), t.frames.map { it.id })
        // and one edited value replaces both copies, in the place of the first
        val t2 = read(edit(original) { it.copy(title = "three") }.bytes)
        assertEquals(listOf("TIT2", "TALB"), t2.frames.map { it.id })
        assertEquals("three", t2.title)
    }

    @Test
    fun theYearGoesIntoTyerAndTdrcAlikeWhereverTheyAre() {
        // ffmpeg's ID3v2.3: both frames.
        val both = read(edit(tag(3, F("TYER", text16("2023")), F("TDRC", latin1("2023-01-27")), padding = 100)) { it.copy(year = "2024-05-01") }.bytes)
        assertEquals("2024", both.text("TYER"))
        assertEquals("2024-05-01", both.text("TDRC"))
        // ID3v2.3 with a year only: a whole date adds TDRC, a year does not.
        val plain = tag(3, F("TYER", text16("2000")), padding = 100)
        assertNull(read(edit(plain) { it.copy(year = "2001") }.bytes).text("TDRC"))
        val dated = read(edit(plain) { it.copy(year = "2001-02-03") }.bytes)
        assertEquals("2001", dated.text("TYER"))
        assertEquals("2001-02-03", dated.text("TDRC"))
        // ID3v2.4: TDRC, and no TYER where there was none.
        val v4 = read(edit(tag(4, F("TDRC", text8("2010")), padding = 100)) { it.copy(year = "2011") }.bytes)
        assertEquals(listOf("TDRC"), v4.frames.map { it.id })
        assertEquals("2011", v4.year)
        // Cleared, both go.
        assertTrue(read(edit(tag(3, F("TYER", text16("2023")), F("TDRC", latin1("2023-01-27")))) { it.copy(year = null) }.bytes).frames.isEmpty())
    }

    @Test
    fun theTrackNumberKeepsTheAlbumsTotal() {
        val original = tag(3, F("TRCK", text16("3/12")), padding = 50)
        assertEquals("4/12", read(edit(original) { it.copy(trackNumber = "4") }.bytes).text("TRCK"))
        assertEquals("4/10", read(edit(original) { it.copy(trackNumber = "4/10") }.bytes).text("TRCK"))
        assertEquals("1", read(edit(tag(3, F("TRCK", text16("3")))) { it.copy(trackNumber = "1") }.bytes).text("TRCK"))
    }

    @Test
    fun editedLyricsReplaceTheOnesShownAndKeepTheirLanguage() {
        val original = tag(3,
            F("USLT", lyrics16("eng", "verse", "Old line")),
            F("USLT", lyrics16("rus", "", "Строка")),
            padding = 200
        )
        val r = edit(original) { it.copy(lyrics = "New line\r\nНовая строка") }
        val t = read(r.bytes)
        assertEquals("New line\nНовая строка", t.lyrics)
        val uslt = t.frames.filter { it.id == "USLT" }
        assertEquals(2, uslt.size)
        assertArrayEquals(lyrics16("eng", "verse", "New line\nНовая строка"), uslt[0].body)
        assertArrayEquals(lyrics16("rus", "", "Строка"), uslt[1].body)                  // untouched
        // The only lyrics in another language stay in it.
        val russian = read(edit(tag(3, F("USLT", lyrics16("rus", "", "Старая")), padding = 100)) { it.copy(lyrics = "Новая") }.bytes)
        assertArrayEquals(lyrics16("rus", "", "Новая"), russian.frame("USLT")!!.body)
        // None before: English, no description, as AIMP writes it - in UTF-8 for ID3v2.4.
        val v4 = read(edit(tag(4, padding = 100)) { it.copy(lyrics = "Ля") }.bytes)
        assertArrayEquals(byteArrayOf(3) + "eng".toByteArray() + byteArrayOf(0) + "Ля".toByteArray(), v4.frame("USLT")!!.body)
    }

    @Test
    fun aNewCoverTakesTheShownOnesPlaceAsTheFrontCover() {
        val back = jpeg + byteArrayOf(9)
        val original = tag(3, F("APIC", picture(3, jpeg, "Обложка", enc = 1)), F("APIC", picture(4, back)), padding = 100)
        val t = read(edit(original, CoverEdit.Replace(png)) { it }.bytes)
        val apic = t.frames.filter { it.id == "APIC" }
        assertEquals(2, apic.size)
        assertArrayEquals(png, t.cover)
        assertArrayEquals(byteArrayOf(0) + "image/png".toByteArray() + byteArrayOf(0, 3, 0) + png, apic[0].body)
        assertArrayEquals(picture(4, back), apic[1].body)
        // A file without a picture gets one; a JPEG says so.
        val added = read(edit(tag(4, padding = 100), CoverEdit.Replace(jpeg)) { it }.bytes)
        assertArrayEquals(picture(3, jpeg), added.frame("APIC")!!.body)
    }

    @Test(expected = IllegalArgumentException::class)
    fun aCoverThatIsNeitherJpegNorPngIsRefused() {
        edit(tag(3, padding = 100), CoverEdit.Replace("GIF89a....".toByteArray())) { it }
    }
}
