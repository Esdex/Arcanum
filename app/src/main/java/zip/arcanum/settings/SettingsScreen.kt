package zip.arcanum.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.SmartDisplay
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import zip.arcanum.BuildConfig
import zip.arcanum.R
import zip.arcanum.core.components.BackButton
import zip.arcanum.core.components.rememberCollapsedLargeTopBarBehavior
import zip.arcanum.core.components.SettingsGroup
import zip.arcanum.core.components.GroupedRow
import zip.arcanum.core.components.GroupedRoundIcon

// Settings: the router between the sub-screens, and the list that leads to them.
// Each sub-screen lives in a file of its own beside this one.

private enum class SubScreen {
    SECURITY, CHANGE_PIN, PANIC_MODE, SET_PANIC_PIN, APPEARANCE, GALLERY, TEXT_EDITOR, VIDEO_PLAYER, BACKUP, BACKUP_SAVE, ABOUT, LICENSES, WHATS_NEW, DONATIONS, DEBUG
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack:         () -> Unit = {},
    viewModel:      SettingsViewModel = hiltViewModel(),
    panicViewModel: PanicModeViewModel = hiltViewModel(),
    openWhatsNew:   Boolean = false,
    openDonations:  Boolean = false,
    openAbout:      Boolean = false
) {
    var subScreen by remember { mutableStateOf<SubScreen?>(null) }
    // Where a screen with two entrances came from. Null means the main list, which is
    // also where a notification's deep link belongs - it never passed through About.
    var whatsNewFrom  by remember { mutableStateOf<SubScreen?>(null) }
    LaunchedEffect(openWhatsNew)  { if (openWhatsNew)  subScreen = SubScreen.WHATS_NEW }
    LaunchedEffect(openDonations) { if (openDonations) subScreen = SubScreen.DONATIONS }
    LaunchedEffect(openAbout)     { if (openAbout)     subScreen = SubScreen.ABOUT }
    val autoLockEnabled         by viewModel.autoLockEnabled.collectAsState()
    val autoLockDelayIndex      by viewModel.autoLockDelayIndex.collectAsState()
    val unmountOnAutoLock       by viewModel.unmountOnAutoLock.collectAsState()
    val themeMode               by viewModel.themeMode.collectAsState()
    val isAmoledGlass           by viewModel.isAmoledGlass.collectAsState()
    val isDynamicColor          by viewModel.isDynamicColor.collectAsState()
    val defaultContainerTab     by viewModel.defaultContainerTab.collectAsState()
    val screenCaptureProtection by viewModel.screenCaptureProtection.collectAsState()
    val disguiseApplied         by viewModel.disguiseApplied.collectAsState()
    val debugMode               by viewModel.debugMode.collectAsState()
    BackHandler(enabled = subScreen != null) {
        subScreen = when (subScreen) {
            SubScreen.SET_PANIC_PIN -> SubScreen.PANIC_MODE
            SubScreen.CHANGE_PIN    -> SubScreen.SECURITY
            SubScreen.LICENSES      -> SubScreen.ABOUT
            SubScreen.BACKUP_SAVE   -> SubScreen.BACKUP
            SubScreen.WHATS_NEW     -> whatsNewFrom
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
                autoLockEnabled          = autoLockEnabled,
                onAutoLockChange         = { viewModel.setAutoLock(it) },
                autoLockDelayIndex       = autoLockDelayIndex,
                onAutoLockDelayChange    = { viewModel.setAutoLockDelayIndex(it) },
                unmountOnAutoLock        = unmountOnAutoLock,
                onUnmountOnAutoLockChange = { viewModel.setUnmountOnAutoLock(it) },
                screenCaptureProtection  = screenCaptureProtection,
                disguiseApplied          = disguiseApplied,
                onBack                   = { subScreen = null },
                onChangePin              = { subScreen = SubScreen.CHANGE_PIN },
                viewModel                = viewModel
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
                themeMode          = themeMode,
                isAmoledGlass      = isAmoledGlass,
                isDynamicColor     = isDynamicColor,
                defaultContainerTab = defaultContainerTab,
                onThemeMode        = { viewModel.setThemeMode(it) },
                onAmoledGlass      = { viewModel.setAmoledGlass(it) },
                onDynamicColor     = { viewModel.setDynamicColor(it) },
                onDefaultContainerTab = { viewModel.setDefaultContainerTab(it) },
                onBack             = { subScreen = null }
            )
            SubScreen.TEXT_EDITOR -> {
                val editorPrefs by viewModel.textEditor.collectAsState()
                TextEditorSubScreen(
                    prefs         = editorPrefs,
                    onFontSize    = viewModel::setEditorFontSize,
                    onMonospace   = viewModel::setEditorMonospace,
                    onLineNumbers = viewModel::setEditorLineNumbers,
                    onWordWrap    = viewModel::setEditorWordWrap,
                    onHighlight   = viewModel::setEditorHighlight,
                    onWhitespace  = viewModel::setEditorWhitespace,
                    onOpenNew     = viewModel::setEditorOpenNew,
                    onHideIme     = viewModel::setEditorHideIme,
                    onBack        = { subScreen = null }
                )
            }
            SubScreen.GALLERY -> {
                val fastScroll by viewModel.galleryFastScroll.collectAsState()
                GallerySubScreen(
                    fastScroll   = fastScroll,
                    onFastScroll = viewModel::setGalleryFastScroll,
                    onBack       = { subScreen = null }
                )
            }
            SubScreen.VIDEO_PLAYER -> {
                val playerPrefs by viewModel.videoPlayer.collectAsState()
                VideoPlayerSubScreen(
                    prefs             = playerPrefs,
                    onSwipeVolume     = viewModel::setPlayerSwipeVolume,
                    onSwipeBrightness = viewModel::setPlayerSwipeBrightness,
                    onHoldForSpeed    = viewModel::setPlayerHoldForSpeed,
                    onLockButton      = viewModel::setPlayerLockButton,
                    onSpeedMenu       = viewModel::setPlayerSpeedMenu,
                    onLoop            = viewModel::setPlayerLoop,
                    onResumeMusic     = viewModel::setPlayerResumeMusic,
                    onBack            = { subScreen = null }
                )
            }

            SubScreen.CHANGE_PIN -> ChangePinScreen(onBack = { subScreen = null })
            SubScreen.BACKUP    -> BackupSubScreen(
                onBack = { subScreen = null },
                onSave = { subScreen = SubScreen.BACKUP_SAVE }
            )

            SubScreen.BACKUP_SAVE -> BackupSaveSubScreen(
                onBack   = { subScreen = SubScreen.BACKUP },
                onSaving = { subScreen = SubScreen.BACKUP }
            )

            SubScreen.ABOUT     -> AboutSubScreen(
                onBack          = { subScreen = null },
                onLicenses      = { subScreen = SubScreen.LICENSES },
                onWhatsNew      = { whatsNewFrom = SubScreen.ABOUT; subScreen = SubScreen.WHATS_NEW },
                viewModel       = viewModel,
                onDebugUnlocked = { subScreen = SubScreen.DEBUG }
            )
            SubScreen.LICENSES  -> LicensesScreen(onBack = { subScreen = SubScreen.ABOUT })
            SubScreen.WHATS_NEW -> WhatsNewSubScreen(onBack = { subScreen = whatsNewFrom })

            // F-Droid only: reached from the main list, or from the support banner.
            SubScreen.DONATIONS -> DonationsSubScreen(onBack = { subScreen = null })
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

/**
 * The circle behind each icon on the main list.
 *
 * Pale and saturated rather than dark and solid, which is what Android's own Settings does:
 * the glyph then sits in black on top and the row keeps its colour without shouting. The
 * hues follow the meaning rather than the palette - the alarm is the red one - and the glyph
 * colour is not stated anywhere, since [zip.arcanum.core.components.GroupedRoundIcon] takes
 * it from how bright the circle is.
 *
 * These stand outside Material You on purpose. The screen used to hand every icon the theme's
 * primaryContainer when dynamic colour was on, which made all five circles the same colour -
 * and a colour that says nothing is worse here than one that does not match the wallpaper.
 * Android's own Settings keeps its section colours for the same reason.
 */
private data class SectionHue(val circle: Color, val glyph: Color)

private object SettingsHue {
    val Security   = SectionHue(Color(0xFF60D5F3), Color(0xFF004E5D))
    val Panic      = SectionHue(Color(0xFFFFB3AE), Color(0xFF8A1A16))
    val Appearance = SectionHue(Color(0xFFFFB683), Color(0xFF753403))
    val Editor     = SectionHue(Color(0xFF7FD8C8), Color(0xFF00504A))
    val Player     = SectionHue(Color(0xFFF2B8D8), Color(0xFF6B1F4C))
    val Gallery    = SectionHue(Color(0xFFD0BCFF), Color(0xFF381E72))
    val About      = SectionHue(Color(0xFFC7C7C7), Color(0xFF474747))
    val Debug      = SectionHue(Color(0xFF80DA88), Color(0xFF00522C))
    val Donate     = SectionHue(Color(0xFFFFC107), Color(0xFF5D4200))
    val Backup     = SectionHue(Color(0xFFB9C4FF), Color(0xFF1B2E82))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainSettingsScreen(
    onBack: () -> Unit,
    onNavigate: (SubScreen) -> Unit,
    debugMode: Boolean
) {
    val scrollBehavior = rememberCollapsedLargeTopBarBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title          = { Text(stringResource(R.string.settings_title)) },
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    BackButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp))
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
            // Grouped the way Android's own Settings groups its sections: no headings, a
            // round icon of its own colour on each row, and the gap between groups doing the
            // sorting. Protection first, then how it looks, then what it is.
            SettingsGroup {
                row { shape ->
                    GroupedRow(
                        shape    = shape,
                        title    = stringResource(R.string.settings_card_security),
                        subtitle = stringResource(R.string.settings_card_security_desc),
                        leading  = {
                            GroupedRoundIcon(
                                icon      = Icons.Outlined.Security,
                                color     = SettingsHue.Security.circle,
                                iconColor = SettingsHue.Security.glyph
                            )
                        },
                        onClick  = { onNavigate(SubScreen.SECURITY) }
                    )
                }
                row { shape ->
                    GroupedRow(
                        shape    = shape,
                        title    = stringResource(R.string.settings_card_panic),
                        subtitle = stringResource(R.string.settings_card_panic_desc),
                        leading  = {
                            GroupedRoundIcon(
                                icon      = Icons.Outlined.Warning,
                                color     = SettingsHue.Panic.circle,
                                iconColor = SettingsHue.Panic.glyph
                            )
                        },
                        onClick  = { onNavigate(SubScreen.PANIC_MODE) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            SettingsGroup {
                row { shape ->
                    GroupedRow(
                        shape    = shape,
                        title    = stringResource(R.string.settings_card_appearance),
                        subtitle = stringResource(R.string.settings_card_appearance_desc),
                        leading  = {
                            GroupedRoundIcon(
                                icon      = Icons.Outlined.Palette,
                                color     = SettingsHue.Appearance.circle,
                                iconColor = SettingsHue.Appearance.glyph
                            )
                        },
                        onClick  = { onNavigate(SubScreen.APPEARANCE) }
                    )
                }
                row { shape ->
                    GroupedRow(
                        shape    = shape,
                        title    = stringResource(R.string.settings_card_gallery),
                        subtitle = stringResource(R.string.settings_card_gallery_desc),
                        leading  = {
                            GroupedRoundIcon(
                                icon      = Icons.Outlined.PhotoLibrary,
                                color     = SettingsHue.Gallery.circle,
                                iconColor = SettingsHue.Gallery.glyph
                            )
                        },
                        onClick  = { onNavigate(SubScreen.GALLERY) }
                    )
                }
                row { shape ->
                    GroupedRow(
                        shape    = shape,
                        title    = stringResource(R.string.settings_card_editor),
                        subtitle = stringResource(R.string.settings_card_editor_desc),
                        leading  = {
                            GroupedRoundIcon(
                                icon      = Icons.Outlined.EditNote,
                                color     = SettingsHue.Editor.circle,
                                iconColor = SettingsHue.Editor.glyph
                            )
                        },
                        onClick  = { onNavigate(SubScreen.TEXT_EDITOR) }
                    )
                }
                row { shape ->
                    GroupedRow(
                        shape    = shape,
                        title    = stringResource(R.string.settings_card_player),
                        subtitle = stringResource(R.string.settings_card_player_desc),
                        leading  = {
                            GroupedRoundIcon(
                                icon      = Icons.Outlined.SmartDisplay,
                                color     = SettingsHue.Player.circle,
                                iconColor = SettingsHue.Player.glyph
                            )
                        },
                        onClick  = { onNavigate(SubScreen.VIDEO_PLAYER) }
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            SettingsGroup {
                row { shape ->
                    GroupedRow(
                        shape    = shape,
                        title    = stringResource(R.string.settings_backup_card),
                        subtitle = stringResource(R.string.settings_backup_card_desc),
                        leading  = {
                            GroupedRoundIcon(
                                icon      = Icons.Outlined.Save,
                                color     = SettingsHue.Backup.circle,
                                iconColor = SettingsHue.Backup.glyph
                            )
                        },
                        onClick  = { onNavigate(SubScreen.BACKUP) }
                    )
                }
                row { shape ->
                    GroupedRow(
                        shape    = shape,
                        title    = stringResource(R.string.settings_card_about),
                        subtitle = stringResource(R.string.settings_card_about_desc),
                        leading  = {
                            GroupedRoundIcon(
                                icon      = Icons.Outlined.Info,
                                color     = SettingsHue.About.circle,
                                iconColor = SettingsHue.About.glyph
                            )
                        },
                        onClick  = { onNavigate(SubScreen.ABOUT) }
                    )
                }
                if (debugMode) {
                    row { shape ->
                        GroupedRow(
                            shape    = shape,
                            title    = stringResource(R.string.settings_card_debug),
                            subtitle = stringResource(R.string.settings_card_debug_desc),
                            leading  = {
                                GroupedRoundIcon(
                                    icon      = Icons.Outlined.BugReport,
                                    color     = SettingsHue.Debug.circle,
                                iconColor = SettingsHue.Debug.glyph
                                )
                            },
                            onClick  = { onNavigate(SubScreen.DEBUG) }
                        )
                    }
                }
            }

            // Donating is F-Droid only. Someone who got the app from Play has paid for it
            // already, so the Play build asks for nothing, here or on About.
            if (BuildConfig.IS_FDROID) {
                Spacer(Modifier.height(16.dp))

                SettingsGroup {
                    row { shape ->
                        GroupedRow(
                            shape    = shape,
                            title    = stringResource(R.string.settings_about_donate),
                            subtitle = stringResource(R.string.settings_about_donate_desc),
                            leading  = {
                                GroupedRoundIcon(
                                    icon      = Icons.Filled.Star,
                                    color     = SettingsHue.Donate.circle,
                                    iconColor = SettingsHue.Donate.glyph
                                )
                            },
                            onClick  = { onNavigate(SubScreen.DONATIONS) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

// ── Sub-screen helpers ────────────────────────────────────────────────────────
