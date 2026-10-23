package zip.arcanum.arcanum.files

import zip.arcanum.crypto.VeraCryptEngine

/**
 * Replaces a file's contents in the file itself, for a file with more than one name.
 *
 * The editors save by writing a sibling and renaming it over the original, which leaves either
 * the old file or the new one if the save is cut short. For a hard-linked file that same rename
 * splits it in two: the name that was saved gets a new file, and every other name keeps the old
 * one, as a separate file nobody asked for. So a file with other names is written where it
 * stands - emptied, then filled - and every name sees the new contents.
 *
 * Writing in place gives up the rename's all-or-nothing, so the new bytes are first written whole
 * to [safety], next to the file, and removed only once the file holds them. A save cut short
 * leaves the new contents under that name rather than nowhere, the same promise the editors'
 * rename makes for its one bad case.
 */
internal object InPlaceWrite {

    /** The four file operations this needs, so a test can stand a fake vault behind them. */
    interface Ops {
        /** Writes from offset 0 (recreating the file) or appends at a block-aligned end. */
        fun writeFile(path: String, data: ByteArray, offset: Long): Int
        /** Writes at [offset] in an existing file without truncating it. */
        fun writeAt(path: String, data: ByteArray, offset: Long): Int
        fun emptyFile(path: String): Int
        fun deleteFile(path: String): Int
    }

    fun ops(engine: VeraCryptEngine, handle: Long) = object : Ops {
        override fun writeFile(path: String, data: ByteArray, offset: Long) = engine.writeFile(handle, path, data, offset)
        override fun writeAt(path: String, data: ByteArray, offset: Long) = engine.writeAt(handle, path, data, offset)
        override fun emptyFile(path: String) = engine.emptyFile(handle, path)
        override fun deleteFile(path: String) = engine.deleteFile(handle, path)
    }

    /** Whether [bytes] are now the contents of [path]. On false, see the class comment for where they are. */
    fun write(ops: Ops, path: String, safety: String, bytes: ByteArray, chunk: Int): Boolean {
        runCatching { ops.deleteFile(safety) }                  // a leftover from a killed save
        if (!writeWhole(ops, safety, bytes, chunk)) {
            runCatching { ops.deleteFile(safety) }
            return false
        }
        // Nothing of the file has changed yet, so a refusal here leaves it as it was.
        if (ops.emptyFile(path) != VeraCryptEngine.ERR_OK) {
            runCatching { ops.deleteFile(safety) }
            return false
        }
        var at = 0
        while (at < bytes.size) {
            val end = minOf(at + chunk, bytes.size)
            if (ops.writeAt(path, bytes.copyOfRange(at, end), at.toLong()) != VeraCryptEngine.ERR_OK)
                return false                                    // the new contents stay at [safety]
            at = end
        }
        runCatching { ops.deleteFile(safety) }
        return true
    }

    private fun writeWhole(ops: Ops, path: String, bytes: ByteArray, chunk: Int): Boolean {
        if (bytes.isEmpty()) return ops.writeAt(path, ByteArray(0), 0L) == VeraCryptEngine.ERR_OK
        var at = 0
        while (at < bytes.size) {
            val end = minOf(at + chunk, bytes.size)
            if (ops.writeFile(path, bytes.copyOfRange(at, end), at.toLong()) != VeraCryptEngine.ERR_OK) return false
            at = end
        }
        return true
    }
}
