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
 * leaves the file where the host can fetch it:
 *
 * ```
 * adb shell am instrument -w -e class zip.arcanum.crypto.HiddenVolumeCreateTest \
 *     zip.arcanum.test/androidx.test.runner.AndroidJUnitRunner
 * adb pull /sdcard/Android/data/zip.arcanum/files/arcanum-hidden.hc
 * scratchpad/vcheader.py arcanum-hidden.hc 0x10000 hidden1234
 * ```
 *
 * What the header holds cannot be checked from in here without asking the same code that
 * wrote it, which would agree with itself about anything: `vcheader.py` decrypts the header
 * from the outside, with hashlib and openssl. This test's own assertions only cover what our
 * side has to be true for - that both volumes open again.
 */
@RunWith(AndroidJUnit4::class)
class HiddenVolumeCreateTest {

    private val engine = VeraCryptEngine(IdleMonitor())

    @Test
    fun createsAnOuterAndHiddenVolumeThatBothOpen() {
        val dir = InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir(null) ?: error("no external files dir")
        val volume = File(dir, "arcanum-hidden.hc")
        volume.delete()

        runBlocking {
            val outer = engine.createContainer(
                path = volume.absolutePath,
                sizeBytes = OUTER_BYTES,
                password = OUTER_PASSWORD,
                algorithm = 0,        // AES
                hashAlgorithm = 0,    // SHA-512
                filesystem = 0,       // FAT (0 = FAT, 1 = exFAT, 2 = ext4)
                quickFormat = true,
                entropyBytes = ByteArray(32) { it.toByte() }
            )
            assertTrue("outer volume not created: $outer", outer is CryptoResult.Success)

            val hidden = engine.createHiddenVolume(
                path = volume.absolutePath,
                hiddenSizeBytes = HIDDEN_BYTES,
                outerPassword = OUTER_PASSWORD,
                hiddenPassword = HIDDEN_PASSWORD,
                hiddenAlgorithm = 0,
                hiddenHashAlgorithm = 0,
                quickFormat = true,
                entropyBytes = ByteArray(32) { (it + 1).toByte() }
            )
            assertTrue("hidden volume not created: $hidden", hidden is CryptoResult.Success)
        }

        assertEquals("outer volume does not open", 0, volumeTypeOf(volume, OUTER_PASSWORD))
        assertEquals("hidden volume does not open", 1, volumeTypeOf(volume, HIDDEN_PASSWORD))

        /* The app creates its files 0600, and the point of this one is to be read from
           outside - adb pull runs as `shell`, which is nobody here. */
        volume.setReadable(true, false)
    }

    /** 0 for a normal volume, 1 for a hidden one, as the mounted drive reports it. */
    private fun volumeTypeOf(volume: File, password: String): Int = runBlocking {
        when (val r = engine.mountContainer(path = volume.absolutePath, password = password)) {
            is CryptoResult.Success -> engine.getVolumeType(r.value)
                .also { engine.unmountContainer(r.value) }
            is CryptoResult.Failure -> throw AssertionError("mount failed: ${r.error}")
        }
    }

    private companion object {
        const val OUTER_BYTES = 40L * 1024 * 1024
        const val HIDDEN_BYTES = 10L * 1024 * 1024
        const val OUTER_PASSWORD = "test1234"
        const val HIDDEN_PASSWORD = "hidden1234"
    }
}
