package zip.arcanum.arcanum.containers.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which vaults an automatic trigger closes (#102).
 *
 * This rule used to live in a ViewModel and now lives in the process, under
 * [zip.arcanum.core.security.LockController], because a kept-alive app outlives its Activity.
 * The move was meant to change where the rule runs and nothing about what it decides - this
 * is the table that says so.
 */
class VaultCloserPolicyTest {

    private fun closes(background: Boolean, lock: Boolean, screenLocked: Boolean) =
        VaultCloser.closesOnTrigger(background, lock, screenLocked)

    @Test
    fun `a vault that asked for neither stays open`() {
        assertFalse(closes(background = false, lock = false, screenLocked = false))
        assertFalse(closes(background = false, lock = false, screenLocked = true))
    }

    @Test
    fun `unmount on background closes on both triggers`() {
        // The screen going off is also a way of leaving the app - a vault that closes when
        // the user switches apps must not survive the phone being pocketed.
        assertTrue(closes(background = true, lock = false, screenLocked = false))
        assertTrue(closes(background = true, lock = false, screenLocked = true))
    }

    @Test
    fun `unmount on lock closes only when the screen goes off`() {
        assertFalse("switching apps is not locking the phone",
                    closes(background = false, lock = true, screenLocked = false))
        assertTrue(closes(background = false, lock = true, screenLocked = true))
    }

    @Test
    fun `both settings together close on both triggers`() {
        assertTrue(closes(background = true, lock = true, screenLocked = false))
        assertTrue(closes(background = true, lock = true, screenLocked = true))
    }
}
