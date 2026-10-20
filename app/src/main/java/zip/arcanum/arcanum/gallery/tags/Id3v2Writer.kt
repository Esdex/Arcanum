package zip.arcanum.arcanum.gallery.tags

import java.io.ByteArrayOutputStream

/**
 * Builds the ID3v2 tag for a track after File info has edited it.
 *
 * Only what was changed is rewritten. Every other frame goes back exactly as it was read and
 * where it was, so a field nobody touched comes out byte for byte - several artists still
 * separate values, a genre still its ID3v1 number, a rating and a comment still there.
 *
 * The tag keeps its version. AIMP turns ID3v2.4 into ID3v2.3 and copies the UTF-8 frames
 * across unchanged, which ID3v2.3 has no encoding for; this does not. New text is UTF-16 with
 * a byte order mark in ID3v2.3 and UTF-8 in ID3v2.4, the widest each version has.
 *
 * Only builds the bytes: putting them into the file is the caller's.
 */
object Id3v2Writer {

    /** The fields File info shows. Null or blank is no value: the frame is left out. */
    data class Values(
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
        val albumArtist: String? = null,
        val genre: String? = null,
        val year: String? = null,
        val trackNumber: String? = null,
        val lyricist: String? = null,
        val lyrics: String? = null
    )

    sealed interface CoverEdit {
        data object Keep : CoverEdit
        data object Remove : CoverEdit
        /** A JPEG or PNG, stored as given. */
        class Replace(val image: ByteArray) : CoverEdit
    }

    /**
     * A built tag. [inPlace] when it is exactly as long as the old one, so it can be written over
     * it and the audio stays where it is; otherwise the audio has to move and the file is rewritten.
     */
    class Rendered(val bytes: ByteArray, val inPlace: Boolean)

    /** What File info shows for a tag, the same accessors it reads; all empty for no tag. */
    fun valuesOf(tag: Id3v2Tag?) = if (tag == null) Values() else Values(
        title = tag.title, artist = tag.artist, album = tag.album, albumArtist = tag.albumArtist,
        genre = tag.genre, year = tag.year, trackNumber = tag.trackNumber,
        lyricist = tag.lyricist, lyrics = tag.lyrics
    )

    /**
     * The tag [old] becomes with [edited] - or a new ID3v2.3 tag, for a file that has none.
     * [old] has to be [Id3v2Tag.rewritable].
     */
    fun render(old: Id3v2Tag?, edited: Values, cover: CoverEdit = CoverEdit.Keep): Rendered {
        require(old == null || old.rewritable) { "the tag uses what this writer does not write" }
        val major = old?.majorVersion ?: 3
        val before = valuesOf(old)
        val frames = old?.frames?.toMutableList() ?: mutableListOf()

        fun changed(a: String?, b: String?) = clean(a) != clean(b)
        fun setText(id: String, value: String?) = put(frames, id, clean(value)?.let { textBody(major, it) })

        if (changed(before.title, edited.title)) setText("TIT2", edited.title)
        if (changed(before.artist, edited.artist)) setText("TPE1", edited.artist)
        if (changed(before.album, edited.album)) setText("TALB", edited.album)
        if (changed(before.albumArtist, edited.albumArtist)) setText("TPE2", edited.albumArtist)
        if (changed(before.genre, edited.genre)) setText("TCON", edited.genre)
        if (changed(before.lyricist, edited.lyricist)) setText("TEXT", edited.lyricist)

        if (changed(before.year, edited.year)) {
            // TYER is ID3v2.3's year and holds four digits; TDRC is ID3v2.4's whole date, which
            // ffmpeg writes into ID3v2.3 tags as well. Both are kept up to date where they are,
            // so no reader is left with the old year.
            val date = clean(edited.year)
            val year = date?.let { Regex("""^\d{4}""").find(it)?.value ?: it }
            val hasTyer = frames.any { it.id == "TYER" }
            val hasTdrc = frames.any { it.id == "TDRC" }
            if (major == 3) {
                setText("TYER", year)
                if (hasTdrc || date != year) setText("TDRC", date)
            } else {
                setText("TDRC", date)
                if (hasTyer) setText("TYER", year)
            }
        }

        if (changed(before.trackNumber, edited.trackNumber)) {
            // The screen shows "3" for "3/12"; the total is the album's, and stays.
            val number = clean(edited.trackNumber)
            val total = old?.text("TRCK")?.substringAfter('/', "")?.trim().orEmpty()
            setText("TRCK", number?.let { if (total.isNotEmpty() && '/' !in it) "$it/$total" else it })
        }

        if (changed(before.lyrics, edited.lyrics)) {
            val shown = old?.lyricsFrame
            val was = shown?.let { Id3v2Tag.parseLyrics(it.content) }
            val text = clean(edited.lyrics)?.replace("\r\n", "\n")
            replace(frames, shown, "USLT", text?.let { lyricsBody(major, was?.language ?: "eng", was?.description.orEmpty(), it) })
        }

        when (cover) {
            CoverEdit.Keep -> Unit
            CoverEdit.Remove -> replace(frames, old?.coverFrame, "APIC", null)
            is CoverEdit.Replace -> replace(frames, old?.coverFrame, "APIC", pictureBody(cover.image))
        }

        return assemble(major, frames, old?.totalSize)
    }

    /** Frames, then padding, behind a fresh header. */
    private fun assemble(major: Int, frames: List<Id3v2Tag.Frame>, oldTotal: Int?): Rendered {
        val body = ByteArrayOutputStream()
        for (f in frames) {
            body.write(f.id.toByteArray(Charsets.ISO_8859_1))
            body.write(if (major == 4) syncsafe(f.body.size) else be32(f.body.size))
            body.write(f.flags shr 8 and 0xFF)
            body.write(f.flags and 0xFF)
            body.write(f.body)
        }
        val used = body.size()
        // The old tag's room: what lay between its header and the audio, its footer included -
        // this writes no footer, and the padding takes the footer's place.
        val room = oldTotal?.minus(Id3v2Tag.HEADER_SIZE)
        val inPlace = room != null && used <= room && room - used <= MAX_KEPT_PADDING
        val padding = if (inPlace) room!! - used else NEW_PADDING
        val size = used + padding
        require(size < 1 shl 28) { "an ID3v2 tag holds at most 256 MB" }

        val out = ByteArrayOutputStream(Id3v2Tag.HEADER_SIZE + size)
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), major.toByte(), 0, 0))
        out.write(syncsafe(size))
        body.writeTo(out)
        out.write(ByteArray(padding))
        return Rendered(out.toByteArray(), inPlace)
    }

    /** Puts [body] where the first [id] frame was and drops the rest of them; no body, no frame. */
    private fun put(frames: MutableList<Id3v2Tag.Frame>, id: String, body: ByteArray?) {
        val at = frames.indexOfFirst { it.id == id }
        frames.removeAll { it.id == id }
        if (body != null) frames.add(if (at >= 0) at else frames.size, Id3v2Tag.Frame(id, 0, body))
    }

    /** Puts [body] in [shown]'s place, or at the end when nothing was shown; the other [id] frames stay. */
    private fun replace(frames: MutableList<Id3v2Tag.Frame>, shown: Id3v2Tag.Frame?, id: String, body: ByteArray?) {
        val at = frames.indexOfFirst { it === shown }
        if (at >= 0) frames.removeAt(at)
        if (body != null) frames.add(if (at >= 0) at else frames.size, Id3v2Tag.Frame(id, 0, body))
    }

    private fun clean(s: String?) = s?.trim()?.ifEmpty { null }

    /**
     * An encoding byte, then the string. No terminator: the frame's size ends it, and that is
     * how AIMP writes it too.
     */
    private fun textBody(major: Int, s: String) = byteArrayOf(encoding(major)) + encode(major, s)

    /** Encoding, language, a description that ends in a terminator, then the lyrics. */
    private fun lyricsBody(major: Int, language: String, description: String, text: String): ByteArray {
        val lang = language.toByteArray(Charsets.ISO_8859_1).copyOf(3)
        val terminator = if (major == 4) byteArrayOf(0) else byteArrayOf(0, 0)
        return byteArrayOf(encoding(major)) + lang + encode(major, description) + terminator + encode(major, text)
    }

    /**
     * A front cover with no description, so the encoding byte is Latin-1's and nothing hangs
     * on it; the MIME type is Latin-1 in every version.
     */
    private fun pictureBody(image: ByteArray): ByteArray {
        val mime = when {
            image.size > 3 && image[0] == 0xFF.toByte() && image[1] == 0xD8.toByte() -> "image/jpeg"
            image.size > 8 && image.copyOf(8).contentEquals(PNG_SIGNATURE) -> "image/png"
            else -> throw IllegalArgumentException("a cover is a JPEG or a PNG")
        }
        return byteArrayOf(0) + mime.toByteArray(Charsets.ISO_8859_1) +
            byteArrayOf(0, Id3v2Tag.PICTURE_FRONT_COVER.toByte(), 0) + image
    }

    private fun encoding(major: Int): Byte = if (major == 4) 3 else 1

    /** UTF-8 in ID3v2.4; UTF-16 little-endian with its byte order mark in ID3v2.3 - even when empty. */
    private fun encode(major: Int, s: String): ByteArray =
        if (major == 4) s.toByteArray(Charsets.UTF_8)
        else byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE)

    private fun syncsafe(n: Int) = byteArrayOf(
        (n shr 21 and 0x7F).toByte(), (n shr 14 and 0x7F).toByte(), (n shr 7 and 0x7F).toByte(), (n and 0x7F).toByte()
    )

    private fun be32(n: Int) = byteArrayOf((n shr 24).toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)

    /** Room left for the next edit when the audio has to move anyway. */
    const val NEW_PADDING = 4096

    /**
     * Padding beyond this is given back rather than kept: after a large cover is removed, the
     * file should get smaller, not keep the cover's space as zeros.
     */
    const val MAX_KEPT_PADDING = 256 * 1024
}
