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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import zip.arcanum.arcanum.containers.ui.MountCoordinator
import zip.arcanum.arcanum.containers.ui.MountScreen
import zip.arcanum.arcanum.share.ShareTargetScreen
import zip.arcanum.arcanum.containers.ui.MountSuccessOverlay
import zip.arcanum.arcanum.containers.ui.UnmountAnimationOverlay
import zip.arcanum.arcanum.containers.ui.VaultConfigScreen
import zip.arcanum.arcanum.containers.ui.VaultScreen
import zip.arcanum.arcanum.containers.ui.VaultViewModel
import zip.arcanum.arcanum.gallery.ui.AudioPlayerDirectScreen
import zip.arcanum.arcanum.files.pdf.PdfViewerScreen
import zip.arcanum.arcanum.files.text.TextEditorScreen
import zip.arcanum.arcanum.gallery.ui.AudioPlayerScreen
import zip.arcanum.arcanum.gallery.ui.MediaViewerScreen
import zip.arcanum.arcanum.gallery.editor.PhotoEditorScreen
import zip.arcanum.calculator.ui.CalculatorScreen
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.notifications.InAppNotificationBanner
import zip.arcanum.core.notifications.LocalNotifications
import zip.arcanum.core.notifications.NotificationCenter
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

@Composable
fun AppNavigation(pinManager: PinManager, notifications: NotificationCenter) {
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
    var pendingMountContainerId    by remember { mutableStateOf<String?>(null) }


    val mountCoordinator: MountCoordinator = hiltViewModel()
    val mountPhase by mountCoordinator.phase.collectAsState()

    val locked by settingsViewModel.locked.collectAsState()

    val lockedRoutes = remember(lockScreenRoute) {
        setOf(Screen.Onboarding.route, Screen.SetupPin.route, Screen.Calculator.route, Screen.PinEntry.route)
    }
    val currentEntry = navController.currentBackStackEntryAsState().value
    val currentRoute = currentEntry?.destination?.route
    val isUnlockedArea = currentRoute != null && currentRoute !in lockedRoutes

    /*
     * A vault's screen cannot outlive the vault.
     *
     * Closing a vault by hand pops this screen as part of the same gesture, and an auto-lock
     * clears the whole stack on its way to the PIN - so the only way to end up looking at a
     * vault that is no longer open was to have it closed from underneath: the per-vault
     * "unmount when I leave the app", or an unmount from somewhere else. Coming back to the
     * app then landed on a screen whose contents cannot be read any more.
     *
     * Only while the vault's own screen is the one on top: a viewer or the editor above it
     * says what happened in its own words, which is better than the stack disappearing from
     * under an unsaved file.
     */
    val mountedIds by mountCoordinator.mountedContainerIds.collectAsState()
    LaunchedEffect(mountedIds, currentRoute) {
        if (currentRoute != Screen.ContainerScreen.route) return@LaunchedEffect
        val id = currentEntry?.arguments?.getString(Screen.ContainerScreen.ARG)
        if (id != null && id !in mountedIds) {
            navController.popBackStack(Screen.VaultScreen.route, inclusive = false)
        }
    }

    /*
     * The one place the UI leaves the authenticated area.
     *
     * Deciding *when* to lock is no longer done here - LockController owns the idle clock,
     * the "lock the moment you leave" grace and the unmount rules, because a composition
     * cannot be trusted to outlive the process it locks (#102). What is left here is the
     * part that genuinely belongs to the UI: when the session says it is locked, go to the
     * lock screen.
     *
     * This also covers a restored back stack. A background kill takes the mount map, the JNI
     * handles and the idle clock with it, but Android still restores the saved stack - so the
     * app can come back sitting on an authenticated screen inside a process that never saw
     * the PIN (#150). A fresh process starts locked, so that lands here like any other lock.
     */
    LaunchedEffect(locked, isUnlockedArea) {
        if (locked && isUnlockedArea) {
            navController.navigate(lockScreenRoute) {
                popUpTo(0) { inclusive = true }
                launchSingleTop = true
            }
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

    // The phase this composition saw, captured - see the effect below for why.
    val phase = mountPhase

    /*
     * Navigate to ContainerScreen when the scan completes, then let the overlay fade out.
     *
     * The phase is captured into a local first, and the body branches on THAT rather than
     * on `mountPhase`. `mountPhase` is a `by collectAsState()` delegate, so every mention
     * of it inside the coroutine is a read of the live state at the moment the coroutine
     * wakes - and a LaunchedEffect starts its coroutine after the composition that created
     * it. The scanner sets ScanComplete a few milliseconds after its last Indexing update,
     * so the effect keyed on that last Indexing woke up already seeing ScanComplete and
     * navigated, and the effect keyed on ScanComplete then navigated again. Two identical
     * screens, and the first Back looked ignored because it removed a copy of the one
     * beneath it (#176).
     */
    LaunchedEffect(phase) {
        if (phase is MountCoordinator.Phase.ScanComplete) {
            val containerId = phase.containerId
            // Single top as well, so that two of the same vault cannot stack whatever
            // else calls this - the guard put in when only the symptom was understood.
            navController.navigate(Screen.ContainerScreen.buildRoute(containerId)) {
                launchSingleTop = true
            }
            delay(100)
            mountCoordinator.dismiss()
        }
    }

    /*
     * Nothing is shown over the disguise. The calculator and the PIN screen are not the
     * user's session, and a vault's name on a banner there would give away both the app and
     * what is in it. What arrives while locked waits and is delivered on the way back in -
     * which is what OperationRefusedLocked was hand-rolled to do before the queue existed.
     */
    LaunchedEffect(isUnlockedArea) { notifications.setDelivering(isUnlockedArea) }

    val currentNotification by notifications.current.collectAsState()

    CompositionLocalProvider(LocalNotifications provides notifications) {
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
                    settingsViewModel.markUnlocked()
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
                    settingsViewModel.markUnlocked()
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
                onUnmountStart = { showUnmountOverlay = true },
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
                onUnmountStart  = {
                    // Pop ContainerScreen immediately — same frame as the overlay appearing.
                    // The scrim fades in over VaultScreen, hiding the instant nav transition.
                    navController.popBackStack(Screen.VaultScreen.route, inclusive = false)
                    showUnmountOverlay = true
                },
                onPhotoClick       = { fileId -> navController.navigate(Screen.PhotoViewer.buildRoute(fileId)) },
                onVideoClick       = { fileId -> navController.navigate(Screen.PhotoViewer.buildRoute(fileId)) },
                onAudioClick       = { fileId -> navController.navigate(Screen.AudioPlayer.buildRoute(fileId)) },
                onAudioFileClick   = { containerId, path, name, size ->
                    navController.navigate(Screen.AudioPlayerDirect.buildRoute(containerId, path, name, size))
                },
                onPdfFileClick     = { containerId, path, name, size ->
                    navController.navigate(Screen.PdfViewer.buildRoute(containerId, path, name, size))
                },
                onTextFileClick    = { containerId, path, name, size ->
                    navController.navigate(Screen.TextEditor.buildRoute(containerId, path, name, size))
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
                onOpenEditor   = { fileId -> navController.navigate(Screen.PhotoEditor.buildRoute(fileId)) }
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

        // ── PDF viewer (files - direct path) ─────────────────────────────
        composable(
            route     = Screen.PdfViewer.route,
            // Half the NavHost's own 700 ms fade on the way out, at his request: closing a
            // document should feel like putting it down, not like a transition.
            popExitTransition = { fadeOut(tween(350)) },
            arguments = listOf(
                navArgument(Screen.PdfViewer.ARG_CONTAINER) { type = NavType.StringType },
                navArgument(Screen.PdfViewer.ARG_PATH)      { type = NavType.StringType },
                navArgument(Screen.PdfViewer.ARG_NAME)      { type = NavType.StringType },
                navArgument(Screen.PdfViewer.ARG_SIZE)      { type = NavType.StringType }
            )
        ) {
            PdfViewerScreen(onBack = { navController.popBackStack() })
        }

        composable(
            route     = Screen.TextEditor.route,
            arguments = listOf(
                navArgument(Screen.TextEditor.ARG_CONTAINER) { type = NavType.StringType },
                navArgument(Screen.TextEditor.ARG_PATH)      { type = NavType.StringType },
                navArgument(Screen.TextEditor.ARG_NAME)      { type = NavType.StringType },
                navArgument(Screen.TextEditor.ARG_SIZE)      { type = NavType.StringType }
            )
        ) {
            TextEditorScreen(onBack = { navController.popBackStack() })
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

    // The unmount overlay — above the NavHost so it survives the navigation underneath it
    if (showUnmountOverlay) {
        UnmountAnimationOverlay(onComplete = { showUnmountOverlay = false })
    }

    /*
     * The one notification host in the app. It sits above every screen and below the
     * full-screen mount and unmount overlays, which are the app talking about the same
     * thing in a louder voice.
     *
     * Four screens used to host their own, each with its own copy of the state, so a
     * notification raised on one screen died the moment the user left it and two raised
     * together meant one was never seen.
     */
    InAppNotificationBanner(
        notification = currentNotification,
        onDismiss    = { notifications.dismiss() },
        onAction     = { notif ->
            // Said before the routing below, because most notifications are acted on by
            // whoever raised them rather than by navigating anywhere.
            notifications.actedOn(notif)
            when (notif) {
                is InAppNotification.AppUpdated -> {
                    settingsViewModel.markUpdateSeen()
                    navController.navigate(Screen.WhatsNew.route)
                }
                is InAppNotification.SupportDeveloper -> navController.navigate(Screen.Donations.route)
                is InAppNotification.GoPremium        -> navController.navigate(Screen.Premium.route)
                else -> Unit
            }
            notifications.dismiss()
        },
        modifier = Modifier
            .align(Alignment.TopCenter)
            .statusBarsPadding()
            .zIndex(50f)
    )

    } // outer Box
    }
}
