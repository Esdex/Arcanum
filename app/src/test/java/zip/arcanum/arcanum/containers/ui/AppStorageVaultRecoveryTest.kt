package zip.arcanum.arcanum.containers.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppStorageVaultRecoveryTest {
    @Test
    fun unknownSectorAlignedFileCanBeRecoveredWithoutChangingBytes() {
        val dir = Files.createTempDirectory("arcanum-recover").toFile()
        val file = File(dir, "vault.hc")
        val bytes = ByteArray(1024) { it.toByte() }
        file.writeBytes(bytes)

        val candidate = AppStorageVaultRecovery.candidateForFile(file, isKnownPath = false)

        assertEquals(file.absolutePath, candidate?.path)
        assertEquals("vault.hc", candidate?.fileName)
        assertEquals(1024L, candidate?.size)
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test
    fun knownPathIsNotOfferedForRecovery() {
        val dir = Files.createTempDirectory("arcanum-known").toFile()
        val file = File(dir, "vault.hc").apply { writeBytes(ByteArray(1024)) }

        assertNull(AppStorageVaultRecovery.candidateForFile(file, isKnownPath = true))
    }

    @Test
    fun nonSectorAlignedFileIsNotRecoverable() {
        val dir = Files.createTempDirectory("arcanum-invalid").toFile()
        val file = File(dir, "vault.hc").apply { writeBytes(ByteArray(513)) }

        assertNull(AppStorageVaultRecovery.candidateForFile(file, isKnownPath = false))
        assertTrue(file.exists())
    }
}
