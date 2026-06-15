package zip.arcanum.settings

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.delay
import zip.arcanum.R

@Composable
fun ChangePinScreen(
    onBack: () -> Unit,
    viewModel: ChangePinViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    AnimatedContent(
        targetState   = state.isSuccess,
        transitionSpec = {
            (scaleIn(initialScale = 0.85f, animationSpec = spring()) + fadeIn()) togetherWith
            fadeOut(animationSpec = tween(150))
        },
        label = "changePinContent"
    ) { isSuccess ->
        if (isSuccess) {
            ChangePinSuccess(onDismiss = onBack)
        } else {
            ChangePinEntry(
                state    = state,
                onBack   = onBack,
                viewModel = viewModel
            )
        }
    }
}

// ── Success view ──────────────────────────────────────────────────────────────

@Composable
private fun ChangePinSuccess(onDismiss: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(2000)
        onDismiss()
    }

    Column(
        modifier            = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier         = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = Icons.Default.Check,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onPrimary,
                modifier           = Modifier.size(52.dp)
            )
        }

        Spacer(Modifier.height(32.dp))

        Text(
            text       = stringResource(R.string.change_pin_success_title),
            style      = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color      = MaterialTheme.colorScheme.onBackground,
            textAlign  = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text      = stringResource(R.string.change_pin_success_desc),
            style     = MaterialTheme.typography.bodyLarge,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ── PIN entry ─────────────────────────────────────────────────────────────────

@Composable
private fun ChangePinEntry(
    state: ChangePinViewModel.State,
    onBack: () -> Unit,
    viewModel: ChangePinViewModel
) {
    // Shake offset driven by errorShake counter
    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(state.errorShake) {
        if (state.errorShake > 0) {
            shakeOffset.animateTo(
                targetValue   = 0f,
                animationSpec = keyframes {
                    durationMillis = 400
                    0f    at 0   using FastOutSlowInEasing
                    -20f  at 50  using FastOutSlowInEasing
                    20f   at 100 using FastOutSlowInEasing
                    -16f  at 150 using FastOutSlowInEasing
                    16f   at 200 using FastOutSlowInEasing
                    -8f   at 270 using FastOutSlowInEasing
                    8f    at 320 using FastOutSlowInEasing
                    0f    at 400 using FastOutSlowInEasing
                }
            )
        }
    }

    val titleText = when (state.step) {
        ChangePinViewModel.Step.VERIFY_CURRENT -> if (state.isError) stringResource(R.string.change_pin_wrong) else stringResource(R.string.change_pin_enter_current)
        ChangePinViewModel.Step.ENTER_NEW      -> if (state.isError) stringResource(R.string.pin_mismatch) else stringResource(R.string.change_pin_enter_new)
        ChangePinViewModel.Step.CONFIRM_NEW    -> stringResource(R.string.change_pin_confirm_new)
    }
    val titleColor by animateColorAsState(
        targetValue   = if (state.isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onBackground,
        animationSpec = tween(200),
        label         = "title_color"
    )

    Column(
        modifier            = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar row
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint               = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text      = stringResource(R.string.settings_security_change_pin),
                style     = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color     = MaterialTheme.colorScheme.onBackground
            )
        }

        // Content area
        AnimatedContent(
            targetState   = state.step,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally(tween(300)) { if (forward) it else -it } + fadeIn(tween(300))) togetherWith
                (slideOutHorizontally(tween(300)) { if (forward) -it else it } + fadeOut(tween(200)))
            },
            modifier = Modifier.weight(1f),
            label    = "changePinStep"
        ) { step ->
            Column(
                modifier            = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.offset { IntOffset(shakeOffset.value.toInt(), 0) }
                ) {
                    Text(
                        text  = titleText,
                        style = MaterialTheme.typography.headlineSmall,
                        color = titleColor
                    )
                    Spacer(Modifier.height(40.dp))
                    ChangePinDots(
                        pinLength = state.pin.length,
                        isError   = state.isError
                    )
                    if (step == ChangePinViewModel.Step.ENTER_NEW) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text  = stringResource(R.string.change_pin_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                ChangePinNumPad(
                    onDigit     = viewModel::onDigit,
                    onBackspace = viewModel::onBackspace
                )

                Button(
                    onClick  = viewModel::advance,
                    enabled  = state.pin.length >= 4,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape    = CircleShape
                ) {
                    Text(
                        text  = when (step) {
                            ChangePinViewModel.Step.VERIFY_CURRENT -> stringResource(R.string.common_continue)
                            ChangePinViewModel.Step.ENTER_NEW      -> stringResource(R.string.common_continue)
                            ChangePinViewModel.Step.CONFIRM_NEW    -> stringResource(R.string.common_confirm)
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

// ── PIN dots ──────────────────────────────────────────────────────────────────

@Composable
private fun ChangePinDots(pinLength: Int, isError: Boolean) {
    val displayCount = pinLength.coerceAtLeast(4).coerceAtMost(12)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(displayCount) { i ->
            val filled = i < pinLength
            val size by animateDpAsState(
                targetValue   = if (filled) 14.dp else 12.dp,
                animationSpec = tween(150),
                label         = "dot_size_$i"
            )
            val color by animateColorAsState(
                targetValue = when {
                    isError && filled -> MaterialTheme.colorScheme.error
                    filled            -> MaterialTheme.colorScheme.primary
                    else              -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                },
                animationSpec = tween(150),
                label         = "dot_color_$i"
            )
            Box(
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(color)
            )
        }
    }
}

// ── Numpad ────────────────────────────────────────────────────────────────────

@Composable
private fun ChangePinNumPad(onDigit: (Char) -> Unit, onBackspace: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(listOf('1','2','3'), listOf('4','5','6'), listOf('7','8','9')).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { digit ->
                    ChangePinDigitKey(digit, onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDigit(digit)
                    })
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Spacer(Modifier.size(72.dp))
            ChangePinDigitKey('0', onClick = {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onDigit('0')
            })
            IconButton(
                onClick  = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onBackspace()
                },
                modifier = Modifier.size(72.dp)
            ) {
                Icon(
                    imageVector        = Icons.AutoMirrored.Filled.Backspace,
                    contentDescription = stringResource(R.string.common_delete),
                    tint               = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

@Composable
private fun ChangePinDigitKey(digit: Char, onClick: () -> Unit) {
    TextButton(
        onClick  = onClick,
        modifier = Modifier.size(72.dp),
        shape    = CircleShape
    ) {
        Text(
            text  = digit.toString(),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}
