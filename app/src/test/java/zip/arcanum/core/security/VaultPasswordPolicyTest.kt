package zip.arcanum.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultPasswordPolicyTest {
    @Test
    fun passwordLimitIsUtf8BytesNotCharacters() {
        assertTrue(VaultPasswordPolicy.isWithinVeraCryptLimit("a".repeat(128)))
        assertFalse(VaultPasswordPolicy.isWithinVeraCryptLimit("a".repeat(129)))
        assertTrue(VaultPasswordPolicy.isWithinVeraCryptLimit("ж".repeat(64)))
        assertFalse(VaultPasswordPolicy.isWithinVeraCryptLimit("ж".repeat(65)))
    }

    @Test
    fun lowPimRequiresLongPassword() {
        assertTrue(VaultPasswordPolicy.hasUnsafeLowPim("short-password", 10))
        assertFalse(VaultPasswordPolicy.hasUnsafeLowPim("short-password", 485))
        assertFalse(VaultPasswordPolicy.hasUnsafeLowPim("a".repeat(20), 10))
        assertFalse(VaultPasswordPolicy.hasUnsafeLowPim("short-password", 0))
    }
}
