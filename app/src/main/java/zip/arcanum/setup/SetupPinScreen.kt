package zip.arcanum.setup

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import zip.arcanum.R
import zip.arcanum.core.security.AppPasswordPolicy
import zip.arcanum.core.ui.AppPasswordInputMode
import zip.arcanum.core.ui.AppPasswordInputModeButton
import zip.arcanum.core.ui.AppPasswordKeyboardField

@Composable
fun SetupPinScreen(
    onPinSet: () -> Unit,
    viewModel: SetupPinViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    AnimatedContent(
        targetState   = state.isSuccess,
        transitionSpec = {
            (scaleIn(initialScale = 0.85f, animationSpec = spring()) + fadeIn()) togetherWith
            fadeOut(animationSpec = tween(150))
        },
        label = "pinSetupContent"
    ) { isSuccess ->
        if (isSuccess) {
            PinSuccessContent(onGetStarted = onPinSet)
        } else {
            PinEntryContent(state = state, viewModel = viewModel)
        }
    }
}

@Composable
private fun PinSuccessContent(onGetStarted: () -> Unit) {
    BackHandler(enabled = true) { /* block back — user must tap I understand */ }

    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.success_check))
    val progress    by animateLottieCompositionAsState(composition = composition, iterations = 1)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
    ) {
        Column(
            modifier            = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            LottieAnimation(
                composition = composition,
                progress    = { progress },
                modifier    = Modifier.size(160.dp)
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text       = stringResource(R.string.setup_pin_success_title),
                style      = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color      = MaterialTheme.colorScheme.onBackground,
                textAlign  = TextAlign.Center
            )

            Spacer(Modifier.height(12.dp))

            Text(
                text      = stringResource(R.string.setup_pin_success_desc),
                style     = MaterialTheme.typography.bodyLarge,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }

        Button(
            onClick  = onGetStarted,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 40.dp, vertical = 24.dp)
                .height(52.dp),
            shape    = CircleShape
        ) {
            Text(
                text       = stringResource(R.string.setup_pin_success_understood),
                style      = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun PinEntryContent(
    state: SetupPinViewModel.State,
    viewModel: SetupPinViewModel
) {
    var inputMode by remember { mutableStateOf(AppPasswordInputMode.Numeric) }
    val titleText = when {
        state.isError -> stringResource(R.string.pin_mismatch)
        state.step == SetupPinViewModel.Step.ENTER -> stringResource(R.string.setup_pin_title)
        else -> stringResource(R.string.setup_pin_confirm_title)
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
            .systemBarsPadding()
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text  = titleText,
                style = MaterialTheme.typography.headlineSmall,
                color = titleColor
            )
            Spacer(Modifier.height(28.dp))
            if (inputMode == AppPasswordInputMode.Numeric) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    PinDots(pinLength = state.pin.length, isError = state.isError)
                    Spacer(Modifier.size(12.dp))
                    AppPasswordInputModeButton(
                        mode = inputMode,
                        onModeChange = { inputMode = it },
                        enabled = !state.isSaving,
                        modifier = Modifier.size(48.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.app_password_numeric_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            } else {
                AppPasswordKeyboardField(
                    value = state.pin,
                    onValueChange = viewModel::setPin,
                    enabled = !state.isSaving,
                    isError = state.isError,
                    onUseNumeric = {
                        if (state.pin.length > 6 || state.pin.any { !it.isDigit() }) viewModel.setPin("")
                        inputMode = AppPasswordInputMode.Numeric
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        if (inputMode == AppPasswordInputMode.Numeric) {
            NumPad(
                onDigit     = { if (!state.isSaving) viewModel.onDigit(it) },
                onBackspace = { if (!state.isSaving) viewModel.onBackspace() }
            )
        }

        Button(
            onClick  = viewModel::advance,
            enabled  = AppPasswordPolicy.isValid(state.pin) && !state.isSaving,
            modifier = Modifier.fillMaxWidth().height(52.dp),
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
                    text  = if (state.step == SetupPinViewModel.Step.ENTER) stringResource(R.string.common_continue) else stringResource(R.string.common_confirm),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
internal fun PinDots(pinLength: Int, isError: Boolean) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        repeat(6) { i ->
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
internal fun NumPad(
    onDigit: (Char) -> Unit,
    onBackspace: () -> Unit,
    leftSlot: @Composable () -> Unit = { Spacer(Modifier.size(72.dp)) }
) {
    val haptic = LocalHapticFeedback.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(listOf('1','2','3'), listOf('4','5','6'), listOf('7','8','9')).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                row.forEach { digit ->
                    DigitKey(digit, onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDigit(digit)
                    })
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            leftSlot()
            DigitKey('0', onClick = {
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
internal fun DigitKey(digit: Char, onClick: () -> Unit) {
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
