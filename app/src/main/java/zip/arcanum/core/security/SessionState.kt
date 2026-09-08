package zip.arcanum.core.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    /*
     * The same answer as [isLocked], as a flow, because the thing that now enforces the lock
     * is not a composition any more: [LockController] runs in the process and has to start
     * and stop its idle clock when the app is unlocked and locked, with no Activity involved.
     * The @Volatile field stays because it is read from callbacks on any thread and must not
     * suspend.
     */
    private val _lockedFlow = MutableStateFlow(true)
    val lockedFlow: StateFlow<Boolean> = _lockedFlow.asStateFlow()

    fun markUnlocked() {
        unlockedInThisProcess = true
        isLocked = false
        _lockedFlow.value = false
    }

    fun markLocked() {
        isLocked = true
        _lockedFlow.value = true
    }
}
