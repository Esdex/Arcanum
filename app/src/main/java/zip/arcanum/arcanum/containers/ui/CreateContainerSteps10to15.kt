package zip.arcanum.arcanum.containers.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import android.view.HapticFeedbackConstants
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import kotlinx.coroutines.delay
import zip.arcanum.R
import androidx.compose.ui.res.stringResource

// ─── Step 10: Hidden Volume Info ─────────────────────────────────────────────

@Composable
fun StepHiddenInfo(state: CreateContainerState) {
    StepContent(
        title    = stringResource(R.string.create_hidden_intro_title),
        subtitle = stringResource(R.string.create_hidden_intro_body)
    ) {
        Surface(
            shape  = RoundedCornerShape(12.dp),
            color  = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text  = stringResource(R.string.create_hidden_info_text),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        Surface(
            shape    = RoundedCornerShape(12.dp),
            color    = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(
                    imageVector = Icons.Outlined.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text       = stringResource(R.string.create_hidden_warnings_title),
                        style      = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color      = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text  = stringResource(R.string.create_hidden_warnings_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Text(
            text  = stringResource(R.string.create_hidden_outer_label, state.fileName, state.sizeMb),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─── Step 11: Hidden Algorithm ────────────────────────────────────────────────

@Composable
fun StepHiddenAlgorithm(state: CreateContainerState, onUpdate: (CreateContainerState.() -> CreateContainerState) -> Unit) {
    StepContent(title = stringResource(R.string.create_hidden_cipher_title), subtitle = stringResource(R.string.create_hidden_cipher_subtitle)) {
        Text(stringResource(R.string.create_step3_cipher), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        CipherAlgorithm.entries.forEach { algo ->
            AlgorithmRow(
                name        = algo.displayName,
                description = algo.description,
                speed       = algo.speed,
                selected    = state.hiddenAlgorithm == algo,
                onClick     = { onUpdate { copy(hiddenAlgorithm = algo) } }
            )
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.create_step3_hash), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier              = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HashAlgorithm.entries.forEach { hash ->
                FilterChip(
                    selected = state.hiddenHashAlgorithm == hash,
                    onClick  = { onUpdate { copy(hiddenHashAlgorithm = hash) } },
                    label    = { Text(hash.displayName) }
                )
            }
        }
    }
}

// ─── Step 12: Hidden Volume Size ─────────────────────────────────────────────

private val hiddenPresets = listOf(50L, 100L, 250L, 512L, 1024L)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepHiddenSize(state: CreateContainerState, onUpdate: (CreateContainerState.() -> CreateContainerState) -> Unit) {
    val maxHiddenMb = (state.sizeMb - 4L).coerceAtLeast(4L)
    val minHiddenMb = 4L
    var customInput by remember { mutableStateOf(state.hiddenSizeMb.toString()) }
    var unitGb      by remember { mutableStateOf(false) }

    val selectedPreset = hiddenPresets.find { it == state.hiddenSizeMb }

    val isInvalid = state.hiddenSizeMb < minHiddenMb || state.hiddenSizeMb > maxHiddenMb

    StepContent(title = stringResource(R.string.create_hidden_size_title)) {
        Text(
            text  = stringResource(R.string.create_hidden_size_constraints, minHiddenMb, maxHiddenMb, state.sizeMb),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier              = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            hiddenPresets.filter { it < maxHiddenMb }.forEach { mb ->
                val label = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"
                FilterChip(
                    selected = selectedPreset == mb,
                    onClick  = {
                        onUpdate { copy(hiddenSizeMb = mb) }
                        customInput = mb.toString()
                        unitGb = false
                    },
                    label    = { Text(label) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value         = customInput,
                onValueChange = { v ->
                    customInput = v.filter { it.isDigit() }
                    val raw = customInput.toLongOrNull() ?: 0L
                    onUpdate { copy(hiddenSizeMb = if (unitGb) raw * 1024L else raw) }
                },
                label           = { Text(stringResource(R.string.create_size_label)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine      = true,
                isError         = isInvalid && customInput.isNotEmpty(),
                modifier        = Modifier.weight(1f)
            )
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = !unitGb,
                    onClick  = {
                        unitGb = false
                        val raw = customInput.toLongOrNull() ?: 0L
                        onUpdate { copy(hiddenSizeMb = raw) }
                    },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)
                ) { Text(stringResource(R.string.common_mb)) }
                SegmentedButton(
                    selected = unitGb,
                    onClick  = {
                        unitGb = true
                        val raw = customInput.toLongOrNull() ?: 0L
                        onUpdate { copy(hiddenSizeMb = raw * 1024L) }
                    },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)
                ) { Text(stringResource(R.string.common_gb)) }
            }
        }

        if (isInvalid && customInput.isNotEmpty() && (customInput.toLongOrNull() ?: 0L) > 0L) {
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment     = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Outlined.Warning,
                    contentDescription = null,
                    tint     = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text  = if (state.hiddenSizeMb < minHiddenMb)
                                stringResource(R.string.create_hidden_size_min_error, minHiddenMb)
                            else
                                stringResource(R.string.create_hidden_size_max_error, maxHiddenMb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ─── Step 13: Hidden Password ─────────────────────────────────────────────────

@Composable
fun StepHiddenPassword(state: CreateContainerState, onUpdate: (CreateContainerState.() -> CreateContainerState) -> Unit) {
    var showPwd by remember { mutableStateOf(false) }
    val mismatch = state.hiddenConfirmPassword.isNotEmpty() && state.hiddenPassword != state.hiddenConfirmPassword

    StepContent(title = stringResource(R.string.create_hidden_pwd_title), subtitle = stringResource(R.string.create_hidden_pwd_subtitle)) {

        Surface(
            shape    = RoundedCornerShape(12.dp),
            color    = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                Text(
                    text  = stringResource(R.string.create_hidden_pwd_same_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value               = state.hiddenPassword,
            onValueChange       = { onUpdate { copy(hiddenPassword = it) } },
            label               = { Text(stringResource(R.string.create_hidden_pwd_label)) },
            singleLine          = true,
            visualTransformation = if (showPwd) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon        = {
                IconButton(onClick = { showPwd = !showPwd }) {
                    Icon(
                        imageVector = if (showPwd) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = null
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value                = state.hiddenConfirmPassword,
            onValueChange        = { onUpdate { copy(hiddenConfirmPassword = it) } },
            label                = { Text(stringResource(R.string.create_hidden_pwd_confirm_label)) },
            singleLine           = true,
            isError              = mismatch,
            supportingText       = if (mismatch) { { Text(stringResource(R.string.create_hidden_pwd_mismatch)) } } else null,
            visualTransformation = PasswordVisualTransformation(),
            modifier             = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value         = if (state.hiddenPim > 0) state.hiddenPim.toString() else "",
            onValueChange = { v ->
                onUpdate { copy(hiddenPim = v.filter { c -> c.isDigit() }.toIntOrNull() ?: 0) }
            },
            label           = { Text(stringResource(R.string.create_hidden_pim_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine      = true,
            modifier        = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text  = stringResource(R.string.create_hidden_pim_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ─── Step 14: Hidden Entropy ──────────────────────────────────────────────────

private const val HIDDEN_ENTROPY_REQUIRED = 500
private data class HiddenParticle(val offset: Offset, val birthMs: Long)

@Composable
fun StepHiddenEntropy(state: CreateContainerState, onAddPoint: (Int, Int) -> Unit) {
    val particles   = remember { mutableStateListOf<HiddenParticle>() }
    val uniquePts   = remember { mutableSetOf<Pair<Int, Int>>() }
    val progress    = (state.hiddenEntropyPoints / HIDDEN_ENTROPY_REQUIRED.toFloat()).coerceIn(0f, 1f)
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
        subtitle = stringResource(R.string.create_hidden_entropy_subtitle)
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
                            val gridX = (pos.x / 10).toInt()
                            val gridY = (pos.y / 10).toInt()
                            if (uniquePts.add(gridX to gridY)) {
                                onAddPoint(gridX, gridY)
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            particles.add(HiddenParticle(pos, System.currentTimeMillis()))
                        }
                    }
            ) {
                val now = System.currentTimeMillis()
                particles.forEach { p ->
                    val age    = (now - p.birthMs) / 1000f
                    val alpha  = (1f - age).coerceIn(0f, 1f)
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

// ─── Step 15: Creating Hidden Volume ─────────────────────────────────────────

@Composable
fun StepCreatingHidden(state: CreateContainerState) {
    Box(
        modifier         = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(
                modifier    = Modifier.size(80.dp),
                strokeWidth = 6.dp
            )
            Text(
                text       = stringResource(R.string.create_hidden_creating_title),
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign  = TextAlign.Center
            )
            Text(
                text      = stringResource(R.string.create_hidden_creating_body, state.hiddenSizeMb),
                style     = MaterialTheme.typography.bodyMedium,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text  = state.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ─── Step 16: Hidden Volume Success ───────────────────────────────────────────

@Composable
fun StepSuccessHidden(state: CreateContainerState, onDone: () -> Unit, onOpenVault: () -> Unit) {
    val haptic      = LocalHapticFeedback.current
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.success_check))
    val progress    by animateLottieCompositionAsState(composition = composition, iterations = 1)

    androidx.compose.runtime.LaunchedEffect(Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp, vertical = 24.dp)
    ) {
                LottieAnimation(
                    composition = composition,
                    progress    = { progress },
                    modifier    = Modifier.size(160.dp)
                )
                Text(
                    text       = stringResource(R.string.create_hidden_done_title),
                    style      = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign  = TextAlign.Center
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text      = state.fileName,
                    style     = MaterialTheme.typography.titleMedium,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text      = stringResource(R.string.create_hidden_done_summary, state.algorithm.displayName, state.sizeMb, state.hiddenAlgorithm.displayName, state.hiddenSizeMb),
                    style     = MaterialTheme.typography.bodyMedium,
                    color     = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(20.dp))
                Surface(
                    shape    = RoundedCornerShape(12.dp),
                    color    = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(
                                Icons.Outlined.Warning,
                                contentDescription = null,
                                tint     = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text       = stringResource(R.string.create_hidden_important_title),
                                style      = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color      = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Text(
                            text  = stringResource(R.string.create_hidden_done_warning_1),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text  = stringResource(R.string.create_hidden_done_warning_2),
                            style      = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color      = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text  = stringResource(R.string.create_hidden_done_warning_3),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                Spacer(Modifier.height(28.dp))
                Button(
                    onClick  = onDone,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape    = CircleShape
                ) {
                    Text(stringResource(R.string.common_done), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.height(12.dp))
        TextButton(
            onClick  = onOpenVault,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.common_open_vault), style = MaterialTheme.typography.labelLarge)
        }
    }
}
