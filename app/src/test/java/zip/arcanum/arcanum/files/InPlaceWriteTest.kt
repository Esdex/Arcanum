package zip.arcanum.arcanum.files

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import zip.arcanum.crypto.VeraCryptEngine

class InPlaceWriteTest {

    /**
     * A vault in memory where names lead to inodes, so two names can be one file the way a hard
     * link makes them. [failAt] makes the Nth operation fail, counting from 1.
     */
    private class FakeVault(var failAt: Int = 0) : InPlaceWrite.Ops {
        private val inodes = mutableMapOf<Int, ByteArray>()
        val names = mutableMapOf<String, Int>()
        private var nextInode = 1
        var calls = 0

        fun create(path: String, bytes: ByteArray) { names[path] = nextInode; inodes[nextInode++] = bytes }
        fun link(existing: String, path: String) { names[path] = names.getValue(existing) }
        fun read(path: String): ByteArray? = names[path]?.let { inodes.getValue(it) }

        val log = mutableListOf<String>()

        private fun failing(op: String): Boolean { log += op; return ++calls == failAt }

        override fun writeFile(path: String, data: ByteArray, offset: Long): Int {
            if (failing("writeFile")) return VeraCryptEngine.ERR_FS
            if (offset == 0L) { names.remove(path); create(path, data); return VeraCryptEngine.ERR_OK }
            val ino = names[path] ?: return VeraCryptEngine.ERR_FILE
            if (offset != inodes.getValue(ino).size.toLong()) return VeraCryptEngine.ERR_UNSUPPORTED
            inodes[ino] = inodes.getValue(ino) + data
            return VeraCryptEngine.ERR_OK
        }

        override fun writeAt(path: String, data: ByteArray, offset: Long): Int {
            if (failing("writeAt")) return VeraCryptEngine.ERR_FS
            if (path !in names) create(path, ByteArray(0))
            val ino = names.getValue(path)
            val old = inodes.getValue(ino)
            if (offset > old.size) return VeraCryptEngine.ERR_UNSUPPORTED
            val out = old.copyOf(maxOf(old.size, offset.toInt() + data.size))
            data.copyInto(out, offset.toInt())
            inodes[ino] = out
            return VeraCryptEngine.ERR_OK
        }

        override fun emptyFile(path: String): Int {
            if (failing("emptyFile")) return VeraCryptEngine.ERR_FS
            val ino = names[path] ?: return VeraCryptEngine.ERR_FILE
            inodes[ino] = ByteArray(0)
            return VeraCryptEngine.ERR_OK
        }

        override fun deleteFile(path: String): Int {
            if (failing("deleteFile")) return VeraCryptEngine.ERR_FS
            return if (names.remove(path) != null) VeraCryptEngine.ERR_OK else VeraCryptEngine.ERR_FILE
        }
    }

    private val old = "the old text, longer than the new one".toByteArray()
    // Two chunks of 4 and one byte over: a fill that stops short of the end shows here.
    private val new = ByteArray(9) { ('a' + it).code.toByte() }

    private fun linkedVault(failAt: Int = 0) = FakeVault(failAt).apply {
        create("/notes.txt", old)
        link("/notes.txt", "/other-name.txt")
    }

    private fun save(v: FakeVault) = InPlaceWrite.write(v, "/notes.txt", "/notes.txt.save", new, chunk = 4)

    @Test
    fun everyNameSeesTheNewContents() {
        val v = linkedVault()
        assertTrue(save(v))
        assertArrayEquals(new, v.read("/notes.txt"))
        assertArrayEquals(new, v.read("/other-name.txt"))
        assertEquals(v.names["/notes.txt"], v.names["/other-name.txt"])
        assertNull(v.read("/notes.txt.save"))
    }

    @Test
    fun aLeftoverFromAKilledSaveIsReplaced() {
        val v = linkedVault().apply { create("/notes.txt.save", "stale".toByteArray()) }
        assertTrue(save(v))
        assertArrayEquals(new, v.read("/other-name.txt"))
        assertNull(v.read("/notes.txt.save"))
    }

    @Test
    fun anEmptyFileIsSavedEmpty() {
        val v = linkedVault()
        assertTrue(InPlaceWrite.write(v, "/notes.txt", "/notes.txt.save", ByteArray(0), chunk = 4))
        assertArrayEquals(ByteArray(0), v.read("/other-name.txt"))
        assertNull(v.read("/notes.txt.save"))
    }

    /**
     * A save cut short at any step leaves the old contents untouched, or the new contents whole
     * somewhere. Never neither, and never half of the new ones passed off as the file.
     */
    @Test
    fun aSaveCutShortAnywhereLosesNeitherTheOldNorTheNewContents() {
        val total = linkedVault().also { save(it) }.calls
        assertTrue("the run should take several steps", total >= 6)
        for (n in 1..total) {
            val v = linkedVault(failAt = n)
            val ok = save(v)
            val file = v.read("/notes.txt")!!
            val safety = v.read("/notes.txt.save")
            when {
                // Only the final clean-up failed: the file is right and a spare copy is left.
                ok -> { assertArrayEquals("step $n", new, file); assertTrue("step $n", safety == null || safety.contentEquals(new)) }
                file.contentEquals(old) -> {}                                   // nothing changed
                else -> assertArrayEquals("step $n: the new contents must be kept", new, safety)
            }
            assertEquals("step $n: still one file", v.names["/notes.txt"], v.names["/other-name.txt"])
        }
    }

    @Test
    fun aFileThatCannotBeEmptiedIsLeftAsItWasAndTheCopyGoes() {
        val emptyStep = linkedVault().also { save(it) }.log.indexOf("emptyFile") + 1
        val v = linkedVault(failAt = emptyStep)
        assertFalse(save(v))
        assertArrayEquals(old, v.read("/notes.txt"))
        assertArrayEquals(old, v.read("/other-name.txt"))
        assertNull(v.read("/notes.txt.save"))
    }

    @Test
    fun aWriteThatStopsPartWayKeepsTheNewContentsInTheCopy() {
        val firstFill = linkedVault().also { save(it) }.log.indexOf("writeAt") + 1
        val v = linkedVault(failAt = firstFill)
        assertFalse(save(v))
        assertArrayEquals(new, v.read("/notes.txt.save"))
    }
}
