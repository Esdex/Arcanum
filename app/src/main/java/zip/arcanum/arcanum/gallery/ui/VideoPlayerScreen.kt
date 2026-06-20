package zip.arcanum.arcanum.gallery.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import zip.arcanum.R
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import android.app.Activity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import zip.arcanum.arcanum.gallery.EncryptedDataSourceFactory
import android.net.Uri

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    fileId: String,
    onBack: () -> Unit,
    viewModel: MediaPlayerViewModel = hiltViewModel()
) {
    val file   by viewModel.file.collectAsState()
    val handle = viewModel.getHandle()
    val engine = viewModel.engine
    val context = LocalContext.current
    val view    = LocalView.current
    DisposableEffect(view) {
        val window = (context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val wic = WindowCompat.getInsetsController(window, view)
        wic.hide(WindowInsetsCompat.Type.systemBars())
        wic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { wic.show(WindowInsetsCompat.Type.systemBars()) }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val currentFile = file
        if (currentFile == null || handle == null) {
            CircularProgressIndicator(
                color    = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            val dataSourceFactory = remember(handle, currentFile.relativePath) {
                EncryptedDataSourceFactory(engine, handle, currentFile.relativePath, currentFile.size)
            }

            val exoPlayer = remember(handle, currentFile.relativePath) {
                ExoPlayer.Builder(context)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
                    .build()
                    .also { player ->
                        val uri = Uri.parse("${EncryptedDataSourceFactory.URI_SCHEME}://${currentFile.relativePath}")
                        player.setMediaItem(MediaItem.fromUri(uri))
                        player.prepare()
                        player.playWhenReady = true
                    }
            }

            DisposableEffect(exoPlayer) {
                onDispose {
                    exoPlayer.stop()
                    exoPlayer.release()
                }
            }

            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = exoPlayer
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Back button always visible
        IconButton(
            onClick  = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(4.dp)
        ) {
            Icon(
                imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.common_back),
                tint               = Color.White
            )
        }
    }
}
