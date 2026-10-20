package zip.arcanum.arcanum.gallery.tags

import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Puts a tag built by [Id3v2Writer] into a track in the vault.
 *
 * A tag that fits the old one's room is written over it, and nothing after it is touched.
 * That is instant for an hour-long mix too, and a power cut in the middle of it can leave the
 * tag broken but never the audio, which lies past it.
 *
 * A tag that does not fit moves the audio, so the file is written again whole: into a sibling
 * first, which then takes the original's place, the way the text editor saves (#109). An
 * interrupted save leaves the old file or the new one. The one stray it can leave is a delete
 * that succeeds and a rename that does not, and then the new file survives under the
 * temporary name rather than being thrown away.
 */
@Singleton
class VaultTagWriter @Inject constructor(private val engine: VeraCryptEngine) {

    /**
     * Writes [tag] in place of the [oldTagSize] bytes the file starts with (0 when it has no
     * tag). Returns the file's size afterwards, or null if the write failed.
     */
    fun write(handle: Long, path: String, size: Long, oldTagSize: Int, tag: Id3v2Writer.Rendered): Long? =
        if (tag.inPlace) writeOver(handle, path, size, tag.bytes) else rewrite(handle, path, size, oldTagSize, tag.bytes)

    private fun writeOver(handle: Long, path: String, size: Long, bytes: ByteArray): Long? {
        var at = 0
        while (at < bytes.size) {
            val end = minOf(at + CHUNK, bytes.size)
            if (!ok { engine.writeAt(handle, path, bytes.copyOfRange(at, end), at.toLong()) }) return null
            at = end
        }
        return size
    }

    private fun rewrite(handle: Long, path: String, size: Long, oldTagSize: Int, tag: ByteArray): Long? {
        val tmp = "$path$TMP_SUFFIX"
        ok { engine.deleteFile(handle, tmp) }                      // a leftover from a killed save
        fun fail(): Long? { ok { engine.deleteFile(handle, tmp) }; return null }

        // Every write but the last is a whole buffer: the ext4 driver appends only at a block
        // boundary, and a tag can be any length - written on its own, it left the audio
        // nowhere to go.
        val buffer = ByteArray(CHUNK)
        var filled = 0
        var written = 0L
        fun flush(): Boolean {
            if (filled == 0) return true
            val data = if (filled == CHUNK) buffer else buffer.copyOf(filled)
            if (!ok { engine.writeFile(handle, tmp, data, written) }) return false
            written += filled
            filled = 0
            return true
        }
        fun put(src: ByteArray): Boolean {
            var at = 0
            while (at < src.size) {
                val n = minOf(CHUNK - filled, src.size - at)
                src.copyInto(buffer, filled, at, at + n)
                filled += n
                at += n
                if (filled == CHUNK && !flush()) return false
            }
            return true
        }

        if (!put(tag)) return fail()
        // The audio, and whatever follows it - an ID3v1 tag, an APE tag - as it was.
        var from = oldTagSize.toLong()
        while (from < size) {
            val chunk = engine.readFile(handle, path, from, minOf(CHUNK.toLong(), size - from).toInt())
            if (chunk == null || chunk.isEmpty()) return fail()
            if (!put(chunk)) return fail()
            from += chunk.size
        }
        if (!flush()) return fail()

        if (!ok { engine.deleteFile(handle, path) }) return fail()
        if (!ok { engine.renameFile(handle, tmp, path) }) return null
        return written
    }

    private inline fun ok(call: () -> Int) =
        runCatching(call).getOrDefault(VeraCryptEngine.ERR_FS) == VeraCryptEngine.ERR_OK

    private companion object {
        /**
         * Well under the 16 MB that a single read out of the vault can return, and a whole
         * number of blocks of any size a filesystem uses.
         */
        const val CHUNK = 4 * 1024 * 1024
        const val TMP_SUFFIX = ".arcanum-save"
    }
}
