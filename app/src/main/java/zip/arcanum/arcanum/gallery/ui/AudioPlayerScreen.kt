package zip.arcanum.arcanum.gallery.ui

import android.net.Uri
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.RepeatOne
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
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
import zip.arcanum.R
import zip.arcanum.arcanum.gallery.EncryptedDataSourceFactory
import zip.arcanum.arcanum.gallery.domain.AudioMetadata
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun AudioPlayerDirectScreen(
    onBack: () -> Unit,
    viewModel: AudioPlayerDirectViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    val view    = LocalView.current
    val context = LocalContext.current
    DisposableEffect(view) {
        (context as? Activity)?.window?.let {
            WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }

    // Listening to audio is real activity though it produces no touch events, so playback must not
    // trip idle auto-lock. Refresh the idle baseline while playing (gated on RESUMED so backgrounded
    // playback still ages the vault out); pausing lets the timer count normally.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(state.isPlaying) {
        if (!state.isPlaying) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) { viewModel.recordInteraction(); delay(10_000) }
        }
    }

    // Animate dominant color smoothly across track changes
    val animatedDominantColor by animateColorAsState(
        targetValue = state.dominantColor?.let { Color(it).copy(alpha = 0.35f) } ?: Color.Transparent,
        animationSpec = tween(700),
        label = "dominant_color"
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Dynamic gradient from dominant album color — fades between tracks
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            animatedDominantColor,
                            MaterialTheme.colorScheme.background
                        ),
                        endY = maxHeight.value * 2f
                    )
                )
        )


        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── Top Bar — 12dp padding so icons (12dp internal) align with 24dp content ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.common_back),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                Text(
                    stringResource(R.string.audio_now_playing),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
                IconButton(onClick = { /* stub */ }) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.audio_cd_more),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

            Spacer(Modifier.height(16.dp))

            // ── Album Art + Metadata — crossfade when track changes ────────
            AnimatedContent(
                targetState = state.metadata,
                transitionSpec = {
                    fadeIn(tween(400)) togetherWith fadeOut(tween(280))
                },
                label = "track_content",
                modifier = Modifier.fillMaxWidth()
            ) { meta ->
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AlbumArtView(
                        metadata = meta,
                        dominantColor = state.dominantColor,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f)
                    )

                    Spacer(Modifier.height(24.dp))

                    Text(
                        text = meta?.title ?: stringResource(R.string.audio_loading),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = meta?.artist ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = meta?.album ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Shuffle / Repeat ──────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = state.isShuffled,
                    onClick = viewModel::toggleShuffle,
                    label = { Text(stringResource(R.string.audio_cd_shuffle), style = MaterialTheme.typography.labelMedium) },
                    leadingIcon = {
                        Icon(Icons.Outlined.Shuffle, null, modifier = Modifier.size(16.dp))
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
                FilterChip(
                    selected = state.repeatMode != AudioPlayerDirectViewModel.RepeatMode.NONE,
                    onClick = viewModel::cycleRepeat,
                    label = {
                        Text(
                            when (state.repeatMode) {
                                AudioPlayerDirectViewModel.RepeatMode.NONE -> stringResource(R.string.audio_cd_repeat)
                                AudioPlayerDirectViewModel.RepeatMode.ALL  -> stringResource(R.string.audio_cd_repeat_all)
                                AudioPlayerDirectViewModel.RepeatMode.ONE  -> stringResource(R.string.audio_cd_repeat_one)
                            },
                            style = MaterialTheme.typography.labelMedium
                        )
                    },
                    leadingIcon = {
                        Icon(
                            if (state.repeatMode == AudioPlayerDirectViewModel.RepeatMode.ONE)
                                Icons.Outlined.RepeatOne else Icons.Outlined.Repeat,
                            null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                        selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }

            Spacer(Modifier.height(16.dp))

            // ── Waveform — fades in once bars are ready ───────────────────
            val waveformAlpha by animateFloatAsState(
                targetValue = if (state.waveformBars != null) 1f else 0.35f,
                animationSpec = tween(500),
                label = "waveform_alpha"
            )
            WaveformView(
                waveformBars = state.waveformBars,
                progress = state.progress,
                onSeek = viewModel::seekTo,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .alpha(waveformAlpha)
            )

            // ── Time codes ────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatMs(state.currentPositionMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
                Text(
                    formatMs(state.durationMs.let { if (it <= 1L) state.metadata?.durationMs ?: 0L else it }),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }

            Spacer(Modifier.height(20.dp))

            // ── Playback Controls ─────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = viewModel::playPrevious,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        Icons.Rounded.SkipPrevious,
                        contentDescription = stringResource(R.string.audio_cd_previous),
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }

                androidx.compose.material3.FilledIconButton(
                    onClick = viewModel::togglePlayPause,
                    modifier = Modifier.size(64.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = if (state.isPlaying) stringResource(R.string.audio_cd_pause) else stringResource(R.string.audio_cd_play),
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(
                    onClick = viewModel::playNext,
                    modifier = Modifier.size(52.dp)
                ) {
                    Icon(
                        Icons.Rounded.SkipNext,
                        contentDescription = stringResource(R.string.audio_cd_next),
                        modifier = Modifier.size(36.dp),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            } // inner padded Column
        }
    }
}

// ── Album Art ─────────────────────────────────────────────────────────────────

@Composable
private fun AlbumArtView(
    metadata: AudioMetadata?,
    dominantColor: Int?,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier = modifier
            .shadow(elevation = 16.dp, shape = shape, clip = false)
            .clip(shape),
        contentAlignment = Alignment.Center
    ) {
        val art = metadata?.artwork
        if (art != null) {
            androidx.compose.foundation.Image(
                bitmap = art.asImageBitmap(),
                contentDescription = stringResource(R.string.audio_cd_album_art),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Gradient placeholder
            val gradientColors = if (dominantColor != null) {
                val base = Color(dominantColor)
                listOf(base.copy(alpha = 0.9f), base.copy(alpha = 0.4f), Color.Black)
            } else {
                listOf(
                    MaterialTheme.colorScheme.primary,
                    MaterialTheme.colorScheme.primaryContainer,
                    Color.Black
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Brush.radialGradient(colors = gradientColors)),
                contentAlignment = Alignment.Center
            ) {
                if (metadata == null) {
                    // Still loading — show nothing / spinner could go here
                } else {
                    Icon(
                        imageVector = Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(72.dp)
                    )
                }
            }
        }
    }
}

// ── Waveform ──────────────────────────────────────────────────────────────────

@Composable
private fun WaveformView(
    waveformBars: List<Float>?,
    progress: Float,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val progressColor = MaterialTheme.colorScheme.primary
    val inactiveColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.25f)

    if (waveformBars == null) {
        // Shimmer skeleton
        val shimmerBars = remember { List(80) { Random.nextFloat().coerceIn(0.1f, 0.9f) } }
        val transition = rememberInfiniteTransition(label = "shimmer")
        val shimmerX by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1400, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmerX"
        )
        Canvas(modifier = modifier) {
            val barCount = shimmerBars.size
            val barWidth = size.width / barCount
            val centerY = size.height / 2f
            shimmerBars.forEachIndexed { i, amp ->
                val x = i * barWidth + barWidth / 2f
                val frac = x / size.width
                val dist = kotlin.math.abs(frac - shimmerX)
                val alpha = (0.15f + (1f - (dist * 6f).coerceIn(0f, 1f)) * 0.35f)
                val barH = amp * size.height * 0.8f
                drawLine(
                    color = Color.White.copy(alpha = alpha),
                    start = Offset(x, centerY - barH / 2f),
                    end   = Offset(x, centerY + barH / 2f),
                    strokeWidth = (barWidth * 0.6f).coerceAtLeast(2.5f),
                    cap = StrokeCap.Round
                )
            }
        }
        return
    }

    Canvas(
        modifier = modifier
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    onSeek((offset.x / size.width).coerceIn(0f, 1f))
                }
            }
            .pointerInput(Unit) {
                detectDragGestures { change, _ ->
                    onSeek((change.position.x / size.width).coerceIn(0f, 1f))
                }
            }
    ) {
        val bars = waveformBars
        val barWidth = size.width / bars.size
        val centerY = size.height / 2f
        val progressX = size.width * progress
        bars.forEachIndexed { i, amp ->
            val x = i * barWidth + barWidth / 2f
            val barH = amp * size.height * 0.9f
            drawLine(
                color = if (x <= progressX) progressColor else inactiveColor,
                start = Offset(x, centerY - barH / 2f),
                end   = Offset(x, centerY + barH / 2f),
                strokeWidth = (barWidth * 0.6f).coerceAtLeast(2.5f),
                cap = StrokeCap.Round
            )
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatMs(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

// ── Legacy gallery audio player (kept for AudioPlayer screen from DB) ─────────

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun AudioPlayerScreen(
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
        (context as? Activity)?.window?.let {
            WindowCompat.getInsetsController(it, view).show(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        IconButton(
            onClick  = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(4.dp)
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.common_back),
                tint = MaterialTheme.colorScheme.onBackground
            )
        }

        val currentFile = file
        if (currentFile == null || handle == null) {
            androidx.compose.material3.CircularProgressIndicator(
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
                onDispose { exoPlayer.stop(); exoPlayer.release() }
            }

            // Keep the idle auto-lock timer from firing while audio actually plays (no touch events
            // during playback). Gated on RESUMED so backgrounded playback still ages the vault out.
            val lifecycleOwner = LocalLifecycleOwner.current
            LaunchedEffect(exoPlayer) {
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    while (true) {
                        if (exoPlayer.isPlaying) viewModel.recordInteraction()
                        delay(10_000)
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(96.dp)
                )
                Text(
                    text = currentFile.fileName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp)
                )
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                            hideController()
                            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )
            }
        }
    }
}
