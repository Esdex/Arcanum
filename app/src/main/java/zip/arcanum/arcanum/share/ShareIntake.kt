package zip.arcanum.arcanum.share

import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Holds files handed to the app through the Android Share sheet (ACTION_SEND / SEND_MULTIPLE)
 * until the user has unlocked and chosen a destination vault + folder.
 *
 * The content URIs carry a read grant scoped to MainActivity's task, so they stay readable for the
 * life of the process/task - long enough for the unlock + destination flow. Nothing is persisted:
 * the intake is cleared once the files are saved (or the flow is abandoned), so a pending share
 * never survives a process restart.
 */
@Singleton
class ShareIntake @Inject constructor() {

    private val _pending = MutableStateFlow<List<Uri>>(emptyList())
    val pending: StateFlow<List<Uri>> = _pending.asStateFlow()

    fun offer(uris: List<Uri>) {
        if (uris.isNotEmpty()) _pending.value = uris
    }

    fun clear() {
        _pending.value = emptyList()
    }
}
