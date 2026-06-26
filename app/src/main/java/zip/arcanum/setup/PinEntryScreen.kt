package zip.arcanum.setup

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import zip.arcanum.R

@Composable
fun PinEntryScreen(
    onAuthenticated: () -> Unit,
    viewModel: PinEntryViewModel = hiltViewModel()
) {
    val state                 by viewModel.state.collectAsState()
    val biometricUnlockEnabled by viewModel.biometricUnlockEnabled.collectAsState()
    val context               = LocalContext.current
    val haptic                = LocalHapticFeedback.current
    var pin                   by remember { mutableStateOf("") }

    val isVerifying = state is PinEntryState.Verifying
    val isError     = state is PinEntryState.WrongPin || state is PinEntryState.Locked

    val titleText = when (val s = state) {
        is PinEntryState.WrongPin   -> stringResource(R.string.pin_entry_wrong)
        is PinEntryState.Locked     -> stringResource(R.string.pin_entry_locked, s.remainingSec)
        else                        -> stringResource(R.string.pin_entry_title)
    }
    val titleColor by animateColorAsState(
        targetValue   = if (isError) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onBackground,
        animationSpec = tween(200),
        label         = "title_color"
    )

    // Auto-prompt biometric whenever the screen enters the foreground.
    // repeatOnLifecycle(RESUMED) re-runs the block on every ON_RESUME transition, which
    // covers two cases: (1) user manually locks while app is in the foreground — RESUMED
    // is already active so the block fires immediately; (2) auto-lock fires while the app
    // is in the background — the block is suspended until the user brings the app back,
    // ensuring BiometricPrompt is only shown while the app is visible.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        if (!viewModel.loadInitialBiometricEnabled() || !viewModel.isBiometricAvailable) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            (context as? FragmentActivity)?.let { activity ->
                viewModel.authenticate(activity) { onAuthenticated() }
            }
        }
    }

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
            Spacer(Modifier.height(40.dp))
            PinDots(pinLength = pin.length, isError = isError)
        }

        val showBiometric = viewModel.isBiometricAvailable

        NumPad(
            onDigit     = {
                if (!isVerifying && pin.length < 6) {
                    pin = pin + it
                    if (isError) viewModel.clearError()
                }
            },
            onBackspace = {
                if (!isVerifying) {
                    pin = pin.dropLast(1)
                    if (isError) viewModel.clearError()
                }
            },
            leftSlot = {
                if (showBiometric) {
                    IconButton(
                        onClick  = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            (context as? FragmentActivity)?.let { activity ->
                                if (pin.isNotEmpty()) {
                                    // PIN entered → register biometric
                                    viewModel.registerBiometricWithPin(pin, activity) {
                                        onAuthenticated()
                                    }
                                } else if (biometricUnlockEnabled) {
                                    // No PIN → just unlock via biometric (already registered)
                                    viewModel.authenticate(activity) { onAuthenticated() }
                                }
                            }
                        },
                        modifier = Modifier.size(72.dp)
                    ) {
                        Icon(
                            imageVector        = Icons.Outlined.Fingerprint,
                            contentDescription = stringResource(R.string.pin_entry_biometric),
                            tint               = MaterialTheme.colorScheme.onBackground,
                            modifier           = Modifier.size(32.dp)
                        )
                    }
                } else {
                    Spacer(Modifier.size(72.dp))
                }
            }
        )

        Button(
            onClick  = {
                if (pin.isNotBlank() && !isVerifying) {
                    viewModel.submitPin(pin) { onAuthenticated() }
                }
            },
            enabled  = pin.isNotBlank() && !isVerifying,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape    = CircleShape
        ) {
            if (isVerifying) {
                CircularProgressIndicator(
                    modifier    = Modifier.size(20.dp),
                    color       = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text       = stringResource(R.string.pin_entry_unlock),
                    style      = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}
