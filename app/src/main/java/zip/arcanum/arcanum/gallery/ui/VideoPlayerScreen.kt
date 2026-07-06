package zip.arcanum.arcanum.gallery.ui

import android.app.Activity
import android.content.ComponentName
import android.net.Uri
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import zip.arcanum.R
import zip.arcanum.arcanum.gallery.ServiceEncryptedDataSource
import zip.arcanum.arcanum.gallery.service.ArcanumMediaService

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    fileId: String,
    onBack: () -> Unit,
    viewModel: MediaPlayerViewModel = hiltViewModel()
) {
    val file    by viewModel.file.collectAsState()
    val context = LocalContext.current
    val view    = LocalView.current

    DisposableEffect(view) {
        val window = (context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        val wic = WindowCompat.getInsetsController(window, view)
        wic.hide(WindowInsetsCompat.Type.systemBars())
        wic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { wic.show(WindowInsetsCompat.Type.systemBars()) }
    }

    val appContext    = context.applicationContext
    val sessionToken  = remember { SessionToken(appContext, ComponentName(appContext, ArcanumMediaService::class.java)) }
    val controllerFuture = remember { MediaController.Builder(appContext, sessionToken).buildAsync() }
    var mediaController by remember { mutableStateOf<MediaController?>(null) }

    DisposableEffect(Unit) {
        controllerFuture.addListener(
            { mediaController = runCatching { controllerFuture.get() }.getOrNull() },
            ContextCompat.getMainExecutor(appContext)
        )
        onDispose {
            mediaController?.run { stop(); clearMediaItems() }
            MediaController.releaseFuture(controllerFuture)
        }
    }

    val currentFile = file

    // Send video to the service — it stops whatever is playing (music) and plays this instead
    LaunchedEffect(mediaController, currentFile) {
        val mc = mediaController ?: return@LaunchedEffect
        val cf = currentFile    ?: return@LaunchedEffect
        val uri = Uri.Builder()
            .scheme(ServiceEncryptedDataSource.URI_SCHEME)
            .authority("media")
            .appendQueryParameter("cid",  cf.containerId)
            .appendQueryParameter("path", "/" + cf.relativePath.trimStart('/'))
            .appendQueryParameter("size", cf.size.toString())
            .build()
        mc.stop()
        mc.clearMediaItems()
        mc.setMediaItem(
            MediaItem.Builder()
                .setUri(uri)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(cf.fileName).build())
                .build()
        )
        mc.prepare()
        mc.playWhenReady = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val mc = mediaController
        if (mc == null || currentFile == null) {
            CircularProgressIndicator(
                color    = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = mc
                        useController = true
                        setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update  = { it.player = mc },
                modifier = Modifier.fillMaxSize()
            )
        }

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
