package zip.arcanum.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import zip.arcanum.BuildConfig
import zip.arcanum.R
import zip.arcanum.core.components.LocalHazeState
import zip.arcanum.core.components.SettingsRow
import zip.arcanum.core.theme.ArcanumHazeStyle
import zip.arcanum.core.theme.LocalAmoledMode
import zip.arcanum.core.theme.LocalDarkMode
import android.widget.Toast
import zip.arcanum.core.components.BackButton
import zip.arcanum.core.components.ActionCapsule
import zip.arcanum.core.components.SettingsGroup
import zip.arcanum.core.components.GroupedRow
import zip.arcanum.core.components.GroupedBox

// Settings / About: version, links, licences, what is new, and the premium page.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AboutSubScreen(
    onBack: () -> Unit,
    onLicenses: () -> Unit,
    onWhatsNew: () -> Unit,
    onDonations: () -> Unit,
    viewModel: SettingsViewModel,
    onDebugUnlocked: () -> Unit
) {
    val context   = LocalContext.current
    val activity  = context as FragmentActivity
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

    SubScreenScaffold(title = stringResource(R.string.settings_about_title), onBack = onBack) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(
                top    = innerPadding.calculateTopPadding(),
                bottom = innerPadding.calculateBottomPadding() + 24.dp
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

            // ── The three actions ────────────────────────────────────────
            // Donating in the middle, and marked out the way it was as a row: the gold
            // sheen slides a repeating gradient by exactly one period, so the loop closes
            // on itself with no visible jump. It opens the app's own Donations screen
            // rather than a website - the wallet addresses have nowhere else to live, and
            // the app has no network permission (#66).
            item {
                // Donating is a capsule here only where it is not a row on the main list:
                // the F-Droid build keeps it in Settings, the Play build keeps it here, and
                // neither shows it twice.
                val shimmer by rememberInfiniteTransition(label = "donate_border")
                    .animateFloat(
                        initialValue  = 0f,
                        targetValue   = 1f,
                        animationSpec = infiniteRepeatable(
                            animation  = tween(2600, easing = LinearEasing),
                            repeatMode = RepeatMode.Restart
                        ),
                        label = "donate_border_shift"
                    )
                val gold   = Color(0xFFFFC107)
                val sheen  = Color(0xFFFFF6C2)
                val period = 640f
                val head   = shimmer * period

                Row(
                    modifier              = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
                ) {
                    ActionCapsule(
                        icon    = Icons.Outlined.Code,
                        label   = stringResource(R.string.settings_about_capsule_github),
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Esdex/Arcanum"))
                            )
                        }
                    )
                    if (!BuildConfig.IS_FDROID) {
                        ActionCapsule(
                            icon       = Icons.Filled.Star,
                            label      = stringResource(R.string.settings_about_capsule_donate),
                            emphasised = true,
                            onClick    = onDonations,
                            border     = BorderStroke(
                                width = 1.5.dp,
                                brush = Brush.linearGradient(
                                    colors   = listOf(gold, sheen, gold),
                                    start    = Offset(head - period, 0f),
                                    end      = Offset(head, 0f),
                                    tileMode = TileMode.Repeated
                                )
                            )
                        )
                    }
                    ActionCapsule(
                        icon    = Icons.Outlined.Language,
                        label   = stringResource(R.string.settings_about_capsule_website),
                        onClick = {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://arcanum.zip"))
                            )
                        }
                    )
                }
            }

            // ── About ─────────────────────────────────────────────────────
            item {
                SettingsGroup(title = stringResource(R.string.settings_about_title)) {
                    row { shape ->
                        GroupedBox(shape) {
                            Text(
                                text  = stringResource(R.string.settings_about_app_desc),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    row { shape ->
                        GroupedRow(
                            shape   = shape,
                            title   = stringResource(R.string.settings_about_licenses),
                            onClick = onLicenses
                        )
                    }
                    row { shape ->
                        GroupedRow(
                            shape    = shape,
                            title    = stringResource(R.string.settings_about_whats_new),
                            subtitle = stringResource(R.string.settings_about_whats_new_desc),
                            onClick  = onWhatsNew
                        )
                    }
                }
            }

            // ── Connect ───────────────────────────────────────────────────
            // Everything here leaves the app, which is what the arrow on the right says.
            item {
                SettingsGroup(title = stringResource(R.string.settings_about_connect_section)) {
                    row { shape ->
                        GroupedRow(
                            shape   = shape,
                            title   = stringResource(R.string.settings_about_privacy),
                            onClick = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://arcanum.zip/privacy"))
                                )
                            },
                            trailing = { ExternalLinkGlyph() }
                        )
                    }
                    row { shape ->
                        GroupedRow(
                            shape    = shape,
                            title    = stringResource(R.string.settings_about_bug_report),
                            subtitle = stringResource(R.string.settings_about_bug_report_desc),
                            onClick  = {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Esdex/Arcanum/issues/new"))
                                )
                            },
                            trailing = { ExternalLinkGlyph() }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PremiumSubScreen(onBack: () -> Unit) {
    SubScreenScaffold(title = stringResource(R.string.settings_card_premium), onBack = onBack) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            SubScreenGroup {
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
internal fun WhatsNewSubScreen(onBack: () -> Unit) {
    val context   = LocalContext.current
    val changelog = remember { loadWhatsNew(context) }
    var showOlder by remember { mutableStateOf(false) }

    val currentVersion = remember(changelog) {
        changelog.versions.firstOrNull { it.versionCode == BuildConfig.VERSION_CODE }
    }
    // Strictly-older only: a not-yet-released block (e.g. the next version being
    // filled in ahead of the version bump) has a versionCode above this build and
    // must stay hidden until the build catches up to it.
    val olderVersions = remember(changelog) {
        changelog.versions
            .filter { it.versionCode < BuildConfig.VERSION_CODE }
            .sortedByDescending { it.versionCode }
    }

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
            // ── Current version header ────────────────────────────────────
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

            // ── Current version entries ───────────────────────────────────
            items((currentVersion?.entries ?: emptyList()).inDisplayOrder()) { entry ->
                val (icon, color) = whatsNewVisualsFor(entry.type)
                WhatsNewEntry(
                    icon     = icon,
                    color    = color,
                    title    = entry.title,
                    subtitle = entry.description
                )
            }

            // ── Older versions (behind a "Show older versions" button) ────
            if (olderVersions.isNotEmpty()) {
                if (!showOlder) {
                    item {
                        TextButton(
                            onClick  = { showOlder = true },
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Text(stringResource(R.string.settings_whats_new_show_older))
                        }
                    }
                } else {
                    olderVersions.forEach { version ->
                        item {
                            Row(
                                modifier              = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                                verticalAlignment     = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                HorizontalDivider(modifier = Modifier.weight(1f))
                                Text(
                                    text  = "Version ${version.version}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                HorizontalDivider(modifier = Modifier.weight(1f))
                            }
                        }
                        items(version.entries.inDisplayOrder()) { entry ->
                            val (icon, color) = whatsNewVisualsFor(entry.type)
                            WhatsNewEntry(
                                icon     = icon,
                                color    = color,
                                title    = entry.title,
                                subtitle = entry.description
                            )
                        }
                    }
                }
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
                        text  = linkified(subtitle),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ── Debug ─────────────────────────────────────────────────────────────────────

/** The mark on a row that leaves the app for a browser. */
@Composable
private fun ExternalLinkGlyph() {
    Icon(
        imageVector        = Icons.AutoMirrored.Outlined.OpenInNew,
        contentDescription = null,
        modifier           = Modifier.size(16.dp),
        tint               = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
