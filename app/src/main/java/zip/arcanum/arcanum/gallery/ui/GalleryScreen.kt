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
import androidx.compose.runtime.remember
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import zip.arcanum.core.components.AppSheet
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material3.RadioButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.outlined.SwapVert
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
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.LocalContentColor
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import android.graphics.Bitmap
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
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
    viewModel: GalleryViewModel = hiltViewModel()
) {
    LaunchedEffect(containerId) {
        if (containerId != null) viewModel.loadForContainer(containerId)
    }

    val sortSheetState    = rememberModalBottomSheetState()
    val folderSheetState  = rememberModalBottomSheetState()

    val uiState           by viewModel.uiState.collectAsState()
    val thumbnails        by viewModel.thumbnails.collectAsState()
    val selectedIds       by viewModel.selectedIds.collectAsState()
    val preloadState      by viewModel.preloadState.collectAsState()
    val showResyncButton  by viewModel.showResyncButton.collectAsState()

    if (uiState.showOptionsSheet) {
        AppSheet(
            onDismissRequest = { viewModel.setOptionsSheet(false) },
            sheetState       = sortSheetState
        ) {
            GalleryOptionsSheet(
                filter      = uiState.selectedFilter,
                sortBy      = uiState.sortBy,
                ascending   = uiState.sortAscending,
                onFilter    = { viewModel.setFilter(it) },
                onSortBy    = { viewModel.setSortBy(it) },
                onToggleDir = viewModel::toggleSortDirection
            )
        }
    }

    if (uiState.showFolderSheet) {
        AppSheet(
            onDismissRequest = { viewModel.setFolderSheet(false) },
            sheetState       = folderSheetState
        ) {
            GalleryFolderSheet(
                folders      = uiState.folders,
                selected     = uiState.folderFilter,
                totalCount   = uiState.allMedia.size,
                thumbnails   = thumbnails,
                onRequestThumbnail = viewModel::requestThumbnail,
                onToggle     = viewModel::toggleFolder,
                onShowAll    = viewModel::showAllFolders
            )
        }
    }

    val selectionMode = selectedIds.isNotEmpty()

    // Intercept back press in selection mode instead of navigating away
    BackHandler(enabled = selectionMode) { viewModel.clearSelection() }

    val isPreloading = containerId != null
            && preloadState.isRunning
            && preloadState.containerId == containerId

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
                    isSearchActive   = uiState.isSearchActive,
                    searchQuery      = uiState.searchQuery,
                    selectionMode    = selectionMode,
                    selectedCount    = selectedIds.size,
                    isReadOnly       = uiState.isReadOnly,
                    showResyncButton = showResyncButton,
                    onSearchToggle   = { viewModel.setSearchActive(!uiState.isSearchActive) },
                    onSearchChange   = { viewModel.setSearchQuery(it) },
                    onSearchClose    = { viewModel.setSearchActive(false) },
                    onResync         = { containerId?.let { viewModel.scanContainer(it) } },
                    onClearSelection = { viewModel.clearSelection() },
                    onDeleteSelected = { viewModel.requestDeleteSelected() },
                    onSortClick      = { viewModel.setOptionsSheet(true) },
                    foldersFiltered  = uiState.folderFilter.isNotEmpty(),
                    onFoldersClick   = { viewModel.setFolderSheet(true) }
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
    showResyncButton: Boolean,
    onSearchToggle: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onResync: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit,
    onSortClick: () -> Unit,
    foldersFiltered: Boolean = false,
    onFoldersClick: () -> Unit = {}
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
                if (showResyncButton && !isSearchActive) {
                    IconButton(onClick = onResync) {
                        Icon(Icons.Outlined.Sync, stringResource(R.string.gallery_scan))
                    }
                }
                if (!isSearchActive) {
                    IconButton(onClick = onSortClick) {
                        Icon(Icons.Outlined.SwapVert, stringResource(R.string.files_sort_title))
                    }
                    FoldersAction(foldersFiltered, onFoldersClick)
                }
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
    onPhotoSelect: (MediaFileEntity) -> Unit,
    onDaySelect: (GalleryViewModel.DayGroup) -> Unit,
    onMonthSelect: (GalleryViewModel.MonthGroup) -> Unit
) {
    val gridState = rememberLazyGridState()

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

    /*
     * A grid of cells rather than a column of hand-made rows of three.
     *
     * The rows were an optimisation - one lazy item per row, so only visible rows composed -
     * and they cost the thing the grid is now asked for: with a row as the unit, a photograph
     * that leaves cannot be animated out and the ones after it cannot slide up, because the
     * row keeps its key and merely changes what is in it. A LazyVerticalGrid composes only
     * visible cells too, and gives every photograph a key of its own, which is what
     * animateItem needs.
     */
    LazyVerticalGrid(
        columns        = GridCells.Fixed(3),
        state          = gridState,
        contentPadding = PaddingValues(
            top    = innerPadding.calculateTopPadding(),
            bottom = bottomPadding
        ),
        modifier       = Modifier.fillMaxSize()
    ) {
        if (uiState.isScanning) {
            item(key = "scan_progress", span = { GridItemSpan(maxLineSpan) }) {
                ScanProgressBar(
                    progress    = uiState.scanProgress,
                    total       = uiState.scanTotal,
                    currentPath = uiState.currentScanPath
                )
            }
        }

        if (isPreloading) {
            item(key = "preload_progress", span = { GridItemSpan(maxLineSpan) }) {
                PreloadProgressBar(done = preloadDone, total = preloadTotal)
            }
        }

        // Headers only for the date sort. "March 2026" and its select-all checkbox describe a
        // timeline; ordered by name or size the same headers would be one meaningless band
        // across the whole grid (#122).
        val grouped = uiState.sortBy == GalleryViewModel.SortBy.DATE

        uiState.monthGroups.forEach { monthGroup ->
            if (grouped) item(key = "month_${monthGroup.month}", span = { GridItemSpan(maxLineSpan) }) {
                // Computed inside item{} so it only runs for visible month headers.
                val monthAllIds = remember(monthGroup) { monthGroup.days.flatMap { it.photos }.map { it.id }.toSet() }
                val monthSelectedCount = monthAllIds.count { it in selectedIds }
                val monthSelState = when {
                    monthSelectedCount == 0               -> TriState.NONE
                    monthSelectedCount == monthAllIds.size -> TriState.ALL
                    else                                  -> TriState.PARTIAL
                }
                MonthHeader(
                    title      = monthGroup.month,
                    triState   = monthSelState,
                    onCheckClick = { onMonthSelect(monthGroup) },
                    modifier   = Modifier.animateItem()
                )
            }

            monthGroup.days.forEach { dayGroup ->
                if (grouped) item(
                    key  = "day_${monthGroup.month}_${dayGroup.date}",
                    span = { GridItemSpan(maxLineSpan) }
                ) {
                    val dayAllIds = remember(dayGroup) { dayGroup.photos.map { it.id }.toSet() }
                    val daySelectedCount = dayAllIds.count { it in selectedIds }
                    val daySelState = when {
                        daySelectedCount == 0             -> TriState.NONE
                        daySelectedCount == dayAllIds.size -> TriState.ALL
                        else                              -> TriState.PARTIAL
                    }
                    DayHeader(
                        date       = dayGroup.displayDate,
                        triState   = daySelState,
                        onCheckClick = { onDaySelect(dayGroup) },
                        modifier   = Modifier.animateItem()
                    )
                }

                items(dayGroup.photos, key = { it.id }) { file ->
                    MediaGridItem(
                        file          = file,
                        thumbnail     = thumbnails[file.id],
                        isSelected    = file.id in selectedIds,
                        selectionMode = selectionMode,
                        /* What was asked for: a photograph that the filter takes away fades
                           where it stands, and the ones after it walk into the gap rather
                           than jumping. */
                        modifier      = Modifier.animateItem(
                            fadeInSpec    = tween(180),
                            placementSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness    = Spring.StiffnessMediumLow
                            ),
                            fadeOutSpec   = tween(150)
                        ),
                        onVisible     = { onThumbnailRequest(file) },
                        onClick       = {
                            if (selectionMode) onPhotoSelect(file) else onMediaClick(file)
                        },
                        onLongPress   = { onPhotoSelect(file) }
                    )
                }
            }
        }

        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(16.dp)) }
    }
}

// ── Headers ───────────────────────────────────────────────────────────────────

private enum class TriState { NONE, PARTIAL, ALL }

@Composable
private fun MonthHeader(
    title: String,
    triState: TriState,
    modifier: Modifier = Modifier,
    onCheckClick: () -> Unit
) {
    Row(
        modifier = modifier
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
    modifier: Modifier = Modifier,
    onCheckClick: () -> Unit
) {
    Row(
        modifier = modifier
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


/**
 * What the Gallery shows and in what order.
 *
 * Laid out like [zip.arcanum.core.components.PickerSheet] - title at 16/8, radio rows at
 * 16/4, no dividers, 32dp of breathing room at the bottom - so it reads as the same kind of
 * sheet as every other picker in the app rather than as its own thing.
 */
@Composable
private fun GalleryOptionsSheet(
    filter: GalleryViewModel.MediaFilter,
    sortBy: GalleryViewModel.SortBy,
    ascending: Boolean,
    onFilter: (GalleryViewModel.MediaFilter) -> Unit,
    onSortBy: (GalleryViewModel.SortBy) -> Unit,
    onToggleDir: () -> Unit
) {
    Column(
        modifier = Modifier
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text     = stringResource(R.string.gallery_show_title),
            style    = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        GalleryViewModel.MediaFilter.entries.forEach { option ->
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .clickable { onFilter(option) }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = filter == option, onClick = { onFilter(option) })
                Text(
                    when (option) {
                        GalleryViewModel.MediaFilter.ALL    -> stringResource(R.string.gallery_filter_all)
                        GalleryViewModel.MediaFilter.PHOTOS -> stringResource(R.string.gallery_filter_photos)
                        GalleryViewModel.MediaFilter.VIDEOS -> stringResource(R.string.gallery_filter_videos)
                    },
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        Text(
            text     = stringResource(R.string.files_sort_title),
            style    = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
        )
        val options = listOf(
            GalleryViewModel.SortBy.NAME to stringResource(R.string.files_sort_name),
            GalleryViewModel.SortBy.DATE to stringResource(R.string.files_sort_date),
            GalleryViewModel.SortBy.SIZE to stringResource(R.string.files_sort_size),
            GalleryViewModel.SortBy.TYPE to stringResource(R.string.files_sort_type),
            GalleryViewModel.SortBy.RANDOM to stringResource(R.string.gallery_sort_random)
        )
        options.forEach { (option, label) ->
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .clickable { onSortBy(option) }
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = sortBy == option, onClick = { onSortBy(option) })
                Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                // No direction for a shuffle: a random order reversed is another random order.
                if (sortBy == option && option != GalleryViewModel.SortBy.RANDOM) {
                    IconButton(onClick = onToggleDir) {
                        Icon(
                            if (ascending) Icons.Outlined.KeyboardArrowUp
                            else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = if (ascending)
                                stringResource(R.string.files_sort_cd_ascending)
                            else
                                stringResource(R.string.files_sort_cd_descending)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Which folders the gallery is showing (#123).
 *
 * The reporter's complaint was that the gallery throws every folder together. This does not
 * add a second way to browse - albums with their own navigation, their own back button and
 * their own bugs - it narrows the one gallery there is: a folder is ticked, and the grid
 * shows what is in it. Nothing is ticked to begin with, which is all of them.
 *
 * A row is four of the folder's newest pictures in a square, its name, and how many files it
 * holds. The tiles are the same thumbnails the grid uses, asked for the same way, so opening
 * this sheet costs nothing that scrolling the gallery would not.
 */
@Composable
private fun GalleryFolderSheet(
    folders: List<GalleryViewModel.MediaFolder>,
    selected: Set<String>,
    totalCount: Int,
    thumbnails: Map<String, Bitmap>,
    onRequestThumbnail: (MediaFileEntity) -> Unit,
    onToggle: (String) -> Unit,
    onShowAll: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        Text(
            text       = stringResource(R.string.gallery_folders_title),
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier.padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 12.dp)
        )
        /* AppSheet lays its content out in a plain Column and scrolls nothing, so a vault
           with many folders would push the rows past the bottom of the screen - the trap
           that made the destination sheet unusable (#179). */
        LazyColumn(
            modifier = Modifier.heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.5f).dp)
        ) {
            item("all") {
                FolderRow(
                    name     = stringResource(R.string.gallery_folders_all),
                    count    = totalCount,
                    selected = selected.isEmpty(),
                    onClick  = onShowAll
                ) {
                    Box(
                        modifier         = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Outlined.PhotoLibrary,
                            contentDescription = null,
                            tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            items(folders, key = { it.path }) { folder ->
                FolderRow(
                    name     = folder.name,
                    count    = folder.count,
                    selected = folder.path in selected,
                    onClick  = { onToggle(folder.path) }
                ) {
                    FolderMosaic(
                        covers             = folder.covers,
                        thumbnails         = thumbnails,
                        onRequestThumbnail = onRequestThumbnail
                    )
                }
            }
        }
    }
}

/** Four pictures in a square: the folder's newest, filling in as their thumbnails arrive. */
@Composable
private fun FolderMosaic(
    covers: List<MediaFileEntity>,
    thumbnails: Map<String, Bitmap>,
    onRequestThumbnail: (MediaFileEntity) -> Unit
) {
    LaunchedEffect(covers) { covers.forEach(onRequestThumbnail) }
    Column(Modifier.fillMaxSize()) {
        for (row in 0 until 2) {
            Row(Modifier.weight(1f).fillMaxWidth()) {
                for (col in 0 until 2) {
                    val file   = covers.getOrNull(row * 2 + col)
                    val bitmap = file?.let { thumbnails[it.id] }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                    ) {
                        if (bitmap != null) {
                            Image(
                                bitmap             = bitmap.asImageBitmap(),
                                contentDescription = null,
                                contentScale       = ContentScale.Crop,
                                modifier           = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(
    name: String,
    count: Int,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                else Color.Transparent
            )
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(12.dp))
        ) {
            icon()
            // The tick sits on the tile rather than beside the count: the tile is what the
            // eye goes to, and the count keeps the right edge it was asked for.
            if (selected) {
                Box(
                    modifier         = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(3.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint     = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(12.dp)
                    )
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text     = name,
            style    = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text  = count.toString(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * The folder filter's button.
 *
 * Coloured while a filter is on, because a gallery showing a fraction of what is in the vault
 * and saying nothing about it is indistinguishable from one that has lost the rest.
 */
@Composable
internal fun FoldersAction(filtered: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector        = if (filtered) Icons.Filled.Folder else Icons.Outlined.Folder,
            contentDescription = stringResource(R.string.gallery_folders_cd),
            tint               = if (filtered) MaterialTheme.colorScheme.primary
                                 else LocalContentColor.current
        )
    }
}
