package zip.arcanum.core.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.Lock
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
import zip.arcanum.core.components.AppDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import zip.arcanum.core.components.LocalHazeState
import zip.arcanum.core.navigation_components.BottomNavItem
import zip.arcanum.core.navigation_components.FloatingBottomBar
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.notifications.InAppNotificationBanner
import zip.arcanum.core.theme.LocalAmoledMode

private val containerTabs = listOf(
    BottomNavItem.ContainerFiles,
    BottomNavItem.ContainerInfo
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContainerScreen(
    onBack: () -> Unit,
    onUnmountStart: (containerId: String) -> Unit = {},
    onAudioFileClick: (containerId: String, path: String, name: String, size: Long) -> Unit = { _, _, _, _ -> },
    onMediaFileClick: (fileId: String) -> Unit = {},
    onTextFileClick: (containerId: String, path: String, name: String) -> Unit = { _, _, _ -> },
    viewModel: ContainerScreenViewModel = hiltViewModel(),
    fileManagerViewModel: FileManagerViewModel = hiltViewModel()
) {
    val container           by viewModel.container.collectAsState()
    val fileManagerState    by fileManagerViewModel.state.collectAsState()
    val isAmoled            = LocalAmoledMode.current
    val hazeState           = remember { HazeState() }
    var selectedTab       by rememberSaveable { mutableStateOf(BottomNavItem.ContainerFiles.route) }
    val activeTab = if (containerTabs.any { it.route == selectedTab }) {
        selectedTab
    } else {
        BottomNavItem.ContainerFiles.route
    }
    var notification        by remember { mutableStateOf<InAppNotification?>(null) }
    var showUnmountConfirm  by remember { mutableStateOf(false) }

    // Per-tab offset: 0f = center, 1f = off-screen right, -1f = off-screen left.
    // Initialized so the default tab (Files) is at 0 and others wait off to the right.
    val tabOffsets = remember {
        containerTabs.mapIndexed { i, tab ->
            Animatable(if (tab.route == activeTab) 0f else 1f)
        }
    }
    val tabAnimScope = rememberCoroutineScope()

    LaunchedEffect(activeTab) {
        if (selectedTab != activeTab) selectedTab = activeTab
    }

    val showBottomBar = !(activeTab == BottomNavItem.ContainerFiles.route && fileManagerState.isSelectionMode)
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    BackHandler { onBack() }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Scaffold(
            contentWindowInsets = WindowInsets(0),
            topBar = {
                when (activeTab) {
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
                        val active = activeTab == tab.route
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
                                BottomNavItem.ContainerFiles -> FileManagerScreen(
                                    containerId      = viewModel.containerId,
                                    onBack           = onBack,
                                    onNotification   = { notification = it },
                                    bottomPadding    = 60.dp + navBarPadding,
                                    onAudioFileClick = { path, name, size ->
                                        onAudioFileClick(viewModel.containerId, path, name, size)
                                    },
                                    onMediaFileClick = onMediaFileClick,
                                    onTextFileClick = { path, name ->
                                        onTextFileClick(viewModel.containerId, path, name)
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
                        currentRoute = activeTab,
                        hazeState    = hazeState,
                        isAmoled     = isAmoled,
                        onItemClick = { item ->
                            val newIndex = containerTabs.indexOfFirst { it.route == item.route }
                            val oldIndex = containerTabs.indexOfFirst { it.route == activeTab }
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

@Composable
private fun BackIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
    }
}
