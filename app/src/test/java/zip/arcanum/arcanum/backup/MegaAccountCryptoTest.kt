package zip.arcanum.arcanum.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class MegaAccountCryptoTest {
    @Test
    fun megaDestinationUsesRegularAccountCredentials() {
        assertTrue(
            BackupSettings(
                provider = BackupProvider.MEGA,
                megaEmail = "user@example.com",
                megaPassword = "password"
            ).hasUsableDestination()
        )

        assertFalse(
            BackupSettings(
                provider = BackupProvider.MEGA,
                megaEmail = "user@example.com",
                megaPassword = ""
            ).hasUsableDestination()
        )
    }

    @Test
    fun attributesEncryptAndDecryptWithMegaPadding() {
        val key = ByteArray(16) { it.toByte() }
        val attributes = """{"n":"backup container.hc"}"""

        val encrypted = MegaAccountCrypto.encryptAttributes(attributes, key)
        val decrypted = MegaAccountCrypto.decryptAttributes(encrypted, key)

        assertNotEquals(attributes, encrypted)
        assertEquals("backup container.hc", decrypted?.get("n")?.toString()?.trim('"'))
    }

    @Test
    fun finalUploadKeyMatchesMegaLayout() {
        val uploadKey = intArrayOf(1, 2, 3, 4, 5, 6)
        val metaMac = intArrayOf(7, 8)

        assertArrayEquals(
            intArrayOf(1 xor 5, 2 xor 6, 3 xor 7, 4 xor 8, 5, 6, 7, 8),
            MegaAccountCrypto.finalFileKey(uploadKey, metaMac)
        )
    }

    @Test
    fun intConversionPadsTrailingBytes() {
        assertArrayEquals(
            intArrayOf(0x61626300),
            MegaAccountCrypto.bytesToInts("abc".toByteArray(Charsets.UTF_8))
        )
    }

    @Test
    fun liveMegaLoginIsOptIn() {
        assumeTrue(
            "Set ARCANUM_MEGA_LIVE=1, ARCANUM_MEGA_EMAIL and ARCANUM_MEGA_PASSWORD to run this test",
            System.getenv("ARCANUM_MEGA_LIVE") == "1"
        )
        val email = requiredEnv("ARCANUM_MEGA_EMAIL")
        val password = requiredEnv("ARCANUM_MEGA_PASSWORD")
        val folder = System.getenv("ARCANUM_MEGA_FOLDER") ?: "/Arcanum"

        val client = MegaAccountClient()
        client.login(email, password)
        client.ensureFolder(folder)
    }

    private fun requiredEnv(name: String): String =
        System.getenv(name).orEmpty().also { value ->
            assumeTrue("$name is required", value.isNotBlank())
        }
}
