package zip.arcanum.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The settings backup file (#63).
 *
 * The half worth testing: what a file promises about itself, what a wrong password gets, and
 * that a file nobody may open cannot be read by editing it either.
 */
class BackupCodecTest {

    private val payload = """{"settings":{"auto_lock":{"t":"b","v":"true"}},"vaults":[]}"""

    @Test
    fun `a file without a password round-trips`() {
        val bytes = BackupCodec.encode(payload, appVersionCode = 10, password = null)
        assertEquals(false, BackupCodec.isEncrypted(bytes))
        val opened = BackupCodec.decode(bytes, password = null)
        assertTrue(opened is BackupCodec.Opened.Payload)
        assertEquals(payload, (opened as BackupCodec.Opened.Payload).json)
        assertEquals(10, opened.writtenBy)
    }

    @Test
    fun `a file with a password round-trips`() {
        val bytes = BackupCodec.encode(payload, 10, "correct horse".toCharArray())
        assertEquals(true, BackupCodec.isEncrypted(bytes))
        val opened = BackupCodec.decode(bytes, "correct horse".toCharArray())
        assertEquals(payload, (opened as BackupCodec.Opened.Payload).json)
    }

    @Test
    fun `an unprotected file says what is in it, a protected one does not`() {
        val plain     = String(BackupCodec.encode(payload, 10, null))
        val protected = String(BackupCodec.encode(payload, 10, "pw".toCharArray()))
        // The point of the password, stated as a test: without one the vault list is there
        // for anyone holding the file.
        assertTrue("auto_lock" in java.util.Base64.getDecoder()
            .decode(Regex("\"data\":\"([^\"]+)\"").find(plain)!!.groupValues[1])
            .decodeToString())
        assertFalse("auto_lock" in protected)
    }

    @Test
    fun `the wrong password is refused rather than guessed at`() {
        val bytes = BackupCodec.encode(payload, 10, "right".toCharArray())
        assertEquals(BackupCodec.Opened.WrongPassword, BackupCodec.decode(bytes, "wrong".toCharArray()))
        assertEquals(BackupCodec.Opened.WrongPassword, BackupCodec.decode(bytes, null))
    }

    @Test
    fun `an edited file is refused`() {
        val bytes = BackupCodec.encode(payload, 10, "pw".toCharArray())
        // Flip one character of the ciphertext: GCM's tag is what makes this fail rather
        // than restore something half-true.
        val text  = String(bytes)
        val data  = Regex("\"data\":\"([^\"]+)\"").find(text)!!.groupValues[1]
        val broken = text.replace(data, data.replaceRange(4, 5, if (data[4] == 'A') "B" else "A"))
        assertNotEquals(text, broken)
        assertEquals(BackupCodec.Opened.WrongPassword, BackupCodec.decode(broken.toByteArray(), "pw".toCharArray()))
    }

    @Test
    fun `a file from a newer format is refused, not guessed at`() {
        val bytes = BackupCodec.encode(payload, 10, null)
        val newer = String(bytes).replace("\"format\":${BackupCodec.FORMAT}", "\"format\":${BackupCodec.FORMAT + 1}")
        val opened = BackupCodec.decode(newer.toByteArray(), null)
        assertEquals(BackupCodec.Opened.TooNew(BackupCodec.FORMAT + 1), opened)
    }

    @Test
    fun `rubbish is rubbish`() {
        assertEquals(BackupCodec.Opened.Malformed, BackupCodec.decode("not a backup".toByteArray(), null))
        assertEquals(null, BackupCodec.isEncrypted("not a backup".toByteArray()))
    }
}
