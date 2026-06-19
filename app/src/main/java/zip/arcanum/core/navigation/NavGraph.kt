package zip.arcanum.core.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
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
    val container           by viewModel.container.collectAsState()
    val galleryState        by galleryViewModel.uiState.collectAsState()
    val fileManagerState    by fileManagerViewModel.state.collectAsState()
    val isAmoled            = LocalAmoledMode.current
    val hazeState           = remember { HazeState() }
    var selectedTab       by rememberSaveable { mutableStateOf(BottomNavItem.ContainerGallery.route) }
    var notification      by remember { mutableStateOf<InAppNotification?>(null) }

    val showBottomBar = !(selectedTab == BottomNavItem.ContainerFiles.route && fileManagerState.isSelectionMode)
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BackHandler { onBack() }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            topBar = {
                when (selectedTab) {
                    BottomNavItem.ContainerGallery.route -> GalleryTopBar(
                        isSearchActive = galleryState.isSearchActive,
                        searchQuery    = galleryState.searchQuery,
                        onBack         = onBack,
                        onSearchToggle = { galleryViewModel.setSearchActive(!galleryState.isSearchActive) },
                        onSearchChange = { galleryViewModel.setSearchQuery(it) },
                        onSearchClose  = { galleryViewModel.setSearchActive(false) },
                        onRescan       = { galleryViewModel.scanContainer(viewModel.containerId) }
                    )
                    BottomNavItem.ContainerFiles.route -> {} // FileManagerScreen owns its top bar
                    else -> TopAppBar(
                        title          = { Text(container?.name ?: stringResource(R.string.nav_vault_fallback_name)) },
                        navigationIcon = { BackIconButton(onBack) },
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
                    containerTabs.forEach { tab ->
                        val active = selectedTab == tab.route
                        Box(
                            Modifier
                                .fillMaxSize()
                                .zIndex(if (active) 1f else 0f)
                                .alpha(if (active) 1f else 0f)
                        ) {
                            when (tab) {
                                BottomNavItem.ContainerGallery -> GalleryScreen(
                                    containerId       = viewModel.containerId,
                                    showTopBar        = false,
                                    bottomPadding     = 60.dp + navBarPadding,
                                    onMediaClick      = { file ->
                                        when (file.fileType) {
                                            MediaFileType.IMAGE -> onPhotoClick(file.id)
                                            MediaFileType.VIDEO -> onVideoClick(file.id)
                                            MediaFileType.AUDIO -> onAudioClick(file.id)
                                        }
                                    },
                                    viewModel         = galleryViewModel
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
                                    contentPadding = PaddingValues(bottom = 60.dp + navBarPadding),
                                    onUnmount      = {
                                        viewModel.unmount {}
                                        onUnmountStart(viewModel.containerId)
                                    }
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
                        onItemClick  = { item -> selectedTab = item.route }
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
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GalleryTopBar(
    isSearchActive: Boolean,
    searchQuery: String,
    onBack: () -> Unit,
    onSearchToggle: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSearchClose: () -> Unit,
    onRescan: () -> Unit
) {
    val isAmoled  = LocalAmoledMode.current
    val hazeState = LocalHazeState.current
    TopAppBar(
        modifier       = if (isAmoled) Modifier.hazeEffect(state = hazeState, style = ArcanumHazeStyle.topBar) else Modifier,
        colors         = if (isAmoled) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                         else TopAppBarDefaults.topAppBarColors(),
        navigationIcon = { BackIconButton(onBack) },
        title = {
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
                Text(stringResource(R.string.nav_gallery))
            }
        },
        actions = {
            if (isSearchActive) {
                IconButton(onClick = onSearchClose) {
                    Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.nav_gallery_cd_close_search))
                }
            } else {
                IconButton(onClick = onSearchToggle) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.nav_gallery_cd_search))
                }
                IconButton(onClick = onRescan) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.nav_gallery_cd_rescan))
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
