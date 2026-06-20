package zip.arcanum.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import android.view.ViewGroup
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.EaseInOutCubic
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.scale
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
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.Switch
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import zip.arcanum.BuildConfig
import zip.arcanum.R
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.components.AppSheet
import zip.arcanum.core.components.LocalHazeState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.rememberModalBottomSheetState
import zip.arcanum.core.components.SettingsRow
import zip.arcanum.core.components.SettingsSwitch
import zip.arcanum.core.database.entities.ContainerEntity
import zip.arcanum.core.security.VaultPanicAction
import zip.arcanum.core.theme.ArcanumHazeStyle
import zip.arcanum.core.theme.LocalAmoledMode
import zip.arcanum.core.theme.LocalDarkMode
import zip.arcanum.core.theme.LocalDynamicColor
import zip.arcanum.core.theme.ThemeMode
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.core.os.LocaleListCompat

private data class AppLanguage(val tag: String, val nativeName: String)

private val SUPPORTED_LANGUAGES = listOf(
    AppLanguage("",      "System default"),
    AppLanguage("en",    "English"),
    AppLanguage("de",    "Deutsch"),
    AppLanguage("es",    "Español"),
    AppLanguage("fr",    "Français"),
    AppLanguage("it",    "Italiano"),
    AppLanguage("ja",    "日本語"),
    AppLanguage("ko",    "한국어"),
    AppLanguage("pl",    "Polski"),
    AppLanguage("pt",    "Português"),
    AppLanguage("ru",    "Русский"),
    AppLanguage("tr",    "Türkçe"),
    AppLanguage("uk",    "Українська"),
    AppLanguage("zh-CN", "简体中文"),
    AppLanguage("zh-TW", "繁體中文")
)

private enum class SubScreen {
    SECURITY, CHANGE_PIN, PANIC_MODE, SET_PANIC_PIN, APPEARANCE, ABOUT, LICENSES, WHATS_NEW, PREMIUM, DEBUG
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack:       () -> Unit = {},
    viewModel:    SettingsViewModel = hiltViewModel(),
    panicViewModel: PanicModeViewModel = hiltViewModel()
) {
    var subScreen           by remember { mutableStateOf<SubScreen?>(null) }
    val autoLockEnabled         by viewModel.autoLockEnabled.collectAsState()
    val autoLockDelayIndex      by viewModel.autoLockDelayIndex.collectAsState()
    val themeMode               by viewModel.themeMode.collectAsState()
    val isAmoledGlass           by viewModel.isAmoledGlass.collectAsState()
    val isDynamicColor          by viewModel.isDynamicColor.collectAsState()
    val screenCaptureProtection by viewModel.screenCaptureProtection.collectAsState()
    val disguiseApplied         by viewModel.disguiseApplied.collectAsState()
    val debugMode               by viewModel.debugMode.collectAsState()
    BackHandler(enabled = subScreen != null) {
        subScreen = when (subScreen) {
            SubScreen.SET_PANIC_PIN -> SubScreen.PANIC_MODE
            SubScreen.CHANGE_PIN    -> SubScreen.SECURITY
            SubScreen.LICENSES      -> SubScreen.ABOUT
            SubScreen.WHATS_NEW     -> SubScreen.ABOUT
            else                    -> null
        }
    }

    AnimatedContent(
        targetState  = subScreen,
        transitionSpec = {
            val spec = tween<IntOffset>(350, easing = EaseInOutCubic)
            val initialOrd = initialState?.ordinal ?: -1
            val targetOrd  = targetState?.ordinal  ?: -1
            if (targetOrd > initialOrd) {
                slideInHorizontally(spec) { it } togetherWith slideOutHorizontally(spec) { -it }
            } else {
                slideInHorizontally(spec) { -it } togetherWith slideOutHorizontally(spec) { it }
            }
        },
        label = "settings_nav"
    ) { screen ->
        when (screen) {
            SubScreen.SECURITY -> SecuritySubScreen(
                autoLockEnabled         = autoLockEnabled,
                onAutoLockChange        = { viewModel.setAutoLock(it) },
                autoLockDelayIndex      = autoLockDelayIndex,
                onAutoLockDelayChange   = { viewModel.setAutoLockDelayIndex(it) },
                screenCaptureProtection = screenCaptureProtection,
                disguiseApplied         = disguiseApplied,
                onBack                  = { subScreen = null },
                onChangePin             = { subScreen = SubScreen.CHANGE_PIN },
                viewModel               = viewModel
            )
            SubScreen.PANIC_MODE -> PanicModeSubScreen(
                onBack        = { subScreen = null },
                onSetPanicPin = { subScreen = SubScreen.SET_PANIC_PIN },
                viewModel     = panicViewModel
            )
            SubScreen.SET_PANIC_PIN -> SetPanicPinScreen(
                onBack    = { subScreen = SubScreen.PANIC_MODE },
                onSuccess = {
                    panicViewModel.setEnabled(true)
                    subScreen = SubScreen.PANIC_MODE
                }
            )
            SubScreen.APPEARANCE -> AppearanceSubScreen(
                themeMode      = themeMode,
                isAmoledGlass  = isAmoledGlass,
                isDynamicColor = isDynamicColor,
                onThemeMode    = { viewModel.setThemeMode(it) },
                onAmoledGlass  = { viewModel.setAmoledGlass(it) },
                onDynamicColor = { viewModel.setDynamicColor(it) },
                onBack         = { subScreen = null }
            )
            SubScreen.CHANGE_PIN -> ChangePinScreen(onBack = { subScreen = null })
            SubScreen.ABOUT     -> AboutSubScreen(
                onBack          = { subScreen = null },
                onLicenses      = { subScreen = SubScreen.LICENSES },
                onWhatsNew      = { subScreen = SubScreen.WHATS_NEW },
                viewModel       = viewModel,
                onDebugUnlocked = { subScreen = SubScreen.DEBUG }
            )
            SubScreen.LICENSES  -> LicensesScreen(onBack = { subScreen = SubScreen.ABOUT })
            SubScreen.WHATS_NEW -> WhatsNewSubScreen(onBack = { subScreen = SubScreen.ABOUT })
            SubScreen.PREMIUM -> PremiumSubScreen(onBack = { subScreen = null })
            SubScreen.DEBUG   -> DebugSubScreen(
                viewModel = viewModel,
                onBack    = { subScreen = null }
            )
            null              -> MainSettingsScreen(
                onBack     = onBack,
                onNavigate = { subScreen = it },
                debugMode  = debugMode
            )
        }
    }
}

// ── Main card list ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainSettingsScreen(
    onBack: () -> Unit,
    onNavigate: (SubScreen) -> Unit,
    debugMode: Boolean
) {
    val isDynamic = LocalDynamicColor.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SettingsCard(
                title     = stringResource(R.string.settings_card_security),
                subtitle  = stringResource(R.string.settings_card_security_desc),
                icon      = Icons.Outlined.Security,
                rawColor  = Color(0xFFF44336),
                isDynamic = isDynamic,
                onClick   = { onNavigate(SubScreen.SECURITY) }
            )
            SettingsCard(
                title     = stringResource(R.string.settings_card_panic),
                subtitle  = stringResource(R.string.settings_card_panic_desc),
                icon      = Icons.Outlined.Warning,
                rawColor  = Color(0xFFFF5722),
                isDynamic = isDynamic,
                onClick   = { onNavigate(SubScreen.PANIC_MODE) }
            )
            SettingsCard(
                title     = stringResource(R.string.settings_card_appearance),
                subtitle  = stringResource(R.string.settings_card_appearance_desc),
                icon      = Icons.Outlined.Palette,
                rawColor  = Color(0xFF673AB7),
                isDynamic = isDynamic,
                onClick   = { onNavigate(SubScreen.APPEARANCE) }
            )
            SettingsCard(
                title     = stringResource(R.string.settings_card_about),
                subtitle  = stringResource(R.string.settings_card_about_desc),
                icon      = Icons.Outlined.Info,
                rawColor  = Color(0xFF607D8B),
                isDynamic = isDynamic,
                onClick   = { onNavigate(SubScreen.ABOUT) }
            )
            if (!BuildConfig.IS_FDROID) {
                SettingsCard(
                    title     = stringResource(R.string.settings_card_premium),
                    subtitle  = stringResource(R.string.settings_card_premium_desc),
                    icon      = Icons.Outlined.Stars,
                    rawColor  = Color(0xFFFFC107),
                    isDynamic = isDynamic,
                    onClick   = { onNavigate(SubScreen.PREMIUM) }
                )
            }
            if (debugMode) {
                SettingsCard(
                    title     = stringResource(R.string.settings_card_debug),
                    subtitle  = stringResource(R.string.settings_card_debug_desc),
                    icon      = Icons.Outlined.BugReport,
                    rawColor  = Color(0xFF009688),
                    isDynamic = isDynamic,
                    onClick   = { onNavigate(SubScreen.DEBUG) }
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    rawColor: Color,
    isDynamic: Boolean,
    onClick: () -> Unit
) {
    val iconColor         = if (isDynamic) MaterialTheme.colorScheme.primary else rawColor
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed         by interactionSource.collectIsPressedAsState()
    val scale             by animateFloatAsState(
        targetValue   = if (isPressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "card_scale"
    )

    Card(
        onClick           = onClick,
        interactionSource = interactionSource,
        shape             = RoundedCornerShape(16.dp),
        colors            = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation         = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier          = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .scale(scale)
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier          = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment  = Alignment.Center
            ) {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    tint               = iconColor,
                    modifier           = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text       = title,
                    style      = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text  = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector        = Icons.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── Sub-screen helpers ────────────────────────────────────────────────────────

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        content()
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SubScreenScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (innerPadding: PaddingValues) -> Unit
) {
    val isAmoled  = LocalAmoledMode.current
    val hazeState = remember { HazeState() }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title          = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    },
                    colors   = if (isAmoled) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                               else TopAppBarDefaults.topAppBarColors(),
                    modifier = if (isAmoled) Modifier.hazeEffect(state = hazeState, style = ArcanumHazeStyle.topBar)
                               else Modifier
                )
            }
        ) { innerPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
            ) {
                content(innerPadding)
            }
        }
    }
}

// ── Sub-screens ───────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecuritySubScreen(
    autoLockEnabled: Boolean,
    onAutoLockChange: (Boolean) -> Unit,
    autoLockDelayIndex: Int,
    onAutoLockDelayChange: (Int) -> Unit,
    screenCaptureProtection: Boolean,
    disguiseApplied: Boolean,
    onBack: () -> Unit,
    onChangePin: () -> Unit,
    viewModel: SettingsViewModel
) {
    val context     = LocalContext.current
    var showWarning by remember { mutableStateOf(false) }

    SubScreenScaffold(title = stringResource(R.string.settings_security_title), onBack = onBack) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            SettingsGroup {
                SettingsRow(
                    title   = stringResource(R.string.settings_security_change_pin),
                    onClick = onChangePin
                )
                SettingsSwitch(
                    title           = stringResource(R.string.settings_security_auto_lock),
                    subtitle        = stringResource(R.string.settings_security_auto_lock_desc),
                    checked         = autoLockEnabled,
                    onCheckedChange = onAutoLockChange
                )
                AnimatedVisibility(visible = autoLockEnabled) {
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
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                    ) {
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Text(
                                text  = stringResource(R.string.settings_auto_lock_delay),
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text  = delayLabels[autoLockDelayIndex],
                                style = MaterialTheme.typography.bodyMedium,
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
                SettingsSwitch(
                    title           = stringResource(R.string.settings_security_screen_capture),
                    subtitle        = stringResource(R.string.settings_security_screen_capture_desc),
                    checked         = screenCaptureProtection,
                    onCheckedChange = { enabled ->
                        if (!enabled) showWarning = true
                        else viewModel.setScreenCaptureProtection(true)
                    }
                )
                val disguiseToast = stringResource(R.string.settings_security_disguise_toast)
                Box {
                    SettingsSwitch(
                        title           = stringResource(R.string.settings_security_disguise_title),
                        subtitle        = stringResource(R.string.settings_security_disguise_desc),
                        checked         = disguiseApplied,
                        enabled         = !disguiseApplied,
                        onCheckedChange = { viewModel.requestDisguise() }
                    )
                    if (disguiseApplied) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication        = null
                                ) {
                                    Toast.makeText(context, disguiseToast, Toast.LENGTH_SHORT).show()
                                }
                        )
                    }
                }
            }
        }
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

@Composable
fun DisguiseOverlay(onApply: () -> Unit) {
    BackHandler(enabled = true) { /* non-dismissable */ }

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
            Column(
                modifier            = Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 28.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.weight(1f))

                Icon(
                    imageVector        = Icons.Outlined.Security,
                    contentDescription = null,
                    modifier           = Modifier.size(72.dp),
                    tint               = MaterialTheme.colorScheme.primary
                )

                Text(
                    text      = stringResource(R.string.disguise_overlay_title),
                    style     = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    color     = MaterialTheme.colorScheme.onBackground
                )

                Text(
                    text      = stringResource(R.string.disguise_overlay_body),
                    style     = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.weight(1f))

                Button(
                    onClick  = onApply,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.disguise_overlay_apply))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PanicModeSubScreen(
    onBack: () -> Unit,
    onSetPanicPin: () -> Unit,
    viewModel: PanicModeViewModel
) {
    val settings   by viewModel.settings.collectAsState()
    val containers by viewModel.containers.collectAsState()

    var showDisableDialog by remember { mutableStateOf(false) }

    if (showDisableDialog) {
        AppDialog(
            onDismissRequest = { showDisableDialog = false },
            title   = { Text(stringResource(R.string.settings_panic_disable_title)) },
            text    = { Text(stringResource(R.string.settings_panic_disable_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.disablePanicMode()
                    showDisableDialog = false
                }) {
                    Text(stringResource(R.string.settings_panic_disable_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    SubScreenScaffold(title = stringResource(R.string.settings_panic_title), onBack = onBack) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            SettingsGroup {
                SettingsSwitch(
                    title           = stringResource(R.string.settings_panic_switch_title),
                    subtitle        = if (settings.enabled) stringResource(R.string.settings_panic_switch_desc)
                                      else stringResource(R.string.settings_panic_setup_hint),
                    checked         = settings.enabled,
                    onCheckedChange = { enabling ->
                        if (enabling) onSetPanicPin()
                        else showDisableDialog = true
                    }
                )
            }

            // Everything below only visible when enabled
            AnimatedVisibility(visible = settings.enabled) {
                Column {
                    PanicSectionLabel(stringResource(R.string.settings_panic_pin_section))
                    SettingsGroup {
                        SettingsRow(title = stringResource(R.string.settings_panic_change_pin), onClick = onSetPanicPin)
                    }

                    PanicSectionLabel(stringResource(R.string.settings_panic_wipe_section))
                    SettingsGroup {
                        SettingsSwitch(
                            title           = stringResource(R.string.settings_panic_full_wipe),
                            subtitle        = stringResource(R.string.settings_panic_full_wipe_desc),
                            checked         = settings.fullWipe,
                            onCheckedChange = { viewModel.setFullWipe(it) }
                        )
                    }

                    SettingsGroup {
                        SettingsSwitch(
                            title           = stringResource(R.string.settings_panic_clear_settings),
                            checked         = settings.clearSettings,
                            onCheckedChange = { viewModel.setClearSettings(it) },
                            enabled         = !settings.fullWipe
                        )
                        SettingsSwitch(
                            title           = stringResource(R.string.settings_panic_clear_history),
                            checked         = settings.clearCalculatorHistory,
                            onCheckedChange = { viewModel.setClearHistory(it) },
                            enabled         = !settings.fullWipe
                        )
                        SettingsSwitch(
                            title           = stringResource(R.string.settings_panic_disable_biometric),
                            subtitle        = stringResource(R.string.settings_panic_disable_biometric_desc),
                            checked         = settings.disableBiometric,
                            onCheckedChange = { viewModel.setDisableBiometric(it) },
                            enabled         = !settings.fullWipe
                        )
                    }

                    if (containers.isNotEmpty() && !settings.fullWipe) {
                        PanicSectionLabel(stringResource(R.string.settings_panic_vaults_section))
                        SettingsGroup {
                            containers.forEach { container ->
                                VaultPanicRow(
                                    container = container,
                                    action    = settings.vaultActions[container.id] ?: VaultPanicAction.KEEP,
                                    onChange  = { viewModel.setVaultAction(container.id, it) }
                                )
                            }
                        }
                    }

                    Text(
                        text     = stringResource(R.string.settings_panic_warning),
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PanicSectionLabel(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelSmall,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultPanicRow(
    container: ContainerEntity,
    action: VaultPanicAction,
    onChange: (VaultPanicAction) -> Unit
) {
    val options = listOf(VaultPanicAction.DELETE, VaultPanicAction.FORGET, VaultPanicAction.KEEP)
    val labels  = listOf(
        stringResource(R.string.settings_panic_vault_delete),
        stringResource(R.string.settings_panic_vault_forget),
        stringResource(R.string.settings_panic_vault_keep)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text  = container.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, opt ->
                SegmentedButton(
                    selected = action == opt,
                    onClick  = { onChange(opt) },
                    shape    = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label    = { Text(labels[index], style = MaterialTheme.typography.labelMedium) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSubScreen(
    themeMode: ThemeMode,
    isAmoledGlass: Boolean,
    isDynamicColor: Boolean,
    onThemeMode: (ThemeMode) -> Unit,
    onAmoledGlass: (Boolean) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.DARK   -> true
        ThemeMode.LIGHT  -> false
    }
    val themeModes  = ThemeMode.entries
    val themeLabels = listOf(
        stringResource(R.string.settings_appearance_theme_system),
        stringResource(R.string.settings_appearance_theme_light),
        stringResource(R.string.settings_appearance_theme_dark)
    )
    var showLanguagePicker by remember { mutableStateOf(false) }
    val systemDefault      = stringResource(R.string.settings_appearance_language_system)
    val currentLocaleTag = remember {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) "" else locales[0]?.toLanguageTag() ?: ""
    }
    val currentLanguageName = remember(currentLocaleTag) {
        SUPPORTED_LANGUAGES.find { it.tag == currentLocaleTag }?.nativeName ?: ""
    }.ifEmpty { systemDefault }

    SubScreenScaffold(title = stringResource(R.string.settings_appearance_title), onBack = onBack) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            PanicSectionLabel(stringResource(R.string.settings_appearance_theme_section))
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    themeModes.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick  = { onThemeMode(mode) },
                            shape    = SegmentedButtonDefaults.itemShape(index = index, count = themeModes.size),
                            label    = { Text(themeLabels[index], style = MaterialTheme.typography.labelMedium) }
                        )
                    }
                }
            }

            PanicSectionLabel(stringResource(R.string.settings_appearance_display_section))
            AnimatedVisibility(
                visible = isDark,
                enter   = expandVertically(),
                exit    = shrinkVertically()
            ) {
                AppearanceSwitch(
                    icon            = Icons.Outlined.Contrast,
                    iconColor       = Color(0xFF5C6BC0),
                    title           = stringResource(R.string.settings_appearance_amoled),
                    subtitle        = stringResource(R.string.settings_appearance_amoled_desc),
                    checked         = isAmoledGlass,
                    onCheckedChange = onAmoledGlass
                )
            }
            AppearanceSwitch(
                icon            = Icons.Outlined.Palette,
                iconColor       = Color(0xFF673AB7),
                title           = stringResource(R.string.settings_appearance_dynamic_colors),
                subtitle        = stringResource(R.string.settings_appearance_dynamic_colors_desc),
                checked         = isDynamicColor,
                onCheckedChange = onDynamicColor
            )
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Text(
                    text     = stringResource(R.string.settings_appearance_dynamic_colors_unavailable),
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            PanicSectionLabel(stringResource(R.string.settings_appearance_language_section))
            AppearanceNavRow(
                icon      = Icons.Outlined.Language,
                iconColor = Color(0xFF00897B),
                title     = stringResource(R.string.settings_appearance_language),
                subtitle  = currentLanguageName,
                onClick   = { showLanguagePicker = true }
            )
        }

        // Inside SubScreenScaffold lambda so LocalHazeState from SubScreenScaffold is in scope
        if (showLanguagePicker) {
            LanguagePickerSheet(
                currentTag  = currentLocaleTag,
                systemLabel = systemDefault,
                onSelect    = { tag ->
                    val list = if (tag.isEmpty())
                        LocaleListCompat.getEmptyLocaleList()
                    else
                        LocaleListCompat.forLanguageTags(tag)
                    AppCompatDelegate.setApplicationLocales(list)
                    showLanguagePicker = false
                },
                onDismiss = { showLanguagePicker = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePickerSheet(
    currentTag: String,
    systemLabel: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AppSheet(
        onDismissRequest = onDismiss,
        sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Text(
            text       = stringResource(R.string.settings_appearance_language),
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
        )
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SUPPORTED_LANGUAGES.forEach { lang ->
                val label = if (lang.tag.isEmpty()) systemLabel else lang.nativeName
                Row(
                    modifier          = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(lang.tag) }
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = currentTag == lang.tag, onClick = { onSelect(lang.tag) })
                    Text(
                        text     = label,
                        style    = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
            Spacer(Modifier.navigationBarsPadding())
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun AppearanceNavRow(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier         = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = iconColor,
                modifier           = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector        = Icons.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AppearanceSwitch(
    icon: ImageVector,
    iconColor: Color,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier         = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(iconColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = iconColor,
                modifier           = Modifier.size(20.dp)
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text  = title,
                style = MaterialTheme.typography.bodyLarge
            )
            if (subtitle != null) {
                Text(
                    text  = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked         = checked,
            onCheckedChange = onCheckedChange,
            enabled         = enabled
        )
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AboutSubScreen(
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    onWhatsNew: () -> Unit,
    viewModel: SettingsViewModel,
    onDebugUnlocked: () -> Unit
) {
    val context   = LocalContext.current
    val activity  = context as FragmentActivity
    val isAmoled  = LocalAmoledMode.current
    val hazeState = remember { HazeState() }
    val haptic       = LocalHapticFeedback.current
    val debugMode    by viewModel.debugMode.collectAsState()
    var tapCount     by remember { mutableIntStateOf(0) }
    var tapMessage   by remember { mutableStateOf("") }
    var showTapHint  by remember { mutableStateOf(false) }
    var tapTrigger   by remember { mutableIntStateOf(0) }
    val totalTaps    = 6

    LaunchedEffect(tapTrigger) {
        if (tapTrigger > 0) {
            showTapHint = true
            delay(550)
            showTapHint = false
        }
    }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
    Scaffold(
        topBar = {
            TopAppBar(
                title          = { Text(stringResource(R.string.settings_about_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors   = if (isAmoled) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                           else TopAppBarDefaults.topAppBarColors(),
                modifier = if (isAmoled) Modifier.hazeEffect(state = hazeState, style = ArcanumHazeStyle.topBar)
                           else Modifier
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .hazeSource(hazeState)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top    = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 8.dp
            )
        ) {
            // ── Hero ──────────────────────────────────────────────────────
            item {
                Column(
                    modifier            = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AsyncImage(
                        model              = R.mipmap.ic_launcher_round,
                        contentDescription = null,
                        modifier           = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .clickable {
                                if (debugMode) return@clickable
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                tapCount++
                                if (tapCount >= totalTaps) {
                                    tapCount = 0
                                    showTapHint = false
                                    viewModel.authenticateForDebug(
                                        activity  = activity,
                                        onSuccess = {
                                            viewModel.setDebugMode(true)
                                            Toast.makeText(context, context.getString(R.string.settings_about_debug_enabled), Toast.LENGTH_SHORT).show()
                                            onDebugUnlocked()
                                        },
                                        onError   = { _, _ -> }
                                    )
                                } else {
                                    val remaining = totalTaps - tapCount
                                    tapMessage = context.resources.getQuantityString(R.plurals.settings_about_debug_taps, remaining, remaining)
                                    tapTrigger++
                                }
                            }
                    )
                    Text(
                        text  = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text  = BuildConfig.VERSION_NAME,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    AnimatedVisibility(
                        visible = showTapHint,
                        enter   = fadeIn(tween(80)),
                        exit    = fadeOut(tween(200))
                    ) {
                        Text(
                            text  = tapMessage,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── About ─────────────────────────────────────────────────────
            item { AboutSectionHeader(icon = Icons.Outlined.Info, title = stringResource(R.string.settings_about_title)) }

            item {
                val isDark        = LocalDarkMode.current
                val isAmoledLocal = LocalAmoledMode.current
                val sv            = MaterialTheme.colorScheme.surfaceVariant
                val cardColor     = if (isDark && !isAmoledLocal)
                    Color(red = sv.red * 0.65f, green = sv.green * 0.65f, blue = sv.blue * 0.65f)
                else sv
                Card(
                    modifier  = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    colors    = CardDefaults.cardColors(containerColor = cardColor),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                ) {
                    Text(
                        text     = stringResource(R.string.settings_about_app_desc),
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            item {
                AboutLinkCard(
                    icon           = Icons.Outlined.LocalCafe,
                    iconBackground = Color(0xFFFF5E5B),
                    title          = stringResource(R.string.settings_about_kofi),
                    subtitle       = stringResource(R.string.settings_about_kofi_desc),
                    onClick        = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/esdex"))
                        )
                    },
                    trailing = {
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            null,
                            modifier = Modifier.size(16.dp),
                            tint     = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            item {
                AboutLinkCard(
                    icon           = Icons.Outlined.Description,
                    iconBackground = Color(0xFF6B7280),
                    title          = stringResource(R.string.settings_about_licenses),
                    onClick        = onLicenses,
                    trailing = {
                        Icon(Icons.Outlined.KeyboardArrowRight, null,
                             tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                )
            }

            item {
                AboutLinkCard(
                    icon           = Icons.Outlined.NewReleases,
                    iconBackground = Color(0xFF2196F3),
                    title          = stringResource(R.string.settings_about_whats_new),
                    subtitle       = stringResource(R.string.settings_about_whats_new_desc),
                    onClick        = onWhatsNew,
                    trailing = {
                        Icon(
                            Icons.Outlined.KeyboardArrowRight,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                )
            }

            // ── Connect ───────────────────────────────────────────────────
            item { AboutSectionHeader(icon = Icons.Outlined.Link, title = stringResource(R.string.settings_about_connect_section)) }

            item {
                AboutLinkCard(
                    icon           = Icons.Outlined.Code,
                    iconBackground = Color(0xFF1F2937),
                    title          = stringResource(R.string.settings_about_source_code),
                    subtitle       = stringResource(R.string.settings_about_source_code_desc),
                    onClick        = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Esdex/Arcanum"))
                        )
                    },
                    trailing = {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null,
                             modifier = Modifier.size(16.dp),
                             tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                )
            }

            item {
                AboutLinkCard(
                    icon           = Icons.Outlined.Language,
                    iconBackground = Color(0xFF3B82F6),
                    title          = stringResource(R.string.settings_about_website),
                    subtitle       = stringResource(R.string.settings_about_website_desc),
                    onClick        = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://arcanum.zip"))
                        )
                    },
                    trailing = {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null,
                             modifier = Modifier.size(16.dp),
                             tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                )
            }

            item {
                AboutLinkCard(
                    icon           = Icons.Outlined.Description,
                    iconBackground = Color(0xFF6B7280),
                    title          = stringResource(R.string.settings_about_privacy),
                    onClick        = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://arcanum.zip/privacy"))
                        )
                    },
                    trailing = {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null,
                             modifier = Modifier.size(16.dp),
                             tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                )
            }

            item {
                AboutLinkCard(
                    icon           = Icons.Outlined.BugReport,
                    iconBackground = Color(0xFFEF4444),
                    title          = stringResource(R.string.settings_about_bug_report),
                    subtitle       = stringResource(R.string.settings_about_bug_report_desc),
                    onClick        = {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Esdex/Arcanum/issues/new"))
                        )
                    },
                    trailing = {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, null,
                             modifier = Modifier.size(16.dp),
                             tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                )
            }
        }
    }
    } // CompositionLocalProvider
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PremiumSubScreen(onBack: () -> Unit) {
    SubScreenScaffold(title = stringResource(R.string.settings_card_premium), onBack = onBack) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            SettingsGroup {
                SettingsRow(
                    title   = stringResource(R.string.settings_premium_title),
                    value   = stringResource(R.string.settings_premium_upgrade),
                    onClick = { /* TODO: show paywall */ }
                )
            }
        }
    }
}

// ── What's New ────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhatsNewSubScreen(onBack: () -> Unit) {
    SubScreenScaffold(
        title  = stringResource(R.string.settings_about_whats_new),
        onBack = onBack
    ) { innerPadding ->
        LazyColumn(
            modifier       = Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top    = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 16.dp
            )
        ) {
            item {
                Row(
                    modifier              = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text       = "Version ${BuildConfig.VERSION_NAME}",
                        style      = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text     = stringResource(R.string.settings_whats_new_current),
                            style    = MaterialTheme.typography.labelSmall,
                            color    = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            item {
                WhatsNewEntry(
                    icon     = Icons.Outlined.Security,
                    color    = Color(0xFF4CAF50),
                    title    = "Full VeraCrypt compatibility",
                    subtitle = "Open containers created on Windows, macOS, or Linux — no conversion needed. AES, Twofish, Serpent and all cascades supported."
                )
            }
            item {
                WhatsNewEntry(
                    icon     = Icons.Outlined.Security,
                    color    = Color(0xFF4CAF50),
                    title    = "All hash algorithms",
                    subtitle = "BLAKE2s-256, SHA-512, and Whirlpool — fully compatible with VeraCrypt 1.26+."
                )
            }
            item {
                WhatsNewEntry(
                    icon     = Icons.Outlined.Security,
                    color    = Color(0xFF4CAF50),
                    title    = "Hidden volumes",
                    subtitle = "Two passwords, two independent datasets. Plausible deniability under coercion — mathematically impossible to prove the hidden volume exists."
                )
            }
            item {
                WhatsNewEntry(
                    icon     = Icons.Outlined.Security,
                    color    = Color(0xFF4CAF50),
                    title    = "Keyfile support",
                    subtitle = "Add one or more keyfiles as a second authentication factor — required alongside the password to open the vault."
                )
            }
            item {
                WhatsNewEntry(
                    icon     = Icons.Outlined.Security,
                    color    = Color(0xFF4CAF50),
                    title    = "PIM support",
                    subtitle = "Personal Iterations Multiplier for fine-tuned key derivation strength and unlock time."
                )
            }
            item {
                WhatsNewEntry(
                    icon     = Icons.Outlined.Security,
                    color    = Color(0xFF4CAF50),
                    title    = "Panic mode",
                    subtitle = "Duress PIN silently wipes vaults, settings, and biometrics — timing-indistinguishable from a normal unlock."
                )
            }
            item {
                WhatsNewEntry(
                    icon     = Icons.Outlined.Stars,
                    color    = Color(0xFFFFC107),
                    title    = "Calculator disguise",
                    subtitle = "Arcanum looks like a regular calculator app. Your secret PIN is the only way in."
                )
            }
            item {
                WhatsNewEntry(
                    icon     = Icons.Outlined.Stars,
                    color    = Color(0xFFFFC107),
                    title    = "Biometric unlock",
                    subtitle = "Save vault credentials to the hardware Keystore and unlock with fingerprint."
                )
            }
            item {
                WhatsNewEntry(
                    icon     = Icons.Outlined.Stars,
                    color    = Color(0xFFFFC107),
                    title    = "Encrypted gallery",
                    subtitle = "Browse photos and videos directly inside mounted vaults without extracting them."
                )
            }
            item {
                WhatsNewEntry(
                    icon     = Icons.Outlined.Stars,
                    color    = Color(0xFFFFC107),
                    title    = "File manager",
                    subtitle = "Import, export, copy, move, and manage files inside encrypted vaults."
                )
            }
            item {
                WhatsNewEntry(
                    icon     = Icons.Outlined.Stars,
                    color    = Color(0xFFFFC107),
                    title    = "Audio player",
                    subtitle = "Stream audio from encrypted vaults without writing decrypted copies to disk."
                )
            }
            item {
                WhatsNewEntry(
                    icon     = Icons.Outlined.Stars,
                    color    = Color(0xFFFFC107),
                    title    = "AMOLED glass mode",
                    subtitle = "Pure-black backgrounds with frosted-glass blur throughout the app."
                )
            }
        }
    }
}

@Composable
private fun WhatsNewEntry(
    icon: ImageVector,
    color: Color,
    title: String,
    subtitle: String? = null
) {
    val isDark    = LocalDarkMode.current
    val isAmoled  = LocalAmoledMode.current
    val sv        = MaterialTheme.colorScheme.surfaceVariant
    val cardColor = if (isDark && !isAmoled)
        Color(red = sv.red * 0.65f, green = sv.green * 0.65f, blue = sv.blue * 0.65f)
    else sv

    Card(
        modifier  = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors    = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier              = Modifier.padding(12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier         = Modifier
                    .size(32.dp)
                    .background(color.copy(alpha = 0.15f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    modifier           = Modifier.size(18.dp),
                    tint               = color
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text       = title,
                    style      = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (subtitle != null) {
                    Text(
                        text  = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── Debug ─────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugSubScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val debugViewModel: DebugViewModel = hiltViewModel()
    val debugMode      = viewModel.debugMode.collectAsState().value
    val state          = debugViewModel.state.collectAsState().value
    val disguiseApplied by viewModel.disguiseApplied.collectAsState()
    val activity       = LocalContext.current as FragmentActivity
    var showWarningDialog by remember { mutableStateOf(false) }
    val isAmoled       = LocalAmoledMode.current
    val debugHazeState = remember { HazeState() }

    CompositionLocalProvider(LocalHazeState provides debugHazeState) {

    LaunchedEffect(debugMode) {
        if (debugMode) debugViewModel.refresh()
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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
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
            SettingsGroup {
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
                // ── Runtime ──────────────────────────────────────────────────
                PanicSectionLabel(stringResource(R.string.settings_debug_section_runtime))
                SettingsGroup {
                    state.runtime?.let { r ->
                        DebugRow("PID", r.pid.toString())
                        DebugRow("UID", r.uid.toString())
                        DebugRow("Heap", "${r.heapUsed} / ${r.heapMax}")
                        DebugRow(
                            label      = "libarcanum-native.so",
                            value      = if (r.nativeLib) "Loaded" else "Not loaded",
                            valueColor = if (r.nativeLib) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                        )
                    }
                    if (state.runtime == null && state.isLoading) DebugRow("", "Loading…")
                }

                // ── Mounted Containers ────────────────────────────────────────
                PanicSectionLabel(stringResource(R.string.settings_debug_section_mounted))
                SettingsGroup {
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
                SettingsGroup {
                    state.security?.let { s ->
                        DebugRow(
                            label      = "Keystore",
                            value      = s.keystore,
                            valueColor = if (s.keystore.startsWith("Hardware")) Color(0xFF4CAF50) else Color(0xFFFFA000),
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
                                "Enforcing"  -> Color(0xFF4CAF50)
                                "Permissive" -> Color(0xFFFFA000)
                                else         -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            infoText   = "Security-Enhanced Linux enforces mandatory access controls at OS level.\n\n" +
                                "Enforcing — policy violations are blocked. Required on all Google-certified devices.\n\n" +
                                "Permissive — violations are only logged, not blocked. Common on custom ROMs. " +
                                "Reduces inter-process isolation."
                        )
                        DebugRow(
                            label      = "Root",
                            value      = if (s.rooted) "Detected" else "Not detected",
                            valueColor = if (s.rooted) MaterialTheme.colorScheme.error else Color(0xFF4CAF50),
                            infoText   = "Checks for signs of root access: su binary in common system paths, " +
                                "test-keys build signature, and response to su commands.\n\n" +
                                "Not detected — no root indicators found.\n\n" +
                                "Detected — root is present. Other apps may bypass the Android security " +
                                "sandbox and potentially access vault data."
                        )
                        DebugRow(
                            label      = "Bootloader",
                            value      = if (s.bootloaderUnlocked) "Unlocked" else "Locked",
                            valueColor = if (s.bootloaderUnlocked) MaterialTheme.colorScheme.error else Color(0xFF4CAF50),
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
                            valueColor = if (s.adbEnabled) Color(0xFFFFA000) else Color(0xFF4CAF50),
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
                            valueColor = if (s.devOptionsEnabled) Color(0xFFFFA000) else Color(0xFF4CAF50),
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
                                overlayCount == 0 -> Color(0xFF4CAF50)
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
                            valueColor = if (isWindowSecure) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error,
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
                SettingsGroup {
                    state.db?.let { db ->
                        DebugRow("Schema", "v${db.version}")
                        DebugRow("Containers", "${db.total} total, ${db.mounted} mounted")
                    }
                }

                // ── Launcher Icons ────────────────────────────────────────────
                PanicSectionLabel(stringResource(R.string.settings_debug_section_icons))
                SettingsGroup {
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

                // ── Tools ─────────────────────────────────────────────────────
                PanicSectionLabel(stringResource(R.string.settings_debug_section_tools))
                SettingsGroup {
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
                    .hazeEffect(state = hazeState, style = ArcanumHazeStyle.dialog)
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
    "Available"    -> Color(0xFF4CAF50)
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

@Composable
private fun AboutSectionHeader(icon: ImageVector, title: String) {
    Row(
        modifier            = Modifier.padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment   = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            modifier           = Modifier.size(14.dp),
            tint               = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text          = title.uppercase(),
            style         = MaterialTheme.typography.labelSmall,
            color         = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.8.sp
        )
    }
}

@Composable
private fun AboutLinkCard(
    icon: ImageVector,
    iconBackground: Color,
    iconTint: Color = Color.White,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null
) {
    val isDark    = LocalDarkMode.current
    val isAmoled  = LocalAmoledMode.current
    val sv        = MaterialTheme.colorScheme.surfaceVariant
    val cardColor = if (isDark && !isAmoled)
        Color(red = sv.red * 0.65f, green = sv.green * 0.65f, blue = sv.blue * 0.65f)
    else sv

    Card(
        onClick   = onClick,
        modifier  = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors    = CardDefaults.cardColors(containerColor = cardColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier              = Modifier.padding(12.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier         = Modifier
                    .size(32.dp)
                    .background(color = iconBackground, shape = RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null,
                     modifier = Modifier.size(18.dp), tint = iconTint)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyMedium)
                if (subtitle != null) {
                    Text(text = subtitle, style = MaterialTheme.typography.labelSmall,
                         color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            trailing?.invoke()
        }
    }
}
