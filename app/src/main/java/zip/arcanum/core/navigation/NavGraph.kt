package zip.arcanum.core.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.LaunchedEffect
import zip.arcanum.core.components.AppDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlin.math.abs
import kotlinx.coroutines.launch
import zip.arcanum.R
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import zip.arcanum.core.theme.ArcanumHazeStyle
import zip.arcanum.arcanum.containers.ui.ContainerScreenViewModel
import zip.arcanum.arcanum.containers.ui.VaultInfoScreen
import zip.arcanum.arcanum.files.ui.FileManagerScreen
import zip.arcanum.arcanum.files.ui.FileManagerViewModel
import zip.arcanum.arcanum.gallery.ui.GalleryScreen
import zip.arcanum.arcanum.gallery.ui.GalleryViewModel
import zip.arcanum.core.components.LocalHazeState
import zip.arcanum.core.database.entities.MediaFileType
import zip.arcanum.core.navigation_components.BottomNavItem
import zip.arcanum.core.navigation_components.FloatingBottomBar
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.notifications.InAppNotificationBanner
import zip.arcanum.core.theme.LocalAmoledMode
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface

private val containerTabs = listOf(
    BottomNavItem.ContainerGallery,
    BottomNavItem.ContainerFiles,
    BottomNavItem.ContainerInfo
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerScreen(
    onBack: () -> Unit,
    onUnmountStart: (containerId: String) -> Unit = {},
    onPhotoClick: (fileId: String) -> Unit = {},
    onVideoClick: (fileId: String) -> Unit = {},
    onAudioClick: (fileId: String) -> Unit = {},
    onAudioFileClick: (containerId: String, path: String, name: String, size: Long) -> Unit = { _, _, _, _ -> },
    onMediaFileClick: (containerId: String, path: String, name: String, size: Long) -> Unit = { _, _, _, _ -> },
    viewModel: ContainerScreenViewModel = hiltViewModel(),
    galleryViewModel: GalleryViewModel = hiltViewModel(),
    fileManagerViewModel: FileManagerViewModel = hiltViewModel()
) {
    val container              by viewModel.container.collectAsState()
    val galleryState           by galleryViewModel.uiState.collectAsState()
    val gallerySelectedIds     by galleryViewModel.selectedIds.collectAsState()
    val galleryShowResync      by galleryViewModel.showResyncButton.collectAsState()
    val fileManagerState       by fileManagerViewModel.state.collectAsState()
    val isAmoled               = LocalAmoledMode.current
    val hazeState              = remember { HazeState() }
    var selectedTab          by rememberSaveable { mutableStateOf(BottomNavItem.ContainerGallery.route) }
    var notification           by remember { mutableStateOf<InAppNotification?>(null) }
    var showUnmountConfirm     by remember { mutableStateOf(false) }

    val gallerySelectionMode = gallerySelectedIds.isNotEmpty()

    val galleryScrollBehavior = TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    // Reset TopBar position when leaving Gallery so it's always visible on return
    LaunchedEffect(selectedTab) {
        if (selectedTab != BottomNavItem.ContainerGallery.route) {
            galleryScrollBehavior.state.heightOffset = 0f
            galleryScrollBehavior.state.contentOffset = 0f
        }
    }

    // Per-tab offset: 0f = center, 1f = off-screen right, -1f = off-screen left.
    // Initialized so the default tab (Gallery) is at 0 and others wait off to the right.
    val tabOffsets = remember {
        containerTabs.mapIndexed { i, tab ->
            Animatable(if (tab.route == selectedTab) 0f else 1f)
        }
    }
    val tabAnimScope = rememberCoroutineScope()

    val showBottomBar = !(selectedTab == BottomNavItem.ContainerFiles.route && fileManagerState.isSelectionMode) &&
                        !(selectedTab == BottomNavItem.ContainerGallery.route && gallerySelectionMode)
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BackHandler { onBack() }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Scaffold(
            modifier            = if (selectedTab == BottomNavItem.ContainerGallery.route)
                                      Modifier.nestedScroll(galleryScrollBehavior.nestedScrollConnection)
                                  else Modifier,
            contentWindowInsets = WindowInsets(0),
            topBar = {
                when (selectedTab) {
                    BottomNavItem.ContainerGallery.route -> GalleryTopBar(
                        isSearchActive   = galleryState.isSearchActive,
                        searchQuery      = galleryState.searchQuery,
                        selectionMode    = gallerySelectionMode,
                        selectedCount    = gallerySelectedIds.size,
                        isReadOnly       = galleryState.isReadOnly,
                        showResyncButton = galleryShowResync,
                        scrollBehavior   = galleryScrollBehavior,
                        onBack           = onBack,
                        onSearchToggle   = { galleryViewModel.setSearchActive(!galleryState.isSearchActive) },
                        onSearchChange   = { galleryViewModel.setSearchQuery(it) },
                        onSearchClose    = { galleryViewModel.setSearchActive(false) },
                        onRescan         = { galleryViewModel.scanContainer(viewModel.containerId) },
                        onClearSelection = { galleryViewModel.clearSelection() },
                        onDeleteSelected = { galleryViewModel.requestDeleteSelected() }
                    )
                    BottomNavItem.ContainerFiles.route -> {} // FileManagerScreen owns its top bar
                    else -> TopAppBar(
                        title          = { Text(stringResource(R.string.nav_info)) },
                        navigationIcon = { BackIconButton(onBack) },
                        actions        = {
                            IconButton(onClick = { showUnmountConfirm = true }) {
                                Icon(Icons.Outlined.Eject, contentDescription = null)
                            }
                        },
                        modifier       = if (isAmoled) Modifier.hazeEffect(state = hazeState, style = ArcanumHazeStyle.topBar) else Modifier,
                        colors         = if (isAmoled) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                                         else TopAppBarDefaults.topAppBarColors()
                    )
                }
            }
        ) { scaffoldPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(top = scaffoldPadding.calculateTopPadding())
            ) {
                // Tab contents — all kept alive via zIndex/alpha
                Box(
                    Modifier
                        .fillMaxSize()
                        .hazeSource(hazeState)
                ) {
                    containerTabs.forEachIndexed { index, tab ->
                        val active = selectedTab == tab.route
                        val offset = tabOffsets[index].value
                        Box(
                            Modifier
                                .fillMaxSize()
                                // During animation the outgoing tab needs a non-zero zIndex so
                                // it renders above its off-screen "parked" siblings.
                                .zIndex(if (active) 1f else if (abs(offset) < 1f) 0.5f else 0f)
                                .graphicsLayer {
                                    translationX = offset * size.width
                                    alpha = (1f - abs(offset)).coerceAtLeast(0f)
                                }
                        ) {
                            when (tab) {
                                BottomNavItem.ContainerGallery -> GalleryScreen(
                                    containerId      = viewModel.containerId,
                                    showTopBar       = false,
                                    bottomPadding    = 60.dp + navBarPadding,
                                    onMediaClick     = { file ->
                                        when (file.fileType) {
                                            MediaFileType.IMAGE -> onPhotoClick(file.id)
                                            MediaFileType.VIDEO -> onVideoClick(file.id)
                                            MediaFileType.AUDIO -> onAudioClick(file.id)
                                        }
                                    },
                                    onNotification   = { notification = it },
                                    viewModel        = galleryViewModel
                                )
                                BottomNavItem.ContainerFiles -> FileManagerScreen(
                                    containerId      = viewModel.containerId,
                                    onBack           = onBack,
                                    onNotification   = { notification = it },
                                    bottomPadding    = 60.dp + navBarPadding,
                                    onAudioFileClick = { path, name, size ->
                                        onAudioFileClick(viewModel.containerId, path, name, size)
                                    },
                                    onMediaFileClick = { path, name, size ->
                                        onMediaFileClick(viewModel.containerId, path, name, size)
                                    },
                                    viewModel        = fileManagerViewModel
                                )
                                BottomNavItem.ContainerInfo -> VaultInfoScreen(
                                    container      = container,
                                    contentPadding = PaddingValues(bottom = 60.dp + navBarPadding)
                                )
                                else -> Box(Modifier.fillMaxSize())
                            }
                        }
                    }
                }

                // Floating bottom bar
                AnimatedVisibility(
                    visible  = showBottomBar,
                    enter    = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit     = slideOutVertically(targetOffsetY = { it }) + fadeOut(),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                        .zIndex(2f)
                ) {
                    FloatingBottomBar(
                        items        = containerTabs,
                        currentRoute = selectedTab,
                        hazeState    = hazeState,
                        isAmoled     = isAmoled,
                        onItemClick = { item ->
                            val newIndex = containerTabs.indexOfFirst { it.route == item.route }
                            val oldIndex = containerTabs.indexOfFirst { it.route == selectedTab }
                            if (newIndex != oldIndex) {
                                val direction = if (newIndex > oldIndex) 1f else -1f
                                tabAnimScope.launch {
                                    tabOffsets[newIndex].snapTo(direction)
                                    launch { tabOffsets[newIndex].animateTo(0f, tween(300, easing = EaseInOutCubic)) }
                                    launch { tabOffsets[oldIndex].animateTo(-direction, tween(300, easing = EaseInOutCubic)) }
                                }
                                selectedTab = item.route
                            }
                        }
                    )
                }

                // In-app notification banner
                InAppNotificationBanner(
                    notification = notification,
                    onDismiss    = { notification = null },
                    onAction     = { notification = null },
                    modifier     = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .zIndex(10f)
                )

            }
        }

        if (showUnmountConfirm) {
            AppDialog(
                onDismissRequest = { showUnmountConfirm = false },
                title            = { Text(stringResource(R.string.vault_unmount_dialog_title)) },
                text             = { Text(stringResource(R.string.vault_info_unmount_body)) },
                confirmButton    = {
                    TextButton(onClick = {
                        showUnmountConfirm = false
                        viewModel.unmount {}
                        onUnmountStart(viewModel.containerId)
                    }) {
                        Text(stringResource(R.string.vault_unmount_confirm), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton    = {
                    TextButton(onClick = { showUnmountConfirm = false }) {
                        Text(stringResource(R.string.common_cancel))
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryTopBar(
    isSearchActive: Boolean,
    searchQuery: String,
    selectionMode: Boolean,
    selectedCount: Int,
    isReadOnly: Boolean = false,
    showResyncButton: Boolean = false,
    scrollBehavior: TopAppBarScrollBehavior,
    onBack: () -> Unit,
    onSearchToggle: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onRescan: () -> Unit,
    onClearSelection: () -> Unit,
    onDeleteSelected: () -> Unit
) {
    val isAmoled  = LocalAmoledMode.current
    val hazeState = LocalHazeState.current
    val modifier  = if (isAmoled) Modifier.hazeEffect(state = hazeState, style = ArcanumHazeStyle.topBar) else Modifier
    val colors    = if (isAmoled) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                    else TopAppBarDefaults.topAppBarColors()

    TopAppBar(
        modifier       = modifier,
        colors         = colors,
        scrollBehavior = scrollBehavior,
        navigationIcon = {
            IconButton(onClick = if (selectionMode) onClearSelection else onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
            }
        },
        title = {
            AnimatedContent(
                targetState = selectionMode,
                transitionSpec = {
                    val dir = if (targetState) 1 else -1
                    (fadeIn(tween(200)) + slideInVertically(tween(200)) { it * dir / 3 }) togetherWith
                    (fadeOut(tween(150)) + slideOutVertically(tween(150)) { it * -dir / 3 })
                },
                label = "gallery_title"
            ) { inSelection ->
                if (inSelection) {
                    Text(
                        stringResource(R.string.gallery_selected_count, selectedCount),
                        style = MaterialTheme.typography.titleMedium
                    )
                } else {
                    if (isSearchActive) {
                        BasicTextField(
                            value         = searchQuery,
                            onValueChange = onSearchChange,
                            singleLine    = true,
                            textStyle     = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            modifier      = Modifier.fillMaxWidth(),
                            decorationBox = { inner ->
                                if (searchQuery.isEmpty()) {
                                    Text(
                                        stringResource(R.string.nav_gallery_search_placeholder),
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                inner()
                            }
                        )
                    } else {
                        Row(
                            verticalAlignment     = Alignment.CenterVertically,
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                        ) {
                            Text(stringResource(R.string.nav_gallery))
                            if (isReadOnly) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.primary
                                ) {
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
                }
            }
        },
        actions = {
            AnimatedContent(
                targetState = selectionMode,
                transitionSpec = {
                    fadeIn(tween(200)) togetherWith fadeOut(tween(150))
                },
                label = "gallery_actions"
            ) { inSelection ->
                if (inSelection) {
                    IconButton(onClick = onDeleteSelected, enabled = !isReadOnly) {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = stringResource(R.string.gallery_delete_selected),
                            tint = if (isReadOnly) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                   else MaterialTheme.colorScheme.error
                        )
                    }
                } else {
                    Row {
                        if (isSearchActive) {
                            IconButton(onClick = onSearchClose) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.nav_gallery_cd_close_search))
                            }
                        } else {
                            if (showResyncButton) {
                                IconButton(onClick = onRescan) {
                                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.nav_gallery_cd_rescan))
                                }
                            }
                            IconButton(onClick = onSearchToggle) {
                                Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.nav_gallery_cd_search))
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun BackIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
    }
}
