package zip.arcanum.arcanum.gallery.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import zip.arcanum.R
import zip.arcanum.core.components.EmptyStateView
import zip.arcanum.core.database.entities.MediaFileEntity
import zip.arcanum.core.database.entities.MediaFileType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    containerId: String? = null,
    showTopBar: Boolean = true,
    bottomPadding: Dp = 80.dp,
    onMediaClick: (MediaFileEntity) -> Unit = {},
    viewModel: GalleryViewModel = hiltViewModel()
) {
    LaunchedEffect(containerId) {
        if (containerId != null) {
            viewModel.loadForContainer(containerId)
        }
    }

    val uiState      by viewModel.uiState.collectAsState()
    val thumbnails   by viewModel.thumbnails.collectAsState()
    val preloadState by viewModel.preloadState.collectAsState()
    val isPreloading = containerId != null
            && preloadState.isRunning
            && preloadState.containerId == containerId

    if (showTopBar) {
        Scaffold(
            topBar = {
                GalleryTopBar(
                    isSearchActive = uiState.isSearchActive,
                    searchQuery    = uiState.searchQuery,
                    onSearchToggle = { viewModel.setSearchActive(!uiState.isSearchActive) },
                    onSearchChange = { viewModel.setSearchQuery(it) },
                    onSearchClose  = { viewModel.setSearchActive(false) },
                    onScanClick    = { containerId?.let { viewModel.scanContainer(it) } }
                )
            }
        ) { innerPadding ->
            GalleryContent(
                uiState            = uiState,
                thumbnails         = thumbnails,
                innerPadding       = innerPadding,
                bottomPadding      = bottomPadding,
                isPreloading       = isPreloading,
                preloadDone        = preloadState.done,
                preloadTotal       = preloadState.total,
                onMediaClick       = onMediaClick,
                onThumbnailRequest = { viewModel.requestThumbnail(it) },
                onFilterSelect     = { viewModel.setFilter(it) }
            )
        }
    } else {
        GalleryContent(
            uiState            = uiState,
            thumbnails         = thumbnails,
            innerPadding       = PaddingValues(0.dp),
            bottomPadding      = bottomPadding,
            isPreloading       = isPreloading,
            preloadDone        = preloadState.done,
            preloadTotal       = preloadState.total,
            onMediaClick       = onMediaClick,
            onThumbnailRequest = { viewModel.requestThumbnail(it) },
            onFilterSelect     = { viewModel.setFilter(it) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryTopBar(
    isSearchActive: Boolean,
    searchQuery: String,
    onSearchToggle: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onScanClick: () -> Unit
) {
    TopAppBar(
        title = {
            AnimatedVisibility(
                visible = isSearchActive,
                enter   = expandHorizontally() + fadeIn(),
                exit    = shrinkHorizontally() + fadeOut()
            ) {
                BasicTextField(
                    value         = searchQuery,
                    onValueChange = onSearchChange,
                    singleLine    = true,
                    textStyle     = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    decorationBox = { inner ->
                        Box {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    stringResource(R.string.gallery_search_placeholder),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            inner()
                        }
                    }
                )
            }
            AnimatedVisibility(visible = !isSearchActive, enter = fadeIn(), exit = fadeOut()) {
                Text(stringResource(R.string.gallery_title))
            }
        },
        actions = {
            IconButton(onClick = if (isSearchActive) onSearchClose else onSearchToggle) {
                Icon(
                    if (isSearchActive) Icons.Outlined.Close else Icons.Outlined.Search,
                    contentDescription = if (isSearchActive) stringResource(R.string.gallery_search_close) else stringResource(R.string.gallery_search_open)
                )
            }
            if (!isSearchActive) {
                IconButton(onClick = onScanClick) {
                    Icon(Icons.Outlined.FilterList, contentDescription = stringResource(R.string.gallery_scan))
                }
            }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun GalleryContent(
    uiState: GalleryViewModel.UiState,
    thumbnails: Map<String, android.graphics.Bitmap?>,
    innerPadding: PaddingValues,
    bottomPadding: Dp,
    isPreloading: Boolean = false,
    preloadDone: Int = 0,
    preloadTotal: Int = 0,
    onMediaClick: (MediaFileEntity) -> Unit,
    onThumbnailRequest: (MediaFileEntity) -> Unit,
    onFilterSelect: (GalleryViewModel.MediaFilter) -> Unit
) {
    val listState = rememberLazyListState()

    if (uiState.isEmpty && !uiState.isScanning) {
        EmptyStateView(
            title     = stringResource(R.string.gallery_empty_title),
            subtitle  = stringResource(R.string.gallery_empty_subtitle),
            lottieRes = R.raw.ghost,
            modifier  = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(bottom = bottomPadding)
        )
        return
    }

    LazyColumn(
        state          = listState,
        contentPadding = PaddingValues(
            top    = innerPadding.calculateTopPadding(),
            bottom = bottomPadding
        ),
        modifier       = Modifier.fillMaxSize()
    ) {
        // Scanning progress header
        if (uiState.isScanning) {
            item(key = "scan_progress") {
                ScanProgressBar(
                    progress    = uiState.scanProgress,
                    total       = uiState.scanTotal,
                    currentPath = uiState.currentScanPath
                )
            }
        }

        // Thumbnail pre-generation progress (background, after first mount)
        if (isPreloading) {
            item(key = "preload_progress") {
                PreloadProgressBar(done = preloadDone, total = preloadTotal)
            }
        }

        // Filter chips
        item(key = "filter_chips") {
            FilterChipsRow(
                selected = uiState.selectedFilter,
                onSelect = onFilterSelect
            )
        }

        // Timeline sections
        uiState.groupedMedia.forEach { (month, files) ->
            stickyHeader(key = "header_$month") {
                Text(
                    text     = month,
                    style    = MaterialTheme.typography.labelLarge,
                    color    = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // Rows of 3 (avoids nested LazyVerticalGrid issues)
            val rows = files.chunked(3)
            items(rows, key = { "row_${month}_${it.firstOrNull()?.id}" }) { rowFiles ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    rowFiles.forEach { file ->
                        MediaGridItem(
                            file              = file,
                            thumbnail         = thumbnails[file.id],
                            modifier          = Modifier.weight(1f),
                            onVisible         = { onThumbnailRequest(file) },
                            onClick           = { onMediaClick(file) }
                        )
                    }
                    // Fill empty slots in last row
                    repeat(3 - rowFiles.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        // Bottom spacer
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun ScanProgressBar(progress: Int, total: Int, currentPath: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier            = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.gallery_scanning),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                stringResource(R.string.gallery_scan_found, total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (currentPath.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text     = currentPath,
                style    = MaterialTheme.typography.labelSmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun PreloadProgressBar(done: Int, total: Int) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text  = stringResource(R.string.gallery_preload_preparing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text  = stringResource(R.string.gallery_preload_progress, done, total),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (total > 0) done.toFloat() / total else 0f },
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterChipsRow(
    selected: GalleryViewModel.MediaFilter,
    onSelect: (GalleryViewModel.MediaFilter) -> Unit
) {
    Row(
        modifier            = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        GalleryViewModel.MediaFilter.entries.forEach { filter ->
            FilterChip(
                selected = selected == filter,
                onClick  = { onSelect(filter) },
                label    = {
                    Text(
                        when (filter) {
                            GalleryViewModel.MediaFilter.ALL    -> stringResource(R.string.gallery_filter_all)
                            GalleryViewModel.MediaFilter.PHOTOS -> stringResource(R.string.gallery_filter_photos)
                            GalleryViewModel.MediaFilter.VIDEOS -> stringResource(R.string.gallery_filter_videos)
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun MediaGridItem(
    file: MediaFileEntity,
    thumbnail: android.graphics.Bitmap?,
    modifier: Modifier = Modifier,
    onVisible: () -> Unit,
    onClick: () -> Unit
) {
    LaunchedEffect(file.id) { onVisible() }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .clickable(onClick = onClick)
    ) {
        if (thumbnail != null) {
            Image(
                bitmap       = thumbnail.asImageBitmap(),
                contentDescription = file.fileName,
                contentScale = ContentScale.Crop,
                modifier     = Modifier.fillMaxSize()
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(shimmerBrush())
            )
        }

        when (file.fileType) {
            MediaFileType.VIDEO -> {
                Icon(
                    imageVector        = Icons.Filled.PlayCircle,
                    contentDescription = stringResource(R.string.gallery_cd_video),
                    tint               = Color.White.copy(alpha = 0.9f),
                    modifier           = Modifier
                        .size(32.dp)
                        .align(Alignment.Center)
                )
                if (file.duration > 0L) {
                    Text(
                        text     = formatDuration(file.duration),
                        style    = MaterialTheme.typography.labelSmall,
                        color    = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                    )
                }
            }
            MediaFileType.IMAGE, MediaFileType.AUDIO -> {}
        }
    }
}

@Composable
private fun shimmerBrush(): Brush {
    val shimmerColors = listOf(
        Color.DarkGray.copy(alpha = 0.6f),
        Color.DarkGray.copy(alpha = 0.2f),
        Color.DarkGray.copy(alpha = 0.6f)
    )
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue   = 0f,
        targetValue    = 1000f,
        animationSpec  = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label          = "shimmer_translate"
    )
    return Brush.linearGradient(
        colors = shimmerColors,
        start  = Offset(translateAnim - 200f, 0f),
        end    = Offset(translateAnim, 0f)
    )
}

private fun formatDuration(ms: Long): String {
    val seconds = ms / 1000
    val minutes = seconds / 60
    val secs    = seconds % 60
    val hours   = minutes / 60
    val mins    = minutes % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, mins, secs)
    else "%d:%02d".format(mins, secs)
}
