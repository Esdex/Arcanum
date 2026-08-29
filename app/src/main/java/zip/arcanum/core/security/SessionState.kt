package zip.arcanum.core.security

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Whether the PIN has been accepted in *this* process.
 *
 * Android restores the navigation back stack after a background kill, so the app can come
 * back sitting on an authenticated screen inside a process that never saw the PIN. The idle
 * clock cannot catch that on its own: [IdleMonitor] is rebuilt with the current time, so the
 * time spent dead counts as zero idle and auto-lock never fires (#150).
 *
 * A configuration change - rotation, theme, locale - recreates the activity but not the
 * process, so this flag survives it and the user is not asked to unlock again.
 *
 * [isLocked] is a different question from [unlockedInThisProcess]: the first says whether
 * the PIN screen is up right now, the second whether it was ever passed at all. Work that
 * arrives from outside - a file picker returning after the session locked - has to ask the
 * first, because a ViewModel outlives the navigation to the lock screen and will happily
 * carry on writing into a vault the user has been told is closed.
 */
@Singleton
class SessionState @Inject constructor() {

    @Volatile
    var unlockedInThisProcess: Boolean = false
        private set

    /** True until the PIN is accepted, and again from the moment the app locks itself. */
    @Volatile
    var isLocked: Boolean = true
        private set

    fun markUnlocked() {
        unlockedInThisProcess = true
        isLocked = false
    }

    fun markLocked() {
        isLocked = true
    }
}
