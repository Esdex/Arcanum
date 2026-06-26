package zip.arcanum.core.navigation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import zip.arcanum.arcanum.containers.ui.MountCoordinator
import zip.arcanum.arcanum.containers.ui.MountScreen
import zip.arcanum.arcanum.containers.ui.MountSuccessOverlay
import zip.arcanum.arcanum.containers.ui.UnmountAnimationOverlay
import zip.arcanum.arcanum.containers.ui.VaultScreen
import zip.arcanum.arcanum.containers.ui.VaultViewModel
import zip.arcanum.arcanum.gallery.ui.AudioPlayerDirectScreen
import zip.arcanum.arcanum.gallery.ui.MediaViewerDirectScreen
import zip.arcanum.arcanum.gallery.ui.AudioPlayerScreen
import zip.arcanum.arcanum.gallery.ui.MediaViewerScreen
import zip.arcanum.arcanum.gallery.ui.VideoPlayerScreen
import zip.arcanum.calculator.ui.CalculatorScreen
import zip.arcanum.core.security.AppPreferences
import zip.arcanum.core.security.PinManager
import zip.arcanum.settings.SettingsViewModel
import zip.arcanum.onboarding.OnboardingScreen
import zip.arcanum.settings.SettingsScreen
import zip.arcanum.arcanum.containers.ui.CreateContainerScreen
import zip.arcanum.arcanum.containers.ui.MoveVaultScreen
import zip.arcanum.setup.PinEntryScreen
import zip.arcanum.setup.SetupPinScreen

// 0=Immediately(1.5s grace) 1=30s 2=1m 3=2m 4=5m 5=10m 6=30m 7=1h
fun autoLockDelayMillis(index: Int): Long = when (index) {
    0    -> 1_500L
    1    -> 30_000L
    2    -> 60_000L
    3    -> 120_000L
    4    -> 300_000L
    5    -> 600_000L
    6    -> 1_800_000L
    7    -> 3_600_000L
    else -> 1_500L
}

@Composable
fun AppNavigation(pinManager: PinManager) {
    val isPinSet          by pinManager.isPinSetFlow.collectAsState()
    val navController      = rememberNavController()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val calculatorEnabled by settingsViewModel.calculatorEnabled.collectAsState()

    // Gate only on isPinSet — calculatorEnabled null means key absent, handled below.
    if (isPinSet == null) {
        Box(Modifier.fillMaxSize())
        return
    }

    // null = key absent → default false (PinEntry). Calculator opt-in only via DisguiseOverlay or Settings.
    val useCalculator = calculatorEnabled ?: false
    val lockScreenRoute = if (useCalculator) Screen.Calculator.route else Screen.PinEntry.route

    val startDestination = remember {
        if (isPinSet == true) lockScreenRoute else Screen.Onboarding.route
    }

    var showUnmountOverlay         by remember { mutableStateOf(false) }
    var unmountedContainerId       by remember { mutableStateOf<String?>(null) }
    var unmountIconOffset          by remember { mutableStateOf<Offset?>(null) }
    var pendingMountContainerId    by remember { mutableStateOf<String?>(null) }


    val mountCoordinator: MountCoordinator = hiltViewModel()
    val mountPhase by mountCoordinator.phase.collectAsState()

    val autoLockEnabled    by settingsViewModel.autoLockEnabled.collectAsState()
    val autoLockDelayIndex by settingsViewModel.autoLockDelayIndex.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    val lockedRoutes = remember(lockScreenRoute) {
        setOf(Screen.Onboarding.route, Screen.SetupPin.route, Screen.Calculator.route, Screen.PinEntry.route)
    }
    val autoLockScope = rememberCoroutineScope()
    DisposableEffect(lifecycleOwner, autoLockEnabled, autoLockDelayIndex, lockScreenRoute) {
        var lockJob: Job? = null
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (autoLockEnabled) {
                        val current = navController.currentDestination?.route ?: return@LifecycleEventObserver
                        if (current !in lockedRoutes) {
                            lockJob = autoLockScope.launch {
                                delay(autoLockDelayMillis(autoLockDelayIndex))
                                navController.navigate(lockScreenRoute) {
                                    popUpTo(0) { inclusive = true }
                                    launchSingleTop = true
                                }
                            }
                        }
                    }
                }
                Lifecycle.Event.ON_START -> lockJob?.cancel()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lockJob?.cancel()
        }
    }

    // Navigate to ContainerScreen when scan completes, then let the overlay fade out
    LaunchedEffect(mountPhase) {
        if (mountPhase is MountCoordinator.Phase.ScanComplete) {
            val containerId = (mountPhase as MountCoordinator.Phase.ScanComplete).containerId
            navController.navigate(Screen.ContainerScreen.buildRoute(containerId))
            delay(100)
            mountCoordinator.dismiss()
        }
    }

    Box(Modifier.fillMaxSize()) {

    NavHost(navController = navController, startDestination = startDestination) {

        composable(Screen.Onboarding.route) {
            OnboardingScreen(
                onFinish = {
                    navController.navigate(Screen.SetupPin.route) {
                        popUpTo(Screen.Onboarding.route) { inclusive = true }
                    }
                }
            )
        }

        composable(Screen.SetupPin.route) {
            SetupPinScreen(
                onPinSet = {
                    navController.navigate(Screen.PinEntry.route) {
                        popUpTo(Screen.SetupPin.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route           = Screen.Calculator.route,
            enterTransition = { slideInHorizontally(tween(300)) { -it } },
            exitTransition  = { slideOutHorizontally(tween(300)) { -it } }
        ) {
            CalculatorScreen(
                onAuthenticated = {
                    settingsViewModel.setFirstLoginDone()
                    navController.navigate(Screen.VaultScreen.route) {
                        popUpTo(Screen.Calculator.route) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route           = Screen.PinEntry.route,
            enterTransition = { slideInHorizontally(tween(300)) { -it } },
            exitTransition  = { slideOutHorizontally(tween(300)) { -it } }
        ) {
            PinEntryScreen(
                onAuthenticated = {
                    settingsViewModel.setFirstLoginDone()
                    navController.navigate(Screen.VaultScreen.route) {
                        popUpTo(Screen.PinEntry.route) { inclusive = true }
                    }
                }
            )
        }

        // ── Vault screen (root of authenticated flow) ────────────────────
        composable(
            route              = Screen.VaultScreen.route,
            enterTransition    = { slideInHorizontally(tween(300)) { it } },
            exitTransition     = {
                when (targetState.destination.route) {
                    Screen.Calculator.route,
                    Screen.PinEntry.route -> slideOutHorizontally(tween(300)) { it }
                    else                  -> slideOutHorizontally(tween(350, easing = EaseInOutCubic)) { -it }
                }
            },
            popEnterTransition = { slideInHorizontally(tween(350, easing = EaseInOutCubic)) { -it } }
        ) {
            VaultScreen(
                onLock = {
                    navController.navigate(lockScreenRoute) {
                        popUpTo(Screen.VaultScreen.route) { inclusive = true }
                    }
                },
                onCreateContainer = {
                    navController.navigate(Screen.CreateContainer.route)
                },
                onOpenSettings = {
                    navController.navigate(Screen.AppSettings.route)
                },
                onOpenContainer = { containerId ->
                    navController.navigate(Screen.ContainerScreen.buildRoute(containerId))
                },
                onMountContainer = { containerId ->
                    navController.navigate(Screen.MountScreen.buildRoute(containerId))
                },
                onMountSuccess = { containerId ->
                    mountCoordinator.beginUnlocking(containerId)
                },
                onUnmountStart = { containerId ->
                    unmountedContainerId = containerId
                    unmountIconOffset    = null
                    showUnmountOverlay   = true
                },
                unmountedContainerId      = unmountedContainerId,
                onUnmountedIconPositioned = { unmountIconOffset = it },
                suppressBackHandler       = showUnmountOverlay,
                autoMountContainerId      = pendingMountContainerId,
                onAutoMountHandled        = { pendingMountContainerId = null },
                onMoveVault               = { containerId, toApp ->
                    navController.navigate(Screen.MoveVault.buildRoute(containerId, toApp))
                }
            )
        }

        // ── Create container wizard ──────────────────────────────────────
        composable(
            route             = Screen.CreateContainer.route,
            enterTransition   = { slideInHorizontally(tween(300)) { it } },
            popExitTransition = { slideOutHorizontally(tween(300)) { it } }
        ) {
            CreateContainerScreen(
                onBack      = { navController.popBackStack() },
                onOpenVault = { containerId ->
                    pendingMountContainerId = containerId
                    navController.popBackStack()
                }
            )
        }

        // ── App settings ────────────────────────────────────────────────
        composable(
            route             = Screen.AppSettings.route,
            enterTransition   = { slideInHorizontally(tween(350, easing = EaseInOutCubic)) { it } },
            popExitTransition = { slideOutHorizontally(tween(350, easing = EaseInOutCubic)) { it } }
        ) {
            // Pass the Activity-scoped VM so requestDisguise() reaches MainActivity's observer.
            SettingsScreen(
                onBack    = { navController.popBackStack() },
                viewModel = settingsViewModel
            )
        }

        // ── Container screen (mounted vault with 3-tab bottom bar) ───────
        composable(
            route     = Screen.ContainerScreen.route,
            arguments = listOf(navArgument(Screen.ContainerScreen.ARG) { type = NavType.StringType })
        ) {
            ContainerScreen(
                onBack          = { navController.popBackStack() },
                onUnmountStart  = { containerId ->
                    // Pop ContainerScreen immediately — same frame as the overlay appearing.
                    // The scrim fades in over VaultScreen, hiding the instant nav transition.
                    navController.popBackStack(Screen.VaultScreen.route, inclusive = false)
                    unmountedContainerId = containerId
                    unmountIconOffset    = null
                    showUnmountOverlay   = true
                },
                onPhotoClick       = { fileId -> navController.navigate(Screen.PhotoViewer.buildRoute(fileId)) },
                onVideoClick       = { fileId -> navController.navigate(Screen.PhotoViewer.buildRoute(fileId)) },
                onAudioClick       = { fileId -> navController.navigate(Screen.AudioPlayer.buildRoute(fileId)) },
                onAudioFileClick   = { containerId, path, name, size ->
                    navController.navigate(Screen.AudioPlayerDirect.buildRoute(containerId, path, name, size))
                },
                onMediaFileClick   = { containerId, path, name, size ->
                    navController.navigate(Screen.MediaViewerDirect.buildRoute(containerId, path, name, size))
                }
            )
        }

        // ── Photo / image viewer ─────────────────────────────────────────
        composable(
            route     = Screen.PhotoViewer.route,
            arguments = listOf(navArgument(Screen.PhotoViewer.ARG) { type = NavType.StringType })
        ) { backStackEntry ->
            val photoId = backStackEntry.arguments?.getString(Screen.PhotoViewer.ARG) ?: ""
            MediaViewerScreen(
                photoId        = photoId,
                onBack         = { navController.popBackStack() },
                onNotification = { /* TODO: propagate to VaultScreen banner */ }
            )
        }

        // ── Video player ─────────────────────────────────────────────────
        composable(
            route     = Screen.VideoPlayer.route,
            arguments = listOf(navArgument(Screen.VideoPlayer.ARG) { type = NavType.StringType })
        ) { backStackEntry ->
            val fileId = backStackEntry.arguments?.getString(Screen.VideoPlayer.ARG) ?: ""
            VideoPlayerScreen(
                fileId = fileId,
                onBack = { navController.popBackStack() }
            )
        }

        // ── Audio player (gallery) ────────────────────────────────────────
        composable(
            route     = Screen.AudioPlayer.route,
            arguments = listOf(navArgument(Screen.AudioPlayer.ARG) { type = NavType.StringType })
        ) { backStackEntry ->
            val fileId = backStackEntry.arguments?.getString(Screen.AudioPlayer.ARG) ?: ""
            AudioPlayerScreen(
                fileId = fileId,
                onBack = { navController.popBackStack() }
            )
        }

        // ── Audio player (files — direct path) ───────────────────────────
        composable(
            route     = Screen.AudioPlayerDirect.route,
            arguments = listOf(
                navArgument(Screen.AudioPlayerDirect.ARG_CONTAINER) { type = NavType.StringType },
                navArgument(Screen.AudioPlayerDirect.ARG_PATH)      { type = NavType.StringType },
                navArgument(Screen.AudioPlayerDirect.ARG_NAME)      { type = NavType.StringType },
                navArgument(Screen.AudioPlayerDirect.ARG_SIZE)      { type = NavType.StringType }
            )
        ) {
            AudioPlayerDirectScreen(onBack = { navController.popBackStack() })
        }

        // ── Media viewer direct (images + videos from Files tab) ─────────
        composable(
            route     = Screen.MediaViewerDirect.route,
            arguments = listOf(
                navArgument(Screen.MediaViewerDirect.ARG_CONTAINER) { type = NavType.StringType },
                navArgument(Screen.MediaViewerDirect.ARG_PATH)      { type = NavType.StringType },
                navArgument(Screen.MediaViewerDirect.ARG_NAME)      { type = NavType.StringType },
                navArgument(Screen.MediaViewerDirect.ARG_SIZE)      { type = NavType.StringType }
            )
        ) {
            MediaViewerDirectScreen(onBack = { navController.popBackStack() })
        }

        // ── Move vault ────────────────────────────────────────────────────
        composable(
            route             = Screen.MoveVault.route,
            enterTransition   = { slideInHorizontally(tween(300)) { it } },
            popExitTransition = { slideOutHorizontally(tween(300)) { it } },
            arguments         = listOf(
                navArgument(Screen.MoveVault.ARG_ID)    { type = NavType.StringType },
                navArgument(Screen.MoveVault.ARG_TO_APP) { type = NavType.BoolType }
            )
        ) {
            MoveVaultScreen(
                onBack = { navController.popBackStack() }
            )
        }

        // ── Mount screen ──────────────────────────────────────────────────
        composable(
            route             = Screen.MountScreen.route,
            arguments         = listOf(navArgument(Screen.MountScreen.ARG) { type = NavType.StringType }),
            enterTransition   = { slideInHorizontally(tween(350, easing = EaseInOutCubic)) { it } },
            popExitTransition = { slideOutHorizontally(tween(350, easing = EaseInOutCubic)) { it } }
        ) { backStackEntry ->
            val containerId = backStackEntry.arguments?.getString(Screen.MountScreen.ARG) ?: return@composable
            val parentEntry = remember(backStackEntry) { navController.getBackStackEntry(Screen.VaultScreen.route) }
            val mountViewModel: VaultViewModel = hiltViewModel(parentEntry)
            MountScreen(
                containerId    = containerId,
                viewModel      = mountViewModel,
                onBack         = { navController.popBackStack() },
                onMountSuccess = { id ->
                    navController.popBackStack()
                    mountCoordinator.beginUnlocking(id)
                }
            )
        }
    }

    // Mount-success overlay — lock-open animation + indexing progress, lives above NavHost
    AnimatedVisibility(
        visible  = mountPhase !is MountCoordinator.Phase.Idle,
        enter    = fadeIn(tween(300)),
        exit     = fadeOut(tween(300)),
        modifier = Modifier.zIndex(200f)
    ) {
        MountSuccessOverlay(
            phase                   = mountPhase,
            onUnlockAnimationComplete = mountCoordinator::beginScanning
        )
    }

    // Unmount animation — lives above NavHost so it survives navigation
    if (showUnmountOverlay) {
        UnmountAnimationOverlay(
            iconTarget   = unmountIconOffset,
            onIconLanded = { unmountedContainerId = null },
            onComplete   = {
                showUnmountOverlay = false
                unmountIconOffset  = null
            }
        )
    }

    } // outer Box
}
