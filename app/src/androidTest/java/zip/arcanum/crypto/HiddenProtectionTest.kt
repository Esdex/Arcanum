package zip.arcanum.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import zip.arcanum.core.security.IdleMonitor
import java.io.File

/**
 * Hidden-volume protection, against real volumes on a real device.
 *
 * The defect these were written for: a mount that asked for protection and could not find
 * the hidden header left the boundary unset and **carried on mounting read-write**, while
 * the UI said protection was active. Every test here therefore asserts on what the mount
 * did, not on what it reported.
 *
 * ## The fixtures are not ours
 *
 * `vc-hidden.hc` and `vc-hidden-argon2.hc` are made by desktop VeraCrypt 1.26.29, not by
 * Arcanum. They belong in the app's own external files directory
 * (`adb push <file> /sdcard/Android/data/zip.arcanum/files/`) because Arcanum holds no
 * all-files permission - it reaches vaults through SAF, and a test process inherits exactly
 * that. A subdirectory made with `adb shell mkdir` is owned by `shell` and the app cannot
 * even list it, so push into that directory itself:
 *
 * ```
 * veracrypt -t --non-interactive -c vc-hidden.hc --volume-type=normal --size=40M \
 *     --encryption=AES --hash=SHA-512 --filesystem=FAT -p test1234 --pim=0 -k "" \
 *     --random-source=/dev/urandom
 * veracrypt -t --non-interactive -c vc-hidden.hc --volume-type=hidden --size=10M \
 *     --encryption=AES --hash=SHA-512 --filesystem=FAT -p hidden1234 --pim=0 -k "" \
 *     --random-source=/dev/urandom
 * ```
 *
 * `vc-hidden-argon2.hc` is the same with `--hash=argon2id --pim=12` on the hidden volume,
 * which is the case our own scan can never find: Argon2id is never auto-detected (#177).
 *
 * A fixture built by the code under test would agree with it about a wrong layout. These
 * were built by the implementation we have to be compatible with.
 *
 * Every test works on its own copy, so a run leaves the fixtures untouched and the tests
 * do not depend on each other's order. Skipped, not failed, when the fixtures are absent -
 * push them with `adb push`.
 */
@RunWith(AndroidJUnit4::class)
class HiddenProtectionTest {

    private val engine = VeraCryptEngine(IdleMonitor())
    private lateinit var fixtureDir: File
    private lateinit var work: File
    private val fixture get() = File(fixtureDir, FIXTURE_NAME)
    private val argon2Fixture get() = File(fixtureDir, ARGON2_FIXTURE_NAME)
    private val opened = mutableListOf<Long>()

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        /* Resolved at runtime rather than written out: the path is the app's own external
           directory, which is where a test process without all-files access can read from. */
        fixtureDir = context.getExternalFilesDir(null) ?: error("no external files dir")
        assumeTrue(
            "fixtures not on the device - push them to $fixtureDir (see the class comment). " +
                "Directory holds: " + (fixtureDir.list()?.joinToString() ?: "<nothing, or unreadable>"),
            File(fixtureDir, FIXTURE_NAME).isFile
        )
        work = File(context.cacheDir, "hidden-protection-work").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        opened.forEach { runBlocking { engine.unmountContainer(it) } }
        opened.clear()
        if (this::work.isInitialized) work.deleteRecursively()
    }

    // ── The mount either protects or does not happen ──────────────────────────

    @Test
    fun correctProtectionCredentials_mountAndBoundaryIsSet() {
        val handle = mountOuter(copyOf(fixture), protectPassword = HIDDEN_PASSWORD).success()
        assertTrue(
            "the mount succeeded but the drive carries no hidden-volume boundary - " +
                "this is the unprotected mount the fix is about",
            engine.hasHiddenVolume(handle)
        )
    }

    @Test
    fun wrongProtectionPassword_refusesTheMountInsteadOfMountingUnprotected() {
        val result = mountOuter(copyOf(fixture), protectPassword = "wrong-on-purpose")
        assertEquals(
            "a hidden password that opens nothing must fail the mount",
            CryptoError.HIDDEN_PROTECTION_FAILED, result.failure()
        )
    }

    @Test
    fun wrongProtectionPim_refusesTheMount() {
        /* Not 485: for SHA-512 that is 15000 + 485*1000 = 500000, the very iteration count
           PIM 0 means, so it would open the header and prove nothing. */
        val result = mountOuter(copyOf(fixture), protectPassword = HIDDEN_PASSWORD, protectPim = 100)
        assertEquals(
            CryptoError.HIDDEN_PROTECTION_FAILED, result.failure()
        )
    }

    @Test
    fun namedProtectionPrf_mountsJustAsAutoDoes() {
        val handle = mountOuter(
            copyOf(fixture),
            protectPassword = HIDDEN_PASSWORD,
            protectHash = SHA512
        ).success()
        assertTrue(engine.hasHiddenVolume(handle))
    }

    @Test
    fun hiddenVolumeCredentials_withProtectionAsked_areRefused() {
        /* Unlocking the hidden volume itself while asking to protect it: there is no outer
           volume in this mount for the protection to guard. VeraCrypt refuses it too. */
        val result = mountOuter(
            copyOf(fixture),
            password = HIDDEN_PASSWORD,
            protectPassword = HIDDEN_PASSWORD
        )
        assertEquals(CryptoError.HIDDEN_IS_PROTECTION_TARGET, result.failure())
    }

    // ── Argon2id: the PRF the scan cannot reach ───────────────────────────────

    @Test
    fun argon2idHiddenVolume_autoScanRefuses_andNamingItWorks() {
        assumeTrue("Argon2id fixture not on the device", argon2Fixture.isFile)

        val auto = mountOuter(copyOf(argon2Fixture), protectPassword = HIDDEN_PASSWORD, protectPim = 12)
        assertEquals(
            "auto-detect does not try Argon2id, so this must refuse rather than mount unprotected",
            CryptoError.HIDDEN_PROTECTION_FAILED, auto.failure()
        )

        val named = mountOuter(
            copyOf(argon2Fixture),
            protectPassword = HIDDEN_PASSWORD,
            protectPim = 12,
            protectHash = VeraCryptEngine.HASH_ARGON2ID
        ).success()
        assertTrue(engine.hasHiddenVolume(named))
    }

    // ── What protection is for, and proof the check can see it fail ───────────

    @Test
    fun protectedOuterWrites_leaveTheHiddenVolumeIntact() {
        val volume = copyOf(fixture)
        writeMarkerIntoHiddenVolume(volume)

        val outer = mountOuter(volume, protectPassword = HIDDEN_PASSWORD).success()
        assertTrue(engine.hasHiddenVolume(outer))
        val written = fillOuterVolume(outer)
        unmountAll()

        assertTrue(
            "the outer volume swallowed all $FILL_MB MB with a hidden volume in the way - " +
                "the boundary is not being enforced",
            written < FILL_MB
        )
        assertEquals(
            "the marker written into the hidden volume did not survive writes to a " +
                "protected outer volume",
            MARKER_TEXT, readMarkerFromHiddenVolume(volume)
        )
    }

    @Test
    fun unprotectedOuterWrites_destroyTheHiddenVolume() {
        /* The control for the test above. Without it, "the marker survived" would prove
           nothing - it would also hold if the writes never came near the hidden area, or if
           the fill did nothing at all. This is the same volume, the same fill, protection
           off, and it has to come back damaged. */
        val volume = copyOf(fixture)
        writeMarkerIntoHiddenVolume(volume)

        val outer = mountOuter(volume, protectPassword = null).success()
        val written = fillOuterVolume(outer)
        unmountAll()

        assertEquals(
            "an unprotected outer volume should have taken the whole fill", FILL_MB, written
        )
        assertNotEquals(
            "the fill did not reach the hidden volume, so the protected case proves nothing " +
                "either - the fixture geometry or the fill size is wrong",
            MARKER_TEXT, readMarkerFromHiddenVolume(volume)
        )
    }

    /**
     * The assertion that found the boundary being 128 KB too high: the hidden volume's
     * ciphertext must not change by one byte. The marker test above reads through our own
     * FAT driver, which can only say whether the damage was bad enough to notice.
     */
    @Test
    fun protectedFill_leavesTheHiddenCiphertextByteIdentical() {
        val volume = copyOf(fixture)
        writeMarkerIntoHiddenVolume(volume)
        val before = hiddenRegionDigest(volume)

        val outer = mountOuter(volume, protectPassword = HIDDEN_PASSWORD).success()
        val written = fillOuterVolume(outer)
        unmountAll()

        val after = hiddenRegionDigest(volume)
        assertEquals(
            "protected fill wrote $written MB and changed the hidden volume's ciphertext",
            before, after
        )
    }

    @Test
    fun hiddenVolumeSurvivesItsOwnRoundTrip() {
        /* The control for the control: if writing a file into the hidden volume and mounting
           it again is already broken, the two tests above say nothing about protection. */
        val volume = copyOf(fixture)
        writeMarkerIntoHiddenVolume(volume)
        assertEquals(MARKER_TEXT, readMarkerFromHiddenVolume(volume))
    }

    // ── The same, on a volume Arcanum made itself ────────────────────────────

    @Test
    fun arcanumMadeHiddenVolume_isProtectedTheSameWay() {
        /* Everything above runs against volumes desktop VeraCrypt built. This one is ours
           end to end - our creator's geometry read back by our own boundary code, which is
           the pair that could agree with each other about a wrong layout. The independent
           check on what it writes is vcheader.py on the host (see HiddenVolumeCreateTest). */
        val volume = File(work, "arcanum-made.hc")
        runBlocking {
            val outer = engine.createContainer(
                path = volume.absolutePath, sizeBytes = 40L * 1024 * 1024,
                password = OUTER_PASSWORD, algorithm = 0, hashAlgorithm = 0,
                filesystem = 0, quickFormat = true,
                entropyBytes = ByteArray(32) { it.toByte() }
            )
            assertTrue("outer not created: $outer", outer is CryptoResult.Success)
            val hidden = engine.createHiddenVolume(
                path = volume.absolutePath, hiddenSizeBytes = 10L * 1024 * 1024,
                outerPassword = OUTER_PASSWORD, hiddenPassword = HIDDEN_PASSWORD,
                hiddenAlgorithm = 0, hiddenHashAlgorithm = 0, quickFormat = true,
                entropyBytes = ByteArray(32) { (it + 1).toByte() }
            )
            assertTrue("hidden not created: $hidden", hidden is CryptoResult.Success)
        }

        writeMarkerIntoHiddenVolume(volume)
        val before = hiddenRegionDigest(volume)

        val outerHandle = mountOuter(volume, protectPassword = HIDDEN_PASSWORD).success()
        assertTrue(engine.hasHiddenVolume(outerHandle))
        val written = fillOuterVolume(outerHandle)
        unmountAll()

        assertTrue("the fill was not stopped by the boundary", written < FILL_MB)
        assertEquals("the hidden volume's ciphertext changed", before, hiddenRegionDigest(volume))
        assertEquals(MARKER_TEXT, readMarkerFromHiddenVolume(volume))
    }

    @Test
    fun protectionHoldsOnAnExfatOuterVolume() = protectionHoldsWith(EXFAT)

    @Test
    fun protectionHoldsOnAnExt4OuterVolume() {
        /* The one filesystem with its own write path: FAT and exFAT are both FatFs and share
           disk_write, ext4 has the check again in ext4_device.cpp. And where FatFs is handed
           a shortened sector count, so it never even tries to allocate past the boundary, an
           ext4 superblock describes the whole outer area - so here the block layer is the
           only thing standing between a fill and the hidden volume. */
        protectionHoldsWith(EXT4)
    }

    /**
     * Creates an outer volume on [filesystem] with a hidden volume inside it, seeds the
     * hidden volume, fills the outer one with protection on, and requires the hidden
     * volume's ciphertext to come out unchanged.
     */
    private fun protectionHoldsWith(filesystem: Int) {
        val volume = File(work, "outer-fs$filesystem.hc")
        runBlocking {
            val outer = engine.createContainer(
                path = volume.absolutePath, sizeBytes = BIG_OUTER_BYTES,
                password = OUTER_PASSWORD, algorithm = 0, hashAlgorithm = 0,
                filesystem = filesystem, quickFormat = true,
                entropyBytes = ByteArray(32) { it.toByte() }
            )
            assertTrue("outer volume not created: $outer", outer is CryptoResult.Success)
            val hidden = engine.createHiddenVolume(
                path = volume.absolutePath, hiddenSizeBytes = BIG_HIDDEN_BYTES,
                outerPassword = OUTER_PASSWORD, hiddenPassword = HIDDEN_PASSWORD,
                hiddenAlgorithm = 0, hiddenHashAlgorithm = 0, quickFormat = true,
                entropyBytes = ByteArray(32) { (it + 1).toByte() }
            )
            assertTrue("hidden volume not created: $hidden", hidden is CryptoResult.Success)
        }

        writeMarkerIntoHiddenVolume(volume)
        val before = hiddenRegionDigest(volume)

        val outerHandle = mountOuter(volume, protectPassword = HIDDEN_PASSWORD).success()
        assertTrue("no boundary on the mounted outer volume", engine.hasHiddenVolume(outerHandle))
        val written = fillOuterVolume(outerHandle, BIG_FILL_MB)
        unmountAll()

        assertTrue("the fill of $BIG_FILL_MB MB was never stopped", written < BIG_FILL_MB)
        assertEquals("the hidden volume's ciphertext changed", before, hiddenRegionDigest(volume))
        assertEquals(MARKER_TEXT, readMarkerFromHiddenVolume(volume))
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun copyOf(fixture: File): File =
        File(work, "${fixture.nameWithoutExtension}-${System.nanoTime()}.hc")
            .also { fixture.copyTo(it, overwrite = true) }

    private fun mountOuter(
        volume: File,
        password: String = OUTER_PASSWORD,
        protectPassword: String? = null,
        protectPim: Int = 0,
        protectHash: Int = VeraCryptEngine.HASH_AUTO
    ): CryptoResult<Long> = runBlocking {
        engine.mountContainer(
            path                  = volume.absolutePath,
            password              = password,
            protectHiddenPassword = protectPassword,
            protectHiddenPim      = protectPim,
            protectHiddenHash     = protectHash
        )
    }.also { if (it is CryptoResult.Success) opened += it.value }

    private fun mountHiddenOrNull(volume: File): CryptoResult<Long> = runBlocking {
        engine.mountContainer(path = volume.absolutePath, password = HIDDEN_PASSWORD)
    }.also { if (it is CryptoResult.Success) opened += it.value }

    /** For the steps where a hidden volume that will not open is a broken harness, not a result. */
    private fun mountHidden(volume: File): Long = when (val r = mountHiddenOrNull(volume)) {
        is CryptoResult.Success -> r.value
        is CryptoResult.Failure -> throw AssertionError(
            "the hidden volume did not open (${r.error}) where it was expected to"
        )
    }

    private fun writeMarkerIntoHiddenVolume(volume: File) {
        val handle = mountHidden(volume)
        assertEquals(
            "could not seed the hidden volume",
            VeraCryptEngine.ERR_OK,
            engine.writeFile(handle, "/$MARKER_NAME", MARKER_TEXT.toByteArray(), 0L)
        )
        unmountAll()
    }

    /**
     * What the hidden volume has to say for itself afterwards: the marker's text, or a
     * description of how it failed. Never throws for damage - damage is the expected
     * answer in the control.
     */
    private fun readMarkerFromHiddenVolume(volume: File): String {
        val handle = when (val r = mountHiddenOrNull(volume)) {
            is CryptoResult.Success -> r.value
            /* Damage is a legitimate answer here - it is what the control asserts. */
            is CryptoResult.Failure -> return "<hidden volume no longer mounts: ${r.error}>"
        }
        val names = engine.listFilesOrNull(handle, "/")
            ?: return "<directory listing failed>".also { unmountAll() }
        if (names.none { it.name == MARKER_NAME }) {
            unmountAll()
            return "<marker gone: ${names.joinToString { it.name }}>"
        }
        val bytes = engine.readFile(handle, "/$MARKER_NAME", 0L, MARKER_TEXT.length)
        unmountAll()
        return bytes?.toString(Charsets.UTF_8) ?: "<marker unreadable>"
    }

    /**
     * SHA-256 of the hidden volume's data area, read straight out of the container file.
     * The area is where both implementations put it: the hidden volume's own size, ending
     * where the backup header group begins. Ciphertext, so any write shows up.
     */
    private fun hiddenRegionDigest(volume: File): String {
        val handle = mountHidden(volume)
        val hiddenSize = engine.getDataSize(handle)
        unmountAll()
        /* Both layouts place the hidden data area to end one backup-header group before
           the end of the file, so its start is the file size less that group and its own
           size. VeraCrypt leaves a further 128 KB of slack at the end of the outer data
           area; measuring from the file's end covers both. */
        val start = volume.length() - BACKUP_AREA_BYTES - hiddenSize
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        java.io.RandomAccessFile(volume, "r").use { raf ->
            raf.seek(start)
            val buffer = ByteArray(1 shl 20)
            var left = hiddenSize
            while (left > 0) {
                val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (n <= 0) break
                digest.update(buffer, 0, n)
                left -= n
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Writes 1 MB files until one is refused; returns how many landed. */
    private fun fillOuterVolume(handle: Long, limitMb: Int = FILL_MB): Int {
        val block = ByteArray(1024 * 1024) { (it and 0xFF).toByte() }
        var written = 0
        while (written < limitMb) {
            if (engine.writeFile(handle, "/fill$written.bin", block, 0L) != VeraCryptEngine.ERR_OK) break
            written++
        }
        return written
    }

    private fun unmountAll() {
        opened.forEach { runBlocking { engine.unmountContainer(it) } }
        opened.clear()
    }

    private fun CryptoResult<Long>.success(): Long = when (this) {
        is CryptoResult.Success -> value
        is CryptoResult.Failure -> throw AssertionError("expected a mount, got $error")
    }

    private fun CryptoResult<Long>.failure(): CryptoError = when (this) {
        is CryptoResult.Success -> throw AssertionError(
            "expected the mount to be refused, but it went through" +
                (if (engine.hasHiddenVolume(value)) " (with a boundary)" else " WITH NO PROTECTION")
        )
        is CryptoResult.Failure -> error
    }

    private companion object {
        const val FIXTURE_NAME = "vc-hidden.hc"
        const val ARGON2_FIXTURE_NAME = "vc-hidden-argon2.hc"

        /** PRF ids are the native table's; only the two interesting ones are named there. */
        const val SHA512 = 0

        const val OUTER_PASSWORD = "test1234"
        const val HIDDEN_PASSWORD = "hidden1234"

        const val MARKER_NAME = "hidden-marker.txt"
        const val MARKER_TEXT = "this text lives in the hidden volume"

        /** Outer data area is ~40 MB with the last 10 MB hidden, so this has to cross it. */
        const val FILL_MB = 34

        /** The backup header group at the end of every VeraCrypt volume. */
        const val BACKUP_AREA_BYTES = 131072L

        /** Filesystem ids as nativeCreateContainer numbers them. */
        const val EXFAT = 1
        const val EXT4 = 2

        /* Roomy enough for an ext4 superblock and its bookkeeping, with a hidden volume
           that still leaves the fill something to cross. */
        const val BIG_OUTER_BYTES = 96L * 1024 * 1024
        const val BIG_HIDDEN_BYTES = 24L * 1024 * 1024
        const val BIG_FILL_MB = 80
    }
}
