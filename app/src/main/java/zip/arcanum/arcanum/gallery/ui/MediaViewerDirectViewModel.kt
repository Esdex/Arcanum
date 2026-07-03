package zip.arcanum.arcanum.gallery.ui

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
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
import kotlinx.coroutines.withContext
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.gallery.EncryptedDataSource
import zip.arcanum.arcanum.gallery.ImageBitmapDecoder
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
    private val bitmapLoadJobs = mutableMapOf<Int, Job>()

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
        val file = queue.files.getOrNull(idx)
        _state.update {
            it.copy(
                currentIndex = idx,
                isLoadingCurrent = file?.isImage() == true && !it.bitmapCache.containsKey(idx)
            )
        }
        activatePage(idx)
    }

    fun toggleBars() = _state.update { it.copy(showBars = !it.showBars) }

    fun setBarsVisible(visible: Boolean) = _state.update { it.copy(showBars = visible) }

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
        val keep = ((center - 1)..(center + 1))
            .filter { it in queue.files.indices }
            .toSet()

        bitmapLoadJobs
            .filterKeys { it !in keep }
            .values
            .forEach { it.cancel() }
        bitmapLoadJobs.keys.removeAll { it !in keep }
        _state.update { state ->
            val trimmed = state.bitmapCache.filterKeys { it in keep }
            val currentIsReady = center in trimmed || queue.files.getOrNull(center)?.isImage() != true
            state.copy(
                bitmapCache = trimmed,
                isLoadingCurrent = if (currentIsReady) false else state.isLoadingCurrent
            )
        }

        for (i in keep) {
            val f = queue.files.getOrNull(i) ?: continue
            if (f.isImage() && !_state.value.bitmapCache.containsKey(i) && bitmapLoadJobs[i]?.isActive != true) {
                loadBitmap(i, f)
            }
        }
    }

    private fun loadBitmap(index: Int, file: NativeFileInfo) {
        bitmapLoadJobs[index] = viewModelScope.launch(Dispatchers.IO) {
            val h = handle
            val path = "/" + file.path.trimStart('/')
            val bytes = runCatching {
                engine.nativeReadFile(h, path, 0L, minOf(file.size, 30 * 1024 * 1024L).toInt())
            }.getOrNull() ?: run {
                withContext(Dispatchers.Main) {
                    bitmapLoadJobs.remove(index)
                    if (index == _state.value.currentIndex) {
                        _state.update { it.copy(isLoadingCurrent = false) }
                    }
                }
                return@launch
            }
            val bitmap = ImageBitmapDecoder.decode(
                bytes = bytes,
                maxWidth = 4096,
                maxHeight = 4096,
                preferImageDecoder = ImageBitmapDecoder.isHeif(file.name)
            )?.bitmap
            withContext(Dispatchers.Main) {
                bitmapLoadJobs.remove(index)
                val currentIndex = _state.value.currentIndex
                val keep = ((currentIndex - 1)..(currentIndex + 1))
                    .filter { it in queue.files.indices }
                    .toSet()
                _state.update { s ->
                    val isCurrent = index == s.currentIndex
                    val updatedCache = if (bitmap != null && index in keep) {
                        (s.bitmapCache + (index to bitmap)).filterKeys { it in keep }
                    } else {
                        s.bitmapCache.filterKeys { it in keep }
                    }
                    s.copy(
                        bitmapCache      = updatedCache,
                        isLoadingCurrent = if (isCurrent) false else s.isLoadingCurrent
                    )
                }
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
        _state.value = ViewerState(isLoadingCurrent = false)
        progressJob?.cancel()
        bitmapLoadJobs.values.forEach { it.cancel() }
        bitmapLoadJobs.clear()
        exoPlayer.removeListener(playerListener)
        exoPlayer.stop()
        exoPlayer.release()
    }
}
