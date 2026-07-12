package zip.arcanum.core.security

import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-wide record of when the user last interacted with the app, used to drive
 * inactivity ("idle") auto-lock. Backed by [SystemClock.elapsedRealtime] - a monotonic
 * clock that keeps counting across background and cannot be moved by changing the wall
 * clock, so the idle window can't be shortened or bypassed by clock tampering.
 *
 * [recordInteraction] is called from MainActivity.onUserInteraction() (every touch / key
 * event). Backgrounding deliberately does NOT touch the timestamp: idle is measured purely
 * from the last real interaction, so a vault left mounted while the app sits in the
 * background still ages out.
 */
@Singleton
class IdleMonitor @Inject constructor() {

    @Volatile
    var lastInteractionAtMs: Long = SystemClock.elapsedRealtime()
        private set

    fun recordInteraction() {
        lastInteractionAtMs = SystemClock.elapsedRealtime()
    }
}
