package zip.arcanum.core.lifecycle

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VaultLifecyclePolicyTest {
    @Test
    fun routeStopIsNotTreatedAsAppBackground() {
        assertTrue(VaultLifecyclePolicy.shouldIgnoreRouteStop())
    }

    @Test
    fun trustedOperationKeepsVaultMountedWhenDeviceIsNotLocked() {
        assertTrue(
            VaultLifecyclePolicy.shouldKeepMountedForTrustedOperation(
                isLocked = false,
                trustedOperationActive = true
            )
        )
    }

    @Test
    fun processBackgroundUnmountsWhenEnabled() {
        assertTrue(
            VaultLifecyclePolicy.shouldUnmountOnProcessStop(
                isLocked = false,
                trustedOperationActive = false,
                unmountOnBackground = true,
                unmountOnLock = false
            )
        )
    }

    @Test
    fun processBackgroundDoesNotUnmountWhenTrustedOperationIsActive() {
        assertFalse(
            VaultLifecyclePolicy.shouldUnmountOnProcessStop(
                isLocked = false,
                trustedOperationActive = true,
                unmountOnBackground = true,
                unmountOnLock = false
            )
        )
    }

    @Test
    fun deviceLockUnmountsWhenLockPolicyIsEnabled() {
        assertTrue(
            VaultLifecyclePolicy.shouldUnmountOnProcessStop(
                isLocked = true,
                trustedOperationActive = true,
                unmountOnBackground = false,
                unmountOnLock = true
            )
        )
    }

    @Test
    fun deviceLockDoesNotUnmountWhenLockPolicyIsDisabled() {
        assertFalse(
            VaultLifecyclePolicy.shouldUnmountOnProcessStop(
                isLocked = true,
                trustedOperationActive = true,
                unmountOnBackground = true,
                unmountOnLock = false
            )
        )
    }
}
