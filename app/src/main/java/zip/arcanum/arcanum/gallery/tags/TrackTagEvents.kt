package zip.arcanum.arcanum.gallery.tags

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tells the player that File info has written a track's tags, so that it shows them - and,
 * when the audio moved, starts the track again from where it was.
 */
@Singleton
class TrackTagEvents @Inject constructor() {

    /**
     * [path] in [containerId] was [oldSize] bytes and is [newSize] now. The same size means the
     * tag was written over the old one and the audio has not moved.
     */
    data class Saved(val containerId: String, val path: String, val oldSize: Long, val newSize: Long)

    private val _saved = MutableSharedFlow<Saved>(extraBufferCapacity = 8)
    val saved: SharedFlow<Saved> = _saved.asSharedFlow()

    fun notifySaved(event: Saved) {
        _saved.tryEmit(event)
    }
}
