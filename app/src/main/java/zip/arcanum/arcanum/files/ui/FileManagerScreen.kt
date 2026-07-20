package zip.arcanum.arcanum.files.ui

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.DriveFolderUpload
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.ExperimentalFoundationApi
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import zip.arcanum.R
import zip.arcanum.arcanum.containers.domain.Container
import zip.arcanum.arcanum.files.ui.FileManagerViewModel.SortBy
import zip.arcanum.arcanum.files.ui.FileManagerViewModel.ViewMode
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.components.AppSheet
import zip.arcanum.core.components.EmptyStateView
import zip.arcanum.core.components.LocalHazeState
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.theme.ArcanumHazeStyle
import zip.arcanum.core.theme.LocalAmoledMode
import zip.arcanum.crypto.NativeFileInfo
import androidx.compose.material3.TopAppBarDefaults
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "ogg", "flac", "wav", "opus")
private val MEDIA_EXTENSIONS = setOf(
    "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif",
    "mp4", "mkv", "avi", "mov", "m4v", "webm", "3gp"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileManagerScreen(
    containerId: String,
    onBack: () -> Unit,
    onNotification: ((InAppNotification) -> Unit)? = null,
    bottomPadding: Dp = 0.dp,
    onAudioFileClick: ((path: String, name: String, size: Long) -> Unit)? = null,
    onMediaFileClick: ((fileId: String) -> Unit)? = null,
    viewModel: FileManagerViewModel = hiltViewModel()
) {
    val context          = LocalContext.current
    val state            by viewModel.state.collectAsState()
    val mountedContainers by viewModel.mountedContainers.collectAsState()
    val lifecycleOwner   = LocalLifecycleOwner.current

    var showFabMenu by remember { mutableStateOf(false) }
    val fabRotation by animateFloatAsState(
        targetValue   = if (showFabMenu) 45f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label         = "fab_rotation"
    )
    val scrimAlpha  by animateFloatAsState(
        targetValue   = if (showFabMenu) 0.6f else 0f,
        animationSpec = tween(300),
        label         = "scrim_alpha"
    )

    LaunchedEffect(containerId) { viewModel.initialize(containerId) }

    // Delete temp files when user returns to app
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.clearTempFiles(context)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Forward notifications
    LaunchedEffect(state.pendingNotification) {
        state.pendingNotification?.let { notif ->
            onNotification?.invoke(notif)
            viewModel.clearPendingNotification()
        }
    }

    // Launch intent for "open with external app"
    LaunchedEffect(state.tempFileToOpen) {
        state.tempFileToOpen?.let { (tempFile, mimeType) ->
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", tempFile)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, mimeType)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
            }
            viewModel.clearTempFileToOpen()
        }
    }

    // Close FAB when selection mode activates
    LaunchedEffect(state.isSelectionMode) { if (state.isSelectionMode) showFabMenu = false }

    // BackHandler: close FAB menu → exit selection → navigate up → onBack
    BackHandler(enabled = showFabMenu) { showFabMenu = false }
    BackHandler(enabled = state.isSelectionMode) { viewModel.exitSelectionMode() }
    BackHandler(enabled = !state.isSelectionMode && state.currentPath != "/") { viewModel.navigateUp() }

    // Activity result launchers
    var showImportSheet       by remember { mutableStateOf(false) }
    var deleteAfterImport     by rememberSaveable { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) viewModel.importFiles(context, uris, deleteAfterImport) }

    val importFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.importFolder(context, it, deleteAfterImport) } }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let { viewModel.exportSelected(context, it) } }

    // Dialog/sheet visibility
    var showNewFolderDialog    by rememberSaveable { mutableStateOf(false) }
    var showDeleteConfirm      by rememberSaveable { mutableStateOf(false) }
    var showSortSheet          by remember { mutableStateOf(false) }
    var showMoveSheet          by remember { mutableStateOf(false) }
    var showCopySheet          by remember { mutableStateOf(false) }
    var showMoreMenu           by remember { mutableStateOf(false) }
    var renameTarget           by remember { mutableStateOf<NativeFileInfo?>(null) }
    var propertiesTarget       by remember { mutableStateOf<NativeFileInfo?>(null) }
    var openWithTarget         by remember { mutableStateOf<NativeFileInfo?>(null) }
    var showOpenWithWarning    by remember { mutableStateOf(false) }

    // Hoist sheet states unconditionally (Compose rule: no hooks inside conditions)
    val propertiesSheetState   = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val openWithSheetState     = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sortSheetState         = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val moveSheetState         = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val copySheetState         = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val isAtRoot          = state.currentPath == "/"
    val isAmoled          = LocalAmoledMode.current
    val localHazeState    = remember { HazeState() }
    var headerHeight      by remember { mutableStateOf(0) }
    val density           = LocalDensity.current

    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalHazeState provides localHazeState) {
            val topPadding = with(density) { headerHeight.toDp() }

            // ── Content area (hazeSource) ─────────────────────────────────
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeSource(localHazeState)
            ) {
                when {
                    state.isLoading -> Box(Modifier.fillMaxSize().padding(top = topPadding)) {
                        CircularProgressIndicator(Modifier.align(Alignment.Center))
                    }
                    state.error != null -> Box(Modifier.fillMaxSize().padding(top = topPadding)) {
                        EmptyStateView(
                            lottieRes = R.raw.ghost,
                            title     = stringResource(R.string.files_error_cannot_browse),
                            subtitle  = state.error,
                            modifier  = Modifier.fillMaxSize()
                        )
                    }
                    state.files.isEmpty() -> Box(Modifier.fillMaxSize().padding(top = topPadding)) {
                        EmptyStateView(
                            lottieRes = R.raw.empty_files,
                            title     = if (state.searchQuery.isNotBlank()) stringResource(R.string.files_empty_no_results_title) else stringResource(R.string.files_empty_folder_title),
                            subtitle  = if (state.searchQuery.isNotBlank()) stringResource(R.string.files_empty_no_results_subtitle)
                                        else stringResource(R.string.files_empty_folder_subtitle),
                            modifier  = Modifier.fillMaxSize()
                        )
                    }
                    state.viewMode == ViewMode.LIST -> {
                        AnimatedContent(
                            targetState = state.currentPath,
                            transitionSpec = {
                                val deeper = targetState.length > initialState.length
                                if (deeper) {
                                    (slideInHorizontally { it } + fadeIn()) togetherWith
                                    (slideOutHorizontally { -it } + fadeOut())
                                } else {
                                    (slideInHorizontally { -it } + fadeIn()) togetherWith
                                    (slideOutHorizontally { it } + fadeOut())
                                }
                            },
                            label = "file_list"
                        ) {
                            FileListContent(
                                files                = state.files,
                                selectedItems        = state.selectedItems,
                                isSelectionMode      = state.isSelectionMode,
                                isReadOnly           = state.isReadOnly,
                                searchQuery          = state.searchQuery,
                                thumbnails           = state.thumbnails,
                                topPadding           = topPadding,
                                bottomPadding        = bottomPadding + if (state.isSelectionMode) 72.dp else 80.dp,
                                onThumbnailRequest   = viewModel::requestThumbnail,
                                onFileClick          = { file ->
                                    if (state.isSelectionMode) viewModel.toggleSelection(file.path)
                                    else if (file.isDirectory) viewModel.navigateTo(file.path)
                                    else if (onAudioFileClick != null &&
                                             file.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS) {
                                        viewModel.setAudioQueue(file)
                                        onAudioFileClick(file.path, file.name, file.size)
                                    } else if (onMediaFileClick != null &&
                                               file.name.substringAfterLast('.', "").lowercase() in MEDIA_EXTENSIONS) {
                                        val open = onMediaFileClick
                                        viewModel.openMediaFile(file) { fileId ->
                                            if (fileId != null) open(fileId)
                                            else { openWithTarget = file; showOpenWithWarning = true }
                                        }
                                    } else { openWithTarget = file; showOpenWithWarning = true }
                                },
                                onFileLongClick      = { file ->
                                    if (!state.isSelectionMode) viewModel.enterSelectionMode(file.path)
                                    else viewModel.toggleSelection(file.path)
                                },
                                onRename             = { renameTarget = it },
                                onProperties         = { propertiesTarget = it },
                                formatSize           = viewModel::formatFileSize
                            )
                        }
                    }
                    else -> {
                        FileGridContent(
                            files              = state.files,
                            selectedItems      = state.selectedItems,
                            isSelectionMode    = state.isSelectionMode,
                            thumbnails         = state.thumbnails,
                            topPadding         = topPadding,
                            bottomPadding      = bottomPadding + if (state.isSelectionMode) 72.dp else 80.dp,
                            onThumbnailRequest = viewModel::requestThumbnail,
                            onFileClick     = { file ->
                                if (state.isSelectionMode) viewModel.toggleSelection(file.path)
                                else if (file.isDirectory) viewModel.navigateTo(file.path)
                                else if (onAudioFileClick != null &&
                                         file.name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS) {
                                    viewModel.setAudioQueue(file)
                                    onAudioFileClick(file.path, file.name, file.size)
                                } else if (onMediaFileClick != null &&
                                           file.name.substringAfterLast('.', "").lowercase() in MEDIA_EXTENSIONS) {
                                    val open = onMediaFileClick
                                    viewModel.openMediaFile(file) { fileId ->
                                        if (fileId != null) open(fileId)
                                        else { openWithTarget = file; showOpenWithWarning = true }
                                    }
                                } else { openWithTarget = file; showOpenWithWarning = true }
                            },
                            onFileLongClick = { file ->
                                if (!state.isSelectionMode) viewModel.enterSelectionMode(file.path)
                                else viewModel.toggleSelection(file.path)
                            }
                        )
                    }
                }
            }

            // ── Floating header (hazeEffect) ──────────────────────────────
            Column(
                Modifier
                    .fillMaxWidth()
                    .onSizeChanged { headerHeight = it.height }
            ) {
                AnimatedContent(
                    targetState = state.isSelectionMode,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                    label = "topbar_mode"
                ) { selectionMode ->
                    if (selectionMode) {
                        SelectionTopBar(
                            selectedCount  = state.selectedItems.size,
                            singleSelected = state.selectedItems.singleOrNull()
                                ?.let { p -> state.files.find { it.path == p } },
                            isReadOnly     = state.isReadOnly,
                            onCancel       = viewModel::exitSelectionMode,
                            onSelectAll    = viewModel::selectAll,
                            onRename       = { renameTarget = it },
                            onProperties   = { propertiesTarget = it }
                        )
                    } else {
                        FileManagerTopBar(
                            isSearchActive   = state.isSearchActive,
                            searchQuery      = state.searchQuery,
                            viewMode         = state.viewMode,
                            isAtRoot         = isAtRoot,
                            isReadOnly       = state.isReadOnly,
                            onBack           = { if (isAtRoot) onBack() else viewModel.navigateUp() },
                            onSearchToggle   = viewModel::toggleSearch,
                            onSearchChange   = viewModel::setSearchQuery,
                            onSearchClose    = { viewModel.setSearchActive(false) },
                            onViewModeToggle = viewModel::toggleViewMode,
                            onMoreClick      = { showMoreMenu = true },
                            moreMenuExpanded = showMoreMenu,
                            onMoreDismiss    = { showMoreMenu = false },
                            onSort           = { showSortSheet = true; showMoreMenu = false },
                            onToggleHidden   = { viewModel.toggleShowHidden(); showMoreMenu = false },
                            showHidden       = state.showHidden,
                            clipboardCount   = state.clipboardCount,
                            onPaste          = { viewModel.paste(); showMoreMenu = false },
                        )
                    }
                }

                AnimatedVisibility(
                    visible = !state.isSelectionMode && !state.isSearchActive,
                    enter = fadeIn(tween(200)),
                    exit = fadeOut(tween(150))
                ) {
                    BreadcrumbRow(
                        pathSegments   = state.pathSegments,
                        onSegmentClick = { idx -> viewModel.navigateToSegment(idx) }
                    )
                }

                AnimatedVisibility(visible = state.isOperationInProgress) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        state.operationMessage?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.height(4.dp))
                        }
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
        }

        // ── Selection Bottom Bar ──────────────────────────────────────────
        AnimatedVisibility(
            visible  = state.isSelectionMode,
            enter    = slideInVertically { it } + fadeIn(),
            exit     = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            SelectionBottomBar(
                isReadOnly = state.isReadOnly,
                onCopy   = {
                    if (mountedContainers.size <= 1) viewModel.copySelected()
                    else showCopySheet = true
                },
                onMove   = {
                    if (mountedContainers.size <= 1) viewModel.cutSelected()
                    else showMoveSheet = true
                },
                onExport = { exportLauncher.launch(null) },
                onDelete = { showDeleteConfirm = true }
            )
        }

        // ── Scrim ─────────────────────────────────────────────────────────
        if (!state.isSelectionMode && (showFabMenu || scrimAlpha > 0f)) {
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(3f)
                    .alpha(scrimAlpha)
                    .background(Color.Black)
                    .clickable(enabled = showFabMenu) { showFabMenu = false }
            )
        }

        // ── FAB menu items ─────────────────────────────────────────────────
        if (!state.isSelectionMode && !state.isReadOnly) Column(
            modifier            = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 8.dp, bottom = bottomPadding + 88.dp)
                .zIndex(4f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            AnimatedVisibility(
                visible = showFabMenu,
                enter   = slideInVertically(
                    animationSpec  = tween(300, delayMillis = 50),
                    initialOffsetY = { it / 2 }
                ) + fadeIn(tween(200, delayMillis = 50)),
                exit    = slideOutVertically(tween(180), targetOffsetY = { it / 2 }) + fadeOut(tween(150))
            ) {
                FabMenuItem(stringResource(R.string.files_action_import), Icons.Outlined.FileUpload) {
                    showImportSheet = true; showFabMenu = false
                }
            }
            AnimatedVisibility(
                visible = showFabMenu,
                enter   = slideInVertically(
                    animationSpec  = tween(300),
                    initialOffsetY = { it / 2 }
                ) + fadeIn(tween(200)),
                exit    = slideOutVertically(tween(200), targetOffsetY = { it / 2 }) + fadeOut(tween(150))
            ) {
                FabMenuItem(stringResource(R.string.files_action_new_folder), Icons.Outlined.CreateNewFolder) {
                    showNewFolderDialog = true; showFabMenu = false
                }
            }
        }

        // ── Diamond FAB ────────────────────────────────────────────────────
        if (!state.isSelectionMode && !state.isReadOnly) Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = bottomPadding + 16.dp)
                .zIndex(5f)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .rotate(fabRotation)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { showFabMenu = !showFabMenu },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = Icons.Rounded.Add,
                    contentDescription = if (showFabMenu) stringResource(R.string.files_cd_close_fab) else stringResource(R.string.files_cd_file_actions_fab),
                    tint               = MaterialTheme.colorScheme.onPrimary,
                    modifier           = Modifier.size(24.dp)
                )
            }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────

    if (showNewFolderDialog) {
        NewFolderDialog(
            onDismiss = { showNewFolderDialog = false },
            onCreate  = { name ->
                viewModel.createFolder(name)
                showNewFolderDialog = false
            }
        )
    }

    if (showDeleteConfirm) {
        val count = state.selectedItems.size
        AppDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title  = { Text(pluralStringResource(R.plurals.files_delete_count, count, count)) },
            text   = { Text(stringResource(R.string.common_this_cannot_be_undone)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSelected()
                    showDeleteConfirm = false
                }) { Text(stringResource(R.string.files_delete_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    renameTarget?.let { file ->
        RenameDialog(
            currentName = file.name,
            isDirectory = file.isDirectory,
            onDismiss   = { renameTarget = null },
            onRename    = { newName ->
                viewModel.renameFile(file, newName) { success ->
                    renameTarget = null
                    // Renaming from selection mode leaves the selection pointing
                    // at a path that no longer exists, so close it out.
                    if (success) viewModel.exitSelectionMode()
                }
            }
        )
    }

    if (propertiesTarget != null) {
        AppSheet(
            onDismissRequest = { propertiesTarget = null },
            sheetState       = propertiesSheetState
        ) {
            propertiesTarget?.let { file ->
                FilePropertiesContent(file = file, formatSize = viewModel::formatFileSize)
            }
        }
    }

    if (showOpenWithWarning && openWithTarget != null) {
        val file = openWithTarget!!
        AppSheet(
            onDismissRequest = { showOpenWithWarning = false; openWithTarget = null },
            sheetState       = openWithSheetState
        ) {
            OpenWithWarningContent(
                fileName  = file.name,
                onCancel  = { showOpenWithWarning = false; openWithTarget = null },
                onConfirm = {
                    showOpenWithWarning = false
                    viewModel.prepareOpenWithExternalApp(context, file)
                    openWithTarget = null
                }
            )
        }
    }

    if (showImportSheet) {
        AppSheet(
            onDismissRequest = { showImportSheet = false },
            sheetState       = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Text(
                text       = stringResource(R.string.files_action_import),
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            ListItem(
                colors          = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent  = { Icon(Icons.Outlined.Description, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.files_action_import_files)) },
                trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier        = Modifier.clickable {
                    showImportSheet = false
                    importLauncher.launch(arrayOf("*/*"))
                }
            )
            ListItem(
                colors          = ListItemDefaults.colors(containerColor = Color.Transparent),
                leadingContent  = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                headlineContent = { Text(stringResource(R.string.files_action_import_folder)) },
                trailingContent = { Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                modifier        = Modifier.clickable {
                    showImportSheet = false
                    importFolderLauncher.launch(null)
                }
            )
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .clickable { deleteAfterImport = !deleteAfterImport }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Delete,
                    contentDescription = null,
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text  = stringResource(R.string.files_import_delete_source),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text  = stringResource(R.string.files_import_delete_source_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = deleteAfterImport, onCheckedChange = { deleteAfterImport = it })
            }
            Spacer(Modifier.navigationBarsPadding().padding(bottom = 8.dp))
        }
    }

    if (showSortSheet) {
        AppSheet(
            onDismissRequest = { showSortSheet = false },
            sheetState       = sortSheetState
        ) {
            SortSheetContent(
                sortBy        = state.sortBy,
                ascending     = state.sortAscending,
                foldersFirst  = state.foldersFirst,
                onSortBy      = { viewModel.setSortBy(it); showSortSheet = false },
                onToggleDir   = viewModel::toggleSortDirection,
                onToggleFoldersFirst = viewModel::toggleFoldersFirst
            )
        }
    }

    if (showMoveSheet) {
        AppSheet(
            onDismissRequest = { showMoveSheet = false },
            sheetState       = moveSheetState
        ) {
            DestinationPickerSheetContent(
                title              = stringResource(R.string.files_move_to_title),
                currentContainerId = containerId,
                containers         = mountedContainers,
                onListDirs         = viewModel::listDirectoriesAt,
                onConfirm          = { destContainerId, path, name ->
                    viewModel.moveSelected(destContainerId, path, name)
                    showMoveSheet = false
                },
                confirmLabel       = stringResource(R.string.files_move_here)
            )
        }
    }

    if (showCopySheet) {
        AppSheet(
            onDismissRequest = { showCopySheet = false },
            sheetState       = copySheetState
        ) {
            DestinationPickerSheetContent(
                title              = stringResource(R.string.files_copy_to_title),
                currentContainerId = containerId,
                containers         = mountedContainers,
                onListDirs         = viewModel::listDirectoriesAt,
                onConfirm          = { destContainerId, path, name ->
                    viewModel.copyToDestination(destContainerId, path, name)
                    showCopySheet = false
                },
                confirmLabel       = stringResource(R.string.files_copy_here)
            )
        }
    }
}

// ── Top App Bar ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun FileManagerTopBar(
    isSearchActive: Boolean,
    searchQuery: String,
    viewMode: ViewMode,
    isAtRoot: Boolean,
    isReadOnly: Boolean,
    onBack: () -> Unit,
    onSearchToggle: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onViewModeToggle: () -> Unit,
    onMoreClick: () -> Unit,
    moreMenuExpanded: Boolean,
    onMoreDismiss: () -> Unit,
    onSort: () -> Unit,
    onToggleHidden: () -> Unit,
    showHidden: Boolean,
    clipboardCount: Int,
    onPaste: () -> Unit,
) {
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) runCatching { searchFocusRequester.requestFocus() }
    }
    val isAmoled  = LocalAmoledMode.current
    val hazeState = LocalHazeState.current

    TopAppBar(
        modifier     = if (isAmoled) Modifier.hazeEffect(state = hazeState, style = ArcanumHazeStyle.topBar) else Modifier,
        colors       = if (isAmoled) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                       else TopAppBarDefaults.topAppBarColors(),
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
        },
        title = {
            if (isSearchActive) {
                BasicTextField(
                    value         = searchQuery,
                    onValueChange = onSearchChange,
                    singleLine    = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    textStyle     = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    modifier      = Modifier.fillMaxWidth().focusRequester(searchFocusRequester),
                    decorationBox = { inner ->
                        if (searchQuery.isEmpty()) {
                            Text(stringResource(R.string.files_search_placeholder), style = MaterialTheme.typography.bodyLarge,
                                 color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        inner()
                    }
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.files_title))
                    if (isReadOnly) {
                        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
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
            if (isSearchActive) {
                IconButton(onClick = onSearchClose) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.files_search_close))
                }
            } else {
                IconButton(onClick = onSearchToggle) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.files_search_open))
                }
                Box {
                    IconButton(onClick = onMoreClick) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.files_cd_more))
                    }
                    DropdownMenu(expanded = moreMenuExpanded, onDismissRequest = onMoreDismiss) {
                        if (clipboardCount > 0) {
                            DropdownMenuItem(
                                text = { Text(pluralStringResource(R.plurals.files_paste_clipboard, clipboardCount, clipboardCount)) },
                                leadingIcon = { Icon(Icons.Outlined.ContentPaste, null) },
                                onClick = onPaste,
                                enabled = !isReadOnly
                            )
                            HorizontalDivider()
                        }
                        DropdownMenuItem(
                            text = { Text(if (showHidden) stringResource(R.string.files_hide_hidden) else stringResource(R.string.files_show_hidden)) },
                            leadingIcon = { Icon(Icons.Outlined.FileOpen, null) },
                            onClick = onToggleHidden
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_sort_by)) },
                            leadingIcon = { Icon(Icons.Outlined.KeyboardArrowDown, null) },
                            onClick = onSort
                        )
                        DropdownMenuItem(
                            text = { Text(if (viewMode == ViewMode.LIST) stringResource(R.string.files_grid_view) else stringResource(R.string.files_list_view)) },
                            leadingIcon = {
                                Icon(
                                    if (viewMode == ViewMode.LIST) Icons.Outlined.GridView
                                    else Icons.AutoMirrored.Outlined.ViewList,
                                    null
                                )
                            },
                            onClick = { onViewModeToggle(); onMoreDismiss() }
                        )
                    }
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    singleSelected: NativeFileInfo?,
    isReadOnly: Boolean,
    onCancel: () -> Unit,
    onSelectAll: () -> Unit,
    onRename: (NativeFileInfo) -> Unit,
    onProperties: (NativeFileInfo) -> Unit
) {
    val isAmoled  = LocalAmoledMode.current
    val hazeState = LocalHazeState.current
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        modifier       = if (isAmoled) Modifier.hazeEffect(state = hazeState, style = ArcanumHazeStyle.topBar) else Modifier,
        colors         = if (isAmoled) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                         else TopAppBarDefaults.topAppBarColors(),
        navigationIcon = {
            IconButton(onClick = onCancel) {
                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.files_cd_cancel_selection))
            }
        },
        title = { Text(stringResource(R.string.files_selection_count, selectedCount)) },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.files_cd_select_all))
            }
            // Single-item actions live here rather than in the bottom bar: that
            // row is SpaceEvenly with a label under each icon, and six entries
            // overflow a narrow screen. This is also the only route to Rename
            // and Properties in the grid layout, whose tiles have no menu of
            // their own - no grid in the app does.
            if (singleSelected != null) {
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Outlined.MoreVert,
                             contentDescription = stringResource(R.string.files_cd_more_actions))
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text        = { Text(stringResource(R.string.files_action_rename)) },
                            leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                            onClick     = { showMenu = false; onRename(singleSelected) },
                            enabled     = !isReadOnly
                        )
                        DropdownMenuItem(
                            text        = { Text(stringResource(R.string.files_action_properties)) },
                            leadingIcon = { Icon(Icons.Outlined.Info, null) },
                            onClick     = { showMenu = false; onProperties(singleSelected) }
                        )
                    }
                }
            }
        }
    )
}

// ── Breadcrumb Row ────────────────────────────────────────────────────────────

@Composable
private fun BreadcrumbRow(
    pathSegments: List<String>,
    onSegmentClick: (Int) -> Unit
) {
    val scrollState = rememberScrollState()
    LaunchedEffect(pathSegments) { scrollState.animateScrollTo(scrollState.maxValue) }
    val isAmoled  = LocalAmoledMode.current
    val hazeState = LocalHazeState.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isAmoled) Modifier.hazeEffect(state = hazeState, style = ArcanumHazeStyle.topBar) else Modifier)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            pathSegments.forEachIndexed { index, segment ->
                val isLast = index == pathSegments.lastIndex
                if (index == 0) {
                    Icon(
                        imageVector        = Icons.Outlined.Home,
                        contentDescription = stringResource(R.string.files_cd_root),
                        modifier           = Modifier
                            .size(18.dp)
                            .then(if (!isLast) Modifier.clickable { onSegmentClick(0) } else Modifier),
                        tint               = if (isLast) MaterialTheme.colorScheme.primary
                                             else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text       = segment,
                        style      = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (isLast) FontWeight.Bold else FontWeight.Normal,
                        color      = if (isLast) MaterialTheme.colorScheme.primary
                                     else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier   = if (!isLast) Modifier.clickable { onSegmentClick(index) } else Modifier
                    )
                }
                if (!isLast) {
                    Icon(
                        Icons.Outlined.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp).padding(horizontal = 2.dp),
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

// ── File List ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListContent(
    files: List<NativeFileInfo>,
    selectedItems: Set<String>,
    isSelectionMode: Boolean,
    isReadOnly: Boolean,
    searchQuery: String,
    thumbnails: Map<String, android.graphics.Bitmap>,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp,
    onThumbnailRequest: (NativeFileInfo) -> Unit,
    onFileClick: (NativeFileInfo) -> Unit,
    onFileLongClick: (NativeFileInfo) -> Unit,
    onRename: (NativeFileInfo) -> Unit,
    onProperties: (NativeFileInfo) -> Unit,
    formatSize: (Long) -> String
) {
    LazyColumn(
        contentPadding = PaddingValues(top = topPadding, bottom = bottomPadding),
        modifier = Modifier.fillMaxSize()
    ) {
        items(files, key = { it.path }) { file ->
            FileListItem(
                file               = file,
                isSelected         = file.path in selectedItems,
                isSelectionMode    = isSelectionMode,
                isReadOnly         = isReadOnly,
                searchQuery        = searchQuery,
                thumbnail          = thumbnails[file.path],
                onThumbnailRequest = { onThumbnailRequest(file) },
                onFileClick        = { onFileClick(file) },
                onFileLongClick    = { onFileLongClick(file) },
                onRename           = { onRename(file) },
                onProperties       = { onProperties(file) },
                formatSize         = formatSize
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileListItem(
    file: NativeFileInfo,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    isReadOnly: Boolean,
    searchQuery: String,
    thumbnail: android.graphics.Bitmap? = null,
    onThumbnailRequest: () -> Unit = {},
    onFileClick: () -> Unit,
    onFileLongClick: () -> Unit,
    onRename: () -> Unit,
    onProperties: () -> Unit,
    formatSize: (Long) -> String
) {
    val isHidden = file.name.startsWith(".")
    val (icon, iconColor) = fileTypeIconAndColor(file.name, file.isDirectory)
    LaunchedEffect(file.path) { if (thumbnail == null && !file.isDirectory) onThumbnailRequest() }
    val bgColor by animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                      else Color.Transparent,
        label = "item_bg"
    )
    var showItemMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (isHidden) 0.6f else 1f)
            .background(bgColor)
            .combinedClickable(
                onClick     = onFileClick,
                onLongClick = onFileLongClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Selection indicator or icon
        AnimatedContent(targetState = isSelectionMode, label = "icon_mode") { selMode ->
            if (selMode) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    if (isSelected) {
                        Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(icon, null, tint = iconColor, modifier = Modifier.size(22.dp))
                    }
                }
            } else {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconColor.copy(alpha = 0.12f))
                ) {
                    if (thumbnail != null) {
                        val isVideo = file.name.substringAfterLast('.', "").lowercase() in
                            setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "3gp")
                        Box(Modifier.fillMaxSize()) {
                            Box(
                                Modifier.fillMaxSize().paint(
                                    painter      = BitmapPainter(thumbnail.asImageBitmap()),
                                    contentScale = ContentScale.Crop
                                )
                            )
                            if (isVideo) {
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = null,
                                    tint     = Color.White,
                                    modifier = Modifier.size(20.dp).align(Alignment.Center)
                                )
                            }
                        }
                    } else {
                        Icon(icon, null, tint = iconColor, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text      = file.name,
                style     = MaterialTheme.typography.bodyLarge,
                maxLines  = 1,
                overflow  = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text  = if (file.isDirectory) stringResource(R.string.files_type_folder)
                        else "${formatSize(file.size)} · ${formatDate(file.lastModified)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (!isSelectionMode) {
            // Folders get the same menu as files. They used to show only a
            // chevron, which left no way to rename one — the gap that led the
            // reporter of #113 to try Move-as-rename and lose the folder. The
            // row itself still opens the folder on tap.
            Box {
                IconButton(onClick = { showItemMenu = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.MoreVert, null, modifier = Modifier.size(18.dp),
                         tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = showItemMenu, onDismissRequest = { showItemMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_action_rename)) },
                        leadingIcon = { Icon(Icons.Outlined.Edit, null) },
                        onClick = { onRename(); showItemMenu = false },
                        enabled = !isReadOnly
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.files_action_properties)) },
                        leadingIcon = { Icon(Icons.Outlined.Info, null) },
                        onClick = { onProperties(); showItemMenu = false }
                    )
                }
            }
        }
    }

    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
        modifier = Modifier.padding(start = 70.dp)
    )
}

// ── File Grid ─────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileGridContent(
    files: List<NativeFileInfo>,
    selectedItems: Set<String>,
    isSelectionMode: Boolean,
    thumbnails: Map<String, android.graphics.Bitmap>,
    topPadding: Dp = 0.dp,
    bottomPadding: Dp,
    onThumbnailRequest: (NativeFileInfo) -> Unit,
    onFileClick: (NativeFileInfo) -> Unit,
    onFileLongClick: (NativeFileInfo) -> Unit
) {
    val folders = files.filter { it.isDirectory }
    val nonFolders = files.filter { !it.isDirectory }

    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        contentPadding = PaddingValues(start = 8.dp, top = topPadding + 8.dp, end = 8.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Folders span 2 columns (using 1 column here since we can't span in LazyVerticalGrid without custom)
        items(folders + nonFolders, key = { it.path }) { file ->
            val isSelected = file.path in selectedItems
            val (icon, iconColor) = fileTypeIconAndColor(file.name, file.isDirectory)
            val thumbnail = thumbnails[file.path]
            LaunchedEffect(file.path) { if (thumbnail == null && !file.isDirectory) onThumbnailRequest(file) }
            val bgColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                              else MaterialTheme.colorScheme.surfaceVariant,
                label = "grid_bg"
            )
            Box(
                modifier = Modifier
                    .alpha(if (file.name.startsWith(".")) 0.6f else 1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(bgColor)
                    .combinedClickable(
                        onClick     = { onFileClick(file) },
                        onLongClick = { onFileLongClick(file) }
                    )
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.TopEnd) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(iconColor.copy(alpha = 0.15f))
                        ) {
                            if (thumbnail != null) {
                                val isVideo = file.name.substringAfterLast('.', "").lowercase() in
                                    setOf("mp4", "mkv", "avi", "mov", "m4v", "webm", "3gp")
                                Box(Modifier.fillMaxSize()) {
                                    Box(
                                        Modifier.fillMaxSize().paint(
                                            painter      = BitmapPainter(thumbnail.asImageBitmap()),
                                            contentScale = ContentScale.Crop
                                        )
                                    )
                                    if (isVideo) {
                                        Icon(
                                            Icons.Filled.PlayArrow,
                                            contentDescription = null,
                                            tint     = Color.White,
                                            modifier = Modifier.size(22.dp).align(Alignment.Center)
                                        )
                                    }
                                }
                            } else {
                                Icon(icon, null, tint = iconColor, modifier = Modifier.size(28.dp))
                            }
                        }
                        if (isSelected) {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text      = file.name,
                        style     = MaterialTheme.typography.labelSmall,
                        maxLines  = 2,
                        overflow  = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

// ── Selection Bottom Bar ──────────────────────────────────────────────────────

@Composable
private fun SelectionBottomBar(
    isReadOnly: Boolean,
    onCopy: () -> Unit,
    onMove: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SelectionAction(stringResource(R.string.files_action_copy),   Icons.Outlined.ContentCopy,                onCopy)
            SelectionAction(stringResource(R.string.files_action_move),   Icons.AutoMirrored.Outlined.DriveFileMove, onMove,   enabled = !isReadOnly)
            SelectionAction(stringResource(R.string.files_action_export), Icons.Outlined.FileUpload,                 onExport)
            SelectionAction(stringResource(R.string.files_action_delete), Icons.Outlined.Delete,                     onDelete,
                            tint = MaterialTheme.colorScheme.error, enabled = !isReadOnly)
        }
    }
}

@Composable
private fun SelectionAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true
) {
    val effectiveTint = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(12.dp)
    ) {
        Icon(icon, null, tint = effectiveTint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(2.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = effectiveTint)
    }
}

// ── FAB Menu ──────────────────────────────────────────────────────────────────

@Composable
private fun FabMenuItem(label: String, icon: ImageVector, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(bottom = 4.dp)
    ) {
        Card(
            onClick = onClick,
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(4.dp)
        ) {
            Text(label, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                 style = MaterialTheme.typography.labelMedium)
        }
        Spacer(Modifier.width(8.dp))
        Surface(
            shape = CircleShape,
            tonalElevation = 4.dp,
            shadowElevation = 4.dp,
            color = MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.size(40.dp).clickable(onClick = onClick)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(icon, null, modifier = Modifier.size(20.dp))
            }
        }
    }
}

// ── Dialogs & Sheets ──────────────────────────────────────────────────────────

@Composable
private fun NewFolderDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var folderName by rememberSaveable { mutableStateOf("") }
    AppDialog(
        onDismissRequest = onDismiss,
        title            = { Text(stringResource(R.string.files_new_folder_title)) },
        text = {
            OutlinedTextField(
                value         = folderName,
                onValueChange = { folderName = it },
                label         = { Text(stringResource(R.string.files_new_folder_label)) },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick  = { if (folderName.isNotBlank()) onCreate(folderName.trim()) },
                enabled  = folderName.isNotBlank()
            ) { Text(stringResource(R.string.files_new_folder_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun RenameDialog(
    currentName: String,
    isDirectory: Boolean,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    // Only files have an extension to hide. A folder called "photos.2026" would
    // otherwise be presented as "photos", and the part after the dot would look
    // like it had been dropped.
    val initial = if (isDirectory) currentName
                  else currentName.substringBeforeLast(".", currentName)
    var newName by rememberSaveable { mutableStateOf(initial) }
    AppDialog(
        onDismissRequest = onDismiss,
        title            = { Text(stringResource(R.string.files_rename_title)) },
        text = {
            OutlinedTextField(
                value         = newName,
                onValueChange = { newName = it },
                label         = { Text(stringResource(R.string.files_rename_label)) },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick  = { if (newName.isNotBlank()) onRename(newName.trim()) },
                enabled  = newName.isNotBlank()
            ) { Text(stringResource(R.string.files_rename_title)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } }
    )
}

@Composable
private fun FilePropertiesContent(file: NativeFileInfo, formatSize: (Long) -> String) {
    val context = LocalContext.current
    val (icon, iconColor) = fileTypeIconAndColor(file.name, file.isDirectory)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .padding(bottom = 32.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconColor.copy(alpha = 0.12f))
            ) {
                Icon(icon, null, tint = iconColor, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.width(12.dp))
            Text(file.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                 maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        HorizontalDivider()
        PropertiesRow(stringResource(R.string.files_props_name),     file.name)
        PropertiesRow(stringResource(R.string.files_props_type),     if (file.isDirectory) stringResource(R.string.files_props_type_folder) else fileTypeCategory(context, file.name))
        PropertiesRow(stringResource(R.string.files_props_size),     if (file.isDirectory) "—" else formatSize(file.size))
        PropertiesRow(stringResource(R.string.files_props_location), file.path.substringBeforeLast("/").ifEmpty { "/" })
        PropertiesRow(stringResource(R.string.files_props_modified), formatDate(file.lastModified))
    }
}

@Composable
private fun PropertiesRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
             color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
             modifier = Modifier.weight(0.6f), textAlign = TextAlign.End,
             maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
}

@Composable
private fun OpenWithWarningContent(fileName: String, onCancel: () -> Unit, onConfirm: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .padding(bottom = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Outlined.FileOpen, null,
             modifier = Modifier.size(40.dp).padding(vertical = 8.dp),
             tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(4.dp))
        Text(fileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
             maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.files_open_with_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.files_open_with_cancel)) }
            androidx.compose.material3.Button(onClick = onConfirm, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.files_open_with_confirm))
            }
        }
    }
}

@Composable
private fun SortSheetContent(
    sortBy: SortBy,
    ascending: Boolean,
    foldersFirst: Boolean,
    onSortBy: (SortBy) -> Unit,
    onToggleDir: () -> Unit,
    onToggleFoldersFirst: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(bottom = 32.dp)
    ) {
        Text(stringResource(R.string.files_sort_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
             modifier = Modifier.padding(vertical = 8.dp))
        HorizontalDivider()
        val sortOptions = listOf(
            SortBy.NAME to stringResource(R.string.files_sort_name),
            SortBy.DATE to stringResource(R.string.files_sort_date),
            SortBy.SIZE to stringResource(R.string.files_sort_size),
            SortBy.TYPE to stringResource(R.string.files_sort_type)
        )
        sortOptions.forEach { (option, label) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSortBy(option) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = sortBy == option, onClick = { onSortBy(option) })
                Text(label, modifier = Modifier.weight(1f))
                if (sortBy == option) {
                    IconButton(onClick = onToggleDir) {
                        Icon(
                            if (ascending) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = if (ascending) stringResource(R.string.files_sort_cd_ascending) else stringResource(R.string.files_sort_cd_descending)
                        )
                    }
                }
            }
        }
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.files_sort_folders_first), modifier = Modifier.weight(1f))
            Switch(checked = foldersFirst, onCheckedChange = { onToggleFoldersFirst() })
        }
    }
}

@Composable
private fun DestinationPickerSheetContent(
    title: String,
    confirmLabel: String,
    currentContainerId: String,
    containers: List<Container>,
    onListDirs: (containerId: String, path: String, onResult: (List<NativeFileInfo>) -> Unit) -> Unit,
    onConfirm: (destinationContainerId: String, destinationPath: String, destinationName: String) -> Unit
) {
    val sortedContainers = remember(containers, currentContainerId) {
        val current = containers.filter { it.id == currentContainerId }
        val others  = containers.filter { it.id != currentContainerId }
        current + others
    }
    var selectedContainer by remember { mutableStateOf<Container?>(null) }
    var browsePath        by remember { mutableStateOf("/") }
    var browseDirs        by remember { mutableStateOf<List<NativeFileInfo>>(emptyList()) }
    var isLoadingDirs     by remember { mutableStateOf(false) }

    fun loadDirs(container: Container, path: String) {
        isLoadingDirs = true
        onListDirs(container.id, path) { dirs ->
            browseDirs = dirs
            isLoadingDirs = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(bottom = 32.dp)
    ) {
        val container = selectedContainer
        if (container == null) {
            // ── Container picker ──────────────────────────────────────────
            Text(
                title,
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.padding(vertical = 8.dp)
            )
            HorizontalDivider()
            if (sortedContainers.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(stringResource(R.string.files_no_mounted_vaults), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                sortedContainers.forEach { c ->
                    val isCurrent   = c.id == currentContainerId
                    val displayName = if (isCurrent) stringResource(R.string.files_this_vault) else c.name
                    val iconTint    = if (isCurrent) Color(0xFF16A34A) else Color(0xFFF59E0B)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedContainer = c
                                browsePath = "/"
                                loadDirs(c, "/")
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Outlined.Folder, null,
                            tint     = iconTint,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(displayName, style = MaterialTheme.typography.bodyLarge,
                             modifier = Modifier.weight(1f))
                        Icon(Icons.Outlined.ChevronRight, null,
                             tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                    )
                }
            }
        } else {
            // ── Directory browser ─────────────────────────────────────────
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selectedContainer = null }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.files_cd_back_to_vaults))
                }
                Text(
                    container.name,
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier   = Modifier.weight(1f)
                )
            }
            if (browsePath != "/") {
                Text(
                    "↳ $browsePath",
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp, bottom = 4.dp)
                )
            }
            HorizontalDivider()

            if (browsePath != "/") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val parent = browsePath.substringBeforeLast("/").ifEmpty { "/" }
                            browsePath = parent
                            loadDirs(container, parent)
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack, null,
                        modifier = Modifier.size(20.dp),
                        tint     = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("..", style = MaterialTheme.typography.bodyLarge,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
            }

            if (isLoadingDirs) {
                Box(
                    Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                }
            }

            browseDirs.forEach { dir ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val newPath = if (browsePath == "/") "/${dir.name}" else "$browsePath/${dir.name}"
                            browsePath = newPath
                            loadDirs(container, newPath)
                        }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Outlined.Folder, null,
                        tint     = Color(0xFFF59E0B),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(dir.name, style = MaterialTheme.typography.bodyLarge,
                         modifier = Modifier.weight(1f))
                    Icon(Icons.Outlined.ChevronRight, null,
                         tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color     = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                )
            }

            Spacer(Modifier.height(16.dp))

            val destLabel = container.name + if (browsePath == "/") "" else browsePath
            androidx.compose.material3.Button(
                onClick  = { onConfirm(container.id, browsePath, destLabel) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.AutoMirrored.Outlined.DriveFileMove, null,
                     modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("$confirmLabel — $destLabel", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ── Helper utilities ──────────────────────────────────────────────────────────

private fun fileTypeIconAndColor(name: String, isDirectory: Boolean): Pair<ImageVector, Color> {
    if (isDirectory) return Icons.Outlined.Folder to Color(0xFFF59E0B)
    return when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif" ->
            Icons.Outlined.Image to Color(0xFF3B82F6)
        "mp4", "mkv", "avi", "mov", "m4v", "webm", "3gp" ->
            Icons.Outlined.Videocam to Color(0xFFEF4444)
        "mp3", "m4a", "aac", "ogg", "flac", "wav", "opus" ->
            Icons.Outlined.AudioFile to Color(0xFF8B5CF6)
        "xls", "xlsx", "csv", "ods" ->
            Icons.Outlined.TableChart to Color(0xFF16A34A)
        "zip", "rar", "7z", "tar", "gz", "bz2" ->
            Icons.Outlined.Archive to Color(0xFF92400E)
        else -> Icons.Outlined.Description to Color(0xFF2563EB)
    }
}

private fun fileTypeCategory(context: Context, name: String): String =
    when (name.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp" -> context.getString(R.string.files_type_image)
        "mp4", "mkv", "avi", "mov"                  -> context.getString(R.string.files_type_video)
        "mp3", "m4a", "aac", "flac", "wav"          -> context.getString(R.string.files_type_audio)
        "pdf"                                        -> context.getString(R.string.files_type_pdf)
        "doc", "docx"                                -> context.getString(R.string.files_type_word)
        "xls", "xlsx"                                -> context.getString(R.string.files_type_spreadsheet)
        "ppt", "pptx"                                -> context.getString(R.string.files_type_presentation)
        "txt", "md"                                  -> context.getString(R.string.files_type_text)
        "zip", "rar", "7z"                           -> context.getString(R.string.files_type_archive)
        "apk"                                        -> context.getString(R.string.files_type_apk)
        else                                         -> context.getString(R.string.files_type_file)
    }

private val DATE_FMT = SimpleDateFormat("MMM dd, yyyy", Locale.getDefault())
private fun formatDate(millis: Long): String =
    if (millis > 0) DATE_FMT.format(Date(millis)) else "—"
