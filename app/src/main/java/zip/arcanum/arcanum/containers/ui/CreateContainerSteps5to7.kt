package zip.arcanum.arcanum.containers.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import zip.arcanum.core.icons.ArcanumIcons
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import android.view.HapticFeedbackConstants
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import zip.arcanum.core.components.AppDialog
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import zip.arcanum.core.utils.DotVisualTransformation
import zip.arcanum.R
import kotlin.math.roundToInt

// ─── Step 5: Password ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepPassword(
    state: CreateContainerState,
    onUpdate: (CreateContainerState.() -> CreateContainerState) -> Unit,
    onAddKeyfile: () -> Unit = {},
    onGenerateKeyfile: () -> Unit = {},
    onRemoveKeyfile: (index: Int) -> Unit = {},
) {
    val context                = LocalContext.current
    val focusManager           = LocalFocusManager.current
    val keyboardController     = LocalSoftwareKeyboardController.current
    val lifecycleOwner         = LocalLifecycleOwner.current
    val passwordFocusRequester = remember { FocusRequester() }

    var refocusCount by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refocusCount++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(refocusCount) {
        if (refocusCount == 0) return@LaunchedEffect
        delay(200)
        focusManager.clearFocus()
        delay(50)
        passwordFocusRequester.requestFocus()
        keyboardController?.show()
    }

    var showPassword by remember { mutableStateOf(false) }
    var showConfirm  by remember { mutableStateOf(false) }
    var showPim      by remember { mutableStateOf(false) }
    var pimText      by remember { mutableStateOf(if (state.pim > 0) state.pim.toString() else "") }

    val strength = passwordStrength(state.password)
    val strengthColor by animateColorAsState(
        targetValue = when (strength) {
            0    -> MaterialTheme.colorScheme.error
            1    -> Color(0xFFFF6B35)
            else -> Color(0xFF16A34A)
        },
        animationSpec = tween(300),
        label = "strength_color"
    )
    val strengthLabels = listOf(
        stringResource(R.string.create_pwd_strength_weak),
        stringResource(R.string.create_pwd_strength_fair),
        stringResource(R.string.create_pwd_strength_strong),
        stringResource(R.string.create_pwd_strength_very_strong)
    )
    val strengthLabel = strengthLabels.getOrElse(strength) { stringResource(R.string.create_pwd_strength_weak) }
    val strengthProgress = when {
        strength >= 2 -> 1f
        else          -> (strength + 1) / 4f
    }

    val isHidden = state.volumeType == VolumeType.HIDDEN
    val title    = if (isHidden) stringResource(R.string.create_step5_outer_title) else stringResource(R.string.create_step5_title)
    val subtitle = if (isHidden)
        stringResource(R.string.create_step5_outer_subtitle)
    else
        stringResource(R.string.create_step5_subtitle)

    StepContent(title = title, subtitle = subtitle) {
        OutlinedTextField(
            value         = state.password,
            onValueChange = { onUpdate { copy(password = it) } },
            label         = { Text(stringResource(R.string.create_pwd_label)) },
            singleLine    = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon  = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(if (showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(passwordFocusRequester)
        )
        if (state.password.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                progress   = { strengthProgress },
                color      = strengthColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap  = StrokeCap.Round,
                modifier   = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(99.dp))
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(strengthLabel, style = MaterialTheme.typography.labelSmall, color = strengthColor)
                Text(stringResource(R.string.create_pwd_char_count, state.password.length), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value         = state.confirmPassword,
            onValueChange = { onUpdate { copy(confirmPassword = it) } },
            label         = { Text(stringResource(R.string.create_pwd_confirm_label)) },
            singleLine    = true,
            visualTransformation = if (showConfirm) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            isError       = state.confirmPassword.isNotEmpty() && state.password != state.confirmPassword,
            supportingText = if (state.confirmPassword.isNotEmpty() && state.password != state.confirmPassword) {
                { Text(stringResource(R.string.create_pwd_mismatch)) }
            } else null,
            trailingIcon  = {
                IconButton(onClick = { showConfirm = !showConfirm }) {
                    Icon(if (showConfirm) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null)
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))

        // PIM field
        val pimInt = pimText.toIntOrNull() ?: 0
        val pimStatusDefault  = stringResource(R.string.create_pim_status_default)
        val pimStatusBelow    = stringResource(R.string.create_pim_status_below)
        val pimStatusSimilar  = stringResource(R.string.create_pim_status_similar)
        val pimStatusEnhanced = stringResource(R.string.create_pim_status_enhanced)
        val (pimIcon, pimMsg) = when {
            pimText.isEmpty() -> "ℹ️" to pimStatusDefault
            pimInt < 486      -> "⚠️" to pimStatusBelow
            pimInt <= 500     -> "✅" to pimStatusSimilar
            else              -> "🔒" to pimStatusEnhanced
        }
        val estSecs = if (pimInt > 0) ((15000 + pimInt * 1000).toFloat() / 500000f * 2f).toInt().coerceAtLeast(1) else 2
        OutlinedTextField(
            value         = pimText,
            onValueChange = {
                if (it.all { c -> c.isDigit() } && it.length <= 7) {
                    val v = it.toLongOrNull() ?: 0L
                    if (it.isEmpty() || v in 1L..2_147_468L) {
                        pimText = it
                        onUpdate { copy(pim = v.toInt()) }
                    }
                }
            },
            label                = { Text(stringResource(R.string.create_pim_short_label)) },
            placeholder          = { Text(stringResource(R.string.create_pim_placeholder)) },
            visualTransformation = if (showPim) VisualTransformation.None else DotVisualTransformation(),
            trailingIcon         = {
                IconButton(onClick = { showPim = !showPim }) {
                    Icon(if (showPim) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null)
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine      = true,
            modifier        = Modifier.fillMaxWidth()
        )
        Text(
            "$pimIcon $pimMsg",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (pimInt > 0) {
            Text(
                stringResource(R.string.create_pim_unlock_est, estSecs),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (pimInt in 1..484 && state.password.length < 20) {
            Text(
                stringResource(R.string.create_pim_short_pwd_error),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(8.dp))

        // Keyfile section
        if (state.keyfileDisplayNames.isNotEmpty()) {
            Card(
                colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                shape    = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.keyfileDisplayNames.forEachIndexed { index, displayName ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                ArcanumIcons.Keyfile,
                                contentDescription = null,
                                tint     = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                displayName,
                                style    = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onRemoveKeyfile(index) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.create_keyfile_cd_remove), modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.create_keyfile_safe_warning),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
        TextButton(onClick = onAddKeyfile, modifier = Modifier.fillMaxWidth()) {
            Icon(ArcanumIcons.Keyfile, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.create_keyfile_add))
        }
        TextButton(onClick = onGenerateKeyfile, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null)
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.keyfile_generate_new))
        }
        if (state.keyfileError != null) {
            Text(
                state.keyfileError,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.create_pwd_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

internal fun passwordStrength(pwd: String): Int {
    if (pwd.length < 4) return 0
    var score = 0
    if (pwd.length >= 8) score++
    if (pwd.length >= 12) score++
    if (pwd.any { it.isUpperCase() } && pwd.any { it.isLowerCase() }) score++
    if (pwd.any { it.isDigit() }) score++
    if (pwd.any { !it.isLetterOrDigit() }) score++
    return (score / 2).coerceIn(0, 3)
}

// ─── Step 6: Format Mode ──────────────────────────────────────────────────────

@Composable
fun StepFormatMode(state: CreateContainerState, onUpdate: (CreateContainerState.() -> CreateContainerState) -> Unit) {
    val quickSecs  = (state.sizeMb / 500.0).toLong().coerceAtLeast(1)
    val secureSecs = (state.sizeMb / 80.0).toLong().coerceAtLeast(1)

    StepContent(title = stringResource(R.string.create_step6_title)) {
        SelectionCard(
            selected    = state.quickFormat,
            icon        = Icons.Outlined.Bolt,
            title       = stringResource(R.string.create_format_quick_title),
            description = stringResource(R.string.create_format_quick_desc, formatSecs(quickSecs)),
            onClick     = { onUpdate { copy(quickFormat = true) } }
        )
        Spacer(Modifier.height(12.dp))
        SelectionCard(
            selected    = !state.quickFormat,
            icon        = Icons.Outlined.Lock,
            title       = stringResource(R.string.create_format_secure_title),
            description = stringResource(R.string.create_format_secure_desc, formatSecs(secureSecs)),
            onClick     = { onUpdate { copy(quickFormat = false) } }
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.create_format_est_text, if (state.quickFormat) formatSecs(quickSecs) else formatSecs(secureSecs)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─── Step 7: Filesystem ───────────────────────────────────────────────────────

@Composable
fun StepFilesystem(
    state: CreateContainerState,
    onUpdate: (CreateContainerState.() -> CreateContainerState) -> Unit
) {
    val recommended = if (state.sizeMb > 2L * 1024L * 1024L) FilesystemType.EXFAT else FilesystemType.FAT32
    var infoFs by remember { mutableStateOf<FilesystemType?>(null) }

    LaunchedEffect(Unit) {
        onUpdate { copy(filesystem = recommended) }
    }

    StepContent(
        title    = stringResource(R.string.create_step7_title),
        subtitle = stringResource(R.string.create_fs_subtitle)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FilesystemType.entries.forEach { fs ->
                FilesystemCard(
                    fs          = fs,
                    selected    = state.filesystem == fs,
                    recommended = fs == recommended,
                    comingSoon  = false,
                    onClick     = { onUpdate { copy(filesystem = fs) } },
                    onInfo      = { infoFs = fs }
                )
            }
        }
    }

    if (infoFs != null) {
        AppDialog(
            onDismissRequest = { infoFs = null },
            title            = { Text(infoFs!!.displayName) },
            text             = { Text(infoFs!!.info) },
            confirmButton    = {
                TextButton(onClick = { infoFs = null }) { Text(stringResource(R.string.create_fs_got_it)) }
            }
        )
    }
}

@Composable
private fun FilesystemCard(
    fs: FilesystemType,
    selected: Boolean,
    recommended: Boolean,
    comingSoon: Boolean = false,
    onClick: () -> Unit,
    onInfo: () -> Unit
) {
    val borderColor    = if (selected) MaterialTheme.colorScheme.primary
                         else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                         else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    val contentAlpha   = 1f

    Card(
        colors   = CardDefaults.cardColors(containerColor = containerColor),
        border   = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        shape    = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier          = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text       = fs.displayName,
                        style      = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color      = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha)
                    )
                    if (recommended && !comingSoon) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(99.dp)
                        ) {
                            Text(
                                text     = stringResource(R.string.create_fs_recommended),
                                style    = MaterialTheme.typography.labelSmall,
                                color    = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = fs.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha)
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = stringResource(R.string.create_fs_max_file_size, fs.maxFileSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha * 0.8f)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (fs) {
                        FilesystemType.FAT32 -> {
                            OsChip("Windows", true)
                            OsChip("macOS", true)
                            OsChip("Linux", true)
                        }
                        FilesystemType.EXFAT -> {
                            OsChip("Windows", true)
                            OsChip("macOS", true)
                            OsChip("Linux", true)
                        }
                        FilesystemType.EXT4 -> {
                            OsChip("Windows", false)
                            OsChip("macOS", false)
                            OsChip("Linux", true)
                        }
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onInfo, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.create_fs_info_cd),
                    tint     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun OsChip(os: String, support: Boolean?) {
    val (icon, bg, fg) = when (support) {
        true -> Triple("✅", Color(0xFF16A34A).copy(alpha = 0.12f), Color(0xFF16A34A))
        null -> Triple("⚠️", Color(0xFFF59E0B).copy(alpha = 0.12f), Color(0xFFB45309))
        else -> Triple("❌", MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), MaterialTheme.colorScheme.error)
    }
    Surface(color = bg, shape = RoundedCornerShape(99.dp)) {
        Text(
            text     = "$icon $os${if (support == null) stringResource(R.string.create_fs_os_read_only) else ""}",
            style    = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color    = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}

// ─── Step 8: Entropy ──────────────────────────────────────────────────────────

private data class Particle(val offset: Offset, val birthMs: Long)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepEntropy(state: CreateContainerState, onAddPoint: (Int, Int) -> Unit) {
    val particles   = remember { mutableStateListOf<Particle>() }
    val uniquePts   = remember { mutableSetOf<Pair<Int, Int>>() }
    val progress    = (state.entropyPoints / 500f).coerceIn(0f, 1f)
    val done        = progress >= 1f
    val accentColor = MaterialTheme.colorScheme.primary
    val view        = LocalView.current

    LaunchedEffect(Unit) {
        while (true) {
            delay(50)
            val now = System.currentTimeMillis()
            particles.removeAll { now - it.birthMs > 1000L }
        }
    }

    StepContent(
        title    = stringResource(R.string.create_entropy_title),
        subtitle = stringResource(R.string.create_entropy_subtitle)
    ) {
        Card(
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape    = RoundedCornerShape(20.dp),
            border   = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            val pos   = change.position
                            val gridX = (pos.x / 10).roundToInt()
                            val gridY = (pos.y / 10).roundToInt()
                            if (uniquePts.add(gridX to gridY)) {
                                onAddPoint(gridX, gridY)
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            particles.add(Particle(pos, System.currentTimeMillis()))
                        }
                    }
            ) {
                val now = System.currentTimeMillis()
                particles.forEach { p ->
                    val age = (now - p.birthMs) / 1000f
                    val alpha = (1f - age).coerceIn(0f, 1f)
                    val radius = 6f * (1f - age * 0.5f)
                    drawCircle(color = accentColor.copy(alpha = alpha), radius = radius, center = p.offset)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        LinearProgressIndicator(
            progress   = { progress },
            color      = if (done) Color(0xFF16A34A) else accentColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap  = StrokeCap.Round,
            modifier   = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(99.dp))
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text  = if (done) stringResource(R.string.create_entropy_done)
                    else stringResource(R.string.create_entropy_progress, (progress * 100).toInt()),
            style = MaterialTheme.typography.bodySmall,
            color = if (done) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
