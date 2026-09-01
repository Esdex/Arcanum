package zip.arcanum.arcanum.containers.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import zip.arcanum.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val GreenLock = Color(0xFF16A34A)

/**
 * Full-screen unmount overlay.
 *
 * Sequence:
 *  1. Fade to fully black (380 ms) — AppNavigation pops ContainerScreen in parallel
 *  2. Green open-lock bounces in
 *  3. Lock closes → haptic → colour transitions white
 *  4. "Vault Unmounted / Your data is protected" fades in
 *  5. Everything fades out and [onComplete] removes the overlay
 *
 * The lock used to fly from the middle of the screen into the vault's icon in the list
 * behind it, which is why this once took the icon's position and told the caller when it
 * had landed. The flight is gone; what it announces is said by the words.
 */
@Composable
fun UnmountAnimationOverlay(
    onComplete: () -> Unit
) {
    val haptic                  = LocalHapticFeedback.current
    val currentOnComplete      by rememberUpdatedState(onComplete)

    val scrimAlpha  = remember { Animatable(0f) }
    val lockScale   = remember { Animatable(0f) }
    val lockAlpha   = remember { Animatable(0f) }
    val textAlpha   = remember { Animatable(0f) }

    var lockClosed     by remember { mutableStateOf(false) }
    var lockColorGreen by remember { mutableStateOf(true) }

    val lockColor by animateColorAsState(
        targetValue   = if (lockColorGreen) GreenLock else Color.White,
        animationSpec = tween(350),
        label         = "lock_color"
    )

    BackHandler(enabled = true) { /* block all back presses during animation */ }

    LaunchedEffect(Unit) {
        // 1. Screen goes fully black
        scrimAlpha.animateTo(1f, tween(380))

        // 2. Green open-lock bounces in
        launch { lockAlpha.animateTo(1f, tween(180)) }
        lockScale.animateTo(
            targetValue   = 1f,
            animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow)
        )
        delay(550)

        // 4. Lock closes: haptic + color white
        lockClosed = true
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        lockColorGreen = false
        delay(500)

        // 5. Text fades in, hold for reading
        textAlpha.animateTo(1f, tween(300))
        delay(900)

        // 6. Everything fades out together
        launch { scrimAlpha.animateTo(0f, tween(520)) }
        launch { textAlpha.animateTo(0f, tween(280)) }
        launch { lockAlpha.animateTo(0f, tween(400)) }
        delay(560)

        // 7. Overlay removed
        currentOnComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(200f)
    ) {
        // Full black scrim
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = scrimAlpha.value))
        )

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            AnimatedContent(
                targetState  = lockClosed,
                transitionSpec = {
                    (scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn(tween(200))) togetherWith
                    (scaleOut(tween(150)) + fadeOut(tween(150)))
                },
                label = "lock_icon"
            ) { closed ->
                Icon(
                    imageVector        = if (closed) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                    contentDescription = null,
                    tint               = lockColor,
                    modifier           = Modifier
                        .size(72.dp)
                        .graphicsLayer {
                            scaleX = lockScale.value
                            scaleY = lockScale.value
                            alpha  = lockAlpha.value
                        }
                )
            }
        }

        // Text — fixed position, fades independently (does not fly)
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .offset(y = 68.dp)
                    .graphicsLayer { alpha = textAlpha.value }
            ) {
                Text(
                    text       = stringResource(R.string.unmount_vault_unmounted),
                    style      = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color      = Color.White
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text  = stringResource(R.string.unmount_data_protected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.65f)
                )
            }
        }
    }
}
