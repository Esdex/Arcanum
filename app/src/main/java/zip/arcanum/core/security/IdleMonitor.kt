package zip.arcanum.core.security

import android.os.SystemClock
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide record of when the app was last doing something for the user, used to drive
 * inactivity ("idle") auto-lock. Backed by [SystemClock.elapsedRealtime] - a monotonic
 * clock that keeps counting across background and cannot be moved by changing the wall
 * clock, so the idle window can't be shortened or bypassed by clock tampering.
 *
 * Two kinds of thing count, and the distinction is the whole point of this class:
 *
 *  - **Interaction.** [recordInteraction] is called from MainActivity.onUserInteraction()
 *    (every touch / key event). Backgrounding deliberately does NOT touch the timestamp:
 *    idle is measured from the last real interaction, so a vault left mounted while the
 *    app sits in the background still ages out.
 *
 *  - **Work.** Watching a progress bar is not a touch, so a mount slower than the auto-lock
 *    window used to be indistinguishable from a phone left on a table: the lock fired
 *    mid-mount and threw the user back to the PIN screen, and with "unmount on auto-lock"
 *    on it tore down the volume that was still being set up. Reported on a slow mount and
 *    it applies to every long operation - creating a vault, changing a password or keyfile,
 *    backing up or restoring a header, a large import, playback that reads for minutes
 *    without a touch. [VeraCryptEngine] brackets its suspend calls with
 *    [operationStarted]/[operationFinished] and stamps its short ones with
 *    [recordOperation], so all of them are covered in one place rather than screen by
 *    screen, where the next long operation would be forgotten again.
 *
 * The security trade this makes, deliberately: an operation running unattended keeps the
 * vault unlocked for as long as it runs. Cutting an operation in half is the worse of the
 * two outcomes - the user is present for the ones that matter, and the window starts
 * counting from the moment the work stops rather than from the last touch before it.
 */
@Singleton
class IdleMonitor @Inject constructor() {

    @Volatile
    var lastInteractionAtMs: Long = SystemClock.elapsedRealtime()
        private set

    fun recordInteraction() {
        lastInteractionAtMs = SystemClock.elapsedRealtime()
    }

    /** Long operations currently running. A counter, not a flag: they can nest and overlap. */
    private val operationsInFlight = AtomicInteger(0)

    @Volatile
    private var lastOperationAtMs: Long = 0L

    /** True while at least one long operation is running. */
    val isBusy: Boolean get() = operationsInFlight.get() > 0

    fun operationStarted() {
        operationsInFlight.incrementAndGet()
    }

    /* The timestamp is written before the count drops, so there is no instant where nothing
     * is in flight and the last-work time is still stale - which is exactly the instant an
     * idle check would fire in. */
    fun operationFinished() {
        lastOperationAtMs = SystemClock.elapsedRealtime()
        operationsInFlight.decrementAndGet()
    }

    /** For work too short to bracket: an import is thousands of these, not one long call. */
    fun recordOperation() {
        lastOperationAtMs = SystemClock.elapsedRealtime()
    }

    /**
     * How long the app has had nothing to do. Zero while an operation is in flight, so a
     * caller comparing this against the auto-lock window can never lock during one.
     */
    fun idleMillis(): Long {
        if (operationsInFlight.get() > 0) return 0L
        val since = maxOf(lastInteractionAtMs, lastOperationAtMs)
        return SystemClock.elapsedRealtime() - since
    }
}
