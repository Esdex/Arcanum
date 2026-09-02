package zip.arcanum.core.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The one place a notification is raised, and the one place it waits its turn.
 *
 * Before this, four screens each kept their own `notification` state and hosted their own
 * banner, three view models put a `pendingNotification` in their UI state for a screen to
 * forward, and a second notification simply overwrote the first mid-animation - so two
 * things happening together meant one of them was never seen (#135).
 *
 * Screens and view models call [notify] and nothing else. The single host in AppNavigation
 * draws whatever [current] holds.
 *
 * ## The rules
 *
 * - Nothing on screen is ever replaced silently. What cannot be shown now waits.
 * - Something more serious interrupts something less serious, and the interrupted one is
 *   put back at the front of the queue rather than lost. Equal seriousness waits its turn -
 *   an error jumps a "file renamed", a "file renamed" does not jump another.
 * - Two of the same thing are one thing: a notification whose [InAppNotification.bannerKey]
 *   is already current or already waiting replaces it. Deleting twice in a row leaves one
 *   notification saying what is true now, not two saying it in sequence.
 * - An [NotificationBehaviour.ANNOUNCEMENT] never interrupts anything and always waits at
 *   the back. A donation prompt does not push in front of a failed import.
 * - The queue holds [MAX_WAITING]. Beyond that the least serious and oldest is dropped,
 *   and if the newcomer is the least serious of all, it is the one dropped.
 * - While delivery is closed - the calculator, the PIN screen, anything the user has not
 *   unlocked - nothing is shown at all and everything waits. A vault's name must never
 *   appear over the disguise, and an operation that finished while the app was locked is
 *   still worth reporting when the user comes back (which is what OperationRefusedLocked
 *   was hand-rolled to do).
 *
 * The queue is deliberately not saved anywhere. It dies with the process, like the mount
 * handles do: telling someone about a copy that finished before a background kill would be
 * telling them about another session's work.
 */
@Singleton
class NotificationCenter @Inject constructor() {

    private val _current = MutableStateFlow<InAppNotification?>(null)
    /** What the host should be showing, or null. */
    val current: StateFlow<InAppNotification?> = _current.asStateFlow()

    private val waiting = ArrayDeque<InAppNotification>()
    private var delivering = true

    /** Raise one. Safe from any thread. */
    @Synchronized
    fun notify(notification: InAppNotification) {
        val shown = _current.value

        // Same thing again: replace in place, wherever it is. Assigning to _current also
        // restarts the dwell, which is what "again" should mean.
        if (shown != null && shown.bannerKey == notification.bannerKey) {
            _current.value = notification
            return
        }
        val queuedAt = waiting.indexOfFirst { it.bannerKey == notification.bannerKey }
        if (queuedAt >= 0) {
            waiting[queuedAt] = notification
            return
        }

        if (!delivering) { enqueue(notification); return }

        if (shown == null) { _current.value = notification; return }

        val interrupts = notification.behaviour != NotificationBehaviour.ANNOUNCEMENT &&
                         notification.severity.rank > shown.severity.rank
        if (interrupts) {
            waiting.addFirst(shown)
            _current.value = notification
        } else {
            enqueue(notification)
        }
    }

    /** The current one is done with - dismissed, acted on, or its time ran out. */
    @Synchronized
    fun dismiss() {
        _current.value = if (delivering) waiting.removeFirstOrNull() else null
    }

    /**
     * Open or close delivery. Closed means locked: what is on screen goes back into the
     * queue and nothing new is shown until it opens again.
     */
    @Synchronized
    fun setDelivering(open: Boolean) {
        if (delivering == open) return
        delivering = open
        if (!open) {
            _current.value?.let { waiting.addFirst(it) }
            _current.value = null
        } else if (_current.value == null) {
            _current.value = waiting.removeFirstOrNull()
        }
    }

    /** How many are waiting. For the debug screen and for tests. */
    @get:Synchronized
    val waitingCount: Int get() = waiting.size

    private fun enqueue(notification: InAppNotification) {
        if (waiting.size < MAX_WAITING) {
            waiting.addLast(notification)
            return
        }
        // Full: the least serious goes, oldest first among equals. If that is the newcomer,
        // the newcomer is what goes - an old error outlives a new confirmation.
        val weakest = waiting.withIndex().minByOrNull { it.value.severity.rank }!!
        if (weakest.value.severity.rank < notification.severity.rank) {
            waiting.removeAt(weakest.index)
            waiting.addLast(notification)
        }
    }

    private companion object {
        const val MAX_WAITING = 3
    }
}

/**
 * The centre, reachable from any composable. Provided once in AppNavigation.
 *
 * Screens used to be handed an `onNotification` callback to pass up to whoever hosted a
 * banner, which is how MediaViewerScreen ended up wired to
 * `{ /* TODO: propagate to VaultScreen banner */ }` - every notification it raised, an
 * export among them, went nowhere at all.
 */
val LocalNotifications = androidx.compose.runtime.staticCompositionLocalOf<NotificationCenter> {
    error("No NotificationCenter provided - AppNavigation provides it")
}
