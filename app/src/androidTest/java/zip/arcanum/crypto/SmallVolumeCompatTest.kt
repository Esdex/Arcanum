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
 * Reading has a floor of its own, further down and not ours: FatFs will not recognise a FAT
 * volume of fewer than 128 sectors (ff.c, "Properness of volume size"), which is 64 KB of
 * data area. [theSmallestVolumesVeraCryptMakesDoNotOpen] is where that is measured; it
 * records where the limit is, not that it ought to stay there.
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
            val read = engine.readFile(handle, "/hello.txt", 0L, MARKER.length)
            runBlocking { engine.unmountContainer(handle) }
            assertEquals("$name did not give the file back", MARKER, read?.decodeToString())
        }
        assertEquals("volumes that would not open", emptyList<String>(), failures)
    }

    @Test
    fun theSmallestVolumesVeraCryptMakesDoNotOpen() {
        /* Measured, not assumed: 65536 bytes of data area opens, 65024 does not, and the
           two fixtures differ by that one sector. The cause is FatFs's own check on the
           volume's sector count, which happens after the header has been authenticated -
           so this is a limit on what our FAT driver recognises, not on what the crypto
           can reach. VeraCrypt's own smallest volume, 36 KB of data, is below it too. */
        for (name in TOO_SMALL_TO_READ) {
            val volume = File(dir, name)
            assumeTrue("$name is not on the device", volume.isFile)
            val opened = runBlocking {
                engine.mountContainer(path = volume.absolutePath, password = PASSWORD)
            }
            if (opened is CryptoResult.Success) {
                runBlocking { engine.unmountContainer(opened.value) }
                throw AssertionError(
                    "$name opened - FatFs's 128-sector floor has moved, so the note in " +
                        "this class and in SmallVolumeCompatTest's other test needs redoing"
                )
            }
        }
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
        /** Below the 5 MB we will make, above the 128 sectors FatFs will read. */
        val OPENABLE = listOf("vc-b327680.hc", "vc-small-1m.hc", "vc-small-4m.hc")

        /** Below FatFs's floor as well: 127 sectors of data, and VeraCrypt's own minimum. */
        val TOO_SMALL_TO_READ = listOf("vc-b327168.hc", "vc-small-292k.hc")
        const val PASSWORD = "test1234"
        const val MARKER = "a volume smaller than we will make"
    }
}
