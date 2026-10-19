package zip.arcanum.arcanum.gallery.tags

/**
 * An ID3v2.3 or ID3v2.4 tag, read from the start of an MP3.
 *
 * Every frame is kept as it was found, the ones this app never shows included, so that a tag
 * written back later can carry them unchanged: a rating, a link, a comment, an ISRC. AIMP keeps
 * them too, and a file should come out of an edit missing nothing it went in with.
 *
 * ID3v2.2 (three-letter frames) is not read: it is rare, and the platform's own reader still
 * fills the screen for it.
 */
class Id3v2Tag private constructor(
    /** 3 or 4. */
    val majorVersion: Int,
    val frames: List<Frame>,
    /** Bytes from the start of the file to the first byte of audio: header, frames, padding, footer. */
    val totalSize: Int,
    val paddingSize: Int,
    /**
     * Whether this app may write the tag back. Not when it uses what this app does not write
     * itself - unsynchronisation, an extended header, compressed or encrypted frames - because a
     * rewrite would have to undo or drop them, and a tag is not rewritten on a guess.
     */
    val rewritable: Boolean
) {
    /**
     * One frame, its body exactly as it stood in the file. [readable] is false for a compressed
     * or encrypted one, which is kept to be written back but never shown.
     */
    class Frame(val id: String, val flags: Int, val body: ByteArray, val readable: Boolean = true) {
        /** The body to read values from: without a group byte or a length, and resynchronised. */
        internal var content: ByteArray = body
    }

    fun frame(id: String): Frame? = frames.firstOrNull { it.id == id && it.readable }

    /** A text frame's value; several values (ID3v2.4 allows them) are joined with "; ". */
    fun text(id: String): String? = frame(id)?.let { decodeText(it.content) }

    val title: String? get() = text("TIT2")
    val artist: String? get() = text("TPE1")
    val album: String? get() = text("TALB")
    val albumArtist: String? get() = text("TPE2")
    val genre: String? get() = text("TCON")?.let(::resolveGenre)
    val lyricist: String? get() = text("TEXT")

    /**
     * The full date where the tag has one. TDRC is ID3v2.4's, but ffmpeg writes it into
     * ID3v2.3 tags as well, next to TYER - and AIMP shows TDRC, so we do too.
     */
    val year: String? get() = text("TDRC") ?: text("TYER")

    /** "3/12" in the tag; the track's own number is what is shown. */
    val trackNumber: String? get() = text("TRCK")?.substringBefore('/')?.trim()?.ifEmpty { null }

    /** Unsynchronised lyrics: the English ones when there are several, else the first. */
    val lyrics: String?
        get() {
            val all = frames.filter { it.id == "USLT" && it.readable }.mapNotNull { parseLyrics(it.content) }
            return (all.firstOrNull { it.first == "eng" } ?: all.firstOrNull())?.second
        }

    /** The front cover, or the first picture when none is marked as the front. */
    val cover: ByteArray?
        get() {
            val all = frames.filter { it.id == "APIC" && it.readable }.mapNotNull { parsePicture(it.content) }
            return (all.firstOrNull { it.first == PICTURE_FRONT_COVER } ?: all.firstOrNull())?.second
        }

    companion object {
        const val HEADER_SIZE = 10
        const val PICTURE_FRONT_COVER = 3

        /** How many bytes the whole tag takes, from its 10-byte header; 0 if there is no tag. */
        fun sizeFromHeader(header: ByteArray): Int {
            if (header.size < HEADER_SIZE || !isId3(header)) return 0
            val footer = if (header[5].toInt() and 0x10 != 0) HEADER_SIZE else 0
            return HEADER_SIZE + syncsafe(header, 6) + footer
        }

        /** Reads a tag from [data], which starts at the first byte of the file; null if none. */
        fun parse(data: ByteArray): Id3v2Tag? {
            if (data.size < HEADER_SIZE || !isId3(data)) return null
            val major = data[3].toInt()
            if (major != 3 && major != 4) return null
            val flags = data[5].toInt() and 0xFF
            val tagSize = syncsafe(data, 6)
            val totalSize = sizeFromHeader(data)
            if (data.size < HEADER_SIZE + tagSize) return null

            var body = data.copyOfRange(HEADER_SIZE, HEADER_SIZE + tagSize)
            val unsync = flags and 0x80 != 0
            val extended = flags and 0x40 != 0
            // ID3v2.3 unsynchronises the whole tag at once; ID3v2.4 does it frame by frame.
            if (unsync && major == 3) body = deunsynchronise(body)

            var pos = 0
            if (extended) {
                pos = if (major == 4) syncsafe(body, 0) else 4 + be32(body, 0)
            }
            var rewritable = !unsync && !extended
            val frames = mutableListOf<Frame>()
            while (pos + HEADER_SIZE <= body.size) {
                if (body[pos].toInt() == 0) break           // padding starts here
                val id = String(body, pos, 4, Charsets.ISO_8859_1)
                if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) { rewritable = false; break }
                val size = if (major == 4) syncsafe(body, pos + 4) else be32(body, pos + 4)
                val frameFlags = ((body[pos + 8].toInt() and 0xFF) shl 8) or (body[pos + 9].toInt() and 0xFF)
                val start = pos + HEADER_SIZE
                if (size < 0 || start + size > body.size) { rewritable = false; break }
                val raw = body.copyOfRange(start, start + size)
                val frame: Frame
                if (major == 4) {
                    // Format flags: 0x40 grouping, 0x08 compression, 0x04 encryption,
                    // 0x02 unsynchronisation, 0x01 data length indicator.
                    if (frameFlags and 0x4F != 0) rewritable = false
                    frame = Frame(id, frameFlags, raw, readable = frameFlags and 0x0C == 0)
                    var content = raw
                    if (frameFlags and 0x40 != 0) content = content.copyOfRange(minOf(1, content.size), content.size)
                    if (frameFlags and 0x01 != 0) content = content.copyOfRange(minOf(4, content.size), content.size)
                    if (frameFlags and 0x02 != 0) content = deunsynchronise(content)
                    frame.content = content
                } else {
                    // Format flags: 0x80 compression, 0x40 encryption, 0x20 grouping.
                    if (frameFlags and 0xE0 != 0) rewritable = false
                    frame = Frame(id, frameFlags, raw, readable = frameFlags and 0xC0 == 0)
                    if (frameFlags and 0x20 != 0) frame.content = raw.copyOfRange(minOf(1, raw.size), raw.size)
                }
                frames += frame
                pos = start + size
            }
            return Id3v2Tag(major, frames, totalSize, (body.size - pos).coerceAtLeast(0), rewritable)
        }

        private fun isId3(b: ByteArray) = b[0] == 'I'.code.toByte() && b[1] == 'D'.code.toByte() && b[2] == '3'.code.toByte()

        private fun syncsafe(b: ByteArray, at: Int): Int =
            ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or
                ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)

        private fun be32(b: ByteArray, at: Int): Int =
            ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
                ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)

        /** Undoes unsynchronisation: every 0xFF 0x00 was a 0xFF on its own. */
        private fun deunsynchronise(b: ByteArray): ByteArray {
            val out = java.io.ByteArrayOutputStream(b.size)
            var i = 0
            while (i < b.size) {
                out.write(b[i].toInt())
                if (b[i] == 0xFF.toByte() && i + 1 < b.size && b[i + 1].toInt() == 0) i++
                i++
            }
            return out.toByteArray()
        }

        /** A text frame's body: an encoding byte, then one or more values. */
        internal fun decodeText(body: ByteArray): String? {
            if (body.isEmpty()) return null
            val values = splitStrings(body[0].toInt(), body, 1).map { it.trim() }.filter { it.isNotEmpty() }
            return values.takeIf { it.isNotEmpty() }?.joinToString("; ")
        }

        private fun parseLyrics(body: ByteArray): Pair<String, String>? {
            if (body.size < 4) return null
            val enc = body[0].toInt()
            val language = String(body, 1, 3, Charsets.ISO_8859_1)
            val (_, textAt) = readString(enc, body, 4)               // the description
            val text = decode(enc, body, textAt, body.size).trimEnd('\u0000').trim()
            return text.takeIf { it.isNotEmpty() }?.let { language to it }
        }

        private fun parsePicture(body: ByteArray): Pair<Int, ByteArray>? {
            if (body.size < 4) return null
            val enc = body[0].toInt()
            var i = 1
            while (i < body.size && body[i].toInt() != 0) i++        // the MIME type, always Latin-1
            if (i + 2 > body.size) return null
            val type = body[i + 1].toInt() and 0xFF
            val (_, dataAt) = readString(enc, body, i + 2)           // the description
            if (dataAt >= body.size) return null
            return type to body.copyOfRange(dataAt, body.size)
        }

        /** The string that starts at [from] and where the next field starts after it. */
        private fun readString(enc: Int, b: ByteArray, from: Int): Pair<String, Int> {
            val wide = enc == 1 || enc == 2
            var i = from
            if (wide) {
                while (i + 1 < b.size && !(b[i].toInt() == 0 && b[i + 1].toInt() == 0)) i += 2
                return decode(enc, b, from, minOf(i, b.size)) to minOf(i + 2, b.size)
            }
            while (i < b.size && b[i].toInt() != 0) i++
            return decode(enc, b, from, i) to minOf(i + 1, b.size)
        }

        private fun splitStrings(enc: Int, b: ByteArray, from: Int): List<String> {
            val out = mutableListOf<String>()
            var at = from
            while (at < b.size) {
                val (s, next) = readString(enc, b, at)
                out += s
                if (next <= at) break
                at = next
            }
            return out
        }

        private fun decode(enc: Int, b: ByteArray, from: Int, to: Int): String {
            if (to <= from) return ""
            return when (enc) {
                0 -> String(b, from, to - from, Charsets.ISO_8859_1)
                1 -> String(b, from, to - from, Charsets.UTF_16)     // with a BOM; big-endian without one
                2 -> String(b, from, to - from, Charsets.UTF_16BE)
                3 -> String(b, from, to - from, Charsets.UTF_8)
                else -> ""
            }
        }

        /**
         * ID3v2.3 may name a genre by its ID3v1 number, "(17)" or "(17)Rock" or just "17".
         * The number is looked up; text after it wins, since that is what a person typed.
         */
        internal fun resolveGenre(raw: String): String {
            val m = Regex("""^\((\d+)\)(.*)$""").matchEntire(raw)
            val number = m?.groupValues?.get(1)?.toIntOrNull() ?: raw.toIntOrNull()
            val rest = m?.groupValues?.get(2)?.trim().orEmpty()
            if (rest.isNotEmpty()) return rest
            return number?.let { ID3V1_GENRES.getOrNull(it) } ?: raw
        }

        /** ID3v1's genres by number, with Winamp's extensions after 79 (127 is Drum & Bass). */
        private val ID3V1_GENRES = listOf(
            "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge", "Hip-Hop", "Jazz",
            "Metal", "New Age", "Oldies", "Other", "Pop", "R&B", "Rap", "Reggae", "Rock", "Techno",
            "Industrial", "Alternative", "Ska", "Death Metal", "Pranks", "Soundtrack", "Euro-Techno",
            "Ambient", "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical",
            "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel", "Noise", "AlternRock",
            "Bass", "Soul", "Punk", "Space", "Meditative", "Instrumental Pop", "Instrumental Rock",
            "Ethnic", "Gothic", "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk", "Eurodance",
            "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta", "Top 40", "Christian Rap",
            "Pop/Funk", "Jungle", "Native American", "Cabaret", "New Wave", "Psychedelic", "Rave",
            "Showtunes", "Trailer", "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro",
            "Musical", "Rock & Roll", "Hard Rock", "Folk", "Folk-Rock", "National Folk", "Swing",
            "Fast Fusion", "Bebop", "Latin", "Revival", "Celtic", "Bluegrass", "Avantgarde",
            "Gothic Rock", "Progressive Rock", "Psychedelic Rock", "Symphonic Rock", "Slow Rock",
            "Big Band", "Chorus", "Easy Listening", "Acoustic", "Humour", "Speech", "Chanson", "Opera",
            "Chamber Music", "Sonata", "Symphony", "Booty Bass", "Primus", "Porn Groove", "Satire",
            "Slow Jam", "Club", "Tango", "Samba", "Folklore", "Ballad", "Power Ballad",
            "Rhythmic Soul", "Freestyle", "Duet", "Punk Rock", "Drum Solo", "A Cappella", "Euro-House",
            "Dance Hall", "Goa", "Drum & Bass", "Club-House", "Hardcore Techno", "Terror", "Indie",
            "BritPop", "Negerpunk", "Polsk Punk", "Beat", "Christian Gangsta Rap", "Heavy Metal",
            "Black Metal", "Crossover", "Contemporary Christian", "Christian Rock", "Merengue",
            "Salsa", "Thrash Metal", "Anime", "Jpop", "Synthpop", "Abstract", "Art Rock", "Baroque",
            "Bhangra", "Big Beat", "Breakbeat", "Chillout", "Downtempo", "Dub", "EBM", "Eclectic",
            "Electro", "Electroclash", "Emo", "Experimental", "Garage", "Global", "IDM", "Illbient",
            "Industro-Goth", "Jam Band", "Krautrock", "Leftfield", "Lounge", "Math Rock",
            "New Romantic", "Nu-Breakz", "Post-Punk", "Post-Rock", "Psytrance", "Shoegaze",
            "Space Rock", "Trop Rock", "World Music", "Neoclassical", "Audiobook", "Audio Theatre",
            "Neue Deutsche Welle", "Podcast", "Indie Rock", "G-Funk", "Dubstep", "Garage Rock",
            "Psybient"
        )
    }
}
