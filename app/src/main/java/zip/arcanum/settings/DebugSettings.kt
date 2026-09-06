package zip.arcanum.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import android.view.ViewGroup
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import android.view.WindowManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.notifications.LocalNotifications
import zip.arcanum.core.notifications.ImportFailureReason
import zip.arcanum.core.notifications.dwellMillis
import zip.arcanum.R
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.components.LocalHazeState
import zip.arcanum.core.components.SettingsSwitch
import zip.arcanum.core.components.hazeOrSolid
import zip.arcanum.core.theme.ArcanumHazeStyle
import zip.arcanum.core.theme.LocalAmoledMode
import android.widget.Toast
import zip.arcanum.core.components.BackButton

// Settings / Debug: the developer screen, and the largest of them.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DebugSubScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val debugViewModel: DebugViewModel = hiltViewModel()
    val debugMode      = viewModel.debugMode.collectAsState().value
    val state          = debugViewModel.state.collectAsState().value
    val disguiseApplied by viewModel.disguiseApplied.collectAsState()
    val activity       = LocalContext.current as FragmentActivity
    var showWarningDialog by remember { mutableStateOf(false) }
    var showUsbWriteConfirm by remember { mutableStateOf(false) }
    var usbMountPassword by remember { mutableStateOf("") }
    var showUsbSweepConfirm by remember { mutableStateOf(false) }
    val isAmoled       = LocalAmoledMode.current
    val debugHazeState = remember { HazeState() }
    val notifications  = LocalNotifications.current
    var debugNotificationWalk by remember { mutableStateOf(false) }

    // One after another, each given its own dwell plus a breath, so they are seen rather
    // than queued behind one another.
    LaunchedEffect(debugNotificationWalk) {
        if (!debugNotificationWalk) return@LaunchedEffect
        allNotificationsForDebug().forEach { n ->
            notifications.notify(n)
            kotlinx.coroutines.delay(if (n.dwellMillis > 0L) n.dwellMillis + 400L else 2_500L)
            notifications.dismiss()
        }
        debugNotificationWalk = false
    }

    CompositionLocalProvider(LocalHazeState provides debugHazeState) {

    LaunchedEffect(debugMode) {
        if (debugMode) debugViewModel.refresh()
    }

    val context = LocalContext.current
    LaunchedEffect(Unit) {
        debugViewModel.events.collect { event ->
            when (event) {
                DebugViewModel.DebugEvent.CacheCleared ->
                    Toast.makeText(context, "Cache cleared successfully", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (showWarningDialog) {
        DebugWarningDialog(
            onDismiss = { showWarningDialog = false },
            onConfirm = {
                showWarningDialog = false
                viewModel.setDebugMode(true)
                debugViewModel.refresh()
            }
        )
    }

    if (state.dryRunActions != null) {
        AppDialog(
            onDismissRequest = { debugViewModel.clearDryRun() },
            title   = { Text(stringResource(R.string.settings_debug_dry_run_panic)) },
            text    = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    state.dryRunActions.forEach { line ->
                        Text(
                            text     = line,
                            style    = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { debugViewModel.clearDryRun() }) { Text(stringResource(R.string.common_close)) }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_debug_title)) },
                navigationIcon = {
                    BackButton(onClick = onBack)
                },
                actions = {
                    if (debugMode) {
                        IconButton(onClick = { debugViewModel.copyToClipboard() }) {
                            Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.settings_debug_cd_copy_all))
                        }
                        IconButton(onClick = { debugViewModel.refresh() }, enabled = !state.isLoading) {
                            if (state.isLoading) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.settings_debug_cd_refresh))
                            }
                        }
                    }
                },
                colors   = if (isAmoled) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                           else TopAppBarDefaults.topAppBarColors(),
                modifier = if (isAmoled) Modifier.hazeEffect(state = debugHazeState, style = ArcanumHazeStyle.topBar)
                           else Modifier
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(debugHazeState)
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            SubScreenGroup {
                SettingsSwitch(
                    title           = stringResource(R.string.settings_debug_mode),
                    subtitle        = stringResource(R.string.settings_debug_mode_desc),
                    checked         = debugMode,
                    onCheckedChange = { enabling ->
                        if (!enabling) {
                            viewModel.setDebugMode(false)
                        } else if (viewModel.hasDeviceLock()) {
                            viewModel.authenticateForDebug(
                                activity  = activity,
                                onSuccess = {
                                    viewModel.setDebugMode(true)
                                    debugViewModel.refresh()
                                },
                                onError = { _, _ -> }
                            )
                        } else {
                            showWarningDialog = true
                        }
                    }
                )
            }

            if (debugMode) {
                val showMountLog by viewModel.showMountLog.collectAsState()
                val saveMountLog by viewModel.saveMountLog.collectAsState()
                val galleryResyncButton by viewModel.galleryResyncButton.collectAsState()
                SubScreenGroup {
                    SettingsSwitch(
                        title           = stringResource(R.string.settings_debug_mount_log_title),
                        subtitle        = stringResource(R.string.settings_debug_mount_log_desc),
                        checked         = showMountLog,
                        onCheckedChange = { viewModel.setShowMountLog(it) }
                    )
                    SettingsSwitch(
                        title           = stringResource(R.string.settings_debug_save_mount_log_title),
                        subtitle        = stringResource(R.string.settings_debug_save_mount_log_desc),
                        checked         = saveMountLog,
                        onCheckedChange = { viewModel.setSaveMountLog(it) }
                    )
                    SettingsSwitch(
                        title           = stringResource(R.string.settings_debug_gallery_resync_title),
                        subtitle        = stringResource(R.string.settings_debug_gallery_resync_desc),
                        checked         = galleryResyncButton,
                        onCheckedChange = { viewModel.setGalleryResyncButton(it) }
                    )
                }

                // ── Runtime ──────────────────────────────────────────────────
                PanicSectionLabel(stringResource(R.string.settings_debug_section_runtime))
                SubScreenGroup {
                    state.runtime?.let { r ->
                        DebugRow("PID", r.pid.toString())
                        DebugRow("UID", r.uid.toString())
                        DebugRow("Heap", "${r.heapUsed} / ${r.heapMax}")
                        DebugRow(
                            label      = "libarcanum-native.so",
                            value      = if (r.nativeLib) "Loaded" else "Not loaded",
                            valueColor = if (r.nativeLib) Color(0xFF22C55E) else MaterialTheme.colorScheme.error
                        )
                    }
                    if (state.runtime == null && state.isLoading) DebugRow("", "Loading…")
                }

                // ── Mounted Containers ────────────────────────────────────────
                PanicSectionLabel(stringResource(R.string.settings_debug_section_mounted))
                SubScreenGroup {
                    if (state.mounted.isEmpty() && !state.isLoading) {
                        Text(
                            stringResource(R.string.settings_debug_no_containers),
                            style    = MaterialTheme.typography.bodySmall,
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    } else {
                        state.mounted.forEachIndexed { index, c ->
                            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Handle: 0x${c.handle.toString(16).uppercase().padStart(16, '0')}",
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "PIM: ${if (c.pim > 0) c.pim.toString() else "default"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (index < state.mounted.lastIndex) {
                                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                            }
                        }
                    }
                }

                // ── Device Security ───────────────────────────────────────────
                val rootView = LocalView.current.rootView
                val isWindowSecure = remember(rootView) {
                    ((rootView.layoutParams as? WindowManager.LayoutParams)
                        ?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) ?: 0) != 0
                }
                PanicSectionLabel(stringResource(R.string.settings_debug_section_device))
                SubScreenGroup {
                    state.security?.let { s ->
                        DebugRow(
                            label      = "Keystore",
                            value      = s.keystore,
                            valueColor = if (s.keystore.startsWith("Hardware")) Color(0xFF22C55E) else Color(0xFFFFA000),
                            infoText   = "Stores cryptographic keys used for biometric authentication.\n\n" +
                                "Hardware-backed (TEE) — keys live in a Trusted Execution Environment, " +
                                "isolated from the main OS. Even root access cannot extract them.\n\n" +
                                "Software (no TEE) — keys are encrypted in memory, lower security level."
                        )
                        DebugRow(
                            label      = "Biometric STRONG",
                            value      = s.biometricStrong,
                            valueColor = biometricStatusColor(s.biometricStrong),
                            infoText   = "High-security biometrics: fingerprint, iris, or 3D face recognition.\n\n" +
                                "Available — enrolled and ready\n" +
                                "Not enrolled — hardware present but not configured\n" +
                                "No hardware — not supported by this device\n" +
                                "Unavailable — temporarily inaccessible\n\n" +
                                "Arcanum uses STRONG biometrics for vault unlock."
                        )
                        DebugRow(
                            label      = "Biometric WEAK",
                            value      = s.biometricWeak,
                            valueColor = biometricStatusColor(s.biometricWeak),
                            infoText   = "Lower-security biometrics: typically 2D face recognition via front camera. " +
                                "More easily spoofed than STRONG.\n\n" +
                                "Available — enrolled and ready\n" +
                                "Not enrolled — hardware present but not configured\n" +
                                "No hardware — not supported\n\n" +
                                "Arcanum does not use WEAK biometrics for vault authentication."
                        )
                        DebugRow(
                            label      = "SELinux",
                            value      = s.selinux,
                            valueColor = when (s.selinux) {
                                "Enforcing"  -> Color(0xFF22C55E)
                                "Permissive" -> Color(0xFFFFA000)
                                else         -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            infoText   = "Security-Enhanced Linux enforces mandatory access controls at OS level.\n\n" +
                                "Enforcing — policy violations are blocked. Required on all Google-certified devices.\n\n" +
                                "Permissive — violations are only logged, not blocked. Common on custom ROMs. " +
                                "Reduces inter-process isolation.\n\n" +
                                "Enforcing (assumed) — the live state could not be queried on this device " +
                                "(the OS blocks the check); Android has mandated enforcing since 5.0."
                        )
                        DebugRow(
                            label      = "Root",
                            value      = if (s.rooted) "Detected" else "Not detected",
                            valueColor = if (s.rooted) MaterialTheme.colorScheme.error else Color(0xFF22C55E),
                            infoText   = "Checks for signs of root access: su binary in common system paths, " +
                                "test-keys build signature, and response to su commands.\n\n" +
                                "Not detected — no root indicators found.\n\n" +
                                "Detected — root is present. Other apps may bypass the Android security " +
                                "sandbox and potentially access vault data."
                        )
                        DebugRow(
                            label      = "Bootloader",
                            value      = if (s.bootloaderUnlocked) "Unlocked" else "Locked",
                            valueColor = if (s.bootloaderUnlocked) MaterialTheme.colorScheme.error else Color(0xFF22C55E),
                            infoText   = "Verified Boot state reported by the firmware.\n\n" +
                                "Locked — device boots only signed, unmodified system images. " +
                                "Provides the strongest hardware-level integrity guarantee.\n\n" +
                                "Unlocked — custom or unsigned images are allowed. " +
                                "Full-disk encryption keys may be accessible to an attacker with physical access, " +
                                "and the device is more susceptible to cold-boot and evil-maid attacks."
                        )
                        DebugRow(
                            label      = "USB Debugging",
                            value      = if (s.adbEnabled) "Enabled" else "Disabled",
                            valueColor = if (s.adbEnabled) Color(0xFFFFA000) else Color(0xFF22C55E),
                            infoText   = "Android Debug Bridge (ADB) over USB.\n\n" +
                                "Disabled — normal state for production use. " +
                                "No shell access to the device over USB without unlocking.\n\n" +
                                "Enabled — a connected computer can run arbitrary shell commands, " +
                                "pull files from app-accessible storage, and install or uninstall apps. " +
                                "Should be turned off when not actively debugging."
                        )
                        DebugRow(
                            label      = "Developer Options",
                            value      = if (s.devOptionsEnabled) "Enabled" else "Disabled",
                            valueColor = if (s.devOptionsEnabled) Color(0xFFFFA000) else Color(0xFF22C55E),
                            infoText   = "Android Developer Options menu.\n\n" +
                                "Disabled — normal state for end users. " +
                                "Hides advanced settings that can affect device security and behavior.\n\n" +
                                "Enabled — exposes low-level options such as USB debugging, mock locations, " +
                                "background process limits, and layout inspection tools. " +
                                "Leaving Developer Options on increases the attack surface of the device."
                        )
                        val overlayCount = s.overlayCapableApps
                        DebugRow(
                            label      = "Overlay-capable apps",
                            value      = if (overlayCount < 0) "Unknown" else "$overlayCount",
                            valueColor = when {
                                overlayCount < 0 -> MaterialTheme.colorScheme.onSurfaceVariant
                                overlayCount == 0 -> Color(0xFF22C55E)
                                else              -> Color(0xFFFFA000)
                            },
                            infoText   = "Number of apps granted the Draw Over Other Apps (SYSTEM_ALERT_WINDOW) permission.\n\n" +
                                "0 — no apps can render windows over other apps.\n\n" +
                                "> 0 — at least one app can draw overlay content while Arcanum is in the foreground. " +
                                "This is common for messaging apps, launchers, and assistants, but also the primary vector " +
                                "for tapjacking attacks (overlaying a fake UI to intercept taps or steal entered data).\n\n" +
                                "Arcanum mitigates this with FLAG_SECURE and filterTouchesWhenObscured."
                        )
                        DebugRow(
                            label      = "FLAG_SECURE",
                            value      = if (isWindowSecure) "Active" else "Inactive",
                            valueColor = if (isWindowSecure) Color(0xFF22C55E) else MaterialTheme.colorScheme.error,
                            infoText   = "Window-level security flag set by Arcanum on every screen.\n\n" +
                                "Active — the OS blocks screenshot APIs and screen recording for this window. " +
                                "Overlay apps cannot capture vault content through the standard capture pipeline, " +
                                "and the recent-apps thumbnail is suppressed.\n\n" +
                                "Inactive — content is capturable. This should never happen in a production build."
                        )
                    }
                }

                // ── Database ──────────────────────────────────────────────────
                PanicSectionLabel(stringResource(R.string.settings_debug_section_database))
                SubScreenGroup {
                    state.db?.let { db ->
                        DebugRow("Schema", "v${db.version}")
                        DebugRow("Containers", "${db.total} total, ${db.mounted} mounted")
                        /* What the app still holds about vaults, whether they exist or not
                         * (#134). With an empty vault list these should all be zero. */
                        DebugRow("Media rows", db.mediaRows.toString())
                        DebugRow("Thumbnail dirs", db.thumbnailDirs.toString())
                        DebugRow("Waveforms", db.waveforms.toString())
                        DebugRow("URI grants held", db.persistedUriGrants.toString())
                        DebugRow("Mount log", if (db.hasMountLog) "saved" else "none")
                        DebugRow("Crash logs", db.crashLogs.toString())
                    }
                }

                // ── Launcher Icons ────────────────────────────────────────────
                PanicSectionLabel(stringResource(R.string.settings_debug_section_icons))
                SubScreenGroup {
                    Row(
                        modifier              = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        IconPreview(
                            label   = stringResource(R.string.app_name),
                            bgColor = Color(0xFF3DDC84),
                            fgRes   = R.drawable.ic_launcher_foreground,
                            active  = !disguiseApplied
                        )
                        IconPreview(
                            label   = stringResource(R.string.app_name_calculator),
                            bgColor = Color(0xFF37474F),
                            fgRes   = R.drawable.ic_launcher_calc_fg,
                            active  = disguiseApplied
                        )
                    }
                }

                /* ── Notifications ─────────────────────────────────────────────
                 * The queue's rules are not visible from any one screen, and reproducing
                 * two dozen situations by hand to look at them is not a test anyone runs
                 * twice. Debug only (#135). */
                PanicSectionLabel("Notifications")
                SubScreenGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            // Success, warning, error, announcement, in that order. The
                            // error should cut in front, and the rest follow it in turn.
                            onClick  = {
                                notifications.notify(InAppNotification.FilesDeleted(3))
                                notifications.notify(InAppNotification.VaultNeedsCheck)
                                notifications.notify(InAppNotification.ReadOnlyError)
                                notifications.notify(InAppNotification.SupportDeveloper)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("One of each", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            // Eight at once, two of them the same thing: three should
                            // survive behind the first, and the duplicate should merge.
                            onClick  = {
                                notifications.notify(InAppNotification.FileRenamed("one.txt"))
                                notifications.notify(InAppNotification.FolderCreated("Folder"))
                                notifications.notify(InAppNotification.FilesDeleted(1))
                                notifications.notify(InAppNotification.FilesDeleted(7))
                                notifications.notify(InAppNotification.DateUpdated)
                                notifications.notify(InAppNotification.FilesImported(12, skipped = 2))
                                notifications.notify(InAppNotification.FilesExported(9, failed = 1))
                                notifications.notify(InAppNotification.ExportSuccess("late.jpg"))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Flood", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    OutlinedButton(
                        // Every one of them, in turn, so the wording and the colour of each
                        // can be looked at without arranging for it to happen.
                        onClick  = { debugNotificationWalk = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            if (debugNotificationWalk) "Walking through them..." else "Show every notification",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }

                // ── Tools ─────────────────────────────────────────────────────
                PanicSectionLabel(stringResource(R.string.settings_debug_section_tools))
                SubScreenGroup {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick  = { debugViewModel.dryRunPanic() },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.settings_debug_dry_run_panic), style = MaterialTheme.typography.labelMedium)
                        }
                        if (disguiseApplied) {
                            OutlinedButton(
                                onClick  = { viewModel.resetDisguise() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.settings_debug_reset_disguise), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    OutlinedButton(
                        onClick  = { debugViewModel.clearAllThumbnailCache() },
                        colors   = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        border   = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Force Clean Cache", style = MaterialTheme.typography.labelMedium)
                    }
                }

                // ── Mount log ─────────────────────────────────────────────────
                state.lastMountLog?.let { mountLog ->
                    PanicSectionLabel("Mount log")
                    SubScreenGroup {
                        Text(
                            text     = mountLog.trim(),
                            style    = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        Row(
                            modifier              = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick  = {
                                    debugViewModel.copyMountLogToClipboard()
                                    Toast.makeText(context, "Mount log copied", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Copy", style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick  = { debugViewModel.clearMountLog() },
                                colors   = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                border   = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Clear", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                // ── USB probe (issue #95 spike) ───────────────────────────────
                PanicSectionLabel("USB probe")
                SubScreenGroup {
                    Text(
                        text     = "Read-only feasibility check for encrypted USB drives. " +
                                   "Plug a drive in over OTG and run it. Issues no write command, " +
                                   "so the drive cannot be altered.",
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    state.usbProbeReport?.let { report ->
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        Text(
                            text     = report.trim(),
                            style    = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color    = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    Row(
                        modifier              = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick  = { debugViewModel.runUsbDriveReport() },
                            enabled  = !state.usbProbeRunning,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Drive report", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick  = { debugViewModel.runUsbProbe() },
                            enabled  = !state.usbProbeRunning,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                if (state.usbProbeRunning) "Probing..." else "Run probe",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        if (state.usbProbeReport != null) {
                            OutlinedButton(
                                onClick  = {
                                    debugViewModel.copyUsbProbeToClipboard()
                                    Toast.makeText(context, "USB probe copied", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Copy", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    OutlinedTextField(
                        value         = usbMountPassword,
                        onValueChange = { usbMountPassword = it },
                        label         = { Text("Volume password") },
                        singleLine    = true,
                        modifier      = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    OutlinedButton(
                        onClick  = { debugViewModel.runUsbMountTest(usbMountPassword) },
                        enabled  = !state.usbProbeRunning && usbMountPassword.isNotEmpty(),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text("Mount USB volume (read-only)", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(
                        onClick  = { debugViewModel.runUsbWriteLadder() },
                        enabled  = !state.usbProbeRunning,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Text("USB write ladder (finds the size limit, DESTROYS the drive)",
                             style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(
                        onClick  = { debugViewModel.runUsbEnduranceWrite() },
                        enabled  = !state.usbProbeRunning,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    ) {
                        Text("USB endurance write (64 MB, DESTROYS the drive)",
                             style = MaterialTheme.typography.labelMedium)
                    }
                    state.usbHeld?.let { held ->
                        Text(
                            text     = "Held mounted: $held",
                            style    = MaterialTheme.typography.labelMedium,
                            color    = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    }
                    Row(
                        modifier              = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick  = { debugViewModel.mountAndHoldUsb(usbMountPassword) },
                            enabled  = !state.usbProbeRunning && usbMountPassword.isNotEmpty() && state.usbHeld == null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Mount and hold", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick  = { debugViewModel.unmountHeldUsb() },
                            enabled  = state.usbHeld != null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Unmount", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    TextButton(
                        onClick  = { debugViewModel.runUsbMountWriteTest(usbMountPassword) },
                        enabled  = !state.usbProbeRunning && usbMountPassword.isNotEmpty(),
                        colors   = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text("Mount read-write + write a test file", style = MaterialTheme.typography.labelMedium)
                    }
                    TextButton(
                        onClick  = { showUsbSweepConfirm = true },
                        enabled  = !state.usbProbeRunning,
                        colors   = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text("Write throughput sweep (destroys the volume)", style = MaterialTheme.typography.labelMedium)
                    }
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    TextButton(
                        onClick  = { showUsbWriteConfirm = true },
                        enabled  = !state.usbProbeRunning,
                        colors   = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Text("Run write test (modifies the drive)", style = MaterialTheme.typography.labelMedium)
                    }
                }

                if (showUsbSweepConfirm) {
                    AppDialog(
                        onDismissRequest = { showUsbSweepConfirm = false },
                        title            = { Text("Destroy the volume on this drive?") },
                        text             = {
                            Text(
                                "This writes 16 MB of test data straight onto the device to " +
                                "measure raw write speed. It overwrites the VeraCrypt header " +
                                "and whatever is stored in the volume.\n\n" +
                                "Everything on the drive will be unrecoverable. Only run this " +
                                "on a drive you are about to reformat anyway."
                            )
                        },
                        confirmButton    = {
                            TextButton(
                                onClick = {
                                    showUsbSweepConfirm = false
                                    debugViewModel.runUsbWriteSweep()
                                },
                                colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) { Text("Destroy and measure") }
                        },
                        dismissButton    = {
                            TextButton(onClick = { showUsbSweepConfirm = false }) { Text("Cancel") }
                        }
                    )
                }

                if (showUsbWriteConfirm) {
                    AppDialog(
                        onDismissRequest = { showUsbWriteConfirm = false },
                        title            = { Text("Write to the connected drive?") },
                        text             = {
                            Text(
                                "This writes a test pattern to one sector in the unused gap between " +
                                "the partition table and the first partition, reads it back, and then " +
                                "restores the original bytes.\n\n" +
                                "It does not touch the partition table or any filesystem. Even so, it " +
                                "is a real write to a real drive - use a drive whose contents you do " +
                                "not need."
                            )
                        },
                        confirmButton    = {
                            TextButton(
                                onClick = {
                                    showUsbWriteConfirm = false
                                    debugViewModel.runUsbWriteTest()
                                },
                                colors  = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) { Text("Write") }
                        },
                        dismissButton    = {
                            TextButton(onClick = { showUsbWriteConfirm = false }) { Text("Cancel") }
                        }
                    )
                }

                // ── Crash log ─────────────────────────────────────────────────
                if (state.crashLogs.isNotEmpty()) {
                    PanicSectionLabel("Crash log")
                    SubScreenGroup {
                        state.crashLogs.forEach { log ->
                            Text(
                                text     = log.name,
                                style    = MaterialTheme.typography.labelMedium,
                                color    = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)
                            )
                            Text(
                                text     = log.content.trim(),
                                style    = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                        Row(
                            modifier              = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick  = {
                                    debugViewModel.copyCrashLogsToClipboard()
                                    Toast.makeText(context, "Crash log copied", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Copy", style = MaterialTheme.typography.labelMedium)
                            }
                            OutlinedButton(
                                onClick  = { debugViewModel.clearCrashLogs() },
                                colors   = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                border   = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Clear", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
        } // Box hazeSource
    }
    } // CompositionLocalProvider
}

@Composable
private fun DebugWarningDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val warningAmber = Color(0xFFFFA000)
    val isAmoled     = LocalAmoledMode.current
    val hazeState    = LocalHazeState.current
    val dialogShape  = RoundedCornerShape(28.dp)

    Dialog(
        onDismissRequest = onDismiss,
        properties       = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val dialogView = LocalView.current
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window
                ?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        Box(
            modifier         = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
        Column(
            modifier = if (isAmoled) {
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clip(dialogShape)
                    .hazeOrSolid(hazeState, ArcanumHazeStyle.dialog, MaterialTheme.colorScheme.surfaceVariant)
                    .border(0.5.dp, Color.White.copy(alpha = 0.12f), dialogShape)
                    .padding(24.dp)
            } else {
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .clip(dialogShape)
                    .background(MaterialTheme.colorScheme.surface, dialogShape)
                    .padding(24.dp)
            },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
                val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.warning))
                val progress    by animateLottieCompositionAsState(composition = composition, iterations = 1)
                LottieAnimation(
                    composition = composition,
                    progress    = { progress },
                    modifier    = Modifier.size(120.dp)
                )

                Spacer(Modifier.height(4.dp))

                Text(
                    text       = stringResource(R.string.settings_debug_warning_title),
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, warningAmber, RoundedCornerShape(12.dp))
                        .background(warningAmber.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                        .padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        imageVector        = Icons.Outlined.Warning,
                        contentDescription = null,
                        tint               = warningAmber,
                        modifier           = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text  = stringResource(R.string.settings_debug_warning_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(Modifier.height(20.dp))

                Button(
                    onClick  = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.common_cancel))
                }

                Spacer(Modifier.height(4.dp))

                TextButton(
                    onClick  = onConfirm,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text  = stringResource(R.string.settings_debug_warning_confirm),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
        }
        } // Box
    }
}

@Composable
private fun IconPreview(label: String, bgColor: Color, fgRes: Int, active: Boolean) {
    val borderColor = MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(bgColor)
                .then(
                    if (active) Modifier.border(2.dp, borderColor, RoundedCornerShape(14.dp))
                    else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter           = painterResource(fgRes),
                contentDescription = null,
                modifier          = Modifier.fillMaxSize(),
                tint              = Color.Unspecified
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text  = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) borderColor else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun biometricStatusColor(status: String): Color = when (status) {
    "Available"    -> Color(0xFF22C55E)
    "Not enrolled" -> Color(0xFFFFA000)
    else           -> MaterialTheme.colorScheme.error
}

@Composable
private fun DebugRow(
    label: String,
    value: String,
    valueColor: Color? = null,
    infoText: String? = null
) {
    var showInfo by remember { mutableStateOf(false) }

    if (showInfo && infoText != null) {
        AppDialog(
            onDismissRequest = { showInfo = false },
            title   = { Text(label) },
            text    = { Text(infoText, style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = { showInfo = false }) { Text(stringResource(R.string.common_ok)) }
            }
        )
    }

    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier          = Modifier.weight(1f)
        ) {
            Text(
                text  = label,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (infoText != null) {
                Spacer(Modifier.width(4.dp))
                Icon(
                    imageVector        = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.settings_debug_cd_info),
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    modifier           = Modifier
                        .size(14.dp)
                        .clickable { showInfo = true }
                )
            }
        }
        Text(
            text      = value,
            style     = MaterialTheme.typography.bodySmall,
            color     = valueColor ?: MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier  = Modifier.padding(start = 8.dp)
        )
    }
}

// ── About screen helpers ──────────────────────────────────────────────────────

/**
 * Every notification the app can raise, with plausible contents. Debug only: it exists so
 * the wording, the colour and the dwell of each can be looked at side by side instead of
 * being arranged for one at a time (#135).
 */
private fun allNotificationsForDebug(): List<InAppNotification> = listOf(
    InAppNotification.FilesPasted(4),
    InAppNotification.FilesPasted(4, leftBehind = 1),
    InAppNotification.FilesMoved(2, "Photos"),
    InAppNotification.FilesMoved(2, "Photos", skipped = 1),
    InAppNotification.FilesDeleted(3),
    InAppNotification.FilesLinked(2, InAppNotification.LinkedKind.FILES),
    InAppNotification.FilesAlreadyHere,
    InAppNotification.FolderCreated("Documents"),
    InAppNotification.FileRenamed("holiday.jpg"),
    InAppNotification.FilesImported(12),
    InAppNotification.FilesImported(12, skipped = 3),
    InAppNotification.FilesExported(9),
    InAppNotification.FilesExported(9, skipped = 2, duplicates = 1),
    InAppNotification.FilesExported(9, failed = 1),
    InAppNotification.ExportSuccess("holiday.jpg"),
    InAppNotification.DateUpdated,
    InAppNotification.AddressCopied("Monero"),
    InAppNotification.VaultAdded("vault.hc"),
    InAppNotification.VaultAlreadyExists("vault.hc"),
    InAppNotification.VaultNeedsCheck,
    InAppNotification.UsbSafeToRemove("id", "USB vault"),
    InAppNotification.DetailsNeedMount,
    InAppNotification.MountNeedsCredentials,
    InAppNotification.DisguiseAlreadyApplied,
    InAppNotification.OperationRefusedLocked,
    InAppNotification.HiddenVolumeWriteProtection,
    InAppNotification.FilesPasteFailed(2, 5),
    InAppNotification.ImportFailed(ImportFailureReason.NO_SPACE),
    InAppNotification.ImportFailed(ImportFailureReason.TOO_FRAGMENTED),
    InAppNotification.ReadOnlyError,
    InAppNotification.VaultError("id", "Could not open the volume"),
    InAppNotification.VaultInvalidFile,
    InAppNotification.VaultAddError("Not a container"),
    InAppNotification.AppUpdated,
    InAppNotification.SupportDeveloper
)
