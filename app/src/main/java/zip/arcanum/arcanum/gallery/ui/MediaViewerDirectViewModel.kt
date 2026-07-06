package zip.arcanum.arcanum.gallery.ui

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import zip.arcanum.arcanum.gallery.NativeFileInputStream
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
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
import android.media.MediaMetadataRetriever
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.gallery.JniMediaDataSource
import zip.arcanum.arcanum.gallery.MediaViewerQueue
import zip.arcanum.arcanum.gallery.ServiceEncryptedDataSource
import zip.arcanum.arcanum.gallery.service.ArcanumMediaService
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

    // Exposed to UI for PlayerView — null until MediaController connects
    private val _player = MutableStateFlow<Player?>(null)
    val player = _player.asStateFlow()

    private val handle: Long get() = repo.getContainerHandle(containerId) ?: 0L

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(playing: Boolean) { _state.update { it.copy(isPlaying = playing) } }
        override fun onPlaybackStateChanged(s: Int) {
            _state.update { it.copy(isBuffering = s == Player.STATE_BUFFERING) }
            mediaController?.duration?.takeIf { it > 0L }?.let { dur ->
                _state.update { it.copy(durationMs = dur) }
            }
        }
    }

    private var progressJob: Job? = null

    init {
        val token = SessionToken(appContext, ComponentName(appContext, ArcanumMediaService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future
        future.addListener({
            val mc = runCatching { future.get() }.getOrNull() ?: return@addListener
            mediaController = mc
            _player.value = mc
            mc.addListener(playerListener)
            startProgressTracking()
            activatePage(queue.currentIndex)
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun navigateTo(index: Int) {
        val idx = index.coerceIn(0, queue.files.size - 1)
        _state.update { it.copy(currentIndex = idx, showBars = true) }
        activatePage(idx)
    }

    fun toggleBars() = _state.update { it.copy(showBars = !it.showBars) }

    fun togglePlayPause() {
        val mc = mediaController ?: return
        if (mc.isPlaying) mc.pause() else mc.play()
    }

    fun seekTo(fraction: Float) {
        val mc = mediaController ?: return
        val dur = mc.duration.coerceAtLeast(1L)
        mc.seekTo((fraction * dur).toLong())
    }

    private fun activatePage(index: Int) {
        val file = queue.files.getOrNull(index)
        when {
            file == null -> _state.update { it.copy(isLoadingCurrent = false) }
            file.isVideo() -> {
                mediaController?.pause()
                loadVideo(file)
                preloadBitmaps(index)
            }
            file.isImage() -> {
                mediaController?.pause()
                preloadBitmaps(index)
            }
        }
    }

    private fun loadVideo(file: NativeFileInfo) {
        val mc = mediaController ?: return
        val path = "/" + file.path.trimStart('/')
        val uri = Uri.Builder()
            .scheme(ServiceEncryptedDataSource.URI_SCHEME)
            .authority("media")
            .appendQueryParameter("cid",  containerId)
            .appendQueryParameter("path", path)
            .appendQueryParameter("size", file.size.toString())
            .build()
        viewModelScope.launch(Dispatchers.Main) {
            mc.stop()
            mc.clearMediaItems()
            mc.setMediaItem(
                MediaItem.Builder()
                    .setUri(uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(file.name).build())
                    .build()
            )
            mc.prepare()
            mc.playWhenReady = true
            _state.update { it.copy(positionMs = 0L, durationMs = 0L, isLoadingCurrent = false) }

            // Extract thumbnail on IO and update notification artwork once ready
            val h = handle
            val thumb = withContext(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    retriever.setDataSource(JniMediaDataSource(engine, h, path, file.size))
                    val raw = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    retriever.release()
                    raw ?: return@withContext null
                    val maxDim = maxOf(raw.width, raw.height).coerceAtLeast(1)
                    val bmp = if (maxDim > 512) {
                        val s = 512f / maxDim
                        Bitmap.createScaledBitmap(raw, (raw.width * s).toInt(), (raw.height * s).toInt(), true)
                            .also { raw.recycle() }
                    } else raw
                    val baos = java.io.ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                    bmp.recycle()
                    baos.toByteArray()
                } catch (_: Exception) { null }
            }
            if (thumb != null && mc.mediaItemCount > 0) {
                mc.replaceMediaItem(0, MediaItem.Builder()
                    .setUri(uri)
                    .setMediaMetadata(MediaMetadata.Builder()
                        .setTitle(file.name)
                        .setArtworkData(thumb, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                        .build())
                    .build())
            }
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
                val mc = mediaController
                if (mc != null && mc.isPlaying) {
                    _state.update { it.copy(
                        positionMs = mc.currentPosition,
                        durationMs = mc.duration.let { d -> if (d > 0L) d else it.durationMs }
                    )}
                }
            }
        }
    }

    fun currentFile() = queue.files.getOrNull(_state.value.currentIndex)
    fun getFileAt(index: Int) = queue.files.getOrNull(index)

    override fun onCleared() {
        progressJob?.cancel()
        mediaController?.run {
            removeListener(playerListener)
            stop()
            clearMediaItems()
        }
        controllerFuture?.let { MediaController.releaseFuture(it) }
    }
}
