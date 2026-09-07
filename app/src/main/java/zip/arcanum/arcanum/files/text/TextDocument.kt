package zip.arcanum.arcanum.files.text

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CharsetDecoder
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.charset.Charset

/**
 * A text file as the editor holds it: the characters to show, plus everything about the file
 * that is not characters and must survive being saved.
 *
 * The editor is allowed to change what the user typed and nothing else. A file has an
 * encoding, it may open with a byte-order mark, its lines end one way or another, and it
 * either ends with a newline or does not - all four are invisible on screen and all four
 * break something when they change behind someone's back. A `.conf` whose CRLF endings come
 * back as LF is a file that stopped working on the machine it was written for, and the editor
 * would have reported success.
 *
 * The one thing that cannot be carried through exactly is a file whose lines end BOTH ways.
 * Editing needs one kind of line break in the buffer, so a mixed file is unified on save -
 * [mixedNewlines] is set for it and the editor says so before anything is written.
 */
class TextDocument(
    val text: String,
    val charset: Charset,
    val bom: ByteArray,
    val newline: String,
    val mixedNewlines: Boolean,
    val trailingNewline: Boolean,
    val decodedAsFallback: Boolean
) {

    /**
     * Whether [newText] can be written in this document's encoding at all.
     *
     * It matters only for a file that was not UTF-8 and is therefore held byte for byte as
     * Latin-1: that encoding has 256 characters, and `String.toByteArray` silently turns
     * everything else into a question mark. Typing Cyrillic into an old Windows-1251 file
     * would have been saved as `????` with a cheerful "Document saved" - the file destroyed
     * by the editor that was supposed to leave it alone.
     */
    fun canEncode(newText: String): Boolean =
        !decodedAsFallback || charset.newEncoder().canEncode(newText)

    /** The same document, to be written as UTF-8 from now on. */
    fun asUtf8(): TextDocument = TextDocument(
        text = text, charset = StandardCharsets.UTF_8, bom = bom, newline = newline,
        mixedNewlines = mixedNewlines, trailingNewline = trailingNewline,
        decodedAsFallback = false
    )

    /** The bytes to write for [newText], keeping everything this document remembers. */
    fun encode(newText: String): ByteArray {
        var body = newText
        // The buffer only ever holds "\n"; put back whatever the file used.
        if (newline != "\n") body = body.replace("\n", newline)
        if (trailingNewline && !body.endsWith(newline)) body += newline
        if (!trailingNewline && body.endsWith(newline)) body = body.dropLast(newline.length)
        val encoded = body.toByteArray(charset)
        return if (bom.isEmpty()) encoded else bom + encoded
    }

    companion object {
        /**
         * Anything longer than this is not offered to the editor at all.
         *
         * The number is about laying the file out, not about memory. The first surface here
         * was a Compose `BasicTextField`, which lays its whole content out in one piece on the
         * main thread: a 250 KB log froze the app for seconds and then crashed it, because the
         * laid-out height of four thousand lines is more than a `Constraints` pair can hold
         * (`Can't represent a width of 0 and height of 488049`). That is why the editing
         * surface is an `EditText` - see [CodeEditText].
         *
         * With the platform text view underneath, the cost of opening is one layout pass over
         * the file and editing costs only the lines touched, so the limit is about how long
         * that first pass may take rather than about what breaks. A megabyte is the working
         * figure; the editor logs what each file actually cost in debug builds, and that is
         * what the number should be moved by.
         */
        const val MAX_EDITABLE_BYTES = 1L * 1024 * 1024

        private val BOM_UTF8    = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        private val BOM_UTF16LE = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        private val BOM_UTF16BE = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

        /**
         * Reads [bytes] as text, or returns null when they are not text at all.
         *
         * Not-text is decided by a NUL byte in the first 8 KB, which no text encoding this
         * reads produces and every binary format has within a few hundred bytes. It matters
         * because "Edit as text" is offered for any unknown extension, and opening a JPEG
         * that way would show mojibake and then offer to save it back as mojibake.
         */
        fun decode(bytes: ByteArray): TextDocument? {
            val head = bytes.copyOf(minOf(bytes.size, 8 * 1024))
            if (head.any { it == 0.toByte() } && !startsWith(bytes, BOM_UTF16LE) && !startsWith(bytes, BOM_UTF16BE)) {
                return null
            }

            val bom: ByteArray
            val charset: Charset
            when {
                startsWith(bytes, BOM_UTF8)    -> { bom = BOM_UTF8;    charset = StandardCharsets.UTF_8 }
                startsWith(bytes, BOM_UTF16LE) -> { bom = BOM_UTF16LE; charset = StandardCharsets.UTF_16LE }
                startsWith(bytes, BOM_UTF16BE) -> { bom = BOM_UTF16BE; charset = StandardCharsets.UTF_16BE }
                else                           -> { bom = ByteArray(0); charset = StandardCharsets.UTF_8 }
            }

            val body = bytes.copyOfRange(bom.size, bytes.size)
            var fallback = false
            val raw = strictDecode(body, charset) ?: run {
                /*
                 * Not valid UTF-8. Latin-1 is the fallback because it maps every one of the
                 * 256 byte values to a character and back again: an old file in some other
                 * encoding comes out wrong on screen but is written back byte for byte
                 * unless it is edited, where guessing at a codepage could turn a file we
                 * cannot read into a file nobody can.
                 */
                fallback = true
                String(body, StandardCharsets.ISO_8859_1)
            }

            val crlf = countOf(raw, "\r\n")
            val lf   = raw.count { it == '\n' } - crlf
            val cr   = raw.count { it == '\r' } - crlf
            val newline = when {
                crlf > lf && crlf >= cr -> "\r\n"
                cr > lf && cr > crlf    -> "\r"
                else                    -> "\n"
            }
            val kinds = listOf(crlf, lf, cr).count { it > 0 }

            val normalised = raw.replace("\r\n", "\n").replace('\r', '\n')
            return TextDocument(
                text            = normalised,
                charset         = if (fallback) StandardCharsets.ISO_8859_1 else charset,
                bom             = bom,
                newline         = newline,
                mixedNewlines   = kinds > 1,
                trailingNewline = raw.endsWith("\n") || raw.endsWith("\r"),
                decodedAsFallback = fallback
            )
        }

        private fun startsWith(bytes: ByteArray, prefix: ByteArray): Boolean =
            bytes.size >= prefix.size && prefix.indices.all { bytes[it] == prefix[it] }

        /** The decoded string, or null when [bytes] are not valid in [charset]. */
        private fun strictDecode(bytes: ByteArray, charset: Charset): String? {
            val decoder: CharsetDecoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return try {
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (e: CharacterCodingException) {
                null
            }
        }

        private fun countOf(s: String, sub: String): Int {
            var count = 0
            var i = s.indexOf(sub)
            while (i >= 0) { count++; i = s.indexOf(sub, i + sub.length) }
            return count
        }
    }
}
