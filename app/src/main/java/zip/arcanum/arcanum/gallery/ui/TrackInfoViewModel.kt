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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.gallery.JniMediaDataSource
import zip.arcanum.arcanum.gallery.tags.Id3v2Tag
import zip.arcanum.arcanum.gallery.tags.Id3v2Writer
import zip.arcanum.arcanum.gallery.tags.TrackTagEvents
import zip.arcanum.arcanum.gallery.tags.VaultTagWriter
import zip.arcanum.core.navigation.Screen
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.notifications.NotificationCenter
import zip.arcanum.crypto.NativeFileInfo
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject

/**
 * Reads what a track's file says about itself, for the File info screen, and writes the tags
 * back when they are edited there.
 *
 * The whole file is open to the retriever here, not the first half megabyte the player reads
 * for its title: a cover can be large, and an M4A keeps its tags at the end.
 */
@HiltViewModel
class TrackInfoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val engine: VeraCryptEngine,
    private val repo: ContainerRepository,
    private val tagWriter: VaultTagWriter,
    private val tagEvents: TrackTagEvents,
    private val notifications: NotificationCenter
) : ViewModel() {

    data class TrackInfo(
        val title: String?,
        val artist: String?,
        val album: String?,
        val albumArtist: String?,
        val genre: String?,
        val year: String?,
        val trackNumber: String?,
        val lyricist: String?,
        val lyrics: String?,
        val cover: Bitmap?,
        val durationMs: Long?,
        val sampleRateHz: Int?,
        val bitrateBps: Int?,
        val channels: Int?,
        val modifiedMs: Long?
    )

    /** Whether the pencil may open the editor, and if not, why. */
    enum class Editability {
        EDITABLE,
        /** The vault is mounted read-only. */
        READ_ONLY,
        /** A tag this app cannot write back without losing part of it, or one it cannot read. */
        UNSUPPORTED_TAG,
        /** Not an MP3: FLAC and the others have no writer yet, and show no pencil. */
        NO_WRITER
    }

    sealed interface State {
        data object Loading : State
        data class Loaded(val info: TrackInfo, val editability: Editability) : State
        /** The vault closed, or the file could not be read at all. */
        data object Failed : State
    }

    /** The fields while they are being edited; null when the screen only shows them. */
    data class Editor(
        val values: Id3v2Writer.Values,
        val saving: Boolean = false,
        /** The last save did not land. The fields keep what was typed. */
        val failed: Boolean = false
    )

    val containerId: String = savedStateHandle[Screen.TrackInfo.ARG_CONTAINER] ?: ""
    val path: String = "/" + (savedStateHandle.get<String>(Screen.TrackInfo.ARG_PATH) ?: "").trimStart('/')
    val name: String = savedStateHandle[Screen.TrackInfo.ARG_NAME] ?: ""
    val size: Long = savedStateHandle.get<String>(Screen.TrackInfo.ARG_SIZE)?.toLongOrNull() ?: 0L

    /** What kind of file it is, as AIMP puts it: the extension, in capitals. */
    val format: String = name.substringAfterLast('.', "").uppercase()

    private val _state = MutableStateFlow<State>(State.Loading)
    val state = _state.asStateFlow()

    private val _editor = MutableStateFlow<Editor?>(null)
    val editor = _editor.asStateFlow()

    /** Set when the pencil was tapped on a tag that cannot be written back. */
    private val _unsupportedTag = MutableStateFlow(false)
    val unsupportedTag = _unsupportedTag.asStateFlow()

    /** What the screen showed before editing: a field typed back to this was not edited. */
    private var shown = Id3v2Writer.Values()

    /**
     * Saving runs here rather than in [viewModelScope], like the text editor's: a write cut
     * short half way through is what the care in [VaultTagWriter] is there to avoid.
     */
    private val saver = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            // Our own reading of an ID3v2 tag comes first: it has the full date where the
            // platform gives only the year, and the lyrics, which the platform does not give
            // at all. The platform fills in for everything else - ID3v1, FLAC, M4A.
            val read = readId3(h, size)
            val id3 = read.tag
            val info = TrackInfo(
                title        = id3?.title ?: tag(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist       = id3?.artist ?: tag(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                album        = id3?.album ?: tag(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                albumArtist  = id3?.albumArtist ?: tag(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                genre        = id3?.genre ?: tag(MediaMetadataRetriever.METADATA_KEY_GENRE),
                year         = id3?.year ?: tag(MediaMetadataRetriever.METADATA_KEY_YEAR),
                // "1/12" in the tag; AIMP shows the track's own number, and so do we.
                trackNumber  = id3?.trackNumber ?: tag(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/'),
                lyricist     = id3?.lyricist,
                lyrics       = id3?.lyrics,
                cover        = (id3?.cover ?: retriever.embeddedPicture)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) },
                durationMs   = tag(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull(),
                sampleRateHz = sampleRate,
                bitrateBps   = tag(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toIntOrNull(),
                channels     = channels,
                modifiedMs   = stat(h)?.lastModified?.takeIf { it > 0L }
            )
            shown = Id3v2Writer.Values(
                title = info.title, artist = info.artist, album = info.album, albumArtist = info.albumArtist,
                genre = info.genre, year = info.year, trackNumber = info.trackNumber,
                lyricist = info.lyricist, lyrics = info.lyrics
            )
            val editability = when {
                format != "MP3" -> Editability.NO_WRITER
                repo.isContainerReadOnly(containerId) -> Editability.READ_ONLY
                read.size > 0 && id3?.rewritable != true -> Editability.UNSUPPORTED_TAG
                else -> Editability.EDITABLE
            }
            State.Loaded(info, editability)
        } catch (_: Exception) {
            State.Failed
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ── Editing ───────────────────────────────────────────────────────────

    /** The pencil: opens the editor, or says why it cannot. */
    fun startEditing() {
        val s = _state.value as? State.Loaded ?: return
        when (s.editability) {
            Editability.EDITABLE -> _editor.value = Editor(shown)
            Editability.READ_ONLY -> notifications.notify(InAppNotification.ReadOnlyError)
            Editability.UNSUPPORTED_TAG -> _unsupportedTag.value = true
            Editability.NO_WRITER -> Unit
        }
    }

    fun dismissUnsupportedTag() { _unsupportedTag.value = false }

    fun onEdit(values: Id3v2Writer.Values) {
        _editor.update { it?.copy(values = values) }
    }

    /** Whether anything on screen differs from what the file said. */
    fun hasChanges(): Boolean = _editor.value?.let { differs(it.values, shown) } ?: false

    fun cancelEditing() { _editor.value = null }

    fun dismissSaveFailure() { _editor.update { it?.copy(failed = false) } }

    /**
     * The save button: writes the tags and calls [onSaved] once they are in the file. Nothing
     * changed is nothing written, so the file keeps its date.
     */
    fun save(onSaved: () -> Unit) {
        val e = _editor.value ?: return
        if (e.saving) return
        if (!differs(e.values, shown)) { _editor.value = null; onSaved(); return }
        _editor.value = e.copy(saving = true, failed = false)
        saver.launch {
            val ok = runCatching { write(e.values) }.getOrDefault(false)
            withContext(Dispatchers.Main) {
                if (ok) { _editor.value = null; onSaved() }
                else _editor.update { it?.copy(saving = false, failed = true) }
            }
        }
    }

    /**
     * Reads the tag again rather than trusting what was read on opening, and writes the edit
     * into it. Only the fields that were changed are handed on as changed; the others keep
     * what the tag itself holds, which is not always what was shown - a field the tag lacks is
     * filled in on screen from ID3v1.
     *
     * A file with no ID3v2 tag gets one with everything that was shown, edited or not: the
     * platform reads either ID3v2 or ID3v1, not a mix, so a new tag holding only the edited
     * title would lose the artist and the album from every other player.
     */
    private fun write(edited: Id3v2Writer.Values): Boolean {
        val h = repo.getContainerHandle(containerId) ?: return false
        val size = stat(h)?.size ?: return false
        val read = readId3(h, size)
        val old = read.tag
        if (read.size > 0 && old?.rewritable != true) return false
        val values = if (old == null) edited else merge(Id3v2Writer.valuesOf(old), edited)
        val rendered = Id3v2Writer.render(old, values)
        val newSize = tagWriter.write(h, path, size, old?.totalSize ?: 0, rendered) ?: return false
        tagEvents.notifySaved(TrackTagEvents.Saved(containerId, path, size, newSize))
        return true
    }

    /** [edited] where it differs from what was shown, [base] everywhere else. */
    private fun merge(base: Id3v2Writer.Values, edited: Id3v2Writer.Values): Id3v2Writer.Values {
        fun pick(b: String?, e: String?, s: String?) = if (same(e, s)) b else e
        return Id3v2Writer.Values(
            title       = pick(base.title, edited.title, shown.title),
            artist      = pick(base.artist, edited.artist, shown.artist),
            album       = pick(base.album, edited.album, shown.album),
            albumArtist = pick(base.albumArtist, edited.albumArtist, shown.albumArtist),
            genre       = pick(base.genre, edited.genre, shown.genre),
            year        = pick(base.year, edited.year, shown.year),
            trackNumber = pick(base.trackNumber, edited.trackNumber, shown.trackNumber),
            lyricist    = pick(base.lyricist, edited.lyricist, shown.lyricist),
            lyrics      = pick(base.lyrics, edited.lyrics, shown.lyrics)
        )
    }

    private fun differs(a: Id3v2Writer.Values, b: Id3v2Writer.Values) =
        !same(a.title, b.title) || !same(a.artist, b.artist) || !same(a.album, b.album) ||
            !same(a.albumArtist, b.albumArtist) || !same(a.genre, b.genre) || !same(a.year, b.year) ||
            !same(a.trackNumber, b.trackNumber) || !same(a.lyricist, b.lyricist) || !same(a.lyrics, b.lyrics)

    private fun same(a: String?, b: String?) = a?.trim().orEmpty() == b?.trim().orEmpty()

    // ── Reading ───────────────────────────────────────────────────────────

    /** The tag's size from its header (0 for none) and the tag, if it could be read. */
    private class TagRead(val size: Int, val tag: Id3v2Tag?)

    /**
     * The ID3v2 tag at the start of the file, if there is one. Read in pieces: a single read
     * of more than 16 MB out of the vault comes back empty, and a cover can be that large.
     */
    private fun readId3(h: Long, fileSize: Long): TagRead {
        val header = engine.readFile(h, path, 0L, Id3v2Tag.HEADER_SIZE) ?: return TagRead(0, null)
        val total = Id3v2Tag.sizeFromHeader(header)
        if (total <= 0) return TagRead(0, null)
        if (total > MAX_TAG_SIZE || total > fileSize) return TagRead(total, null)
        val bytes = ByteArray(total)
        var at = 0
        while (at < total) {
            val chunk = engine.readFile(h, path, at.toLong(), minOf(READ_CHUNK, total - at))
            if (chunk == null || chunk.isEmpty()) return TagRead(total, null)
            chunk.copyInto(bytes, at)
            at += chunk.size
        }
        return TagRead(total, Id3v2Tag.parse(bytes))
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

    private companion object {
        const val READ_CHUNK = 4 * 1024 * 1024
        /** A tag larger than this is not a tag anyone made on purpose; the platform reads it instead. */
        const val MAX_TAG_SIZE = 64 * 1024 * 1024
    }

    /** The file's entry in its folder: its size as it is now, and its date. */
    private fun stat(h: Long): NativeFileInfo? {
        val parent = path.substringBeforeLast('/', "").ifEmpty { "/" }
        return engine.listFilesOrNull(h, parent)?.firstOrNull { it.name == name }
    }
}
