package zip.arcanum.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import android.os.Build
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.outlined.Contrast
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Switch
import androidx.compose.ui.res.stringResource
import zip.arcanum.R
import zip.arcanum.core.components.AppSheet
import zip.arcanum.core.components.LocalHazeState
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.rememberModalBottomSheetState
import zip.arcanum.core.components.UpgradeOverlay
import zip.arcanum.core.navigation_components.DefaultContainerTab
import zip.arcanum.core.theme.ThemeMode
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material3.RadioButton
import androidx.core.os.LocaleListCompat
import zip.arcanum.core.components.SettingsGroup
import zip.arcanum.core.components.GroupedRow
import zip.arcanum.core.components.GroupedSwitch
import zip.arcanum.core.components.GroupedBox
import androidx.compose.foundation.interaction.MutableInteractionSource

// Settings / Appearance: theme, dynamic colour, AMOLED and the app's language.

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppearanceSubScreen(
    themeMode: ThemeMode,
    isAmoledGlass: Boolean,
    isDynamicColor: Boolean,
    defaultContainerTab: DefaultContainerTab,
    isPro: Boolean,
    onThemeMode: (ThemeMode) -> Unit,
    onAmoledGlass: (Boolean) -> Unit,
    onDynamicColor: (Boolean) -> Unit,
    onDefaultContainerTab: (DefaultContainerTab) -> Unit,
    onBack: () -> Unit
) {
    var showUpgradeDialog by remember { mutableStateOf(false) }
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
                .padding(horizontal = 16.dp)
        ) {
            SettingsGroup(title = stringResource(R.string.settings_appearance_theme_section)) {
                row { shape ->
                    GroupedBox(shape) {
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
                }
            }

            SettingsGroup(title = stringResource(R.string.settings_appearance_display_section)) {
                // AMOLED is a dark-theme setting and simply is not there in a light one.
                if (isDark) {
                    row { shape ->
                        if (isPro) {
                            GroupedSwitch(
                                shape           = shape,
                                title           = stringResource(R.string.settings_appearance_amoled),
                                subtitle        = stringResource(R.string.settings_appearance_amoled_desc),
                                checked         = isAmoledGlass,
                                onCheckedChange = onAmoledGlass
                            )
                        } else {
                            // Locked rather than absent: the padlock in the switch is the
                            // whole message, and a tap on the row explains the rest.
                            Box {
                                GroupedSwitch(
                                    shape           = shape,
                                    title           = stringResource(R.string.settings_appearance_amoled),
                                    subtitle        = stringResource(R.string.upgrade_pro_feature_subtitle),
                                    checked         = false,
                                    enabled         = false,
                                    onCheckedChange = {}
                                )
                                Box(
                                    modifier = Modifier
                                        .matchParentSize()
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication        = null
                                        ) { showUpgradeDialog = true }
                                )
                            }
                        }
                    }
                }
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_appearance_dynamic_colors),
                        subtitle        = stringResource(R.string.settings_appearance_dynamic_colors_desc),
                        checked         = isDynamicColor,
                        onCheckedChange = onDynamicColor
                    )
                }
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                Text(
                    text     = stringResource(R.string.settings_appearance_dynamic_colors_unavailable),
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
                )
            }

            if (showUpgradeDialog) {
                UpgradeOverlay(onDismiss = { showUpgradeDialog = false })
            }

            SettingsGroup(title = stringResource(R.string.settings_appearance_language_section)) {
                row { shape ->
                    GroupedRow(
                        shape    = shape,
                        title    = stringResource(R.string.settings_appearance_language),
                        subtitle = currentLanguageName,
                        onClick  = { showLanguagePicker = true }
                    )
                }
            }

            SettingsGroup(title = stringResource(R.string.settings_appearance_default_tab_section)) {
                row { shape ->
                    GroupedBox(shape) {
                        Text(
                            text  = stringResource(R.string.settings_appearance_default_tab_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(12.dp))
                        val tabs = DefaultContainerTab.entries
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            tabs.forEachIndexed { index, tab ->
                                SegmentedButton(
                                    selected = defaultContainerTab == tab,
                                    onClick  = { onDefaultContainerTab(tab) },
                                    shape    = SegmentedButtonDefaults.itemShape(index = index, count = tabs.size),
                                    label    = {
                                        Text(
                                            when (tab) {
                                                DefaultContainerTab.FILES   -> stringResource(R.string.nav_files)
                                                DefaultContainerTab.GALLERY -> stringResource(R.string.nav_gallery)
                                            },
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
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

