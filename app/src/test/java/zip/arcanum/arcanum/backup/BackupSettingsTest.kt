package zip.arcanum.arcanum.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        assertFalse(megaOnly.hasSensitiveCredentials(BackupProvider.S3))
        assertFalse(megaOnly.copy(provider = BackupProvider.S3).hasSensitiveCredentials())
    }
}
