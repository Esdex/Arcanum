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
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import android.os.SystemClock
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import zip.arcanum.arcanum.containers.ui.MountCoordinator
import zip.arcanum.arcanum.containers.ui.MountScreen
import zip.arcanum.arcanum.share.ShareTargetScreen
import zip.arcanum.arcanum.containers.ui.MountSuccessOverlay
import zip.arcanum.arcanum.containers.ui.UnmountAnimationOverlay
import zip.arcanum.arcanum.containers.ui.VaultConfigScreen
import zip.arcanum.arcanum.containers.ui.VaultScreen
import zip.arcanum.arcanum.containers.ui.VaultViewModel
import zip.arcanum.arcanum.gallery.ui.AudioPlayerDirectScreen
import zip.arcanum.arcanum.gallery.ui.AudioPlayerScreen
import zip.arcanum.arcanum.gallery.ui.MediaViewerScreen
import zip.arcanum.arcanum.gallery.editor.PhotoEditorScreen
import zip.arcanum.calculator.ui.CalculatorScreen
import zip.arcanum.core.security.AppPreferences
import zip.arcanum.core.security.PinManager
import zip.arcanum.settings.SettingsViewModel
import zip.arcanum.onboarding.OnboardingScreen
import zip.arcanum.settings.SettingsScreen
import zip.arcanum.arcanum.containers.ui.BackupHeaderScreen
import zip.arcanum.arcanum.containers.ui.ChangeKeyfileScreen
import zip.arcanum.arcanum.containers.ui.ChangePasswordScreen
import zip.arcanum.arcanum.containers.ui.RestoreHeaderScreen
import zip.arcanum.arcanum.containers.ui.CreateContainerScreen
import zip.arcanum.arcanum.containers.ui.GenerateKeyfileScreen
import zip.arcanum.arcanum.containers.ui.MoveVaultScreen
import zip.arcanum.setup.PinEntryScreen
import zip.arcanum.setup.SetupPinScreen

// 0=Immediately(1.5s grace) 1=30s 2=1m 3=2m 4=5m 5=10m 6=30m 7=1h
// Index 0 is a background-only "lock the moment you leave" grace period; indices >= 1 are
// inactivity windows enforced by the idle loop in AppNavigation (foreground and background).
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

    // Wait for BOTH the PIN state and the calculator preference before choosing the start
    // destination. They load from independent async stores (EncryptedSharedPreferences vs
    // DataStore) with no ordering guarantee. If we committed the start destination the moment
    // isPinSet resolved, a not-yet-loaded calculator preference would fall back to the PIN
    // screen even though the disguise is enabled — issue #97. calculatorEnabled is null only
    // while still loading; once loaded it is a real Boolean (absent key → false).
    if (isPinSet == null || calculatorEnabled == null) {
        Box(Modifier.fillMaxSize())
        return
    }

    val useCalculator = calculatorEnabled == true
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

    val autoLockEnabled      by settingsViewModel.autoLockEnabled.collectAsState()
    val autoLockDelayIndex   by settingsViewModel.autoLockDelayIndex.collectAsState()
    val unmountOnAutoLock    by settingsViewModel.unmountOnAutoLock.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    val lockedRoutes = remember(lockScreenRoute) {
        setOf(Screen.Onboarding.route, Screen.SetupPin.route, Screen.Calculator.route, Screen.PinEntry.route)
    }
    val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
    val isUnlockedArea = currentRoute != null && currentRoute !in lockedRoutes
    val autoLockScope = rememberCoroutineScope()

    fun lockNow() {
        if (unmountOnAutoLock) mountCoordinator.unmountAll()
        navController.navigate(lockScreenRoute) {
            popUpTo(0) { inclusive = true }
            launchSingleTop = true
        }
    }

    // Index 0 ("Immediately") keeps the original background-only behavior: lock shortly after
    // the app leaves the foreground. Indices >= 1 are handled by the idle loop below instead,
    // so this observer only arms for index 0.
    DisposableEffect(lifecycleOwner, autoLockEnabled, autoLockDelayIndex, unmountOnAutoLock, lockScreenRoute) {
        var lockJob: Job? = null
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    if (autoLockEnabled && autoLockDelayIndex == 0) {
                        val current = navController.currentDestination?.route ?: return@LifecycleEventObserver
                        if (current !in lockedRoutes) {
                            lockJob = autoLockScope.launch {
                                delay(autoLockDelayMillis(0))
                                lockNow()
                            }
                        }
                    }
                }
                Lifecycle.Event.ON_START -> {
                    lockJob?.cancel()
                    // Idle windows (index >= 1): if the app was backgrounded long enough that the
                    // inactivity window already elapsed, lock immediately on return - before the
                    // resume touch can reset the timer. Covers Doze deferring the idle loop's
                    // wake-ups while the process was in the background.
                    if (autoLockEnabled && autoLockDelayIndex >= 1) {
                        val current = navController.currentDestination?.route
                        if (current != null && current !in lockedRoutes) {
                            val idleMs = SystemClock.elapsedRealtime() - settingsViewModel.lastInteractionAtMs()
                            if (idleMs >= autoLockDelayMillis(autoLockDelayIndex)) lockNow()
                        }
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            lockJob?.cancel()
        }
    }

    // Idle auto-lock (index >= 1): lock once the time since the last user interaction reaches
    // the configured window. Interaction time is monotonic and untouched by backgrounding, so a
    // vault left mounted ages out whether the app is in the foreground or the background. Idle is
    // measured from the last *real* interaction (never reset on ON_START), so a long background
    // can't be "forgiven" by returning to the app.
    LaunchedEffect(autoLockEnabled, autoLockDelayIndex, isUnlockedArea) {
        if (!autoLockEnabled || autoLockDelayIndex == 0 || !isUnlockedArea) return@LaunchedEffect
        // Fresh baseline for the unlock we just entered.
        settingsViewModel.recordInteraction()
        val windowMs = autoLockDelayMillis(autoLockDelayIndex)
        while (isActive) {
            val idleMs = SystemClock.elapsedRealtime() - settingsViewModel.lastInteractionAtMs()
            val remaining = windowMs - idleMs
            if (remaining <= 0L) {
                lockNow()
                break
            }
            // Re-check at least every 20s so a late interaction is picked up promptly.
            delay(remaining.coerceIn(500L, 20_000L))
        }
    }

    // A file shared into Arcanum waits in ShareIntake until the user is in the authenticated area
    // (any unlocked screen - MainActivity is singleTask, so a share reuses the live session rather
    // than spawning a fresh, locked instance). Never fires while locked or already on the picker.
    val pendingShare by settingsViewModel.pendingShare.collectAsState()
    LaunchedEffect(pendingShare.isNotEmpty(), isUnlockedArea, currentRoute) {
        if (pendingShare.isNotEmpty() && isUnlockedArea && currentRoute != Screen.ShareTarget.route) {
            navController.navigate(Screen.ShareTarget.route)
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
                onGenerateKeyfile = {
                    navController.navigate(Screen.GenerateKeyfile.route)
                },
                onOpenSettings = {
                    navController.navigate(Screen.AppSettings.route)
                },
                onVaultConfig = { containerId ->
                    navController.navigate(Screen.VaultConfig.buildRoute(containerId))
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
                onOpenWhatsNew            = {
                    navController.navigate(Screen.WhatsNew.route)
                },
                onOpenDonations           = {
                    navController.navigate(Screen.Donations.route)
                },
                onOpenPremium             = {
                    navController.navigate(Screen.Premium.route)
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

        // ── Keyfile generator ───────────────────────────────────────────
        composable(
            route             = Screen.GenerateKeyfile.route,
            enterTransition   = { slideInHorizontally(tween(300)) { it } },
            popExitTransition = { slideOutHorizontally(tween(300)) { it } }
        ) {
            GenerateKeyfileScreen(onBack = { navController.popBackStack() })
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
                onMediaFileClick   = { fileId ->
                    navController.navigate(Screen.PhotoViewer.buildRoute(fileId, folderScope = true))
                }
            )
        }

        // ── Photo / image viewer ─────────────────────────────────────────
        composable(
            route     = Screen.PhotoViewer.route,
            arguments = listOf(
                navArgument(Screen.PhotoViewer.ARG) { type = NavType.StringType },
                navArgument(Screen.PhotoViewer.ARG_FOLDER_SCOPE) { type = NavType.BoolType; defaultValue = false }
            )
        ) { backStackEntry ->
            val photoId = backStackEntry.arguments?.getString(Screen.PhotoViewer.ARG) ?: ""
            MediaViewerScreen(
                photoId        = photoId,
                onBack         = { navController.popBackStack() },
                onOpenEditor   = { fileId -> navController.navigate(Screen.PhotoEditor.buildRoute(fileId)) },
                onNotification = { /* TODO: propagate to VaultScreen banner */ }
            )
        }

        // ── Photo editor ─────────────────────────────────────────────────
        composable(
            route             = Screen.PhotoEditor.route,
            arguments         = listOf(navArgument(Screen.PhotoEditor.ARG) { type = NavType.StringType }),
            enterTransition   = { slideInHorizontally(tween(350, easing = EaseInOutCubic)) { it } },
            popExitTransition = { slideOutHorizontally(tween(350, easing = EaseInOutCubic)) { it } }
        ) {
            PhotoEditorScreen(onBack = { savedFileId ->
                if (savedFileId != null) {
                    navController.navigate(Screen.PhotoViewer.buildRoute(savedFileId)) {
                        popUpTo(Screen.PhotoViewer.route) { inclusive = true }
                    }
                } else {
                    navController.popBackStack()
                }
            })
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

        // ── What's new (opens Settings directly at the What's New subscreen) ─
        composable(
            route             = Screen.WhatsNew.route,
            enterTransition   = { slideInHorizontally(tween(350, easing = EaseInOutCubic)) { it } },
            popExitTransition = { slideOutHorizontally(tween(350, easing = EaseInOutCubic)) { it } }
        ) {
            SettingsScreen(
                onBack       = { navController.popBackStack() },
                viewModel    = settingsViewModel,
                openWhatsNew = true
            )
        }

        // ── Donations / Premium (Settings opened at the matching subscreen) ──
        composable(
            route             = Screen.Donations.route,
            enterTransition   = { slideInHorizontally(tween(350, easing = EaseInOutCubic)) { it } },
            popExitTransition = { slideOutHorizontally(tween(350, easing = EaseInOutCubic)) { it } }
        ) {
            SettingsScreen(
                onBack         = { navController.popBackStack() },
                viewModel      = settingsViewModel,
                openDonations  = true
            )
        }

        composable(
            route             = Screen.Premium.route,
            enterTransition   = { slideInHorizontally(tween(350, easing = EaseInOutCubic)) { it } },
            popExitTransition = { slideOutHorizontally(tween(350, easing = EaseInOutCubic)) { it } }
        ) {
            SettingsScreen(
                onBack        = { navController.popBackStack() },
                viewModel     = settingsViewModel,
                openPremium   = true
            )
        }

        // ── Share destination picker (files received from the Android share sheet) ─
        composable(Screen.ShareTarget.route) {
            val backToVault: () -> Unit = {
                navController.navigate(Screen.VaultScreen.route) {
                    popUpTo(Screen.VaultScreen.route) { inclusive = true }
                    launchSingleTop = true
                }
            }
            ShareTargetScreen(
                onDone   = backToVault,
                onCancel = { settingsViewModel.clearPendingShare(); backToVault() }
            )
        }

        // ── Change password wizard ────────────────────────────────────────
        composable(
            route             = Screen.ChangePassword.route,
            arguments         = listOf(navArgument(Screen.ChangePassword.ARG) { type = NavType.StringType }),
            enterTransition   = { slideInHorizontally(tween(300)) { it } },
            popExitTransition = { slideOutHorizontally(tween(300)) { it } }
        ) { backStackEntry ->
            val containerId = backStackEntry.arguments?.getString(Screen.ChangePassword.ARG) ?: return@composable
            ChangePasswordScreen(
                containerId = containerId,
                onBack      = { navController.popBackStack() }
            )
        }

        // ── Change keyfile wizard ─────────────────────────────────────────
        composable(
            route             = Screen.ChangeKeyfile.route,
            arguments         = listOf(navArgument(Screen.ChangeKeyfile.ARG) { type = NavType.StringType }),
            enterTransition   = { slideInHorizontally(tween(300)) { it } },
            popExitTransition = { slideOutHorizontally(tween(300)) { it } }
        ) { backStackEntry ->
            val containerId = backStackEntry.arguments?.getString(Screen.ChangeKeyfile.ARG) ?: return@composable
            ChangeKeyfileScreen(
                containerId = containerId,
                onBack      = { navController.popBackStack() }
            )
        }

        // ── Vault config ─────────────────────────────────────────────────
        composable(
            route             = Screen.VaultConfig.route,
            arguments         = listOf(navArgument(Screen.VaultConfig.ARG) { type = NavType.StringType }),
            enterTransition   = { slideInHorizontally(tween(350, easing = EaseInOutCubic)) { it } },
            popExitTransition = { slideOutHorizontally(tween(350, easing = EaseInOutCubic)) { it } }
        ) { backStackEntry ->
            val containerId = backStackEntry.arguments?.getString(Screen.VaultConfig.ARG) ?: return@composable
            val parentEntry = remember(backStackEntry) { navController.getBackStackEntry(Screen.VaultScreen.route) }
            val vaultViewModel: VaultViewModel = hiltViewModel(parentEntry)
            VaultConfigScreen(
                containerId      = containerId,
                viewModel        = vaultViewModel,
                onBack           = { navController.popBackStack() },
                onMount          = { id -> navController.navigate(Screen.MountScreen.buildRoute(id)) },
                onOpenVault      = { id -> navController.navigate(Screen.ContainerScreen.buildRoute(id)) },
                onChangePassword = { id -> navController.navigate(Screen.ChangePassword.buildRoute(id)) },
                onChangeKeyfile  = { id -> navController.navigate(Screen.ChangeKeyfile.buildRoute(id)) },
                onBackupHeader   = { id -> navController.navigate(Screen.BackupHeader.buildRoute(id)) },
                onRestoreHeader  = { id -> navController.navigate(Screen.RestoreHeader.buildRoute(id)) },
                onMoveVault      = { id, toApp -> navController.navigate(Screen.MoveVault.buildRoute(id, toApp)) }
            )
        }

        // ── Backup header ─────────────────────────────────────────────────
        composable(
            route             = Screen.BackupHeader.route,
            arguments         = listOf(navArgument(Screen.BackupHeader.ARG) { type = NavType.StringType }),
            enterTransition   = { slideInHorizontally(tween(300)) { it } },
            popExitTransition = { slideOutHorizontally(tween(300)) { it } }
        ) { backStackEntry ->
            val containerId = backStackEntry.arguments?.getString(Screen.BackupHeader.ARG) ?: return@composable
            BackupHeaderScreen(
                containerId = containerId,
                onBack      = { navController.popBackStack() }
            )
        }

        // ── Restore header ────────────────────────────────────────────────
        composable(
            route             = Screen.RestoreHeader.route,
            arguments         = listOf(navArgument(Screen.RestoreHeader.ARG) { type = NavType.StringType }),
            enterTransition   = { slideInHorizontally(tween(300)) { it } },
            popExitTransition = { slideOutHorizontally(tween(300)) { it } }
        ) { backStackEntry ->
            val containerId = backStackEntry.arguments?.getString(Screen.RestoreHeader.ARG) ?: return@composable
            RestoreHeaderScreen(
                containerId = containerId,
                onBack      = { navController.popBackStack() }
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
