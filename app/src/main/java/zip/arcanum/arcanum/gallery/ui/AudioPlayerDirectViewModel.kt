package zip.arcanum.arcanum.gallery.ui

import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.palette.graphics.Palette
import com.google.common.util.concurrent.ListenableFuture
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.gallery.AudioPlayerQueue
import zip.arcanum.arcanum.gallery.ByteArrayMediaDataSource
import zip.arcanum.arcanum.gallery.JniMediaDataSource
import zip.arcanum.arcanum.gallery.ServiceEncryptedDataSource
import zip.arcanum.arcanum.gallery.WaveformBuilder
import zip.arcanum.arcanum.gallery.domain.AudioMetadata
import zip.arcanum.arcanum.gallery.service.ArcanumMediaService
import zip.arcanum.arcanum.gallery.service.COMMAND_RESHUFFLE
import zip.arcanum.arcanum.gallery.service.NEUTRAL_METADATA
import zip.arcanum.core.navigation.Screen
import zip.arcanum.core.security.IdleMonitor
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/** Leads a cached waveform. Raised whenever the way bars are worked out changes. */
private const val WAVEFORM_CACHE_VERSION = 0x57460002

@androidx.annotation.OptIn(UnstableApi::class)
@HiltViewModel
class AudioPlayerDirectViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    val engine: VeraCryptEngine,
    private val repo: ContainerRepository,
    private val queue: AudioPlayerQueue,
    private val idleMonitor: IdleMonitor,
    private val prefs: zip.arcanum.core.security.AppPreferences,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    enum class RepeatMode { NONE, ALL, ONE }

    data class PlayerState(
        val metadata: AudioMetadata? = null,
        val waveformBars: List<Float>? = null,
        val isPlaying: Boolean = false,
        val progress: Float = 0f,
        val currentPositionMs: Long = 0L,
        val durationMs: Long = 0L,
        val isShuffled: Boolean = false,
        val repeatMode: RepeatMode = RepeatMode.NONE,
        val playlistSize: Int = 1,
        val currentIndex: Int = 0,
        val dominantColor: Int? = null,
        val error: String? = null
    )

    val containerId: String = savedStateHandle[Screen.AudioPlayerDirect.ARG_CONTAINER] ?: ""
    private val navPath: String = "/" + (savedStateHandle.get<String>(Screen.AudioPlayerDirect.ARG_PATH) ?: "").trimStart('/')
    private val navName: String = savedStateHandle[Screen.AudioPlayerDirect.ARG_NAME] ?: ""
    private val navSize: Long = savedStateHandle.get<String>(Screen.AudioPlayerDirect.ARG_SIZE)?.toLongOrNull() ?: 0L
    private val navResume: Boolean = savedStateHandle[Screen.AudioPlayerDirect.ARG_RESUME] ?: false

    private val _state = MutableStateFlow(PlayerState(
        playlistSize = queue.playlist.size.coerceAtLeast(1),
        currentIndex = queue.currentIndex
    ))
    val state = _state.asStateFlow()

    /*
     * The whole playlist goes to the player, and the player owns the order, the shuffle and
     * the repeat (#139). It used to be handed one track at a time, and a timeline of one item
     * has no next to offer: the notification could not show a next button and its previous
     * degraded to a seek back to zero. Skipping worked inside the app only because the app
     * kept the list itself.
     *
     * Which track is playing is therefore `mediaController.currentMediaItemIndex` and not a
     * field here - two places holding that would be one place too many.
     */
    private val playlist: List<zip.arcanum.crypto.NativeFileInfo> get() = queue.playlist

    private val currentIndex: Int
        get() = mediaController?.currentMediaItemIndex ?: queue.currentIndex

    val handle: Long get() = repo.getContainerHandle(containerId) ?: 0L

    // Backed by the MediaController (implements Player); null until service connects
    val exoPlayer: Player? get() = mediaController

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var progressJob: Job? = null
    private var loadJob: Job? = null

    /** Which index the metadata and the waveform in [_state] belong to. */
    private var loadedIndex: Int = -1

    private val playerListener = object : Player.Listener {
        /*
         * Every move between tracks arrives here, whoever caused it: the buttons in the app,
         * the ones in the notification, a headset, a car, or the player reaching the end of a
         * track on its own.
         */
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            onTrackChanged()
        }
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
        }
        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(error = error.message) }
        }
    }

    init {
        val token = SessionToken(appContext, ComponentName(appContext, ArcanumMediaService::class.java))
        /* Applied to what is already playing rather than only to the next track: a setting
           that takes effect later looks broken. */
        viewModelScope.launch {
            prefs.mediaSessionContent.collect { on ->
                showContent = on
                val mc = mediaController ?: return@collect
                val item = mc.currentMediaItem ?: return@collect
                val wanted = sessionMetadata()
                if (item.mediaMetadata != wanted) {
                    mc.replaceMediaItem(
                        mc.currentMediaItemIndex,
                        item.buildUpon().setMediaMetadata(wanted).build()
                    )
                }
            }
        }

        controllerFuture = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture!!.addListener({
            val mc = runCatching { controllerFuture!!.get() }.getOrElse { e ->
                _state.update { it.copy(error = "Media service unavailable: ${e.message}") }
                return@addListener
            }
            mediaController = mc
            mc.addListener(playerListener)
            startProgressTracking()
            if (shouldAttach(mc)) attachQueue(mc) else startQueue(mc, queue.currentIndex)
        }, ContextCompat.getMainExecutor(appContext))
    }

    /**
     * What the shared MediaSession is allowed to say about this track.
     *
     * Off by default, and then the session gets a fixed neutral title: whatever it carries is
     * mirrored to the system notification, the lock screen and every connected controller,
     * which is past the PIN, past biometrics, past the disguise and past FLAG_SECURE. Turned
     * on, the track and its cover appear out there like any other player - a choice that
     * belongs to the person using it, not to us.
     */
    private fun sessionMetadata(): MediaMetadata {
        if (!showContent) return NEUTRAL_METADATA
        val m = _state.value.metadata ?: return NEUTRAL_METADATA
        return MediaMetadata.Builder()
            .setTitle(m.title)
            .setArtist(m.artist)
            .setAlbumTitle(m.album)
            .apply {
                m.artwork?.let { bmp ->
                    val out = java.io.ByteArrayOutputStream()
                    if (bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, out))
                        setArtworkData(out.toByteArray(), MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                }
            }
            .build()
    }

    @Volatile private var showContent = false

    // ── Track loading ─────────────────────────────────────────────────────

    /**
     * Puts the whole playlist on the player and starts at [startIndex].
     *
     * Every item carries metadata, because an item without it makes the notification show
     * nothing when the player moves to it. What that metadata may say is a separate question
     * - see [sessionMetadata].
     */
    private fun startQueue(mc: MediaController, startIndex: Int) {
        val files = playlist
        val items = if (files.isEmpty()) {
            // Opened straight at one file, without a list behind it.
            listOf(mediaItemFor(navPath, navSize))
        } else {
            files.map { mediaItemFor("/" + it.path.trimStart('/'), it.size) }
        }
        val index = startIndex.coerceIn(0, items.size - 1)
        mc.setMediaItems(items, index, androidx.media3.common.C.TIME_UNSET)
        mc.shuffleModeEnabled = _state.value.isShuffled
        mc.repeatMode = _state.value.repeatMode.toPlayerRepeatMode()
        mc.prepare()
        mc.playWhenReady = true
        // A fresh queue: whatever was loaded before belongs to the old one.
        loadedIndex = -1
        onTrackChanged()
    }

    /**
     * Whether the player is taken as it is rather than started again - which would throw away
     * the position, the track the player has moved on to, and the shuffled order. That is so
     * when the screen is opened from the music's notification, or onto the track that is
     * playing. A different track picked from the same folder brings the same queue with it,
     * so the queue alone cannot tell: matching it only used to keep the old track playing.
     */
    private fun shouldAttach(mc: MediaController): Boolean =
        isPlayingThisQueue(mc) && (navResume || mc.currentMediaItemIndex == queue.currentIndex)

    /** Whether the player already holds exactly the queue this screen would put on it. */
    private fun isPlayingThisQueue(mc: MediaController): Boolean {
        val paths = if (playlist.isEmpty()) listOf(navPath) else playlist.map { "/" + it.path.trimStart('/') }
        if (mc.mediaItemCount != paths.size) return false
        return paths.indices.all { i ->
            val uri = mc.getMediaItemAt(i).localConfiguration?.uri
            uri?.getQueryParameter("cid") == containerId && uri.getQueryParameter("path") == paths[i]
        }
    }

    private fun attachQueue(mc: MediaController) {
        _state.update { it.copy(
            isShuffled = mc.shuffleModeEnabled,
            repeatMode = when (mc.repeatMode) {
                Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                else                   -> RepeatMode.NONE
            },
            isPlaying  = mc.isPlaying
        ) }
        loadedIndex = -1
        onTrackChanged()
    }

    private fun mediaItemFor(path: String, size: Long): MediaItem {
        val uri = Uri.Builder()
            .scheme(ServiceEncryptedDataSource.URI_SCHEME)
            .authority("media")
            .appendQueryParameter("cid", containerId)
            .appendQueryParameter("path", path)
            .appendQueryParameter("size", size.toString())
            .build()
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(NEUTRAL_METADATA)
            .build()
    }

    /**
     * A different track is playing. Reads its tags for the in-app screen, redraws the
     * waveform, and - only when the user has asked for it - puts the real name on the item so
     * the notification can show it too.
     */
    private fun onTrackChanged() {
        val index = currentIndex
        /*
         * Replacing an item to give it metadata is itself a transition, and that transition
         * arrives back here: without this the track would be read, replaced, read again and
         * replaced again for as long as it played, and the cover flickered the whole time.
         * The index is the thing that says a different track is playing; a replacement of the
         * same one is not news.
         */
        if (index == loadedIndex) return
        loadedIndex = index
        _state.update { it.copy(waveformBars = null, error = null, currentIndex = index) }
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            val path = pathAt(index)
            val size = sizeAt(index)
            val name = nameAt(index)
            val h    = handle

            val headerBytes = runCatching {
                engine.readFile(h, path, 0L, minOf(size, 524288L).toInt())
            }.getOrNull()

            val metadata = parseMetadata(headerBytes, name)
            val dominantColor = metadata.artwork?.let { bmp ->
                runCatching { Palette.from(bmp).generate().getDominantColor(0) }.getOrNull()
            }
            _state.update { it.copy(metadata = metadata, dominantColor = dominantColor) }

            withContext(Dispatchers.Main) {
                if (!showContent) return@withContext
                val mc = mediaController ?: return@withContext
                // Tags are read for the track that is playing, not for the whole list: the
                // list can be long, and each one costs half a megabyte read out of the vault.
                val item = mc.currentMediaItem ?: return@withContext
                val wanted = sessionMetadata()
                if (item.mediaMetadata != wanted) {
                    mc.replaceMediaItem(
                        mc.currentMediaItemIndex,
                        item.buildUpon().setMediaMetadata(wanted).build()
                    )
                }
            }

            val bars = generateWaveform(h, path, size)
            // The track may have changed while it was decoding; its bars are not this one's.
            ensureActive()
            _state.update { it.copy(waveformBars = bars) }
        }
    }

    private fun parseMetadata(bytes: ByteArray?, fallbackName: String): AudioMetadata {
        if (bytes == null || bytes.isEmpty()) {
            return AudioMetadata(stripExtension(fallbackName), "Unknown Artist", "Unknown Album", 0L, null)
        }
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(ByteArrayMediaDataSource(bytes))
            val title  = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?: stripExtension(fallbackName)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: "Unknown Artist"
            val album  = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?: "Unknown Album"
            val dur    = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val art    = retriever.embeddedPicture?.let {
                BitmapFactory.decodeByteArray(it, 0, it.size)
            }
            retriever.release()
            AudioMetadata(title, artist, album, dur, art)
        } catch (_: Exception) {
            AudioMetadata(stripExtension(fallbackName), "Unknown Artist", "Unknown Album", 0L, null)
        }
    }

    /**
     * The waveform of a track, from the cache or worked out from the whole of its audio.
     *
     * When the audio cannot be decoded the bars are flat, and that is not cached, so the next
     * play tries again. A shape made up from anything else would be a lie: the old fallback
     * measured the raw bytes of the file as if they were samples, which for any compressed
     * format is noise.
     */
    private suspend fun generateWaveform(h: Long, path: String, fileSize: Long, barCount: Int = 80): List<Float> {
        if (fileSize <= 0) return List(barCount) { 0.5f }
        // Return cached result instantly on repeated plays
        loadCachedWaveform(path, fileSize, barCount)?.let { return it }
        val bars = decodeWaveform(h, path, fileSize, barCount)
        // Cancelled because another track came on: nothing to show and nothing to keep.
        currentCoroutineContext().ensureActive()
        if (bars == null) return List(barCount) { 0.5f }
        saveWaveformCache(path, fileSize, bars)
        return bars
    }

    /**
     * Decodes the whole track, in order, and hands every frame to a [WaveformBuilder].
     *
     * It used to jump to 80 points and measure the first buffer the decoder gave back at each:
     * about 26 ms of MP3 out of a stretch several seconds long, so a bar landed on a drum hit or
     * between two and the shape of the song was lost. Worse, an MP3 frame leans on the frames
     * before it, so the first one after a jump comes out too quiet: every bar but the first was
     * low, and the first stood alone at full height.
     */
    private suspend fun decodeWaveform(h: Long, path: String, fileSize: Long, barCount: Int): List<Float>? {
        val job = currentCoroutineContext()
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(JniMediaDataSource(engine, h, path, fileSize))
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)

            codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()

            // 50 ms blocks: fine enough for any number of bars, few enough for an hour-long mix.
            val builder = WaveformBuilder((format.getInteger(MediaFormat.KEY_SAMPLE_RATE) / 20).coerceAtLeast(1))
            var pcm = PcmReader(AudioFormat.ENCODING_PCM_16BIT, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            /*
             * Every free input buffer is filled and every ready output taken before waiting, so
             * the decoder, which runs in another process, is never left idle between frames.
             *
             * Measured on a 2:16 MP3 (5.7 MB, 5702 frames), 5.6 s in all: MediaExtractor 3.45 s,
             * about 0.6 ms a frame, of which reading the vault was 0.24 s; waiting on the decoder
             * 0.45 s; reading the samples 0.32 s. The extractor is the cost, and it is the
             * platform's - Media3's in-process MediaExtractorCompat is the way out of it.
             */
            while (job.isActive) {
                var moved = false
                while (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(0L)
                    if (inIdx < 0) break
                    val sz = extractor.readSampleData(codec.getInputBuffer(inIdx)!!, 0)
                    if (sz < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                    moved = true
                }
                while (true) {
                    // Wait only when nothing at all moved; otherwise take what is ready and go on feeding.
                    val outIdx = codec.dequeueOutputBuffer(info, if (moved) 0L else 5_000L)
                    when {
                        outIdx >= 0 -> {
                            moved = true
                            if (info.size > 0) pcm.read(codec.getOutputBuffer(outIdx)!!, info, builder)
                            codec.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return builder.bars(barCount)
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val out = codec.outputFormat
                            pcm = PcmReader(
                                out.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT),
                                out.getInteger(MediaFormat.KEY_CHANNEL_COUNT, 2)
                            )
                        }
                        outIdx == MediaCodec.INFO_TRY_AGAIN_LATER -> break
                    }
                }
                // A decoder that stops answering would otherwise hold this job forever (~5 s).
                idle = if (moved) 0 else idle + 1
                if (idle > 1000) return null
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { extractor?.release() } catch (_: Exception) {}
        }
    }

    private fun loadCachedWaveform(path: String, fileSize: Long, barCount: Int): List<Float>? {
        return try {
            val file = waveformCacheFile(path, fileSize)
            if (!file.exists()) return null
            val encFile = androidx.security.crypto.EncryptedFile.Builder(
                appContext, file, waveformMasterKey(),
                androidx.security.crypto.EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
            val bb = java.nio.ByteBuffer.wrap(encFile.openFileInput().use { it.readBytes() })
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            // Bars from the old sampling carry no version and are refused, so they are worked
            // out again rather than kept: they were wrong, and the cache would have kept them
            // for as long as the file did not change size.
            if (bb.int != WAVEFORM_CACHE_VERSION) return null
            val count = bb.int
            if (count != barCount) return null
            List(count) { bb.float }
        } catch (_: Exception) { null }
    }

    private fun saveWaveformCache(path: String, fileSize: Long, bars: List<Float>) {
        try {
            val file = waveformCacheFile(path, fileSize)
            // EncryptedFile cannot overwrite — delete stale file first
            if (file.exists()) file.delete()
            val encFile = androidx.security.crypto.EncryptedFile.Builder(
                appContext, file, waveformMasterKey(),
                androidx.security.crypto.EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
            ).build()
            val bb = java.nio.ByteBuffer.allocate(8 + bars.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.putInt(WAVEFORM_CACHE_VERSION)
            bb.putInt(bars.size)
            bars.forEach { bb.putFloat(it) }
            encFile.openFileOutput().use { it.write(bb.array()) }
        } catch (_: Exception) {}
    }

    /*
     * The vault is its own segment of the name rather than being mixed into one hash. Mixed
     * in, it could not be picked out again, so a vault that was forgotten or wiped left its
     * waveforms in the cache with no way to tell which were its (#134). The contents were
     * always encrypted; the names were not, and they outlived the vault.
     */
    private fun waveformCacheFile(path: String, fileSize: Long): java.io.File {
        val vault = zip.arcanum.core.security.VaultTraceCleaner.waveformVaultKey(containerId)
        val file  = java.lang.Long.toHexString(path.hashCode().toLong() xor fileSize)
        return java.io.File(appContext.cacheDir, "wf_${vault}_$file.dat")
    }

    private fun waveformMasterKey(): androidx.security.crypto.MasterKey =
        androidx.security.crypto.MasterKey.Builder(appContext)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build()

    // ── Playback controls ─────────────────────────────────────────────────

    fun togglePlayPause() = viewModelScope.launch(Dispatchers.Main) {
        val mc = mediaController ?: return@launch
        if (mc.mediaItemCount == 0) {
            // The video player shares this service and clears the queue when it takes over.
            // Coming back rebuilds the whole list, not just the track that was playing.
            startQueue(mc, _state.value.currentIndex)
            return@launch
        }
        if (mc.isPlaying) mc.pause() else mc.play()
    }

    fun seekTo(progress: Float) = viewModelScope.launch(Dispatchers.Main) {
        val mc = mediaController ?: return@launch
        val dur = mc.duration.coerceAtLeast(1L)
        mc.seekTo((progress * dur).toLong())
    }

    fun playNext() = viewModelScope.launch(Dispatchers.Main) {
        mediaController?.seekToNextMediaItem()
    }

    /* Media3's own rule, and the one the app already had: past the first few seconds the
       button restarts the track instead of leaving it. */
    fun playPrevious() = viewModelScope.launch(Dispatchers.Main) {
        mediaController?.seekToPrevious()
    }

    /* Shuffle and repeat are the player's, not ours. Reordering the list by hand was what
       kept the notification from being able to offer either. */
    fun toggleShuffle() {
        val on = !_state.value.isShuffled
        _state.update { it.copy(isShuffled = on) }
        viewModelScope.launch(Dispatchers.Main) {
            val mc = mediaController ?: return@launch
            mc.shuffleModeEnabled = on
            /* Turning it on lays the order out again with the current track first. Without
               that, the order drawn when the queue was built is reused - so switching shuffle
               off and on gives the same order back, and switching it on while the current
               track happens to sit last in that order leaves nothing after it, which takes
               the next button out of the notification. */
            if (on) mc.sendCustomCommand(
                androidx.media3.session.SessionCommand(COMMAND_RESHUFFLE, android.os.Bundle.EMPTY),
                android.os.Bundle.EMPTY
            )
        }
    }

    fun cycleRepeat() {
        val next = when (_state.value.repeatMode) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL  -> RepeatMode.ONE
            RepeatMode.ONE  -> RepeatMode.NONE
        }
        _state.update { it.copy(repeatMode = next) }
        viewModelScope.launch(Dispatchers.Main) {
            mediaController?.repeatMode = next.toPlayerRepeatMode()
        }
    }

    private fun RepeatMode.toPlayerRepeatMode(): Int = when (this) {
        RepeatMode.NONE -> Player.REPEAT_MODE_OFF
        RepeatMode.ALL  -> Player.REPEAT_MODE_ALL
        RepeatMode.ONE  -> Player.REPEAT_MODE_ONE
    }

    // ── Progress tracking ─────────────────────────────────────────────────

    private fun startProgressTracking() {
        progressJob = viewModelScope.launch(Dispatchers.Main) {
            while (true) {
                delay(200L)
                val mc = mediaController ?: continue
                val pos = mc.currentPosition
                val dur = mc.duration.let { if (it <= 0L) 1L else it }
                _state.update { it.copy(
                    currentPositionMs = pos,
                    durationMs        = dur,
                    progress          = (pos.toFloat() / dur).coerceIn(0f, 1f),
                    isPlaying         = mc.isPlaying
                )}
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** A track as File info is told about it: where it lies, what it is called, how big it is. */
    data class TrackRef(val path: String, val name: String, val size: Long)

    /** The track on screen - the one the player is on, which the list may have moved past. */
    fun currentTrack(): TrackRef = currentIndex.let { TrackRef(pathAt(it), nameAt(it), sizeAt(it)) }

    private fun pathAt(index: Int): String =
        playlist.getOrNull(index)?.path?.let { "/" + it.trimStart('/') } ?: navPath

    private fun nameAt(index: Int): String =
        playlist.getOrNull(index)?.name ?: navName

    private fun sizeAt(index: Int): Long =
        playlist.getOrNull(index)?.size?.takeIf { it > 0L } ?: navSize

    private fun stripExtension(name: String) = name.substringBeforeLast(".", name)

    // Refresh the idle auto-lock baseline. Called periodically by the screen while audio is
    // actively playing so listening (which produces no touch events) doesn't trip the idle timer.
    fun recordInteraction() = idleMonitor.recordInteraction()

    override fun onCleared() {
        progressJob?.cancel()
        loadJob?.cancel()
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}

/**
 * Reads the decoder's output into frame energies, whichever sample format the decoder chose.
 *
 * Only 16-bit and float used to be understood. A 24-bit FLAC can come out as packed 24-bit or
 * as 32-bit samples, and reading those as 16-bit pairs draws noise.
 */
private class PcmReader(private val encoding: Int, private val channels: Int) {

    // Reused between buffers: a buffer is copied out in one go and walked as an array, which
    // costs a fraction of fetching every sample from the ByteBuffer on its own.
    private var shorts = ShortArray(0)
    private var floats = FloatArray(0)
    private var ints = IntArray(0)
    private var bytes = ByteArray(0)

    fun read(buffer: java.nio.ByteBuffer, info: MediaCodec.BufferInfo, into: WaveformBuilder) {
        if (channels <= 0) return
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val b = buffer.slice().order(java.nio.ByteOrder.nativeOrder())
        when (encoding) {
            AudioFormat.ENCODING_PCM_16BIT -> {
                val n = b.remaining() / 2
                if (shorts.size < n) shorts = ShortArray(n)
                b.asShortBuffer().get(shorts, 0, n)
                frames(n, into) { shorts[it] / 32768.0 }
            }
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val n = b.remaining() / 4
                if (floats.size < n) floats = FloatArray(n)
                b.asFloatBuffer().get(floats, 0, n)
                frames(n, into) { floats[it].toDouble() }
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                val n = b.remaining() / 4
                if (ints.size < n) ints = IntArray(n)
                b.asIntBuffer().get(ints, 0, n)
                frames(n, into) { ints[it] / 2147483648.0 }
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val n = b.remaining() / 3
                if (bytes.size < n * 3) bytes = ByteArray(n * 3)
                b.get(bytes, 0, n * 3)
                frames(n, into) {
                    // Little-endian, as nativeOrder is on every Android device; the top byte keeps its sign.
                    val lo = bytes[it * 3].toInt() and 0xFF
                    val mid = bytes[it * 3 + 1].toInt() and 0xFF
                    val hi = bytes[it * 3 + 2].toInt()
                    ((hi shl 16) or (mid shl 8) or lo) / 8388608.0
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val n = b.remaining()
                if (bytes.size < n) bytes = ByteArray(n)
                b.get(bytes, 0, n)
                frames(n, into) { ((bytes[it].toInt() and 0xFF) - 128) / 128.0 }
            }
        }
    }

    /** Walks [samples] interleaved samples, [sample] giving the one at an index in -1..1. */
    private inline fun frames(samples: Int, into: WaveformBuilder, sample: (Int) -> Double) {
        var i = 0
        while (i + channels <= samples) {
            var sum = 0.0
            for (c in 0 until channels) { val s = sample(i + c); sum += s * s }
            into.addFrame(sum / channels)
            i += channels
        }
    }
}
