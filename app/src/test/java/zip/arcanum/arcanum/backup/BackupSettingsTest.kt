package zip.arcanum.arcanum.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class BackupSettingsTest {
    @Test
    fun sensitiveCredentialsAreProviderSpecific() {
        val megaOnly = BackupSettings(
            provider = BackupProvider.MEGA,
            megaEmail = "user@example.com",
            megaPassword = "password"
        )

        assertTrue(megaOnly.hasSensitiveCredentials(BackupProvider.MEGA))
        assertTrue(megaOnly.hasAnySensitiveCredentials())
        assertFalse(megaOnly.hasSensitiveCredentials(BackupProvider.S3))
        assertFalse(megaOnly.copy(provider = BackupProvider.S3).hasSensitiveCredentials())
    }

    @Test
    fun s3EndpointRejectsPlaintextHttp() {
        assertThrows(BackupValidationException::class.java) {
            S3BackupClientFactory.normalizeEndpoint("http://minio.local:9000")
        }
    }

    @Test
    fun s3EndpointDefaultsToHttps() {
        assertEquals("https://s3.example.com", S3BackupClientFactory.normalizeEndpoint("s3.example.com/"))
        assertEquals("https://s3.example.com", S3BackupClientFactory.normalizeEndpoint("https://s3.example.com/"))
    }
}
