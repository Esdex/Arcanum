package zip.arcanum.arcanum.gallery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A tap on the music's notification, waiting to be acted on.
 *
 * The tap opens the activity, and the activity may be sitting on the calculator or the PIN:
 * the player is opened only once the user is past them, like a share. Held here because the
 * activity that receives the tap is not the composition that navigates.
 */
@Singleton
class PlayerOpenRequest @Inject constructor() {
    private val _pending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    fun request() { _pending.value = true }
    fun consume() { _pending.value = false }

    companion object {
        /** The action the music session's activity intent carries. */
        const val ACTION = "zip.arcanum.OPEN_AUDIO_PLAYER"
    }
}
