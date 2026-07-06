package zip.arcanum.arcanum.gallery.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import zip.arcanum.R
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.components.EmptyStateView
import zip.arcanum.core.database.entities.MediaFileEntity
import zip.arcanum.core.database.entities.MediaFileType
import zip.arcanum.core.notifications.InAppNotification
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.StrokeCap
import androidx.activity.compose.BackHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    containerId: String? = null,
    showTopBar: Boolean = true,
    bottomPadding: Dp = 80.dp,
    onMediaClick: (MediaFileEntity) -> Unit = {},
    onNotification: ((InAppNotification) -> Unit)? = null,
    viewModel: GalleryViewModel = hiltViewModel()
) {
    LaunchedEffect(containerId) {
        if (containerId != null) viewModel.loadForContainer(containerId)
    }

    val uiState      by viewModel.uiState.collectAsState()
    val thumbnails   by viewModel.thumbnails.collectAsState()
    val selectedIds  by viewModel.selectedIds.collectAsState()
    val preloadState by viewModel.preloadState.collectAsState()
    val selectionMode = selectedIds.isNotEmpty()

    // Intercept back press in selection mode instead of navigating away
    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }

    val isPreloading = containerId != null
            && preloadState.isRunning
            && preloadState.containerId == containerId

    // Bubble notification up to parent
    LaunchedEffect(uiState.pendingNotification) {
        val n = uiState.pendingNotification
        if (n != null) {
            onNotification?.invoke(n)
            viewModel.clearNotification()
        }
    }

    // Delete confirmation dialog
    if (uiState.showDeleteConfirm) {
        AppDialog(
            onDismissRequest = { viewModel.dismissDeleteConfirm() },
            title = { Text(stringResource(R.string.gallery_delete_dialog_title, selectedIds.size)) },
            text  = { Text(stringResource(R.string.gallery_delete_dialog_body)) },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmDeleteSelected() },
                    colors  = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) { Text(stringResource(R.string.gallery_delete_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDeleteConfirm() }) {
                    Text(stringResource(R.string.gallery_delete_cancel))
                }
            }
        )
    }

    if (showTopBar) {
        Scaffold(
            topBar = {
                GalleryTopBar(
                    isSearchActive  = uiState.isSearchActive,
                    searchQuery     = uiState.searchQuery,
                    selectionMode   = selectionMode,
                    selectedCount   = selectedIds.size,
                    isReadOnly      = uiState.isReadOnly,
                    onSearchToggle  = { viewModel.setSearchActive(!uiState.isSearchActive) },
                    onSearchChange  = { viewModel.setSearchQuery(it) },
                    onSearchClose   = { viewModel.setSearchActive(false) },
                    onClearSelection = { viewModel.clearSelection() },
                    onDeleteSelected = { viewModel.requestDeleteSelected() }
                )
            }
        ) { innerPadding ->
            GalleryContent(
                uiState            = uiState,
                thumbnails         = thumbnails,
                selectedIds        = selectedIds,
                selectionMode      = selectionMode,
                innerPadding       = innerPadding,
                bottomPadding      = bottomPadding,
                isPreloading       = isPreloading,
                preloadDone        = preloadState.done,
                preloadTotal       = preloadState.total,
                onMediaClick       = onMediaClick,
                onThumbnailRequest = { viewModel.requestThumbnail(it) },
                onFilterSelect     = { viewModel.setFilter(it) },
                onPhotoSelect      = { viewModel.togglePhotoSelection(it) },
                onDaySelect        = { viewModel.toggleDaySelection(it) },
                onMonthSelect      = { viewModel.toggleMonthSelection(it) }
            )
        }
    } else {
        GalleryContent(
            uiState            = uiState,
            thumbnails         = thumbnails,
            selectedIds        = selectedIds,
            selectionMode      = selectionMode,
            innerPadding       = PaddingValues(0.dp),
            bottomPadding      = bottomPadding,
            isPreloading       = isPreloading,
            preloadDone        = preloadState.done,
            preloadTotal       = preloadState.total,
            onMediaClick       = onMediaClick,
            onThumbnailRequest = { viewModel.requestThumbnail(it) },
            onFilterSelect     = { viewModel.setFilter(it) },
            onPhotoSelect      = { viewModel.togglePhotoSelection(it) },
            onDaySelect        = { viewModel.toggleDaySelection(it) },
            onMonthSelect      = { viewModel.toggleMonthSelection(it) }
        )
    }
}

// ── TopBar ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryTopBar(
    isSearchActive: Boolean,
    searchQuery: String,
    selectionMode: Boolean,
    selectedCount: Int,
    isReadOnly: Boolean,
    onSearchToggle: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    if (selectionMode) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onClearSelection) {
                    Icon(Icons.Outlined.ArrowBack, stringResource(R.string.gallery_deselect_all))
                }
            },
            title = {
                Text(
                    stringResource(R.string.gallery_selected_count, selectedCount),
                    style = MaterialTheme.typography.titleMedium
                )
            },
            actions = {
                IconButton(onClick = onDeleteSelected, enabled = !isReadOnly) {
                    Icon(
                        Icons.Outlined.Delete,
                        stringResource(R.string.gallery_delete_selected),
                        tint = if (isReadOnly) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                               else MaterialTheme.colorScheme.error
                    )
                }
            }
        )
    } else {
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
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.gallery_title))
                        if (isReadOnly) {
                            Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
                                Text(
                                    text     = stringResource(R.string.vault_mount_read_only),
                                    style    = MaterialTheme.typography.labelSmall,
                                    color    = MaterialTheme.colorScheme.onPrimary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }
            },
            actions = {
                IconButton(onClick = if (isSearchActive) onSearchClose else onSearchToggle) {
                    Icon(
                        if (isSearchActive) Icons.Outlined.Close else Icons.Outlined.Search,
                        contentDescription = if (isSearchActive)
                            stringResource(R.string.gallery_search_close)
                        else
                            stringResource(R.string.gallery_search_open)
                    )
                }
            }
        )
    }
}

// ── Content ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun GalleryContent(
    uiState: GalleryViewModel.UiState,
    thumbnails: Map<String, android.graphics.Bitmap?>,
    selectedIds: Set<String>,
    selectionMode: Boolean,
    innerPadding: PaddingValues,
    bottomPadding: Dp,
    isPreloading: Boolean,
    preloadDone: Int,
    preloadTotal: Int,
    onMediaClick: (MediaFileEntity) -> Unit,
    onThumbnailRequest: (MediaFileEntity) -> Unit,
    onFilterSelect: (GalleryViewModel.MediaFilter) -> Unit,
    onPhotoSelect: (MediaFileEntity) -> Unit,
    onDaySelect: (GalleryViewModel.DayGroup) -> Unit,
    onMonthSelect: (GalleryViewModel.MonthGroup) -> Unit
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
        if (uiState.isScanning) {
            item(key = "scan_progress") {
                ScanProgressBar(
                    progress    = uiState.scanProgress,
                    total       = uiState.scanTotal,
                    currentPath = uiState.currentScanPath
                )
            }
        }

        if (isPreloading) {
            item(key = "preload_progress") {
                PreloadProgressBar(done = preloadDone, total = preloadTotal)
            }
        }

        item(key = "filter_chips") {
            FilterChipsRow(selected = uiState.selectedFilter, onSelect = onFilterSelect)
        }

        uiState.monthGroups.forEach { monthGroup ->
            val monthAllIds = monthGroup.days.flatMap { it.photos }.map { it.id }.toSet()
            val monthSelectedCount = monthAllIds.count { it in selectedIds }
            val monthSelState = when {
                monthSelectedCount == 0               -> TriState.NONE
                monthSelectedCount == monthAllIds.size -> TriState.ALL
                else                                  -> TriState.PARTIAL
            }

            item(key = "month_${monthGroup.month}") {
                MonthHeader(
                    title      = monthGroup.month,
                    triState   = monthSelState,
                    onCheckClick = { onMonthSelect(monthGroup) }
                )
            }

            monthGroup.days.forEach { dayGroup ->
                val dayAllIds = dayGroup.photos.map { it.id }.toSet()
                val daySelectedCount = dayAllIds.count { it in selectedIds }
                val daySelState = when {
                    daySelectedCount == 0             -> TriState.NONE
                    daySelectedCount == dayAllIds.size -> TriState.ALL
                    else                              -> TriState.PARTIAL
                }

                item(key = "day_${monthGroup.month}_${dayGroup.date}") {
                    DayHeader(
                        date       = dayGroup.displayDate,
                        triState   = daySelState,
                        onCheckClick = { onDaySelect(dayGroup) }
                    )
                }

                item(key = "photos_${monthGroup.month}_${dayGroup.date}") {
                    DayPhotosGrid(
                        photos         = dayGroup.photos,
                        thumbnails     = thumbnails,
                        selectedIds    = selectedIds,
                        selectionMode  = selectionMode,
                        onVisible      = onThumbnailRequest,
                        onClick        = { file ->
                            if (selectionMode) onPhotoSelect(file) else onMediaClick(file)
                        },
                        onLongPress    = { file -> onPhotoSelect(file) }
                    )
                }
            }
        }

        item { Spacer(Modifier.height(16.dp)) }
    }
}

// ── Headers ───────────────────────────────────────────────────────────────────

private enum class TriState { NONE, PARTIAL, ALL }

@Composable
private fun MonthHeader(
    title: String,
    triState: TriState,
    onCheckClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = 16.dp, end = 12.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text     = title,
            style    = MaterialTheme.typography.titleLarge,
            color    = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        SelectionCircle(triState = triState, size = 22.dp, onClick = onCheckClick)
    }
}

@Composable
private fun DayHeader(
    date: String,
    triState: TriState,
    onCheckClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text     = date,
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        SelectionCircle(triState = triState, size = 20.dp, onClick = onCheckClick)
    }
}

@Composable
private fun SelectionCircle(
    triState: TriState,
    size: Dp,
    onClick: () -> Unit
) {
    val primary  = MaterialTheme.colorScheme.primary
    val outline  = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)

    Box(
        modifier = Modifier
            .size(size + 8.dp)  // tap target
            .clip(CircleShape)
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val radius = size.toPx() / 2f
            val strokePx = (size.toPx() * 0.1f).coerceAtLeast(1.5f)

            when (triState) {
                TriState.NONE -> {
                    drawCircle(
                        color  = outline,
                        radius = radius - strokePx / 2,
                        style  = Stroke(width = strokePx)
                    )
                }
                TriState.ALL -> {
                    drawCircle(color = primary, radius = radius)
                    // Checkmark
                    val cx = size.toPx() / 2f
                    val cy = size.toPx() / 2f
                    val ck = size.toPx() * 0.18f
                    drawLine(
                        color       = Color.White,
                        start       = Offset(cx - ck * 1.4f, cy),
                        end         = Offset(cx - ck * 0.3f, cy + ck),
                        strokeWidth = strokePx * 1.5f,
                        cap         = StrokeCap.Round
                    )
                    drawLine(
                        color       = Color.White,
                        start       = Offset(cx - ck * 0.3f, cy + ck),
                        end         = Offset(cx + ck * 1.4f, cy - ck),
                        strokeWidth = strokePx * 1.5f,
                        cap         = StrokeCap.Round
                    )
                }
                TriState.PARTIAL -> {
                    drawCircle(color = primary, radius = radius)
                    // Dash for partial
                    val cx = size.toPx() / 2f
                    val cy = size.toPx() / 2f
                    val hw = size.toPx() * 0.28f
                    drawLine(
                        color       = Color.White,
                        start       = Offset(cx - hw, cy),
                        end         = Offset(cx + hw, cy),
                        strokeWidth = strokePx * 1.5f,
                        cap         = StrokeCap.Round
                    )
                }
            }
        }
    }
}

// ── Day photo grid ────────────────────────────────────────────────────────────

@Composable
private fun DayPhotosGrid(
    photos: List<MediaFileEntity>,
    thumbnails: Map<String, android.graphics.Bitmap?>,
    selectedIds: Set<String>,
    selectionMode: Boolean,
    onVisible: (MediaFileEntity) -> Unit,
    onClick: (MediaFileEntity) -> Unit,
    onLongPress: (MediaFileEntity) -> Unit
) {
    val rows = photos.chunked(3)
    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEach { rowPhotos ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowPhotos.forEach { file ->
                    MediaGridItem(
                        file         = file,
                        thumbnail    = thumbnails[file.id],
                        isSelected   = file.id in selectedIds,
                        selectionMode = selectionMode,
                        modifier     = Modifier.weight(1f),
                        onVisible    = { onVisible(file) },
                        onClick      = { onClick(file) },
                        onLongPress  = { onLongPress(file) }
                    )
                }
                repeat(3 - rowPhotos.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

// ── Media grid item ───────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGridItem(
    file: MediaFileEntity,
    thumbnail: android.graphics.Bitmap?,
    isSelected: Boolean,
    selectionMode: Boolean,
    modifier: Modifier = Modifier,
    onVisible: () -> Unit,
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    LaunchedEffect(file.id) { onVisible() }

    val scale by animateFloatAsState(
        targetValue   = if (selectionMode) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label         = "photo_scale"
    )
    val cornerDp by animateDpAsState(
        targetValue   = if (selectionMode) 10.dp else 0.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMedium),
        label         = "photo_corner"
    )
    val borderAlpha by animateFloatAsState(
        targetValue   = if (isSelected) 1f else 0f,
        animationSpec = tween(200),
        label         = "border_alpha"
    )

    val primary = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .padding(1.dp)
    ) {
        // Photo (scaled + clipped with animated rounded corners)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    clip   = true
                    shape  = RoundedCornerShape(cornerDp)
                }
                .combinedClickable(onClick = onClick, onLongClick = onLongPress)
        ) {
            if (thumbnail != null) {
                Image(
                    bitmap             = thumbnail.asImageBitmap(),
                    contentDescription = file.fileName,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize()
                )
            } else {
                Box(Modifier.fillMaxSize().background(shimmerBrush()))
            }

            // Dim overlay when selected
            if (isSelected) {
                Box(Modifier.fillMaxSize().background(primary.copy(alpha = 0.3f)))
            }

            // Video badge
            if (file.fileType == MediaFileType.VIDEO) {
                Icon(
                    imageVector        = Icons.Filled.PlayCircle,
                    contentDescription = stringResource(R.string.gallery_cd_video),
                    tint               = Color.White.copy(alpha = 0.9f),
                    modifier           = Modifier.size(32.dp).align(Alignment.Center)
                )
                if (file.duration > 0L) {
                    Text(
                        text     = formatDuration(file.duration),
                        style    = MaterialTheme.typography.labelSmall,
                        color    = Color.White,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    )
                }
            }

            // Selection badge (inside the graphicsLayer so it scales with the photo)
            if (selectionMode) {
                Box(Modifier.align(Alignment.TopEnd).padding(5.dp)) {
                    Canvas(modifier = Modifier.size(20.dp)) {
                        val r = size.minDimension / 2f
                        val stroke = r * 0.18f
                        if (isSelected) {
                            drawCircle(color = primary, radius = r)
                            val cx = r; val cy = r; val ck = r * 0.38f
                            drawLine(Color.White, Offset(cx - ck * 1.4f, cy), Offset(cx - ck * 0.3f, cy + ck), stroke * 1.6f, cap = StrokeCap.Round)
                            drawLine(Color.White, Offset(cx - ck * 0.3f, cy + ck), Offset(cx + ck * 1.4f, cy - ck), stroke * 1.6f, cap = StrokeCap.Round)
                        } else {
                            drawCircle(Color.Black.copy(alpha = 0.4f), r)
                            drawCircle(Color.White.copy(alpha = 0.9f), r - stroke / 2, style = Stroke(stroke))
                        }
                    }
                }
            }
        }

        // Selection border overlay: same scale as photo but no clip, so border is visible on rounded edges
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .border(3.dp, primary.copy(alpha = borderAlpha), RoundedCornerShape(cornerDp))
        )
    }
}

// ── Progress bars ─────────────────────────────────────────────────────────────

@Composable
private fun ScanProgressBar(progress: Int, total: Int, currentPath: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                stringResource(R.string.gallery_scanning),
                style      = MaterialTheme.typography.bodyMedium,
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
        modifier              = Modifier
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

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun shimmerBrush(): Brush {
    val shimmerColors = listOf(
        Color.DarkGray.copy(alpha = 0.6f),
        Color.DarkGray.copy(alpha = 0.2f),
        Color.DarkGray.copy(alpha = 0.6f)
    )
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue  = 0f,
        targetValue   = 1000f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart),
        label         = "shimmer_translate"
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
