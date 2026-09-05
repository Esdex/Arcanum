package zip.arcanum.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import zip.arcanum.core.security.IdleMonitor
import java.io.File

/**
 * The two halves of the size floor: Arcanum will not FORMAT a volume below 5 MB, and it
 * still OPENS one that something else made.
 *
 * Why there is a floor at all: f_mkfs settles the FAT type on a first estimate of the
 * cluster count, and around 4 MB that estimate lands just above the FAT16 boundary while
 * the real count, after the reserved sector and the tables and the root directory, lands
 * just below it. With the cluster size passed explicitly - which it is, to follow
 * VeraCrypt's ladder (#115) - FatFs cannot step down to FAT12 and gives up. Measured: 4096
 * KB fails, 4160 KB and up work. The wizard says 5 MB because a round number is easier to
 * state than a boundary.
 *
 * Desktop VeraCrypt has no such limit - it writes FAT12 itself and its floor is 292 KB for
 * a container (`TC_MIN_VOLUME_SIZE`) and 40 KB for a hidden volume. So volumes below ours
 * exist, and refusing to open them would be a compatibility break rather than a limit.
 *
 * Reading used to have a floor of its own, further down and not ours: FatFs refused a FAT
 * volume of fewer than 128 sectors (ff.c, "Properness of volume size"), which is 64 KB of
 * data area - so the smallest volumes VeraCrypt makes could not be opened here at all. That
 * number is a plausibility heuristic rather than a layout rule, and it is lowered to 16 in
 * our copy of ff.c. These fixtures are what says so: the smallest of them is 72 sectors.
 *
 * The fixtures are made by VeraCrypt 1.26.29 and pushed to the app's own external files
 * directory (see HiddenProtectionTest for why that directory). Their size is the whole
 * file, headers included, where the wizard's is the data area:
 *
 * ```
 * for sz in 299008 327168 327680 1048576 4194304; do
 *   veracrypt -t --non-interactive -c vc-$sz.hc --volume-type=normal --size=$sz \
 *       --encryption=AES --hash=sha512 --filesystem=FAT -p test1234 --pim=0 -k "" \
 *       --random-source=/dev/urandom
 * done
 * ```
 *
 * pushed as `vc-small-292k.hc`, `vc-b327168.hc`, `vc-b327680.hc`, `vc-small-1m.hc` and
 * `vc-small-4m.hc` - the two `vc-b*` ones straddle the 128-sector line by one sector.
 */
@RunWith(AndroidJUnit4::class)
class SmallVolumeCompatTest {

    private val engine = VeraCryptEngine(IdleMonitor())
    private lateinit var dir: File

    @Before
    fun findFixtures() {
        dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null) ?: error("no external files dir")
        assumeTrue(
            "fixtures not on the device - push them to $dir (see the class comment). " +
                "Directory holds: " + (dir.list()?.joinToString() ?: "<nothing, or unreadable>"),
            File(dir, OPENABLE.first()).isFile
        )
    }

    @Test
    fun aVolumeBelowTheFloorStillOpensAndTakesFiles() {
        val failures = mutableListOf<String>()
        for (name in OPENABLE) {
            val volume = File(dir, name)
            assumeTrue("$name is not on the device", volume.isFile)

            val opened = runBlocking {
                engine.mountContainer(path = volume.absolutePath, password = PASSWORD)
            }
            android.util.Log.i("SMALLVOL", "$name -> $opened")
            if (opened is CryptoResult.Failure) { failures += "$name: ${opened.error}"; continue }
            val handle = (opened as CryptoResult.Success).value
            /* FAT12 reports as 1. Reading it is the whole point: it is the type our own
               formatter is the one that cannot write. */
            assertTrue(
                "$name is not a FAT volume: ${engine.getFilesystem(handle)}",
                engine.getFilesystem(handle) in 1..3
            )
            assertEquals(
                "$name would not take a file",
                VeraCryptEngine.ERR_OK,
                engine.writeFile(handle, "/hello.txt", MARKER.toByteArray(), 0L)
            )
            runBlocking { engine.unmountContainer(handle) }

            /* Read after a remount rather than from the same mount: what is being checked
               is that the write reached the volume, not that FatFs remembers it. */
            val back = runBlocking {
                when (val r = engine.mountContainer(path = volume.absolutePath, password = PASSWORD)) {
                    is CryptoResult.Success -> r.value
                    is CryptoResult.Failure -> throw AssertionError("$name would not reopen: ${r.error}")
                }
            }
            val read = engine.readFile(back, "/hello.txt", 0L, MARKER.length)
            runBlocking { engine.unmountContainer(back) }
            assertEquals("$name did not give the file back", MARKER, read?.decodeToString())
        }
        assertEquals("volumes that would not open", emptyList<String>(), failures)
    }

    @Test
    fun aVolumeBelowTheFloorIsNotCreated() {
        val volume = File(dir, "below-the-floor.hc")
        volume.delete()
        val result = runBlocking {
            engine.createContainer(
                path = volume.absolutePath, sizeBytes = 4L * 1024 * 1024,
                password = PASSWORD, algorithm = 0, hashAlgorithm = 0, filesystem = 0,
                quickFormat = true, entropyBytes = ByteArray(32)
            )
        }
        volume.delete()
        assertTrue("4 MB was accepted: $result", result is CryptoResult.Failure)
        assertEquals(
            "refused, but not for being too small",
            CryptoError.NO_SPACE, (result as CryptoResult.Failure).error
        )
    }

    private companion object {
        /**
         * All below the 5 MB we will make. In order: VeraCrypt's own smallest container
         * (36 KB of data, 72 sectors), then the two that straddle the 128-sector line
         * FatFs used to insist on, then two ordinary small ones.
         */
        val OPENABLE = listOf(
            "vc-small-292k.hc", "vc-b327168.hc", "vc-b327680.hc",
            "vc-small-1m.hc", "vc-small-4m.hc"
        )
        const val PASSWORD = "test1234"
        const val MARKER = "a volume smaller than we will make"
    }
}
