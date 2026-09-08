package zip.arcanum.arcanum.containers.data

import kotlinx.coroutines.flow.first
import zip.arcanum.crypto.VeraCryptEngine
import zip.arcanum.usb.UsbVolumeManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one way a mounted volume is closed (#102).
 *
 * There were three before this, and they did not agree. [VaultViewModel] closed a volume
 * through its own `closeByHandle`, which asks [UsbVolumeManager] first because a USB volume
 * closed behind the manager's back leaves the interface claimed and the drive missing from
 * Android. [MountCoordinator.unmountAll], the one auto-lock used, went straight to the
 * engine - so locking the phone with a vault on a drive closed it the wrong way. That is
 * fixed by there being a single closer rather than by a fourth copy of the check.
 *
 * It is a [Singleton] rather than a ViewModel because the rules that call it now live in the
 * process, not in the UI: [zip.arcanum.core.security.LockController] closes vaults with a
 * kept-alive process behind it and no Activity necessarily left alive.
 */
@Singleton
class VaultCloser @Inject constructor(
    private val repo: ContainerRepository,
    private val engine: VeraCryptEngine,
    private val usbVolumes: UsbVolumeManager
) {

    /** Closes one mounted vault. A vault that is not mounted is not an error. */
    suspend fun close(id: String) {
        repo.getContainerHandle(id)?.let { closeByHandle(it) }
        repo.unmountContainer(id)
    }

    /**
     * Closes a volume by its handle, for the callers that hold one and no id: a mount being
     * torn down before it was ever recorded, and a vault being deleted along with its row.
     */
    suspend fun closeHandle(handle: Long) = closeByHandle(handle)

    /** Closes every mounted vault. Used by auto-lock and by the panic wipe. */
    suspend fun closeAll() {
        repo.mountedContainerIds.value.forEach { close(it) }
    }

    /**
     * Closes the vaults whose own settings ask to be closed - and only those.
     *
     * [screenLocked] separates the two triggers a vault can distinguish: leaving the app
     * ("unmount on background") and the screen going off ("unmount on lock"). Everything
     * else stays open, which is what makes the keep-alive service worth having.
     */
    suspend fun closeAsPolicy(screenLocked: Boolean) {
        // Re-read rather than reusing a list from before a wait: what is mounted, and what
        // each vault asks for, may have changed while an operation was waited out.
        val wanted = repo.getAllContainersRaw().first()
            .filter { it.id in repo.mountedContainerIds.value }
            .filter { closesOnTrigger(it.unmountOnBackground, it.unmountOnLock, screenLocked) }
        wanted.forEach { close(it.id) }
    }

    companion object {
        /**
         * Whether a vault with these two settings closes on this trigger.
         *
         * Kept as one expression, and tested, because it is the rule most easily got wrong
         * when it is read in a hurry: the screen going off is also a way of leaving the app,
         * so "unmount when I leave" fires then too, while "unmount when the screen goes off"
         * does not fire merely because the user switched to another app.
         */
        fun closesOnTrigger(
            unmountOnBackground: Boolean,
            unmountOnLock: Boolean,
            screenLocked: Boolean
        ): Boolean = unmountOnBackground || (screenLocked && unmountOnLock)
    }

    /**
     * Pushes buffered writes down to the medium for every mounted vault.
     *
     * Unconditional, and before any decision about closing: the USB backend holds writes
     * back to merge them, and a process killed in the background takes those bytes with it.
     * Whether a vault should be closed is a choice; whether it should lose data is not.
     */
    suspend fun flushAll() {
        repo.mountedContainerIds.value.forEach { id ->
            repo.getContainerHandle(id)?.let { engine.flushContainer(it) }
        }
    }

    private suspend fun closeByHandle(handle: Long) {
        if (usbVolumes.mounted.value?.handle == handle) usbVolumes.unmount()
        else engine.unmountContainer(handle)
    }
}
