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

    @Test
    fun megaUploadUrlAllowsCleartextOnlyForMegaStorageHosts() {
        val client = MegaAccountClient()

        assertEquals(
            "http://gfs270n001.userstorage.mega.co.nz/ul/abc",
            client.validateMegaUploadUrl("http://gfs270n001.userstorage.mega.co.nz/ul/abc/")
        )
        assertEquals(
            "http://gfs270n001.userstorage.mega.nz/ul/abc",
            client.validateMegaUploadUrl("http://gfs270n001.userstorage.mega.nz/ul/abc/")
        )
        assertThrows(BackupValidationException::class.java) {
            client.validateMegaUploadUrl("http://mega.nz/ul/abc")
        }
        assertThrows(BackupValidationException::class.java) {
            client.validateMegaUploadUrl("http://userstorage.mega.nz.evil.example/ul/abc")
        }
    }

    @Test
    fun megaUploadUrlAllowsHttpsMegaHosts() {
        val client = MegaAccountClient()

        assertEquals(
            "https://g.api.mega.co.nz/ul/abc",
            client.validateMegaUploadUrl("https://g.api.mega.co.nz/ul/abc/")
        )
        assertEquals(
            "https://mega.nz/ul/abc",
            client.validateMegaUploadUrl("https://mega.nz/ul/abc/")
        )
    }
}
