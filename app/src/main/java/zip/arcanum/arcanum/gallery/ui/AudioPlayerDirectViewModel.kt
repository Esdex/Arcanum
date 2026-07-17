package zip.arcanum.arcanum.gallery.ui

import android.content.ComponentName
import android.content.Context
import android.graphics.BitmapFactory
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.gallery.AudioPlayerQueue
import zip.arcanum.arcanum.gallery.ByteArrayMediaDataSource
import zip.arcanum.arcanum.gallery.JniMediaDataSource
import zip.arcanum.arcanum.gallery.ServiceEncryptedDataSource
import zip.arcanum.arcanum.gallery.domain.AudioMetadata
import zip.arcanum.arcanum.gallery.service.ArcanumMediaService
import zip.arcanum.core.navigation.Screen
import zip.arcanum.core.security.IdleMonitor
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject
import kotlin.math.sqrt

// Neutral title exposed to the shared MediaSession (notification / lockscreen / controllers).
// Real tags stay in-app only — see loadTrackAt().
private const val SESSION_TITLE = "Arcanum"

@androidx.annotation.OptIn(UnstableApi::class)
@HiltViewModel
class AudioPlayerDirectViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    val engine: VeraCryptEngine,
    private val repo: ContainerRepository,
    private val queue: AudioPlayerQueue,
    private val idleMonitor: IdleMonitor,
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

    private val _state = MutableStateFlow(PlayerState(
        playlistSize = queue.playlist.size.coerceAtLeast(1),
        currentIndex = queue.currentIndex
    ))
    val state = _state.asStateFlow()

    // play order: indices into queue.playlist; empty = single-file mode
    private var playOrder: List<Int> = buildDefaultOrder()
    private var posInOrder: Int = queue.currentIndex.coerceIn(0, (playOrder.size - 1).coerceAtLeast(0))

    val handle: Long get() = repo.getContainerHandle(containerId) ?: 0L

    // Backed by the MediaController (implements Player); null until service connects
    val exoPlayer: Player? get() = mediaController

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var progressJob: Job? = null
    private var waveformJob: Job? = null

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                viewModelScope.launch(Dispatchers.Main) { handleTrackEnd() }
            }
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
        controllerFuture = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture!!.addListener({
            val mc = runCatching { controllerFuture!!.get() }.getOrElse { e ->
                _state.update { it.copy(error = "Media service unavailable: ${e.message}") }
                return@addListener
            }
            mediaController = mc
            mc.addListener(playerListener)
            startProgressTracking()
            viewModelScope.launch(Dispatchers.IO) { loadTrackAt(posInOrder) }
        }, ContextCompat.getMainExecutor(appContext))
    }

    // ── Track loading ─────────────────────────────────────────────────────

    private suspend fun loadTrackAt(position: Int) {
        val path = currentPath()
        val size = currentSize()
        val name = currentName()
        val h = handle

        // Keep old metadata visible until new one arrives — avoids blank flash during transition
        _state.update { it.copy(waveformBars = null, error = null) }

        val headerBytes = runCatching {
            engine.readFile(h, path, 0L, minOf(size, 524288L).toInt())
        }.getOrNull()

        val metadata = parseMetadata(headerBytes, name)
        val dominantColor = metadata.artwork?.let { bmp ->
            runCatching { Palette.from(bmp).generate().getDominantColor(0) }.getOrNull()
        }

        _state.update { it.copy(
            metadata = metadata,
            dominantColor = dominantColor,
            currentIndex = playOrder.getOrElse(position) { 0 }
        )}

        withContext(Dispatchers.Main) {
            val mc = mediaController ?: return@withContext
            val uri = Uri.Builder()
                .scheme(ServiceEncryptedDataSource.URI_SCHEME)
                .authority("media")
                .appendQueryParameter("cid", containerId)
                .appendQueryParameter("path", path)
                .appendQueryParameter("size", size.toString())
                .build()
            mc.stop()
            mc.clearMediaItems()
            // Generic session metadata ONLY. Real title/artist (from the file's tags) must not
            // enter the shared MediaSession — Media3 mirrors it to the system notification, the
            // lockscreen, any NotificationListenerService and every connected MediaController,
            // bypassing the PIN/biometric/disguise/FLAG_SECURE. The in-app player shows the real
            // metadata from _state.metadata, populated separately above.
            mc.setMediaItem(MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder()
                    .setTitle(SESSION_TITLE)
                    .build())
                .build())
            mc.prepare()
            mc.playWhenReady = true
        }

        waveformJob?.cancel()
        waveformJob = viewModelScope.launch(Dispatchers.IO) {
            val bars = generateWaveform(h, path, size)
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

    private fun generateWaveform(h: Long, path: String, fileSize: Long, barCount: Int = 80): List<Float> {
        if (fileSize <= 0) return List(barCount) { 0.5f }
        // Return cached result instantly on repeated plays
        loadCachedWaveform(path, fileSize, barCount)?.let { return it }
        // Primary: seek-based PCM sampling — decode a few frames at each of barCount positions
        val result = generateWaveformViaPcm(h, path, fileSize, barCount) ?: run {
            // Fallback: raw byte RMS (WAV/PCM or unsupported codec)
            val bars = mutableListOf<Float>()
            val chunkSize = (fileSize / barCount).toInt().coerceAtLeast(1024)
            var offset = 0L
            while (offset < fileSize && bars.size < barCount) {
                val toRead = minOf(chunkSize.toLong(), fileSize - offset).toInt()
                val chunk = runCatching { engine.readFile(h, path, offset, toRead) }
                    .getOrNull() ?: break
                if (chunk.isEmpty()) break
                var sum = 0.0
                var i = 0
                while (i + 1 < chunk.size) {
                    val sample = ((chunk[i + 1].toInt() shl 8) or (chunk[i].toInt() and 0xFF)).toShort()
                    sum += sample.toDouble() * sample
                    i += 2
                }
                bars.add(sqrt(sum / (chunk.size / 2).coerceAtLeast(1)).toFloat())
                offset += chunk.size
            }
            if (bars.isEmpty()) List(barCount) { 0.5f } else {
                val minVal = bars.minOrNull() ?: 0f
                val maxVal = bars.maxOrNull() ?: 1f
                val range  = (maxVal - minVal).coerceAtLeast(0.001f)
                bars.map { ((it - minVal) / range).coerceIn(0f, 1f) }
            }
        }
        saveWaveformCache(path, fileSize, result)
        return result
    }

    // Seeks to barCount evenly-spaced positions and decodes a handful of frames at each one.
    // Much faster than full sequential decode: ~8 frames × 200 bars vs the entire file.
    private fun generateWaveformViaPcm(h: Long, path: String, fileSize: Long, barCount: Int): List<Float>? {
        var extractor: MediaExtractor? = null
        var codec: MediaCodec? = null
        return try {
            extractor = MediaExtractor()
            extractor.setDataSource(JniMediaDataSource(engine, h, path, fileSize))

            var audioTrackIdx = -1
            var durationUs = 0L
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                if (fmt.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    audioTrackIdx = i
                    durationUs = if (fmt.containsKey(MediaFormat.KEY_DURATION))
                        fmt.getLong(MediaFormat.KEY_DURATION) else 0L
                    trackFormat = fmt
                    break
                }
            }
            if (audioTrackIdx < 0 || durationUs <= 0L || trackFormat == null) return null

            val mime = trackFormat.getString(MediaFormat.KEY_MIME)!!
            extractor.selectTrack(audioTrackIdx)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            val stepUs = durationUs / barCount
            val bars = FloatArray(barCount)
            var isFloatPcm = false

            for (bar in 0 until barCount) {
                extractor.seekTo(bar * stepUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                codec.flush()

                var inputFed = 0
                var gotOutput = false
                var attempts = 0

                while (!gotOutput && attempts < 24) {
                    attempts++
                    // Feed up to 8 compressed frames per bar (covers codec warm-up delay)
                    if (inputFed < 8) {
                        val inIdx = codec.dequeueInputBuffer(1_000L)
                        if (inIdx >= 0) {
                            val inBuf = codec.getInputBuffer(inIdx)!!
                            val sz = extractor.readSampleData(inBuf, 0)
                            if (sz > 0) {
                                codec.queueInputBuffer(inIdx, 0, sz, extractor.sampleTime, 0)
                                extractor.advance()
                                inputFed++
                            } else {
                                codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputFed = 8
                            }
                        }
                    }
                    val info = MediaCodec.BufferInfo()
                    val outIdx = codec.dequeueOutputBuffer(info, 1_000L)
                    when {
                        outIdx >= 0 -> {
                            if (info.size > 0) {
                                val buf = codec.getOutputBuffer(outIdx)!!
                                buf.order(java.nio.ByteOrder.nativeOrder())
                                var sum = 0.0
                                var count = 0
                                if (isFloatPcm) {
                                    val floats = FloatArray(info.size / 4)
                                    buf.asFloatBuffer().get(floats)
                                    for (f in floats) { sum += f.toDouble() * f; count++ }
                                } else {
                                    val shorts = ShortArray(info.size / 2)
                                    buf.asShortBuffer().get(shorts)
                                    for (s in shorts) { sum += s.toDouble() * s; count++ }
                                }
                                if (count > 0) { bars[bar] = sqrt(sum / count).toFloat(); gotOutput = true }
                            }
                            codec.releaseOutputBuffer(outIdx, false)
                        }
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val enc = codec.outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING, 2)
                            isFloatPcm = (enc == 4) // AudioFormat.ENCODING_PCM_FLOAT
                        }
                    }
                }
            }

            if (bars.count { it > 0f } < barCount / 4) return null
            val minVal = bars.minOrNull()!!
            val maxVal = bars.maxOrNull()!!
            val range = (maxVal - minVal).coerceAtLeast(0.001f)
            bars.map { ((it - minVal) / range).coerceIn(0f, 1f) }
        } catch (_: Exception) { null } finally {
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
            val bb = java.nio.ByteBuffer.allocate(4 + bars.size * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            bb.putInt(bars.size)
            bars.forEach { bb.putFloat(it) }
            encFile.openFileOutput().use { it.write(bb.array()) }
        } catch (_: Exception) {}
    }

    private fun waveformCacheFile(path: String, fileSize: Long): java.io.File {
        val hash = (containerId.hashCode().toLong() shl 32) xor path.hashCode().toLong() xor fileSize
        return java.io.File(appContext.cacheDir, "wf_${java.lang.Long.toHexString(hash)}.dat")
    }

    private fun waveformMasterKey(): androidx.security.crypto.MasterKey =
        androidx.security.crypto.MasterKey.Builder(appContext)
            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
            .build()

    // ── Playback controls ─────────────────────────────────────────────────

    fun togglePlayPause() = viewModelScope.launch(Dispatchers.Main) {
        val mc = mediaController ?: return@launch
        if (mc.mediaItemCount == 0) {
            // Service queue was cleared by another screen (e.g. video player); reload current track
            viewModelScope.launch(Dispatchers.IO) { loadTrackAt(posInOrder) }
            return@launch
        }
        if (mc.isPlaying) mc.pause() else mc.play()
    }

    fun seekTo(progress: Float) = viewModelScope.launch(Dispatchers.Main) {
        val mc = mediaController ?: return@launch
        val dur = mc.duration.coerceAtLeast(1L)
        mc.seekTo((progress * dur).toLong())
    }

    fun playNext() { advanceTrack(+1, _state.value.repeatMode == RepeatMode.ALL) }

    fun playPrevious() {
        if ((mediaController?.currentPosition ?: 0L) > 3_000L) {
            viewModelScope.launch(Dispatchers.Main) { mediaController?.seekTo(0L) }
        } else {
            advanceTrack(-1, _state.value.repeatMode == RepeatMode.ALL)
        }
    }

    fun toggleShuffle() {
        val newShuffled = !_state.value.isShuffled
        if (newShuffled) {
            val cur = playOrder.getOrElse(posInOrder) { 0 }
            val rest = (0 until queue.playlist.size).filter { it != cur }.shuffled()
            playOrder = listOf(cur) + rest
            posInOrder = 0
        } else {
            val cur = playOrder.getOrElse(posInOrder) { 0 }
            playOrder = buildDefaultOrder()
            posInOrder = cur.coerceIn(0, (playOrder.size - 1).coerceAtLeast(0))
        }
        _state.update { it.copy(isShuffled = newShuffled) }
    }

    fun cycleRepeat() {
        _state.update { it.copy(repeatMode = when (it.repeatMode) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL  -> RepeatMode.ONE
            RepeatMode.ONE  -> RepeatMode.NONE
        })}
    }

    private fun handleTrackEnd() {
        val mc = mediaController ?: return
        when (_state.value.repeatMode) {
            RepeatMode.ONE  -> { mc.seekTo(0L); mc.play() }
            RepeatMode.ALL  -> advanceTrack(+1, wraparound = true)
            RepeatMode.NONE -> if (posInOrder < playOrder.size - 1) advanceTrack(+1, wraparound = false)
        }
    }

    private fun advanceTrack(delta: Int, wraparound: Boolean) {
        if (playOrder.isEmpty()) return
        val next = posInOrder + delta
        posInOrder = if (wraparound) ((next % playOrder.size) + playOrder.size) % playOrder.size
                     else next.coerceIn(0, playOrder.size - 1)
        viewModelScope.launch(Dispatchers.IO) { loadTrackAt(posInOrder) }
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

    private fun buildDefaultOrder(): List<Int> {
        val size = queue.playlist.size
        return if (size == 0) emptyList() else (0 until size).toList()
    }

    private fun currentPath(): String =
        queue.playlist.getOrNull(playOrder.getOrElse(posInOrder) { 0 })
            ?.path?.let { "/" + it.trimStart('/') } ?: navPath

    private fun currentName(): String =
        queue.playlist.getOrNull(playOrder.getOrElse(posInOrder) { 0 })?.name ?: navName

    private fun currentSize(): Long =
        queue.playlist.getOrNull(playOrder.getOrElse(posInOrder) { 0 })
            ?.size?.takeIf { it > 0L } ?: navSize

    private fun stripExtension(name: String) = name.substringBeforeLast(".", name)

    // Refresh the idle auto-lock baseline. Called periodically by the screen while audio is
    // actively playing so listening (which produces no touch events) doesn't trip the idle timer.
    fun recordInteraction() = idleMonitor.recordInteraction()

    override fun onCleared() {
        progressJob?.cancel()
        waveformJob?.cancel()
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
