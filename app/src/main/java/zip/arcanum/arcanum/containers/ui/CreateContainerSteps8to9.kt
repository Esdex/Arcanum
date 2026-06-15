package zip.arcanum.arcanum.containers.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import zip.arcanum.R
import androidx.compose.ui.res.stringResource

// ─── Step 8: Creating ────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepCreating(state: CreateContainerState) {
    val writtenMb = (state.sizeMb * state.creationProgress).toLong()
    val pct       = (state.creationProgress * 100).toInt()

    Box(
        modifier         = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    progress     = { state.creationProgress },
                    modifier     = Modifier.size(120.dp),
                    strokeWidth  = 8.dp,
                    trackColor   = MaterialTheme.colorScheme.surfaceVariant
                )
                Text(
                    text      = "$pct%",
                    style     = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(state.fileName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (state.creationSpeed.isNotBlank()) {
                Text(state.creationSpeed, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.creationTimeRemaining.isNotBlank()) {
                Text(state.creationTimeRemaining, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                "${writtenMb.formatSize()} / ${state.sizeMb.formatSize()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ─── Step 9: Success ─────────────────────────────────────────────────────────

@Composable
fun StepSuccess(state: CreateContainerState, onDone: () -> Unit, onOpenVault: () -> Unit) {
    val haptic      = LocalHapticFeedback.current
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.success_check))
    val progress    by animateLottieCompositionAsState(composition = composition, iterations = 1)

    androidx.compose.runtime.LaunchedEffect(Unit) {
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    Box(
        modifier         = Modifier.fillMaxSize().padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            LottieAnimation(
                composition = composition,
                progress    = { progress },
                modifier    = Modifier.size(180.dp)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text       = stringResource(R.string.create_done_title),
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
                text      = "${state.algorithm.displayName} · ${state.sizeMb.formatSize()}",
                style     = MaterialTheme.typography.bodyMedium,
                color     = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(40.dp))
            Button(
                onClick  = onDone,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape    = CircleShape
            ) {
                Text(stringResource(R.string.common_done), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.TextButton(
                onClick  = onOpenVault,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.common_open_vault), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private fun Long.formatSize(): String {
    val mb = this.toDouble()
    return if (mb >= 1024) "${"%.1f".format(mb / 1024)} GB" else "${mb.toLong()} MB"
}
