package zip.arcanum.arcanum.gallery.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import android.app.Activity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

@OptIn(ExperimentalFoundationApi::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MediaViewerDirectScreen(
    onBack: () -> Unit,
    viewModel: MediaViewerDirectViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    val view    = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view) {
        val window = (context as? Activity)?.window ?: return@DisposableEffect onDispose {}
        onDispose { WindowCompat.getInsetsController(window, view).show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(state.showBars) {
        val window = (context as? Activity)?.window ?: return@LaunchedEffect
        val wic = WindowCompat.getInsetsController(window, view)
        if (state.showBars) {
            wic.show(WindowInsetsCompat.Type.systemBars())
            delay(3_000); viewModel.toggleBars()
        } else {
            wic.hide(WindowInsetsCompat.Type.systemBars())
            wic.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    val pagerState = rememberPagerState(initialPage = state.currentIndex) { state.fileCount }

    LaunchedEffect(state.currentIndex) {
        if (pagerState.currentPage != state.currentIndex) pagerState.scrollToPage(state.currentIndex)
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .drop(1)
            .collect { viewModel.navigateTo(it) }
    }

    var isImageZoomed by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // ── Pager ─────────────────────────────────────────────────────────
        HorizontalPager(
            state             = pagerState,
            userScrollEnabled = !isImageZoomed,
            modifier          = Modifier.fillMaxSize()
        ) { page ->
            val pageFile  = viewModel.getFileAt(page)
            val isCurrent = page == pagerState.currentPage
            when {
                pageFile?.isImage() == true -> ZoomableImagePage(
                    bitmap       = state.bitmapCache[page],
                    isLoading    = state.bitmapCache[page] == null,
                    onTap        = { viewModel.toggleBars() },
                    onZoomChange = { isImageZoomed = it > 1.05f }
                )
                pageFile?.isVideo() == true -> VideoPage(
                    exoPlayer   = if (isCurrent) viewModel.exoPlayer else null,
                    isPlaying   = state.isPlaying,
                    isBuffering = state.isBuffering,
                    onTap       = { viewModel.toggleBars() }
                )
                else -> Box(Modifier.fillMaxSize())
            }
        }

        // ── Top bar ───────────────────────────────────────────────────────
        AnimatedVisibility(
            visible  = state.showBars,
            enter    = fadeIn() + slideInVertically { -it },
            exit     = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(0.65f), Color.Transparent)))
            ) {
                Row(
                    modifier          = Modifier.fillMaxWidth().statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                    Text(
                        text     = viewModel.getFileAt(state.currentIndex)?.name ?: "",
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = Color.White,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (state.fileCount > 1) {
                        Text(
                            text     = "${state.currentIndex + 1} / ${state.fileCount}",
                            style    = MaterialTheme.typography.labelSmall,
                            color    = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                }
            }
        }

        // ── Video bottom bar ──────────────────────────────────────────────
        val currentFile = viewModel.getFileAt(state.currentIndex)
        if (currentFile?.isVideo() == true) {
            AnimatedVisibility(
                visible  = state.showBars,
                enter    = fadeIn() + slideInVertically { it },
                exit     = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                VideoBottomBar(
                    positionMs = state.positionMs,
                    durationMs = state.durationMs,
                    onSeek     = { viewModel.seekTo(it) }
                )
            }
        }

        // Loading spinner for current page
        if (state.isLoadingCurrent) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color    = Color.White
            )
        }
    }
}

// ── Zoomable image page ───────────────────────────────────────────────────────

@Composable
private fun ZoomableImagePage(
    bitmap: Bitmap?,
    isLoading: Boolean,
    onTap: () -> Unit,
    onZoomChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    if (bitmap == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (isLoading) CircularProgressIndicator(color = Color.White)
        }
        return
    }
    var scale   by remember(bitmap) { mutableStateOf(1f) }
    var offset  by remember(bitmap) { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { boxSize = it }
            .pointerInput(bitmap) {
                var lastTapMs = 0L
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downMs = System.currentTimeMillis()
                    var moved = false
                    var multiTouch = false

                    while (true) {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        when {
                            pressed.size >= 2 -> {
                                // Pinch-to-zoom: compute scale/pan from two-pointer geometry,
                                // consume so the pager doesn't also try to scroll.
                                multiTouch = true
                                moved = true
                                val prevMid  = (pressed[0].previousPosition + pressed[1].previousPosition) / 2f
                                val currMid  = (pressed[0].position         + pressed[1].position)         / 2f
                                val prevSpan = (pressed[0].previousPosition - pressed[1].previousPosition).getDistance()
                                val currSpan = (pressed[0].position         - pressed[1].position).getDistance()
                                val z        = if (prevSpan > 0f) currSpan / prevSpan else 1f
                                val pan      = currMid - prevMid
                                val newScale = (scale * z).coerceIn(1f, 8f)
                                onZoomChange(newScale)
                                scale = newScale
                                if (newScale > 1f) {
                                    val maxX = boxSize.width  * (newScale - 1f) / 2f
                                    val maxY = boxSize.height * (newScale - 1f) / 2f
                                    offset = Offset(
                                        (offset.x + pan.x).coerceIn(-maxX, maxX),
                                        (offset.y + pan.y).coerceIn(-maxY, maxY)
                                    )
                                } else {
                                    offset = Offset.Zero
                                }
                                event.changes.forEach { it.consume() }
                            }
                            pressed.size == 1 && !multiTouch -> {
                                val c         = pressed[0]
                                val totalDrag = (c.position - down.position).getDistance()
                                if (totalDrag > viewConfiguration.touchSlop) moved = true
                                if (scale > 1f) {
                                    // Pan the zoomed image and consume so the pager doesn't swipe.
                                    val delta = c.position - c.previousPosition
                                    val maxX  = boxSize.width  * (scale - 1f) / 2f
                                    val maxY  = boxSize.height * (scale - 1f) / 2f
                                    offset = Offset(
                                        (offset.x + delta.x).coerceIn(-maxX, maxX),
                                        (offset.y + delta.y).coerceIn(-maxY, maxY)
                                    )
                                    c.consume()
                                }
                                // scale == 1f: do NOT consume — the HorizontalPager sees
                                // the unconsumed moves and handles the swipe + fling itself.
                            }
                            pressed.isEmpty() -> break
                        }
                    }

                    if (!moved) {
                        val isDoubleTap = (downMs - lastTapMs) in 50L..400L
                        lastTapMs = downMs
                        if (isDoubleTap) {
                            lastTapMs = 0L
                            if (scale > 1f) {
                                scale = 1f; offset = Offset.Zero; onZoomChange(1f)
                            } else {
                                scale = 3f; onZoomChange(3f)
                            }
                        } else {
                            onTap()
                        }
                    }
                }
            }
    ) {
        Image(
            bitmap             = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier           = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX       = scale,
                    scaleY       = scale,
                    translationX = offset.x,
                    translationY = offset.y
                )
        )
    }
}

// ── Video page ────────────────────────────────────────────────────────────────

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPage(
    exoPlayer: ExoPlayer?,
    isPlaying: Boolean,
    isBuffering: Boolean,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center
    ) {
        if (exoPlayer != null) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player     = exoPlayer
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update   = { it.player = exoPlayer },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

// ── Video bottom bar ──────────────────────────────────────────────────────────

@Composable
private fun VideoBottomBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Float) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.75f))))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp)
        ) {
            Slider(
                value         = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
                onValueChange = { onSeek(it) },
                colors        = SliderDefaults.colors(
                    thumbColor         = Color.White,
                    activeTrackColor   = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
            Row(
                modifier              = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(formatMs(positionMs), color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text(formatMs(durationMs), color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun formatMs(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}
