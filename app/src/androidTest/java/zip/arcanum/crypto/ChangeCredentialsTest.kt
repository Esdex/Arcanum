package zip.arcanum.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import zip.arcanum.core.security.IdleMonitor
import java.io.File

/**
 * Changing a password or a keyfile set, on the volumes those operations could not reach.
 *
 * Both read the header at offset 0 and nothing else, so a hidden volume's own password was
 * answered with "wrong password" - the operations could not touch a hidden volume at all,
 * where desktop VeraCrypt rewrites whichever header the password opened
 * (`Volume.cpp: ReEncryptHeader`). Two more gaps of the same kind: an Argon2id volume could
 * not be opened for either operation, because auto-detect never tries that PRF, and asking
 * for Argon2id as the *new* PRF was clamped away and silently wrote the old one.
 *
 * Needs the VeraCrypt-made fixture from [HiddenProtectionTest]; skipped without it.
 */
@RunWith(AndroidJUnit4::class)
class ChangeCredentialsTest {

    private val engine = VeraCryptEngine(IdleMonitor())
    private lateinit var fixture: File
    private lateinit var work: File
    private val opened = mutableListOf<Long>()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fixture = File(context.getExternalFilesDir(null), "vc-hidden.hc")
        assumeTrue("fixture not on the device: $fixture", fixture.isFile)
        work = File(context.cacheDir, "change-credentials-work").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        opened.forEach { runBlocking { engine.unmountContainer(it) } }
        opened.clear()
        if (this::work.isInitialized) work.deleteRecursively()
    }

    @Test
    fun theHiddenVolumesPasswordCanBeChanged_andTheOuterHeaderIsUntouched() {
        val volume = copyOfFixture()
        val outerHeaderBefore = digestOf(volume, 0, VC_HEADER_SIZE)

        val result = runBlocking {
            engine.changePassword(
                path = volume.absolutePath,
                oldPassword = HIDDEN_PASSWORD,
                newPassword = NEW_HIDDEN_PASSWORD,
                wipePassCount = 1
            )
        }
        assertTrue("change refused: $result", result is CryptoResult.Success)

        assertEquals("the hidden volume does not open with its new password",
                     HIDDEN, volumeTypeWith(volume, NEW_HIDDEN_PASSWORD))
        assertTrue("the old hidden password still opens it",
                   mount(volume, HIDDEN_PASSWORD) is CryptoResult.Failure)
        assertEquals("the outer volume stopped opening with its own password",
                     NORMAL, volumeTypeWith(volume, OUTER_PASSWORD))
        assertEquals("the outer volume's header was rewritten by a change to the hidden one",
                     outerHeaderBefore, digestOf(volume, 0, VC_HEADER_SIZE))
    }

    @Test
    fun theHiddenVolumesBackupHeaderIsChangedTogetherWithItsPrimary() {
        /* Both headers or neither: a volume whose backup still answers to the old password
           is a volume the old password can still open through header restore. */
        val volume = copyOfFixture()
        runBlocking {
            engine.changePassword(
                path = volume.absolutePath,
                oldPassword = HIDDEN_PASSWORD,
                newPassword = NEW_HIDDEN_PASSWORD,
                wipePassCount = 1
            )
        }
        val backupOffset = volume.length() - HIDDEN_BACKUP_FROM_END
        val backup = ByteArray(VC_HEADER_SIZE)
        java.io.RandomAccessFile(volume, "r").use { it.seek(backupOffset); it.readFully(backup) }

        val scratch = File(work, "backup-only.hc")
        scratch.writeBytes(ByteArray(0))
        java.io.RandomAccessFile(volume, "r").use { src ->
            java.io.RandomAccessFile(scratch, "rw").use { dst ->
                val whole = ByteArray(volume.length().toInt().coerceAtMost(MAX_COPY))
                src.seek(0); src.readFully(whole); dst.write(whole)
                /* Put the hidden backup header where the primary lives, then ask who opens it. */
                dst.seek(VC_HIDDEN_HEADER_OFFSET.toLong()); dst.write(backup)
            }
        }
        assertEquals("the hidden backup header still answers to the old password",
                     HIDDEN, volumeTypeWith(scratch, NEW_HIDDEN_PASSWORD))
    }

    @Test
    fun aVolumeCanBeChangedToArgon2id() {
        val volume = copyOfFixture()
        val result = runBlocking {
            engine.changePassword(
                path = volume.absolutePath,
                oldPassword = OUTER_PASSWORD,
                newPassword = OUTER_PASSWORD,
                newHashAlgorithm = VeraCryptEngine.HASH_ARGON2ID,
                newPim = ARGON2_PIM,
                wipePassCount = 1
            )
        }
        assertTrue("change refused: $result", result is CryptoResult.Success)

        /* Auto-detect never tries Argon2id, so a volume the scan can no longer find is the
           proof that the PRF really changed - it used to report success and write SHA-512. */
        assertTrue("auto-detect still opens it, so the PRF did not change",
                   mount(volume, OUTER_PASSWORD, pim = ARGON2_PIM) is CryptoResult.Failure)

        val handle = mount(volume, OUTER_PASSWORD, pim = ARGON2_PIM,
                           hash = VeraCryptEngine.HASH_ARGON2ID).valueOrFail()
        assertEquals(VeraCryptEngine.HASH_ARGON2ID, engine.getHashId(handle))
    }

    @Test
    fun anArgon2idVolumeCanHaveItsPasswordChangedWhenThePrfIsNamed() {
        val volume = copyOfFixture()
        runBlocking {
            engine.changePassword(
                path = volume.absolutePath,
                oldPassword = OUTER_PASSWORD, newPassword = OUTER_PASSWORD,
                newHashAlgorithm = VeraCryptEngine.HASH_ARGON2ID, newPim = ARGON2_PIM,
                wipePassCount = 1
            )
        }

        val blind = runBlocking {
            engine.changePassword(
                path = volume.absolutePath,
                oldPassword = OUTER_PASSWORD, oldPim = ARGON2_PIM,
                newPassword = "changed-again", wipePassCount = 1
            )
        }
        assertTrue("auto-detect must not find an Argon2id volume", blind is CryptoResult.Failure)

        val named = runBlocking {
            engine.changePassword(
                path = volume.absolutePath,
                oldPassword = OUTER_PASSWORD, oldPim = ARGON2_PIM,
                newPassword = "changed-again", wipePassCount = 1,
                oldHashAlgorithm = VeraCryptEngine.HASH_ARGON2ID
            )
        }
        assertTrue("naming the PRF should have opened it: $named", named is CryptoResult.Success)
        /* Still Argon2id - the change was asked for no new PRF, so the header keeps the one
           it had, and auto-detect cannot open it any more than it could before. */
        val handle = mount(volume, "changed-again", hash = VeraCryptEngine.HASH_ARGON2ID).valueOrFail()
        assertEquals(NORMAL, engine.getVolumeType(handle))
        assertEquals(VeraCryptEngine.HASH_ARGON2ID, engine.getHashId(handle))
    }

    @Test
    fun keyfilesCanBeChangedOnAHiddenVolume() {
        val volume = copyOfFixture()
        val keyfile = ByteArray(64) { (it * 7).toByte() }

        val result = runBlocking {
            engine.changeKeyfile(
                path = volume.absolutePath,
                password = HIDDEN_PASSWORD,
                newKeyfileData = listOf(keyfile)
            )
        }
        assertTrue("change refused: $result", result is CryptoResult.Success)

        assertTrue("the hidden volume still opens without the new keyfile",
                   mount(volume, HIDDEN_PASSWORD) is CryptoResult.Failure)
        val handle = mount(volume, HIDDEN_PASSWORD, keyfiles = listOf(keyfile)).valueOrFail()
        assertEquals(HIDDEN, engine.getVolumeType(handle))
    }

    @Test
    fun changingKeyfilesKeepsThePrfTheVolumeAlreadyHad() {
        /* The keyfile flow passed the PRF the app had recorded for the vault, which is
           SHA-512 for one never mounted in this install - so changing a keyfile on a
           Whirlpool volume quietly rewrote its header as SHA-512. */
        val volume = File(work, "whirlpool.hc")
        runBlocking {
            val created = engine.createContainer(
                path = volume.absolutePath, sizeBytes = 5L * 1024 * 1024,
                password = OUTER_PASSWORD, algorithm = 0,
                hashAlgorithm = WHIRLPOOL, filesystem = 0, quickFormat = true,
                entropyBytes = ByteArray(32) { it.toByte() }
            )
            assertTrue("volume not created: $created", created is CryptoResult.Success)
        }
        assertEquals(WHIRLPOOL, engine.getHashId(mount(volume, OUTER_PASSWORD).valueOrFail()))
        unmountAll()

        val result = runBlocking {
            engine.changeKeyfile(
                path = volume.absolutePath, password = OUTER_PASSWORD,
                newKeyfileData = listOf(ByteArray(64) { it.toByte() })
            )
        }
        assertTrue("change refused: $result", result is CryptoResult.Success)

        val handle = mount(volume, OUTER_PASSWORD,
                           keyfiles = listOf(ByteArray(64) { it.toByte() })).valueOrFail()
        assertEquals("the PRF was rewritten by a keyfile change",
                     WHIRLPOOL, engine.getHashId(handle))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun copyOfFixture(): File =
        File(work, "work-${System.nanoTime()}.hc").also { fixture.copyTo(it, overwrite = true) }

    private fun mount(
        volume: File,
        password: String,
        pim: Int = 0,
        hash: Int = VeraCryptEngine.HASH_AUTO,
        keyfiles: List<ByteArray> = emptyList()
    ): CryptoResult<Long> = runBlocking {
        engine.mountContainer(
            path = volume.absolutePath, password = password, pim = pim,
            hashAlgorithm = hash, keyfileData = keyfiles
        )
    }.also { if (it is CryptoResult.Success) opened += it.value }

    private fun volumeTypeWith(volume: File, password: String): Int =
        engine.getVolumeType(mount(volume, password).valueOrFail()).also { unmountAll() }

    private fun CryptoResult<Long>.valueOrFail(): Long = when (this) {
        is CryptoResult.Success -> value
        is CryptoResult.Failure -> throw AssertionError("expected a mount, got $error")
    }

    private fun unmountAll() {
        opened.forEach { runBlocking { engine.unmountContainer(it) } }
        opened.clear()
    }

    private fun digestOf(volume: File, offset: Long, length: Int): String {
        val bytes = ByteArray(length)
        java.io.RandomAccessFile(volume, "r").use { it.seek(offset); it.readFully(bytes) }
        return java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val OUTER_PASSWORD = "test1234"
        const val HIDDEN_PASSWORD = "hidden1234"
        const val NEW_HIDDEN_PASSWORD = "hidden-changed-1234"

        const val NORMAL = 0
        const val HIDDEN = 1
        const val WHIRLPOOL = 2

        /** Argon2id with a password under 20 characters needs a PIM of 12 or more. */
        const val ARGON2_PIM = 12

        const val VC_HEADER_SIZE = 512
        const val VC_HIDDEN_HEADER_OFFSET = 65536
        /** The hidden volume's backup header is the last 64 KB block of the file. */
        const val HIDDEN_BACKUP_FROM_END = 65536L
        const val MAX_COPY = 64 * 1024 * 1024
    }
}
