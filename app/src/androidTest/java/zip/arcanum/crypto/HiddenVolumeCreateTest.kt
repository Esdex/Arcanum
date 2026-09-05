package zip.arcanum.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import zip.arcanum.core.security.IdleMonitor
import java.io.File

/**
 * Builds an outer volume with a hidden volume inside it, the way the app's wizard does, and
 * leaves the files where the host can fetch them:
 *
 * ```
 * adb shell am instrument -w -e class zip.arcanum.crypto.HiddenVolumeCreateTest \
 *     zip.arcanum.test/androidx.test.runner.AndroidJUnitRunner
 * adb pull /sdcard/Android/data/zip.arcanum/files/arcanum-hidden-fat.hc
 * tools/veracrypt/vcheader.py arcanum-hidden-fat.hc 0x10000 hidden1234
 * ```
 *
 * What the header holds cannot be checked from in here without asking the same code that
 * wrote it, which would agree with itself about anything: `vcheader.py` decrypts the header
 * from the outside, with hashlib and openssl. This test's own assertions only cover what our
 * side has to be true for - that both volumes open, that each carries the filesystem it was
 * asked for, and that files put in the hidden one survive a remount.
 */
@RunWith(AndroidJUnit4::class)
class HiddenVolumeCreateTest {

    private val engine = VeraCryptEngine(IdleMonitor())

    @Test
    fun createsAnOuterAndHiddenVolumeThatBothOpen() = hiddenVolumeOn(FAT, "fat")

    @Test
    fun hiddenVolumeCanBeExfat() = hiddenVolumeOn(EXFAT, "exfat")

    @Test
    fun hiddenVolumeCanBeExt4() = hiddenVolumeOn(EXT4, "ext4")

    @Test
    fun hiddenVolumeKeepsItsOwnFilesystemInsideAnExt4OuterVolume() {
        /* The other direction, and the one that says the two choices are genuinely
           independent rather than one being copied into the other: an ext4 outer volume
           with a FAT hidden volume inside it. */
        val volume = build(outerFs = EXT4, hiddenFs = FAT, name = "arcanum-hidden-ext4outer.hc")
        assertEquals("outer volume is not ext4", EXT4_ID, filesystemOf(volume, OUTER_PASSWORD))
        assertTrue("hidden volume is not FAT", filesystemOf(volume, HIDDEN_PASSWORD) in FAT_IDS)
    }

    /**
     * The whole round trip for one hidden filesystem: create, check what each volume
     * reports, write a file into the hidden volume and read it back after a remount.
     *
     * The outer volume is FAT in every case, so a hidden volume that came out right can
     * only have got its filesystem from what was asked for it.
     */
    private fun hiddenVolumeOn(hiddenFs: Int, tag: String) {
        val volume = build(outerFs = FAT, hiddenFs = hiddenFs, name = "arcanum-hidden-$tag.hc")

        assertEquals("outer volume does not open", 0, volumeTypeOf(volume, OUTER_PASSWORD))
        assertEquals("hidden volume does not open", 1, volumeTypeOf(volume, HIDDEN_PASSWORD))

        assertTrue("outer volume is not FAT", filesystemOf(volume, OUTER_PASSWORD) in FAT_IDS)
        val reported = filesystemOf(volume, HIDDEN_PASSWORD)
        when (hiddenFs) {
            /* A 10 MB FAT volume is FAT16, not FAT32: the cluster size comes from
               VeraCrypt's ladder (1 KB here), and 10240 clusters is far below the FAT16
               limit. Which of the three it is does not matter here - that it is a FAT at
               all does. */
            FAT   -> assertTrue("hidden volume is not FAT: $reported", reported in FAT_IDS)
            EXFAT -> assertEquals("hidden volume is not exFAT", EXFAT_ID, reported)
            EXT4  -> assertEquals("hidden volume is not ext4", EXT4_ID, reported)
        }

        /* Probing says what the metadata claims. Writing a file and reading it back after a
           remount says the filesystem is real - and on ext4 it is a different driver
           entirely, so this is not the same assertion twice. */
        val handle = mount(volume, HIDDEN_PASSWORD)
        assertEquals(
            "could not write into the hidden volume",
            VeraCryptEngine.ERR_OK,
            engine.writeFile(handle, "/$MARKER_NAME", MARKER_TEXT.toByteArray(), 0L)
        )
        runBlocking { engine.unmountContainer(handle) }

        val back = mount(volume, HIDDEN_PASSWORD)
        val read = engine.readFile(back, "/$MARKER_NAME", 0L, MARKER_TEXT.length)
        runBlocking { engine.unmountContainer(back) }
        assertEquals("the file did not survive a remount", MARKER_TEXT, read?.decodeToString())
    }

    @Test
    fun aSmallHiddenVolumeCanBeAnyFilesystem() {
        /* Every filesystem offered on the step has to survive the smallest volume anyone
           would put it on, and that is not an obvious yes: FatFs refuses exFAT below 4096
           sectors outright, and the ext4 formatter has its own "too little space for even
           one group's metadata" answer.

           5 MB, not the 4 MB floor the size step advertises, because 4 MB does not work
           today: FatFs aborts a FAT format there when it is handed VeraCrypt's cluster size
           (1 KB below 256 MB), since the ~4030 clusters that leaves are too few for FAT16
           and it will not fall back on its own. That is older than the filesystem choice and
           reaches the outer volume too - the size step takes any number of megabytes. */
        for (fs in listOf(FAT, EXFAT, EXT4)) {
            val volume = build(
                outerFs = FAT, hiddenFs = fs, name = "arcanum-hidden-min-fs$fs.hc",
                outerBytes = 20L * 1024 * 1024, hiddenBytes = 5L * 1024 * 1024
            )
            assertEquals(
                "the smallest hidden volume does not open on filesystem $fs",
                1, volumeTypeOf(volume, HIDDEN_PASSWORD)
            )
            volume.delete()
        }
    }

    /** Creates the pair and leaves the file behind for `adb pull`. */
    private fun build(
        outerFs: Int,
        hiddenFs: Int,
        name: String,
        outerBytes: Long = OUTER_BYTES,
        hiddenBytes: Long = HIDDEN_BYTES
    ): File {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null) ?: error("no external files dir")
        val volume = File(dir, name)
        volume.delete()

        runBlocking {
            val outer = engine.createContainer(
                path = volume.absolutePath,
                sizeBytes = outerBytes,
                password = OUTER_PASSWORD,
                algorithm = 0,        // AES
                hashAlgorithm = 0,    // SHA-512
                filesystem = outerFs,
                quickFormat = true,
                entropyBytes = ByteArray(32) { it.toByte() }
            )
            assertTrue("outer volume not created: $outer", outer is CryptoResult.Success)

            val hidden = engine.createHiddenVolume(
                path = volume.absolutePath,
                hiddenSizeBytes = hiddenBytes,
                outerPassword = OUTER_PASSWORD,
                hiddenPassword = HIDDEN_PASSWORD,
                hiddenAlgorithm = 0,
                hiddenHashAlgorithm = 0,
                hiddenFilesystem = hiddenFs,
                quickFormat = true,
                entropyBytes = ByteArray(32) { (it + 1).toByte() }
            )
            assertTrue("hidden volume not created: $hidden", hidden is CryptoResult.Success)
        }

        /* The app creates its files 0600, and the point of these is to be read from
           outside - adb pull runs as `shell`, which is nobody here. */
        volume.setReadable(true, false)
        return volume
    }

    private fun mount(volume: File, password: String): Long = runBlocking {
        when (val r = engine.mountContainer(path = volume.absolutePath, password = password)) {
            is CryptoResult.Success -> r.value
            is CryptoResult.Failure -> throw AssertionError("mount failed: ${r.error}")
        }
    }

    /** 0 for a normal volume, 1 for a hidden one, as the mounted drive reports it. */
    private fun volumeTypeOf(volume: File, password: String): Int {
        val handle = mount(volume, password)
        return engine.getVolumeType(handle).also { runBlocking { engine.unmountContainer(handle) } }
    }

    /** What the mounted volume says it is formatted with (1-3 FAT, 4 exFAT, 5 ext4). */
    private fun filesystemOf(volume: File, password: String): Int {
        val handle = mount(volume, password)
        return engine.getFilesystem(handle).also { runBlocking { engine.unmountContainer(handle) } }
    }

    private companion object {
        const val OUTER_BYTES = 40L * 1024 * 1024
        const val HIDDEN_BYTES = 10L * 1024 * 1024
        const val OUTER_PASSWORD = "test1234"
        const val HIDDEN_PASSWORD = "hidden1234"

        /** The ids the create path takes, the same three the wizard offers. */
        const val FAT = 0
        const val EXFAT = 1
        const val EXT4 = 2

        /** The ids a mounted volume reports back, which are a different set. */
        val FAT_IDS = setOf(1, 2, 3)
        const val EXFAT_ID = 4
        const val EXT4_ID = 5

        const val MARKER_NAME = "marker.txt"
        const val MARKER_TEXT = "hidden volume marker"
    }
}
