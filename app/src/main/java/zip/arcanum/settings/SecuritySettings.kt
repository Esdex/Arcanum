package zip.arcanum.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.fragment.app.FragmentActivity
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import androidx.compose.ui.res.stringResource
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.notifications.LocalNotifications
import zip.arcanum.R
import androidx.compose.foundation.layout.navigationBarsPadding
import zip.arcanum.core.components.SettingsRow
import zip.arcanum.core.components.SettingsSwitch
import androidx.compose.material3.Slider
import zip.arcanum.core.components.SettingsGroup
import zip.arcanum.core.components.GroupedRow
import zip.arcanum.core.components.GroupedSwitch
import zip.arcanum.core.components.GroupedBox

// Settings / Security: the PIN, biometrics, auto-lock, screenshots and the disguise.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SecuritySubScreen(
    autoLockEnabled: Boolean,
    onAutoLockChange: (Boolean) -> Unit,
    autoLockDelayIndex: Int,
    onAutoLockDelayChange: (Int) -> Unit,
    unmountOnAutoLock: Boolean,
    onUnmountOnAutoLockChange: (Boolean) -> Unit,
    screenCaptureProtection: Boolean,
    disguiseApplied: Boolean,
    onBack: () -> Unit,
    onChangePin: () -> Unit,
    viewModel: SettingsViewModel
) {
    val context     = LocalContext.current
    var showWarning by remember { mutableStateOf(false) }
    var showKeepMountedWarning by remember { mutableStateOf(false) }
    val receiveShares by viewModel.receiveShares.collectAsState()
    val mediaSessionContent by viewModel.mediaSessionContent.collectAsState()
    val argon2Offer         by viewModel.argon2Offer.collectAsState()
    val keepVaultsMounted   by viewModel.keepVaultsMounted.collectAsState()
    val notifications = LocalNotifications.current

    SubScreenScaffold(title = stringResource(R.string.settings_security_title), onBack = onBack) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
        ) {
            // ── Getting in ───────────────────────────────────────────────
            SettingsGroup(title = stringResource(R.string.settings_security_group_unlock)) {
                row { shape ->
                    GroupedRow(
                        shape   = shape,
                        title   = stringResource(R.string.settings_security_change_pin),
                        onClick = onChangePin
                    )
                }
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_security_auto_lock),
                        subtitle        = stringResource(R.string.settings_security_auto_lock_desc),
                        checked         = autoLockEnabled,
                        onCheckedChange = onAutoLockChange
                    )
                }
                // The delay and what auto-lock does to a mounted vault only exist while it is
                // on, so they are absent rather than greyed - a group counts the rows it has.
                if (autoLockEnabled) {
                    row { shape ->
                        val delayLabels = listOf(
                            stringResource(R.string.settings_auto_lock_immediately),
                            "30 ${stringResource(R.string.settings_auto_lock_seconds)}",
                            "1 ${stringResource(R.string.settings_auto_lock_minute)}",
                            "2 ${stringResource(R.string.settings_auto_lock_minutes)}",
                            "5 ${stringResource(R.string.settings_auto_lock_minutes)}",
                            "10 ${stringResource(R.string.settings_auto_lock_minutes)}",
                            "30 ${stringResource(R.string.settings_auto_lock_minutes)}",
                            "1 ${stringResource(R.string.settings_auto_lock_hour)}"
                        )
                        GroupedBox(shape) {
                            Row(
                                modifier              = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment     = Alignment.CenterVertically
                            ) {
                                Text(
                                    text  = stringResource(R.string.settings_auto_lock_delay),
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    text  = delayLabels[autoLockDelayIndex],
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Slider(
                                value         = autoLockDelayIndex.toFloat(),
                                onValueChange = { onAutoLockDelayChange(it.toInt()) },
                                valueRange    = 0f..7f,
                                steps         = 6,
                                modifier      = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    row { shape ->
                        GroupedSwitch(
                            shape           = shape,
                            title           = stringResource(R.string.settings_security_unmount_on_auto_lock),
                            subtitle        = stringResource(R.string.settings_security_unmount_on_auto_lock_desc),
                            checked         = unmountOnAutoLock,
                            onCheckedChange = onUnmountOnAutoLockChange
                        )
                    }
                }
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_security_argon2_offer),
                        info            = stringResource(R.string.settings_security_argon2_offer_desc),
                        checked         = argon2Offer,
                        onCheckedChange = { viewModel.setArgon2Offer(it) }
                    )
                }
            }

            // ── While a vault is open ────────────────────────────────────
            SettingsGroup(title = stringResource(R.string.settings_security_group_mounted)) {
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_security_keep_mounted),
                        info            = stringResource(R.string.settings_security_keep_mounted_desc),
                        checked         = keepVaultsMounted,
                        onCheckedChange = { on ->
                            // Turning it OFF needs no warning, and neither does turning it on
                            // when the app is not pretending to be anything: the panel naming
                            // Arcanum only costs something to someone relying on the disguise.
                            if (on && disguiseApplied) showKeepMountedWarning = true
                            else viewModel.setKeepVaultsMounted(on)
                        }
                    )
                }
            }

            // ── On this device ───────────────────────────────────────────
            SettingsGroup(title = stringResource(R.string.settings_security_group_device)) {
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_security_screen_capture),
                        info            = stringResource(R.string.settings_security_screen_capture_desc),
                        checked         = screenCaptureProtection,
                        onCheckedChange = { enabled ->
                            if (!enabled) showWarning = true
                            else viewModel.setScreenCaptureProtection(true)
                        }
                    )
                }
                row { shape ->
                    // Applied once, it cannot be taken back without reinstalling - so the row
                    // greys out, and a tap on it says why rather than doing nothing. The row
                    // takes that tap itself rather than an overlay on top of it, which would
                    // swallow every tap in the row including the ones meant for what is in it.
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_security_disguise_title),
                        info            = stringResource(R.string.settings_security_disguise_desc),
                        checked         = disguiseApplied,
                        enabled         = !disguiseApplied,
                        onCheckedChange = { viewModel.requestDisguise() },
                        onDisabledClick = {
                            notifications.notify(InAppNotification.DisguiseAlreadyApplied)
                        }
                    )
                }
            }

            // ── What other apps may see ──────────────────────────────────
            SettingsGroup(title = stringResource(R.string.settings_security_group_apps)) {
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_security_receive_shares),
                        info            = stringResource(R.string.settings_security_receive_shares_desc),
                        checked         = receiveShares,
                        onCheckedChange = { viewModel.setReceiveShares(it) }
                    )
                }
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_security_media_content),
                        info            = stringResource(R.string.settings_security_media_content_desc),
                        checked         = mediaSessionContent,
                        onCheckedChange = { viewModel.setMediaSessionContent(it) }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showKeepMountedWarning) {
        KeepMountedWarningOverlay(
            onDismiss = { showKeepMountedWarning = false },
            onConfirm = {
                viewModel.setKeepVaultsMounted(true)
                showKeepMountedWarning = false
            }
        )
    }

    if (showWarning) {
        ScreenshotWarningOverlay(
            viewModel   = viewModel,
            onDismiss   = { showWarning = false },
            onConfirmed = { viewModel.setScreenCaptureProtection(false); showWarning = false }
        )
    }
}

@Composable
private fun ScreenshotWarningOverlay(
    viewModel: SettingsViewModel,
    onDismiss: () -> Unit,
    onConfirmed: () -> Unit
) {
    val context         = LocalContext.current
    val activity        = context as FragmentActivity
    val scope           = rememberCoroutineScope()
    var countdown       by remember { mutableIntStateOf(5) }
    var showPinFallback by remember { mutableStateOf(false) }
    var pinInput        by remember { mutableStateOf("") }
    var pinError        by remember { mutableStateOf(false) }
    var verifying       by remember { mutableStateOf(false) }

    val authTitle    = stringResource(R.string.settings_security_screenshot_auth_title)
    val authSubtitle = stringResource(R.string.settings_security_screenshot_auth_subtitle)

    LaunchedEffect(Unit) {
        while (countdown > 0) { delay(1_000); countdown-- }
    }

    fun triggerAuth() {
        viewModel.authenticateForScreenshotDisable(
            activity      = activity,
            title         = authTitle,
            subtitle      = authSubtitle,
            onSuccess     = onConfirmed,
            onError       = { _, _ -> },
            onNoDeviceLock = { showPinFallback = true }
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows  = false,
            dismissOnBackPress      = true,
            dismissOnClickOutside   = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color    = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(1f))

                val lottieComposition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.warning))
                val lottieProgress    by animateLottieCompositionAsState(composition = lottieComposition, iterations = 1)
                LottieAnimation(
                    composition = lottieComposition,
                    progress    = { lottieProgress },
                    modifier    = Modifier.size(140.dp)
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text       = stringResource(R.string.settings_security_screenshot_warn_title),
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center
                )

                Spacer(Modifier.height(20.dp))

                Surface(
                    shape    = RoundedCornerShape(16.dp),
                    color    = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text     = stringResource(R.string.settings_security_screenshot_warn_body),
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                if (showPinFallback) {
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value               = pinInput,
                        onValueChange       = { pinInput = it; pinError = false },
                        label               = { Text(stringResource(R.string.settings_security_screenshot_pin_label)) },
                        isError             = pinError,
                        supportingText      = if (pinError) { { Text(stringResource(R.string.settings_security_screenshot_pin_error)) } } else null,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions     = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine          = true,
                        modifier            = Modifier.fillMaxWidth()
                    )
                }

                Spacer(Modifier.weight(1f))
                Spacer(Modifier.height(16.dp))

                if (!showPinFallback) {
                    Button(
                        onClick  = { triggerAuth() },
                        enabled  = countdown == 0,
                        colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (countdown > 0)
                                stringResource(R.string.settings_security_screenshot_warn_disable, countdown)
                            else
                                stringResource(R.string.settings_security_screenshot_warn_disable_ready)
                        )
                    }
                } else {
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        OutlinedButton(
                            onClick  = { showPinFallback = false; pinInput = ""; pinError = false },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.common_back))
                        }
                        Button(
                            onClick  = {
                                scope.launch {
                                    verifying = true
                                    val ok = viewModel.verifyPin(pinInput)
                                    if (ok) onConfirmed() else pinError = true
                                    verifying = false
                                }
                            },
                            enabled  = pinInput.isNotEmpty() && !verifying,
                            colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f)
                        ) {
                            if (verifying) {
                                CircularProgressIndicator(
                                    modifier    = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color       = MaterialTheme.colorScheme.onError
                                )
                            } else {
                                Text(stringResource(R.string.settings_security_screenshot_warn_disable_ready))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
            }
        }
    }
}

/**
 * What keeping a vault open costs someone wearing the disguise.
 *
 * A full screen rather than a dialog because it is the same kind of thing the screenshot
 * warning is: a setting that cannot be undone by turning it back off - once the shade has
 * named the app to someone, it has named it. It is shown only when the disguise is applied;
 * with no disguise on there is nothing to give away.
 */
@Composable
private fun KeepMountedWarningOverlay(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.warning))
    val progress    by animateLottieCompositionAsState(composition = composition, iterations = 1)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows  = false,
            dismissOnBackPress      = true,
            dismissOnClickOutside   = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color    = MaterialTheme.colorScheme.background
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            ) {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        // The buttons are pinned to the bottom of the same Box, so centring
                        // this on the whole screen leaves the card resting on them. The
                        // padding is what the buttons occupy: the content is centred in what
                        // is left rather than in the screen.
                        .padding(horizontal = 28.dp)
                        .padding(bottom = 180.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LottieAnimation(
                        composition = composition,
                        progress    = { progress },
                        modifier    = Modifier.size(160.dp)
                    )

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text      = stringResource(R.string.settings_security_keep_mounted_warn_title),
                        style     = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        color     = MaterialTheme.colorScheme.onBackground
                    )

                    Spacer(Modifier.height(20.dp))

                    Surface(
                        shape    = RoundedCornerShape(16.dp),
                        color    = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text     = stringResource(R.string.settings_security_keep_mounted_warn_body),
                            style    = MaterialTheme.typography.bodyMedium,
                            color    = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick  = onConfirm,
                        colors   = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.settings_security_keep_mounted_warn_confirm))
                    }
                    TextButton(onClick = onDismiss) {
                        Text(
                            text  = stringResource(R.string.common_cancel),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DisguiseOverlay(onApply: () -> Unit, onMaybeLater: () -> Unit) {
    BackHandler(enabled = true) { /* non-dismissable */ }

    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.shield))
    val progress    by animateLottieCompositionAsState(composition = composition, iterations = 1)

    Dialog(
        onDismissRequest = { /* non-dismissable */ },
        properties = DialogProperties(
            usePlatformDefaultWidth  = false,
            decorFitsSystemWindows   = false,
            dismissOnBackPress       = false,
            dismissOnClickOutside    = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color    = MaterialTheme.colorScheme.background
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
            ) {
                Column(
                    modifier            = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    LottieAnimation(
                        composition = composition,
                        progress    = { progress },
                        modifier    = Modifier.size(160.dp)
                    )

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text      = stringResource(R.string.disguise_overlay_title),
                        style     = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                        color     = MaterialTheme.colorScheme.onBackground
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        text      = stringResource(R.string.disguise_overlay_body),
                        style     = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Column(
                    modifier            = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick  = onApply,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.disguise_overlay_apply))
                    }
                    TextButton(onClick = onMaybeLater) {
                        Text(
                            text  = stringResource(R.string.common_cancel),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
