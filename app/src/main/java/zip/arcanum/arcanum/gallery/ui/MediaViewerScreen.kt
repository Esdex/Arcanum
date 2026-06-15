package zip.arcanum.arcanum.gallery.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import zip.arcanum.arcanum.gallery.EncryptedDataSource
import zip.arcanum.arcanum.gallery.MutableEncryptedDataSourceFactory
import zip.arcanum.core.database.entities.MediaFileType
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForwardIos
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import android.content.ClipData
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import zip.arcanum.R
import zip.arcanum.arcanum.gallery.ExifTag
import zip.arcanum.arcanum.gallery.MediaExifData
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.components.AppSheet
import zip.arcanum.core.components.LocalHazeState
import zip.arcanum.core.database.entities.MediaFileEntity
import zip.arcanum.core.notifications.InAppNotification
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun MediaViewerScreen(
    photoId: String,
    onBack: () -> Unit,
    onNotification: (InAppNotification) -> Unit = {},
    viewModel: PhotoViewerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val scope   = rememberCoroutineScope()
    val context = LocalContext.current

    // ── Video player ──────────────────────────────────────────────────────
    val containerId  = uiState.currentFile?.containerId
    val videoHandle  = remember(containerId) { containerId?.let { viewModel.getHandleForContainer(it) } }
    val videoFactory = remember(videoHandle) { videoHandle?.let { MutableEncryptedDataSourceFactory(viewModel.engine, it) } }
    val exoPlayer    = remember(videoHandle) {
        videoFactory?.let { factory ->
            ExoPlayer.Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
                .build()
        }
    }

    var isPlaying   by remember { mutableStateOf(false) }
    var positionMs  by remember { mutableStateOf(0L)    }
    var durationMs  by remember { mutableStateOf(0L)    }
    var isBuffering by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    var scrubMs     by remember { mutableStateOf(0L)    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                isBuffering = state == Player.STATE_BUFFERING
                exoPlayer?.duration?.takeIf { it > 0L }?.let { durationMs = it }
            }
        }
        exoPlayer?.addListener(listener)
        onDispose {
            exoPlayer?.removeListener(listener)
            exoPlayer?.stop()
            exoPlayer?.release()
        }
    }

    LaunchedEffect(isPlaying, isScrubbing) {
        while (isPlaying && !isScrubbing) {
            positionMs = exoPlayer?.currentPosition ?: 0L
            delay(200)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri -> if (uri != null) viewModel.exportToUri(uri) }

    LaunchedEffect(uiState.exportDone) {
        if (uiState.exportDone) {
            onNotification(InAppNotification.ExportSuccess(uiState.currentFile?.fileName ?: ""))
            viewModel.clearExportDone()
        }
    }

    LaunchedEffect(uiState.pendingNotification) {
        uiState.pendingNotification?.let {
            onNotification(it)
            viewModel.clearPendingNotification()
        }
    }

    val pageCount = uiState.siblings.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(initialPage = uiState.currentIndex) { pageCount }

    // Sync pager to the ViewModel's resolved index (siblings load asynchronously,
    // so initialPage=0 is always captured on first composition).
    LaunchedEffect(uiState.currentIndex) {
        if (pagerState.currentPage != uiState.currentIndex) {
            pagerState.scrollToPage(uiState.currentIndex)
        }
    }

    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }
            .distinctUntilChanged()
            .drop(1)
            .collect { viewModel.navigateTo(it) }
    }

    val systemUiController = rememberSystemUiController()
    DisposableEffect(Unit) { onDispose { systemUiController.isSystemBarsVisible = true } }

    var showBars by remember { mutableStateOf(true) }
    LaunchedEffect(showBars) {
        systemUiController.isSystemBarsVisible = showBars
        if (showBars) { delay(3_000); showBars = false }
    }

    var seekLeftToken  by remember { mutableStateOf(0) }
    var seekRightToken by remember { mutableStateOf(0) }
    var showSeekLeft   by remember { mutableStateOf(false) }
    var showSeekRight  by remember { mutableStateOf(false) }
    LaunchedEffect(seekLeftToken)  { if (seekLeftToken  > 0) { showSeekLeft  = true; delay(800); showSeekLeft  = false } }
    LaunchedEffect(seekRightToken) { if (seekRightToken > 0) { showSeekRight = true; delay(800); showSeekRight = false } }

    // True once the user has deliberately tapped the video — enables the center play button
    var userInteracted by remember { mutableStateOf(false) }

    // Reset bars and configure the shared ExoPlayer when the active file changes
    LaunchedEffect(uiState.currentFile?.id) {
        userInteracted = false
        showBars = true
        val file = uiState.currentFile ?: return@LaunchedEffect
        if (file.fileType == MediaFileType.VIDEO && videoFactory != null && exoPlayer != null) {
            videoFactory.configure(file.relativePath, file.size)
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse("${EncryptedDataSource.URI_SCHEME}://${file.relativePath}")))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true  // auto-play
            positionMs  = 0L
            durationMs  = 0L
            isPlaying   = false
            isScrubbing = false
        } else if (file.fileType != MediaFileType.VIDEO) {
            exoPlayer?.pause()
        }
    }

    var isImageZoomed   by remember { mutableStateOf(false) }
    var swipeDownOffset by remember { mutableStateOf(0f) }
    val bgAlpha = (1f - swipeDownOffset / 800f).coerceIn(0f, 1f)

    var showDeleteDialog   by remember { mutableStateOf(false) }
    var showInfoSheet      by remember { mutableStateOf(false) }
    var showDateSheet      by remember { mutableStateOf(false) }
    var showExifSheet      by remember { mutableStateOf(false) }
    var showRenameDialog   by remember { mutableStateOf(false) }
    var showGpsDialog      by remember { mutableStateOf(false) }
    var videoMenuExpanded  by remember { mutableStateOf(false) }

    LaunchedEffect(showInfoSheet, uiState.currentFile?.id) {
        if (showInfoSheet && uiState.exifData == null && !uiState.isExifLoading) {
            viewModel.loadExif()
        }
    }

    val hazeState = remember { HazeState() }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = bgAlpha))
                .hazeSource(hazeState)
        ) {
            // ── Pager ────────────────────────────────────────────────────
            HorizontalPager(
                state             = pagerState,
                userScrollEnabled = !isImageZoomed,
                modifier          = Modifier.fillMaxSize()
            ) { page ->
                val isCurrent = page == pagerState.currentPage
                val pageFile  = uiState.siblings.getOrNull(page)
                val bitmap    = pageFile?.let { uiState.bitmapCache[it.id] }
                when (pageFile?.fileType) {
                    MediaFileType.IMAGE -> ZoomableImagePage(
                        bitmap              = bitmap,
                        isLoading           = uiState.isLoading && isCurrent,
                        onTap               = { showBars = !showBars },
                        onScaleChanged      = { isImageZoomed = it > 1.05f },
                        onSwipeOffsetChange = { swipeDownOffset = it },
                        onDismiss           = onBack
                    )
                    MediaFileType.VIDEO -> VideoSurfacePage(
                        exoPlayer           = if (isCurrent) exoPlayer else null,
                        onTap               = { userInteracted = true; showBars = !showBars },
                        onSwipeOffsetChange = { swipeDownOffset = it },
                        onDismiss           = onBack,
                        onDoubleTapLeft     = { exoPlayer?.let { p -> p.seekTo((p.currentPosition - 10_000L).coerceAtLeast(0L)) }; seekLeftToken++ },
                        onDoubleTapRight    = { exoPlayer?.let { p -> p.seekTo(p.currentPosition + 10_000L) }; seekRightToken++ }
                    )
                    else -> Box(Modifier.fillMaxSize().background(Color.Black))
                }
            }

            // ── Seek indicators — sit just above the bottom controls bar ──
            AnimatedVisibility(
                visible  = showSeekLeft,
                enter    = fadeIn(tween(120)),
                exit     = fadeOut(tween(300)),
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 24.dp, bottom = 170.dp)
            ) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Filled.FastRewind, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    Text(stringResource(R.string.viewer_seek_back), color = Color.White, style = MaterialTheme.typography.labelLarge)
                }
            }

            AnimatedVisibility(
                visible  = showSeekRight,
                enter    = fadeIn(tween(120)),
                exit     = fadeOut(tween(300)),
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 170.dp)
            ) {
                Row(
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.6f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(stringResource(R.string.viewer_seek_forward), color = Color.White, style = MaterialTheme.typography.labelLarge)
                    Icon(Icons.Filled.FastForward, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            // ── Center play/pause — circle button, shown only after user tap ─
            if (uiState.currentFile?.fileType == MediaFileType.VIDEO) {
                AnimatedVisibility(
                    visible  = showBars && userInteracted,
                    enter    = fadeIn(tween(200)),
                    exit     = fadeOut(tween(200)),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color.Black.copy(alpha = 0.55f))
                            .clickable { if (isPlaying) exoPlayer?.pause() else exoPlayer?.play() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (isBuffering) {
                            CircularProgressIndicator(color = Color.White, modifier = Modifier.size(34.dp), strokeWidth = 2.5.dp)
                        } else {
                            Icon(
                                if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (isPlaying) stringResource(R.string.viewer_cd_pause) else stringResource(R.string.viewer_cd_play),
                                tint     = Color.White,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }
            }

            // ── Top bar ───────────────────────────────────────────────────
            AnimatedVisibility(
                visible  = showBars,
                enter    = fadeIn() + slideInVertically { -it },
                exit     = fadeOut() + slideOutVertically { -it },
                modifier = Modifier.align(Alignment.TopCenter)
            ) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Black.copy(0.65f), Color.Transparent)))
                ) {
                    Row(
                        modifier          = Modifier.fillMaxWidth().statusBarsPadding()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.viewer_cd_back), tint = Color.White)
                        }
                        Text(
                            text     = uiState.currentFile?.fileName ?: "",
                            style    = MaterialTheme.typography.bodyMedium,
                            color    = Color.White,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (uiState.currentFile?.fileType == MediaFileType.VIDEO) {
                            val isFavMenu = uiState.currentFile?.isFavorite == true
                            Box {
                                IconButton(onClick = { videoMenuExpanded = true }) {
                                    Icon(Icons.Filled.MoreVert, stringResource(R.string.viewer_cd_more_options), tint = Color.White)
                                }
                                DropdownMenu(
                                    expanded         = videoMenuExpanded,
                                    onDismissRequest = { videoMenuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        leadingIcon = {
                                            Icon(
                                                if (isFavMenu) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                                null,
                                                tint = if (isFavMenu) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        },
                                        text    = { Text(if (isFavMenu) stringResource(R.string.viewer_action_remove_favorite) else stringResource(R.string.viewer_action_add_favorite)) },
                                        onClick = { viewModel.toggleFavorite(); videoMenuExpanded = false }
                                    )
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Filled.Share, null) },
                                        text        = { Text(stringResource(R.string.viewer_action_export)) },
                                        onClick     = { exportLauncher.launch(uiState.currentFile?.fileName ?: "export"); videoMenuExpanded = false }
                                    )
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                        text        = { Text(stringResource(R.string.viewer_action_delete)) },
                                        onClick     = { showDeleteDialog = true; videoMenuExpanded = false }
                                    )
                                    DropdownMenuItem(
                                        leadingIcon = { Icon(Icons.Filled.Info, null) },
                                        text        = { Text(stringResource(R.string.viewer_action_details)) },
                                        onClick     = { showInfoSheet = true; videoMenuExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Bottom bar ────────────────────────────────────────────────
            AnimatedVisibility(
                visible  = showBars,
                enter    = fadeIn() + slideInVertically { it },
                exit     = fadeOut() + slideOutVertically { it },
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                val isFav = uiState.currentFile?.isFavorite == true
                if (uiState.currentFile?.fileType == MediaFileType.VIDEO) {
                    val sliderValue = if (durationMs > 0L) {
                        ((if (isScrubbing) scrubMs else positionMs).toFloat() / durationMs).coerceIn(0f, 1f)
                    } else 0f
                    VideoBottomBar(
                        sliderValue            = sliderValue,
                        positionMs             = if (isScrubbing) scrubMs else positionMs,
                        durationMs             = durationMs,
                        onSliderChange         = { f -> val ms = (f * durationMs).toLong(); isScrubbing = true; scrubMs = ms; exoPlayer?.seekTo(ms) },
                        onSliderChangeFinished = { exoPlayer?.seekTo(scrubMs); isScrubbing = false }
                    )
                } else {
                    Box(
                        modifier = Modifier.fillMaxWidth()
                            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.65f))))
                    ) {
                        Row(
                            modifier              = Modifier.fillMaxWidth().navigationBarsPadding()
                                .padding(horizontal = 8.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { viewModel.toggleFavorite() }) {
                                Icon(if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                    stringResource(R.string.viewer_action_favorite), tint = if (isFav) Color.Red else Color.White)
                            }
                            IconButton(onClick = {
                                exportLauncher.launch(uiState.currentFile?.fileName ?: "export")
                            }) { Icon(Icons.Filled.Share, stringResource(R.string.viewer_action_export), tint = Color.White) }
                            IconButton(onClick = { showDeleteDialog = true }) {
                                Icon(Icons.Filled.Delete, stringResource(R.string.viewer_action_delete), tint = Color.White)
                            }
                            IconButton(onClick = { showInfoSheet = true }) {
                                Icon(Icons.Filled.Info, stringResource(R.string.common_info), tint = Color.White)
                            }
                        }
                    }
                }
            }
        }

        // ── Info sheet ────────────────────────────────────────────────────
        if (showInfoSheet) {
            uiState.currentFile?.let { file ->
                AppSheet(
                    onDismissRequest = { showInfoSheet = false },
                    sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = false)
                ) {
                    MediaInfoSheetContent(
                        file              = file,
                        exifData          = uiState.exifData,
                        isExifLoading     = uiState.isExifLoading,
                        onSaveDescription = { viewModel.updateDescription(it) },
                        onEditDate        = { showInfoSheet = false; showDateSheet = true },
                        onEditFile        = { showRenameDialog = true },
                        onOpenMaps        = { lat, lng -> launchMaps(context, lat, lng) },
                        onEditGps         = { showGpsDialog = true },
                        onViewExif        = { showInfoSheet = false; showExifSheet = true }
                    )
                }
            }
        }

        // ── Date/time edit sheet ──────────────────────────────────────────
        if (showDateSheet) {
            uiState.currentFile?.let { file ->
                AppSheet(
                    onDismissRequest = { showDateSheet = false; showInfoSheet = true },
                    sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                ) {
                    DateTimeEditContent(
                        initialMillis = file.dateCreated,
                        onDismiss     = { showDateSheet = false; showInfoSheet = true },
                        onSave        = { millis ->
                            viewModel.updateDateTime(millis)
                            showDateSheet = false
                            showInfoSheet = true
                        }
                    )
                }
            }
        }

        // ── Full EXIF sheet ───────────────────────────────────────────────
        if (showExifSheet) {
            AppSheet(
                onDismissRequest = { showExifSheet = false; showInfoSheet = true },
                sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true)
            ) {
                FullExifSheetContent(
                    allTags   = uiState.exifData?.allTags ?: emptyList(),
                    isLoading = uiState.isExifLoading
                )
            }
        }

        // ── Rename dialog ─────────────────────────────────────────────────
        if (showRenameDialog) {
            uiState.currentFile?.let { file ->
                RenameFileDialog(
                    currentName = file.fileName,
                    onDismiss   = { showRenameDialog = false },
                    onRename    = { newName ->
                        viewModel.renameFile(newName) { _ -> }
                        showRenameDialog = false
                    }
                )
            }
        }

        // ── GPS edit dialog ───────────────────────────────────────────────
        if (showGpsDialog) {
            GpsEditDialog(
                initialLat = uiState.exifData?.gpsLatitude ?: 0.0,
                initialLng = uiState.exifData?.gpsLongitude ?: 0.0,
                onDismiss  = { showGpsDialog = false },
                onSave     = { lat, lng ->
                    viewModel.updateGps(lat, lng)
                    showGpsDialog = false
                }
            )
        }
    }

    // ── Delete dialog ─────────────────────────────────────────────────────
    if (showDeleteDialog) {
        AppDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.viewer_delete_title, uiState.currentFile?.fileName ?: "file")) },
            text  = { Text(stringResource(R.string.common_this_cannot_be_undone)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteCurrentFile { onBack() }
                }) { Text(stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

// ── Video surface page ────────────────────────────────────────────────────

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoSurfacePage(
    exoPlayer: ExoPlayer?,
    onTap: () -> Unit,
    onSwipeOffsetChange: (Float) -> Unit,
    onDismiss: () -> Unit,
    onDoubleTapLeft: () -> Unit,
    onDoubleTapRight: () -> Unit
) {
    var swipeY by remember { mutableStateOf(0f) }
    var width  by remember { mutableStateOf(0)  }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { width = it.width }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap       = { onTap() },
                    onDoubleTap = { offset ->
                        if (offset.x < width / 2f) onDoubleTapLeft() else onDoubleTapRight()
                    }
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dy ->
                        if (dy > 0) { swipeY += dy; onSwipeOffsetChange(swipeY) }
                    },
                    onDragEnd    = {
                        if (swipeY > 200.dp.toPx()) onDismiss()
                        else { swipeY = 0f; onSwipeOffsetChange(0f) }
                    },
                    onDragCancel = { swipeY = 0f; onSwipeOffsetChange(0f) }
                )
            }
            .graphicsLayer { translationY = swipeY }
    ) {
        if (exoPlayer != null) {
            AndroidView(
                factory  = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode    = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setBackgroundColor(android.graphics.Color.BLACK)
                    }
                },
                update   = { view -> view.player = exoPlayer },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier         = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint               = Color.White.copy(alpha = 0.35f),
                    modifier           = Modifier.size(72.dp)
                )
            }
        }

    }
}

// ── Video bottom bar ──────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VideoBottomBar(
    sliderValue: Float,
    positionMs: Long,
    durationMs: Long,
    onSliderChange: (Float) -> Unit,
    onSliderChangeFinished: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.75f))))
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 8.dp, end = 16.dp, bottom = 12.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Slider(
                value                 = sliderValue,
                onValueChange         = onSliderChange,
                onValueChangeFinished = onSliderChangeFinished,
                colors                = SliderDefaults.colors(
                    thumbColor         = Color.White,
                    activeTrackColor   = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.35f)
                ),
                thumb    = { _ -> Spacer(Modifier.size(12.dp).background(Color.White, CircleShape)) },
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text     = "${formatTime(positionMs)} / ${formatTime(durationMs)}",
                color    = Color.White.copy(alpha = 0.85f),
                style    = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

// ── Maps launcher (non-composable) ───────────────────────────────────────

private fun launchMaps(context: Context, lat: Double, lng: Double) {
    val uri = Uri.parse("geo:$lat,$lng?q=$lat,$lng")
    val intent = Intent(Intent.ACTION_VIEW, uri).apply { setPackage("com.google.android.apps.maps") }
    try {
        context.startActivity(intent)
    } catch (_: Exception) {
        intent.setPackage(null)
        try { context.startActivity(Intent.createChooser(intent, "Open in Maps")) } catch (_: Exception) {}
    }
}

// ── Zoomable image page ───────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ZoomableImagePage(
    bitmap: Bitmap?,
    isLoading: Boolean,
    onTap: () -> Unit,
    onScaleChanged: (Float) -> Unit,
    onSwipeOffsetChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    val scale         = remember { Animatable(1f) }
    val panX          = remember { Animatable(0f) }
    val panY          = remember { Animatable(0f) }
    var swipeY        by remember { mutableStateOf(0f) }
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val scope         = rememberCoroutineScope()

    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val newScale = (scale.value * zoomChange).coerceIn(1f, 5f)
        val maxX = (containerSize.width  * (newScale - 1f)) / 2f
        val maxY = (containerSize.height * (newScale - 1f)) / 2f
        val newX = if (newScale > 1f) (panX.value + panChange.x).coerceIn(-maxX, maxX) else 0f
        val newY = if (newScale > 1f) (panY.value + panChange.y).coerceIn(-maxY, maxY) else 0f
        scope.launch {
            scale.snapTo(newScale)
            panX.snapTo(newX)
            panY.snapTo(newY)
        }
        onScaleChanged(newScale)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap       = { onTap() },
                    onDoubleTap = {
                        scope.launch {
                            if (scale.value > 1.05f) {
                                onScaleChanged(1f)
                                launch { scale.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
                                launch { panX.animateTo(0f, tween(280, easing = FastOutSlowInEasing)) }
                                launch { panY.animateTo(0f, tween(280, easing = FastOutSlowInEasing)) }
                            } else {
                                onScaleChanged(2.5f)
                                scale.animateTo(2.5f, tween(280, easing = FastOutSlowInEasing))
                            }
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onVerticalDrag = { _, dy ->
                        if (scale.value <= 1.05f && dy > 0) { swipeY += dy; onSwipeOffsetChange(swipeY) }
                    },
                    onDragEnd    = {
                        if (swipeY > 200.dp.toPx()) onDismiss()
                        else { swipeY = 0f; onSwipeOffsetChange(0f) }
                    },
                    onDragCancel = { swipeY = 0f; onSwipeOffsetChange(0f) }
                )
            }
            .transformable(state = transformState, canPan = { scale.value > 1.05f })
            .graphicsLayer {
                scaleX = scale.value; scaleY = scale.value
                translationX = panX.value; translationY = panY.value + swipeY
            }
    ) {
        when {
            isLoading    -> CircularProgressIndicator(color = Color.White, modifier = Modifier.size(48.dp))
            bitmap != null -> Image(bitmap.asImageBitmap(), null,
                contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            else -> Icon(Icons.Filled.BrokenImage, null,
                tint = Color.White.copy(alpha = 0.3f), modifier = Modifier.size(72.dp))
        }
    }
}

// ── Media info sheet content ──────────────────────────────────────────────

@Composable
private fun MediaInfoSheetContent(
    file: MediaFileEntity,
    exifData: MediaExifData?,
    isExifLoading: Boolean,
    onSaveDescription: (String) -> Unit,
    onEditDate: () -> Unit,
    onEditFile: () -> Unit,
    onOpenMaps: (Double, Double) -> Unit,
    onEditGps: () -> Unit,
    onViewExif: () -> Unit
) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 36.dp)
    ) {
        Text(
            text       = stringResource(R.string.viewer_sheet_info_title),
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        )

        if (isExifLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        // Description
        DescriptionField(description = file.description, onSaveDescription = onSaveDescription)

        InfoDivider()

        // Date row
        val dateMillis = exifData?.dateTimeOriginal ?: file.dateCreated
        val (dateStr, timeStr) = formatDateParts(dateMillis)
        InfoRow(
            icon     = Icons.Outlined.CalendarMonth,
            onClick  = onEditDate,
            trailing = { RowEditIcon(onEditDate) }
        ) {
            Text(dateStr, style = MaterialTheme.typography.bodyLarge)
            Text(timeStr, style = MaterialTheme.typography.bodySmall, color = secondary)
        }

        InfoDivider()

        // Filename row
        val w  = (exifData?.widthPx?.takeIf { it > 0 } ?: file.width.takeIf { it > 0 })
        val h  = (exifData?.heightPx?.takeIf { it > 0 } ?: file.height.takeIf { it > 0 })
        val mp = exifData?.megapixels
        InfoRow(
            icon     = Icons.Outlined.Image,
            onClick  = onEditFile,
            trailing = { RowEditIcon(onEditFile) }
        ) {
            Text(file.fileName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            when {
                mp != null && w != null && h != null ->
                    Text("%.1fMP · %d×%d".format(mp, w, h), style = MaterialTheme.typography.bodySmall, color = secondary)
                w != null && h != null ->
                    Text("%d×%d".format(w, h), style = MaterialTheme.typography.bodySmall, color = secondary)
            }
            Text(file.size.formatFileSize(), style = MaterialTheme.typography.bodySmall, color = secondary)
        }

        // Camera row (only if EXIF has camera data)
        val hasCam = exifData != null && (exifData.cameraMake != null || exifData.cameraModel != null)
        if (hasCam) {
            InfoDivider()
            val cameraName = listOfNotNull(exifData?.cameraMake, exifData?.cameraModel).joinToString(" ")
            val params     = listOfNotNull(exifData?.fNumber, exifData?.exposureTime, exifData?.focalLength).joinToString(" · ")
            InfoRow(icon = Icons.Outlined.CameraAlt) {
                Text(cameraName, style = MaterialTheme.typography.bodyLarge)
                if (params.isNotEmpty()) Text(params, style = MaterialTheme.typography.bodySmall, color = secondary)
                exifData?.iso?.let { Text("ISO $it", style = MaterialTheme.typography.bodySmall, color = secondary) }
            }
        }

        // GPS row (only if EXIF has location)
        val lat = exifData?.gpsLatitude
        val lng = exifData?.gpsLongitude
        if (lat != null && lng != null) {
            InfoDivider()
            InfoRow(
                icon     = Icons.Outlined.LocationOn,
                onClick  = { onOpenMaps(lat, lng) },
                trailing = { RowEditIcon(onEditGps) }
            ) {
                Text("${lat.formatCoord(true)}, ${lng.formatCoord(false)}", style = MaterialTheme.typography.bodyLarge)
                Text(stringResource(R.string.viewer_exif_maps_hint), style = MaterialTheme.typography.bodySmall, color = secondary)
            }
        }

        // EXIF row
        InfoDivider()
        InfoRow(
            icon     = Icons.Outlined.PhotoLibrary,
            onClick  = onViewExif,
            trailing = {
                Icon(Icons.AutoMirrored.Outlined.ArrowForwardIos, null,
                    tint = secondary, modifier = Modifier.size(16.dp))
            }
        ) {
            Text(stringResource(R.string.viewer_exif_section), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.viewer_exif_view_all), style = MaterialTheme.typography.bodySmall, color = secondary)
        }
    }
}

// ── Description text field ────────────────────────────────────────────────

@Composable
private fun DescriptionField(description: String, onSaveDescription: (String) -> Unit) {
    var text by rememberSaveable(description) { mutableStateOf(description) }

    Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp)) {
        BasicTextField(
            value         = text.take(500),
            onValueChange = { if (it.length <= 500) text = it },
            textStyle     = MaterialTheme.typography.bodyLarge.copy(
                color = MaterialTheme.colorScheme.onSurface
            ),
            cursorBrush   = SolidColor(MaterialTheme.colorScheme.primary),
            maxLines      = 3,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                imeAction      = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { onSaveDescription(text) }),
            modifier      = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (text.isEmpty()) {
                    Text(
                        text  = stringResource(R.string.viewer_description_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                inner()
            }
        )
    }
}

// ── Row / divider helpers ─────────────────────────────────────────────────

@Composable
private fun InfoRow(
    icon: ImageVector,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null,
            tint     = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f), content = content)
        if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
    }
}

@Composable
private fun RowEditIcon(onClick: () -> Unit) {
    Icon(Icons.Outlined.Edit, stringResource(R.string.viewer_cd_edit),
        tint     = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp).clickable(onClick = onClick))
}

@Composable
private fun InfoDivider() {
    HorizontalDivider(
        modifier  = Modifier.padding(horizontal = 20.dp),
        thickness = 0.5.dp,
        color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

// ── Date/time edit sheet ──────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateTimeEditContent(
    initialMillis: Long,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit
) {
    val zdt       = Instant.ofEpochMilli(initialMillis).atZone(ZoneId.systemDefault())
    val dateState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
    val timeState = rememberTimePickerState(initialHour = zdt.hour, initialMinute = zdt.minute)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(bottom = 24.dp)
    ) {
        Text(
            text       = stringResource(R.string.viewer_edit_date_title),
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier.padding(horizontal = 8.dp, vertical = 16.dp)
        )

        DatePicker(state = dateState, modifier = Modifier.fillMaxWidth(), showModeToggle = false)

        Spacer(Modifier.height(8.dp))

        TimeInput(state = timeState, modifier = Modifier.align(Alignment.CenterHorizontally))

        Spacer(Modifier.height(12.dp))

        Text(
            text     = stringResource(R.string.viewer_edit_date_warning),
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )

        Spacer(Modifier.height(16.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = {
                    val sel = dateState.selectedDateMillis ?: initialMillis
                    val combined = Instant.ofEpochMilli(sel)
                        .atZone(ZoneId.systemDefault())
                        .withHour(timeState.hour)
                        .withMinute(timeState.minute)
                        .withSecond(0)
                        .toInstant()
                        .toEpochMilli()
                    onSave(combined)
                }
            ) { Text(stringResource(R.string.common_save)) }
        }
    }
}

// ── Rename dialog ─────────────────────────────────────────────────────────

@Composable
private fun RenameFileDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by rememberSaveable(currentName) { mutableStateOf(currentName) }
    val ext  = currentName.substringAfterLast(".", "")
    val invalidChars = Regex("""[/\\:*?"<>|]""")
    val errorEmpty   = stringResource(R.string.viewer_rename_error_empty)
    val errorInvalid = stringResource(R.string.viewer_rename_error_invalid)
    val nameError = when {
        name.isBlank()                     -> errorEmpty
        invalidChars.containsMatchIn(name) -> errorInvalid
        else                               -> null
    }
    val canRename = nameError == null && name != currentName

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_rename_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text(stringResource(R.string.viewer_rename_label)) },
                    isError       = nameError != null,
                    supportingText = {
                        when {
                            nameError != null -> Text(nameError)
                            ext.isNotEmpty() && !name.contains('.') ->
                                Text(stringResource(R.string.viewer_rename_ext_hint, ext.uppercase()))
                        }
                    },
                    singleLine = true,
                    modifier   = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = canRename, onClick = { onRename(name) }) { Text(stringResource(R.string.viewer_rename_confirm)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

// ── GPS edit dialog ───────────────────────────────────────────────────────

@Composable
private fun GpsEditDialog(
    initialLat: Double,
    initialLng: Double,
    onDismiss: () -> Unit,
    onSave: (Double, Double) -> Unit
) {
    var latStr by rememberSaveable { mutableStateOf("%.6f".format(initialLat)) }
    var lngStr by rememberSaveable { mutableStateOf("%.6f".format(initialLng)) }

    fun valid(s: String, lo: Double, hi: Double) = s.toDoubleOrNull()?.takeIf { it in lo..hi }

    val lat = valid(latStr, -90.0, 90.0)
    val lng = valid(lngStr, -180.0, 180.0)

    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.viewer_gps_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value         = latStr,
                    onValueChange = { latStr = it },
                    label         = { Text(stringResource(R.string.viewer_gps_latitude)) },
                    isError       = latStr.isNotEmpty() && lat == null,
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = lngStr,
                    onValueChange = { lngStr = it },
                    label         = { Text(stringResource(R.string.viewer_gps_longitude)) },
                    isError       = lngStr.isNotEmpty() && lng == null,
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(enabled = lat != null && lng != null,
                onClick = { onSave(lat!!, lng!!) }) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

// ── Full EXIF sheet content ───────────────────────────────────────────────

@Composable
private fun FullExifSheetContent(allTags: List<ExifTag>, isLoading: Boolean) {
    val clipboard = LocalClipboard.current
    val scope     = rememberCoroutineScope()
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val grouped   = allTags.groupBy { it.directory }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 36.dp)
    ) {
        Text(
            text       = stringResource(R.string.viewer_exif_title),
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        )

        if (isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }

        if (!isLoading && allTags.isEmpty()) {
            Text(
                text     = stringResource(R.string.viewer_exif_empty),
                style    = MaterialTheme.typography.bodyMedium,
                color    = secondary,
                modifier = Modifier.padding(24.dp)
            )
        }

        grouped.forEach { (dirName, tags) ->
            Text(
                text       = dirName,
                style      = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.primary,
                modifier   = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 4.dp)
            )
            tags.forEach { tag ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(tag) {
                            detectTapGestures(
                                onLongPress = { scope.launch { clipboard.setClipEntry(ClipData.newPlainText("", tag.value).toClipEntry()) } }
                            )
                        }
                        .padding(horizontal = 24.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text     = tag.name,
                        style    = MaterialTheme.typography.bodySmall,
                        color    = secondary,
                        modifier = Modifier.weight(0.45f)
                    )
                    Text(
                        text     = tag.value,
                        style    = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(0.55f)
                    )
                }
            }
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
                color    = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )
        }
    }
}

// ── Formatters ────────────────────────────────────────────────────────────

private fun Long.formatFileSize(): String {
    val fmt = DecimalFormat("#.##")
    return when {
        this >= 1_073_741_824L -> "${fmt.format(this / 1_073_741_824.0)} GB"
        this >= 1_048_576L     -> "${fmt.format(this / 1_048_576.0)} MB"
        else                   -> "${fmt.format(this / 1024.0)} KB"
    }
}

private fun formatDateParts(millis: Long): Pair<String, String> {
    if (millis == 0L) return Pair("—", "—")
    val zdt  = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    val dow  = zdt.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    val mon  = zdt.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    val date = "$dow, $mon ${zdt.dayOfMonth}, ${zdt.year}"
    val h12  = if (zdt.hour % 12 == 0) 12 else zdt.hour % 12
    val ampm = if (zdt.hour < 12) "AM" else "PM"
    val time = "%d:%02d %s".format(h12, zdt.minute, ampm)
    return Pair(date, time)
}

private fun Double.formatCoord(isLat: Boolean): String {
    val dir = if (isLat) (if (this >= 0) "N" else "S") else (if (this >= 0) "E" else "W")
    return "%.4f° %s".format(abs(this), dir)
}

