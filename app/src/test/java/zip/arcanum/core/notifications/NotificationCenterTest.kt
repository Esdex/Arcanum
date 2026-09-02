package zip.arcanum.core.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The queue rules of #135, as tests rather than as a comment. Every one of these is a
 * situation that used to lose a notification or show the wrong one.
 */
class NotificationCenterTest {

    private fun center() = NotificationCenter()

    private val renamed  = InAppNotification.FileRenamed("a.txt")
    private val deleted  = InAppNotification.FilesDeleted(3)
    private val readOnly = InAppNotification.ReadOnlyError
    private val noSpace  = InAppNotification.ImportFailed(ImportFailureReason.NO_SPACE)
    private val support  = InAppNotification.SupportDeveloper

    @Test
    fun `first one is shown`() {
        val c = center()
        c.notify(renamed)
        assertEquals(renamed, c.current.value)
    }

    @Test
    fun `a second of equal weight waits instead of replacing`() {
        val c = center()
        c.notify(renamed)
        c.notify(deleted)
        assertEquals(renamed, c.current.value)
        assertEquals(1, c.waitingCount)
        c.dismiss()
        assertEquals(deleted, c.current.value)
    }

    @Test
    fun `an error interrupts a confirmation and the confirmation comes back`() {
        val c = center()
        c.notify(renamed)
        c.notify(readOnly)
        assertEquals(readOnly, c.current.value)
        c.dismiss()
        assertEquals(renamed, c.current.value)
    }

    @Test
    fun `a confirmation does not interrupt an error`() {
        val c = center()
        c.notify(readOnly)
        c.notify(renamed)
        assertEquals(readOnly, c.current.value)
    }

    @Test
    fun `the same key replaces rather than queues`() {
        val c = center()
        c.notify(InAppNotification.FilesDeleted(1))
        c.notify(InAppNotification.FilesDeleted(4))
        assertEquals(InAppNotification.FilesDeleted(4), c.current.value)
        assertEquals(0, c.waitingCount)
    }

    @Test
    fun `the same key waiting is replaced in the queue`() {
        val c = center()
        c.notify(renamed)
        c.notify(InAppNotification.FilesDeleted(1))
        c.notify(InAppNotification.FilesDeleted(9))
        assertEquals(1, c.waitingCount)
        c.dismiss()
        assertEquals(InAppNotification.FilesDeleted(9), c.current.value)
    }

    @Test
    fun `an announcement never interrupts`() {
        val c = center()
        c.notify(renamed)
        c.notify(support)
        assertEquals(renamed, c.current.value)
    }

    @Test
    fun `an announcement is shown when nothing else wants the screen`() {
        val c = center()
        c.notify(support)
        assertEquals(support, c.current.value)
    }

    @Test
    fun `the queue is capped and the weakest is what goes`() {
        val c = center()
        c.notify(readOnly)                                   // current
        c.notify(InAppNotification.FileRenamed("1"))         // waiting 1
        c.notify(InAppNotification.FolderCreated("2"))       // waiting 2
        c.notify(InAppNotification.DateUpdated)              // waiting 3
        c.notify(noSpace)                                    // an error: takes a place
        assertEquals(3, c.waitingCount)
        // The oldest of the weakest made room for the error: the rename is gone, and the
        // error is served last because it arrived last among what survived.
        c.dismiss()
        assertEquals(InAppNotification.FolderCreated("2"), c.current.value)
        c.dismiss()
        assertEquals(InAppNotification.DateUpdated, c.current.value)
        c.dismiss()
        assertEquals(noSpace, c.current.value)
    }

    @Test
    fun `a confirmation is dropped when the queue is full of equals`() {
        val c = center()
        c.notify(readOnly)
        c.notify(InAppNotification.FileRenamed("1"))
        c.notify(InAppNotification.FolderCreated("2"))
        c.notify(InAppNotification.DateUpdated)
        c.notify(InAppNotification.ExportSuccess("late.txt"))
        assertEquals(3, c.waitingCount)
        repeat(3) { c.dismiss() }
        assertEquals(InAppNotification.DateUpdated, c.current.value)
        c.dismiss()
        assertNull(c.current.value)
    }

    @Test
    fun `nothing is shown while locked and everything waits`() {
        val c = center()
        c.setDelivering(false)
        c.notify(renamed)
        c.notify(readOnly)
        assertNull(c.current.value)
        assertEquals(2, c.waitingCount)
        c.setDelivering(true)
        assertEquals(renamed, c.current.value)
    }

    @Test
    fun `locking puts what is on screen back in the queue`() {
        val c = center()
        c.notify(readOnly)
        c.setDelivering(false)
        assertNull(c.current.value)
        c.setDelivering(true)
        assertEquals(readOnly, c.current.value)
    }

    @Test
    fun `dismissing the last one leaves nothing`() {
        val c = center()
        c.notify(renamed)
        c.dismiss()
        assertNull(c.current.value)
        assertEquals(0, c.waitingCount)
    }

    @Test
    fun `severity follows the instance, not the class`() {
        assertEquals(NotificationSeverity.SUCCESS, InAppNotification.FilesExported(5).severity)
        assertEquals(NotificationSeverity.WARNING, InAppNotification.FilesExported(5, skipped = 1).severity)
        assertEquals(NotificationSeverity.ERROR,   InAppNotification.FilesExported(5, failed = 1).severity)
    }

    @Test
    fun `every error stays until it is dismissed`() {
        listOf(readOnly, noSpace, InAppNotification.VaultInvalidFile).forEach {
            assertEquals(NotificationBehaviour.STICKY, it.behaviour)
            assertEquals(0L, it.dwellMillis)
        }
    }

    @Test
    fun `a drive waiting to be unplugged does not time out`() {
        val usb = InAppNotification.UsbSafeToRemove("id", "Vault")
        assertEquals(NotificationBehaviour.STICKY, usb.behaviour)
        assertEquals(0L, usb.dwellMillis)
    }
}
