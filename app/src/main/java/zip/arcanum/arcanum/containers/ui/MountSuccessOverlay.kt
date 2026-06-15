package zip.arcanum.arcanum.containers.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import zip.arcanum.R

private val GreenLock = Color(0xFF16A34A)

/**
 * Full-screen overlay shown after a successful vault mount.
 *
 * Sequence (mirror of UnmountAnimationOverlay, but lock goes closed → open):
 *  1. White closed lock bounces in
 *  2. Lock opens → turns green → haptic
 *  3. "Vault Unlocked" text fades in
 *  4. [onUnlockAnimationComplete] — scanning starts in background
 *  5. Text crossfades to "Indexing Vault" + live scan status
 *  6. Overlay is dismissed by AppNavigation when [phase] reaches ScanComplete
 */
@Composable
fun MountSuccessOverlay(
    phase: MountCoordinator.Phase,
    onUnlockAnimationComplete: () -> Unit
) {
    BackHandler(enabled = true) { /* block back during entire sequence */ }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val haptic                        = LocalHapticFeedback.current
    val currentOnUnlockComplete       by rememberUpdatedState(onUnlockAnimationComplete)

    val lockScale  = remember { Animatable(0f) }
    val lockAlpha  = remember { Animatable(0f) }
    val textAlpha  = remember { Animatable(0f) }

    var lockOpen       by remember { mutableStateOf(false) }  // false = Lock, true = LockOpen
    var lockColorGreen by remember { mutableStateOf(false) }  // false = white, true = green

    val lockColor by animateColorAsState(
        targetValue   = if (lockColorGreen) GreenLock else Color.White,
        animationSpec = tween(350),
        label         = "lock_color"
    )

    LaunchedEffect(Unit) {
        // 1. Closed white lock bounces in
        launch { lockAlpha.animateTo(1f, tween(180)) }
        lockScale.animateTo(1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMediumLow))
        delay(400)

        // 2. Lock opens, turns green, haptic
        lockOpen       = true
        lockColorGreen = true
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        delay(300)

        // 3. "Vault Unlocked" text fades in
        textAlpha.animateTo(1f, tween(300))
        delay(700)

        // 4. Begin scanning (overlay transitions to Indexing text automatically via phase)
        currentOnUnlockComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() }
                    }
                }
            }
    ) {
        // ── Lock icon — centered ─────────────────────────────────────────────
        Box(
            contentAlignment = Alignment.Center,
            modifier         = Modifier.fillMaxSize()
        ) {
            AnimatedContent(
                targetState  = lockOpen,
                transitionSpec = {
                    (scaleIn(spring(Spring.DampingRatioMediumBouncy)) + fadeIn(tween(200))) togetherWith
                    (scaleOut(tween(150)) + fadeOut(tween(150)))
                },
                label = "lock_icon"
            ) { open ->
                Icon(
                    imageVector        = if (open) Icons.Outlined.LockOpen else Icons.Outlined.Lock,
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

        // ── Status text — 68 dp below icon center ────────────────────────────
        Box(
            contentAlignment = Alignment.Center,
            modifier         = Modifier.fillMaxSize()
        ) {
            Box(
                modifier         = Modifier
                    .offset(y = 68.dp)
                    .graphicsLayer { alpha = textAlpha.value }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    when (phase) {
                        is MountCoordinator.Phase.Unlocking -> {
                            Text(
                                text       = stringResource(R.string.mount_vault_unlocked),
                                style      = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color      = Color.White
                            )
                        }
                        is MountCoordinator.Phase.Indexing -> {
                            Text(
                                text       = stringResource(R.string.mount_indexing_vault),
                                style      = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color      = Color.White
                            )
                            Spacer(Modifier.height(6.dp))
                            val mediaFilesStr = pluralStringResource(R.plurals.mount_media_files, phase.found, phase.found)
                            val statusText = buildString {
                                if (phase.found > 0) append(mediaFilesStr)
                                if (phase.currentPath.isNotEmpty()) {
                                    if (isNotEmpty()) append(" · ")
                                    append(phase.currentPath.substringAfterLast('/'))
                                }
                            }
                            Text(
                                text      = statusText.ifEmpty { stringResource(R.string.mount_scanning) },
                                style     = MaterialTheme.typography.bodySmall,
                                color     = Color.White.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center,
                                maxLines  = 1,
                                overflow  = TextOverflow.Ellipsis
                            )
                        }
                        is MountCoordinator.Phase.ScanComplete -> {
                            Text(
                                text       = stringResource(R.string.mount_indexing_vault),
                                style      = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                                color      = Color.White
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text  = stringResource(R.string.common_done),
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.White.copy(alpha = 0.6f)
                            )
                        }
                        else -> {}
                    }
                }
            }
        }
    }
}
