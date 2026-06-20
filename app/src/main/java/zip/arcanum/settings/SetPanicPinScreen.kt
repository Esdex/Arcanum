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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.outlined.Warning
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import zip.arcanum.R
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun SetPanicPinScreen(
    onBack: () -> Unit,
    onSuccess: () -> Unit,
    viewModel: SetPanicPinViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) { viewModel.reset() }

    BackHandler(enabled = state.isSuccess) { /* block back on success screen */ }

    AnimatedContent(
        targetState  = state.isSuccess,
        transitionSpec = {
            (scaleIn(initialScale = 0.85f, animationSpec = spring()) + fadeIn()) togetherWith
            fadeOut(animationSpec = tween(150))
        },
        label = "setPanicPinContent"
    ) { isSuccess ->
        if (isSuccess) {
            SetPanicPinSuccess(onDismiss = onSuccess)
        } else {
            SetPanicPinEntry(
                state     = state,
                onBack    = onBack,
                viewModel = viewModel
            )
        }
    }
}

@Composable
private fun SetPanicPinSuccess(onDismiss: () -> Unit) {
    val warningAmber = Color(0xFFFFB300)
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.success_check))
    val progress    by animateLottieCompositionAsState(composition = composition, iterations = 1)

    Column(
        modifier            = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.height(32.dp))

        LottieAnimation(
            composition = composition,
            progress    = { progress },
            modifier    = Modifier.size(160.dp)
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text       = stringResource(R.string.panic_pin_success_title),
            style      = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color      = MaterialTheme.colorScheme.onBackground,
            textAlign  = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text      = stringResource(R.string.panic_pin_success_desc),
            style     = MaterialTheme.typography.bodyLarge,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        // Warning box
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, warningAmber, RoundedCornerShape(12.dp))
                .background(warningAmber.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector        = Icons.Outlined.Warning,
                contentDescription = null,
                tint               = warningAmber,
                modifier           = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text  = stringResource(R.string.panic_pin_success_warning),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick  = onDismiss,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape    = CircleShape
        ) {
            Text(
                text  = stringResource(R.string.panic_pin_success_confirm),
                style = MaterialTheme.typography.labelLarge
            )
        }

        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SetPanicPinEntry(
    state: SetPanicPinViewModel.State,
    onBack: () -> Unit,
    viewModel: SetPanicPinViewModel
) {
    val shakeOffset = remember { Animatable(0f) }
    LaunchedEffect(state.errorShake) {
        if (state.errorShake > 0) {
            shakeOffset.animateTo(
                targetValue   = 0f,
                animationSpec = keyframes {
                    durationMillis = 400
                    0f   at 0   using FastOutSlowInEasing
                    -20f at 50  using FastOutSlowInEasing
                    20f  at 100 using FastOutSlowInEasing
                    -16f at 150 using FastOutSlowInEasing
                    16f  at 200 using FastOutSlowInEasing
                    -8f  at 270 using FastOutSlowInEasing
                    8f   at 320 using FastOutSlowInEasing
                    0f   at 400 using FastOutSlowInEasing
                }
            )
        }
    }

    val titleText = when {
        state.isError                                     -> state.errorMessage
        state.step == SetPanicPinViewModel.Step.ENTER -> stringResource(R.string.panic_pin_title)
        else                                              -> stringResource(R.string.panic_pin_confirm_title)
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
                text       = stringResource(R.string.panic_pin_title),
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color      = MaterialTheme.colorScheme.onBackground
            )
        }

        Column(
            modifier            = Modifier
                .weight(1f)
                .fillMaxWidth()
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
                PinDots(pinLength = state.pin.length, isError = state.isError)
                if (state.step == SetPanicPinViewModel.Step.ENTER) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text  = stringResource(R.string.panic_pin_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }

            PinNumPad(
                onDigit     = { if (!state.isSaving) viewModel.onDigit(it) },
                onBackspace = { if (!state.isSaving) viewModel.onBackspace() }
            )

            Button(
                onClick  = viewModel::advance,
                enabled  = state.pin.length >= 4 && !state.isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape    = CircleShape
            ) {
                if (state.isSaving) {
                    CircularProgressIndicator(
                        modifier    = Modifier.size(20.dp),
                        color       = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(
                        text  = if (state.step == SetPanicPinViewModel.Step.ENTER) stringResource(R.string.panic_pin_btn_continue) else stringResource(R.string.panic_pin_btn_confirm),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}

@Composable
private fun PinDots(pinLength: Int, isError: Boolean) {
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

@Composable
private fun PinNumPad(onDigit: (Char) -> Unit, onBackspace: () -> Unit) {
    val haptic = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(listOf('1','2','3'), listOf('4','5','6'), listOf('7','8','9')).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { digit ->
                    PinDigitKey(digit, onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDigit(digit)
                    })
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            Spacer(Modifier.size(72.dp))
            PinDigitKey('0', onClick = {
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
                    contentDescription = stringResource(R.string.panic_pin_cd_backspace),
                    tint               = MaterialTheme.colorScheme.onBackground
                )
            }
        }
    }
}

@Composable
private fun PinDigitKey(digit: Char, onClick: () -> Unit) {
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
