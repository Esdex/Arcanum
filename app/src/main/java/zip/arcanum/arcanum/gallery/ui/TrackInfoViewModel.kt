package zip.arcanum.arcanum.gallery.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.gallery.JniMediaDataSource
import zip.arcanum.core.navigation.Screen
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject

/**
 * Reads what a track's file says about itself, for the File info screen.
 *
 * The whole file is open to the retriever here, not the first half megabyte the player reads
 * for its title: a cover can be large, and an M4A keeps its tags at the end.
 */
@HiltViewModel
class TrackInfoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val engine: VeraCryptEngine,
    private val repo: ContainerRepository
) : ViewModel() {

    data class TrackInfo(
        val title: String?,
        val artist: String?,
        val album: String?,
        val albumArtist: String?,
        val genre: String?,
        val year: String?,
        val trackNumber: String?,
        val cover: Bitmap?,
        val durationMs: Long?,
        val sampleRateHz: Int?,
        val bitrateBps: Int?,
        val channels: Int?,
        val modifiedMs: Long?
    )

    sealed interface State {
        data object Loading : State
        data class Loaded(val info: TrackInfo) : State
        /** The vault closed, or the file could not be read at all. */
        data object Failed : State
    }

    val containerId: String = savedStateHandle[Screen.TrackInfo.ARG_CONTAINER] ?: ""
    val path: String = "/" + (savedStateHandle.get<String>(Screen.TrackInfo.ARG_PATH) ?: "").trimStart('/')
    val name: String = savedStateHandle[Screen.TrackInfo.ARG_NAME] ?: ""
    val size: Long = savedStateHandle.get<String>(Screen.TrackInfo.ARG_SIZE)?.toLongOrNull() ?: 0L

    /** What kind of file it is, as AIMP puts it: the extension, in capitals. */
    val format: String = name.substringAfterLast('.', "").uppercase()

    private val _state = MutableStateFlow<State>(State.Loading)
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch(Dispatchers.IO) { _state.value = load() }
    }

    private fun load(): State {
        val h = repo.getContainerHandle(containerId) ?: return State.Failed
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(JniMediaDataSource(engine, h, path, size))
            fun tag(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            val (sampleRate, channels) = audioFormat(h)
            State.Loaded(TrackInfo(
                title        = tag(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist       = tag(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album        = tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist  = tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                genre        = tag(MediaMetadataRetriever.METADATA_KEY_GENRE),
                year         = tag(MediaMetadataRetriever.METADATA_KEY_YEAR),
                // "1/12" in the tag; AIMP shows the track's own number, and so do we.
                trackNumber  = tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/'),
                cover        = retriever.embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) },
                durationMs   = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                sampleRateHz = sampleRate,
                bitrateBps   = tag(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(),
                channels     = channels,
                modifiedMs   = modifiedAt(h)
            ))
        } catch (_: Exception) {
            State.Failed
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Sample rate and channel count, which the retriever only reports from Android 12. */
    private fun audioFormat(h: Long): Pair<Int?, Int?> {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(JniMediaDataSource(engine, h, path, size))
            val format = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return null to null
            fun int(key: String) = if (format.containsKey(key)) format.getInteger(key) else null
            int(MediaFormat.KEY_SAMPLE_RATE) to int(MediaFormat.KEY_CHANNEL_COUNT)
        } catch (_: Exception) {
            null to null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun modifiedAt(h: Long): Long? {
        val parent = path.substringBeforeLast('/', "").ifEmpty { "/" }
        return engine.listFilesOrNull(h, parent)
            ?.firstOrNull { it.name == name }
            ?.lastModified
            ?.takeIf { it > 0L }
    }
}
