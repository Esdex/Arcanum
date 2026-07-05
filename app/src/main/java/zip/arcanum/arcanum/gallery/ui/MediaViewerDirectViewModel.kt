package zip.arcanum.arcanum.gallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import zip.arcanum.arcanum.gallery.NativeFileInputStream
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.gallery.EncryptedDataSource
import zip.arcanum.arcanum.gallery.MediaViewerQueue
import zip.arcanum.arcanum.gallery.MutableEncryptedDataSourceFactory
import zip.arcanum.core.navigation.Screen
import zip.arcanum.crypto.NativeFileInfo
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject

private val IMAGE_EXTS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif")
private val VIDEO_EXTS = setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "3gp")

fun NativeFileInfo.isImage() = name.substringAfterLast('.', "").lowercase() in IMAGE_EXTS
fun NativeFileInfo.isVideo() = name.substringAfterLast('.', "").lowercase() in VIDEO_EXTS

@androidx.annotation.OptIn(UnstableApi::class)
@HiltViewModel
class MediaViewerDirectViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    val engine: VeraCryptEngine,
    private val repo: ContainerRepository,
    private val queue: MediaViewerQueue,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    data class ViewerState(
        val currentIndex: Int = 0,
        val fileCount: Int = 1,
        val bitmapCache: Map<Int, Bitmap> = emptyMap(),
        val isLoadingCurrent: Boolean = true,
        val showBars: Boolean = true,
        val isPlaying: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val isBuffering: Boolean = false
    )

    private val containerId = savedStateHandle[Screen.MediaViewerDirect.ARG_CONTAINER] ?: ""

    private val _state = MutableStateFlow(
        ViewerState(
            currentIndex     = queue.currentIndex,
            fileCount        = queue.files.size.coerceAtLeast(1),
            isLoadingCurrent = queue.files.getOrNull(queue.currentIndex)?.isImage() == true
        )
    )
    val state = _state.asStateFlow()

    private val handle: Long get() = repo.getContainerHandle(containerId) ?: 0L

    private lateinit var mutableFactory: MutableEncryptedDataSourceFactory
    lateinit var exoPlayer: ExoPlayer
        private set

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) { _state.update { it.copy(isPlaying = playing) } }
        override fun onPlaybackStateChanged(s: Int) {
            _state.update { it.copy(isBuffering = s == Player.STATE_BUFFERING) }
            exoPlayer.duration.takeIf { it > 0L }?.let { dur -> _state.update { it.copy(durationMs = dur) } }
        }
    }

    private var progressJob: Job? = null

    init {
        mutableFactory = MutableEncryptedDataSourceFactory(engine, handle)
        exoPlayer = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(mutableFactory))
            .build()
        exoPlayer.addListener(playerListener)
        startProgressTracking()
        activatePage(queue.currentIndex)
    }

    fun navigateTo(index: Int) {
        val idx = index.coerceIn(0, queue.files.size - 1)
        _state.update { it.copy(currentIndex = idx, showBars = true) }
        activatePage(idx)
    }

    fun toggleBars() = _state.update { it.copy(showBars = !it.showBars) }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
    }

    fun seekTo(fraction: Float) {
        val dur = exoPlayer.duration.coerceAtLeast(1L)
        exoPlayer.seekTo((fraction * dur).toLong())
    }

    private fun activatePage(index: Int) {
        val file = queue.files.getOrNull(index)
        when {
            file == null -> _state.update { it.copy(isLoadingCurrent = false) }
            file.isVideo() -> {
                exoPlayer.pause()
                loadVideo(file)
                preloadBitmaps(index)
            }
            file.isImage() -> {
                exoPlayer.pause()
                preloadBitmaps(index)
            }
        }
    }

    private fun loadVideo(file: NativeFileInfo) {
        val path = "/" + file.path.trimStart('/')
        viewModelScope.launch(Dispatchers.Main) {
            mutableFactory.configure(path, file.size)
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse("${EncryptedDataSource.URI_SCHEME}://$path")))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true
            _state.update { it.copy(positionMs = 0L, durationMs = 0L, isLoadingCurrent = false) }
        }
    }

    private fun preloadBitmaps(center: Int) {
        for (i in (center - 1)..(center + 1)) {
            val f = queue.files.getOrNull(i) ?: continue
            if (f.isImage() && !_state.value.bitmapCache.containsKey(i)) {
                loadBitmap(i, f)
            }
        }
    }

    private fun loadBitmap(index: Int, file: NativeFileInfo) {
        viewModelScope.launch(Dispatchers.IO) {
            val h = handle
            val path = "/" + file.path.trimStart('/')
            // Stream the file instead of reading into a ByteArray — no size cap, handles 30 MB+ images.
            val stream = NativeFileInputStream(engine, h, path, file.size)
            stream.mark(0)
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(stream, null, opts)
            if (opts.outWidth <= 0) return@launch
            stream.reset()
            opts.inSampleSize = run {
                var s = 1
                while (opts.outWidth / s > 4096 || opts.outHeight / s > 4096) s *= 2
                s
            }
            opts.inJustDecodeBounds = false
            val bitmap = BitmapFactory.decodeStream(stream, null, opts) ?: return@launch
            _state.update { s ->
                val isCurrent = index == s.currentIndex
                s.copy(
                    bitmapCache      = s.bitmapCache + (index to bitmap),
                    isLoadingCurrent = if (isCurrent) false else s.isLoadingCurrent
                )
            }
        }
    }

    private fun startProgressTracking() {
        progressJob = viewModelScope.launch(Dispatchers.Main) {
            while (true) {
                delay(200L)
                if (exoPlayer.isPlaying) {
                    _state.update { it.copy(
                        positionMs = exoPlayer.currentPosition,
                        durationMs = exoPlayer.duration.let { d -> if (d > 0L) d else it.durationMs }
                    )}
                }
            }
        }
    }

    fun currentFile() = queue.files.getOrNull(_state.value.currentIndex)
    fun getFileAt(index: Int) = queue.files.getOrNull(index)

    override fun onCleared() {
        progressJob?.cancel()
        exoPlayer.removeListener(playerListener)
        exoPlayer.stop()
        exoPlayer.release()
    }
}
