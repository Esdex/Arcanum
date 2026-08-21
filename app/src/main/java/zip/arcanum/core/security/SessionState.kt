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
 */
@Singleton
class SessionState @Inject constructor() {

    @Volatile
    var unlockedInThisProcess: Boolean = false
        private set

    fun markUnlocked() {
        unlockedInThisProcess = true
    }
}
