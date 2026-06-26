package zip.arcanum.arcanum.containers.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import zip.arcanum.R

/**
 * Full-screen mounting overlay shown while PBKDF2 + AES decryption runs (isError = false)
 * or after a failed attempt (isError = true).
 * Fade in/out is handled by the AnimatedVisibility wrapper in VaultScreen.
 * Blocks back during loading; back acts as dismiss on error.
 */
@Composable
fun MountingOverlay(
    isError: Boolean = false,
    logs: List<String>? = null,
    onCancel: () -> Unit,
    onDismissError: () -> Unit = {}
) {
    BackHandler(enabled = true) {
        if (isError) onDismissError()
        // else: silently block back while decryption is running
    }

    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val sub1 = stringResource(R.string.mount_subtitle_1)
    val sub2 = stringResource(R.string.mount_subtitle_2)
    val sub3 = stringResource(R.string.mount_subtitle_3)
    val sub4 = stringResource(R.string.mount_subtitle_4)
    val sub5 = stringResource(R.string.mount_subtitle_5)
    val subtitles = remember { listOf(sub1, sub2, sub3, sub4, sub5) }
    var subtitleIndex by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            subtitleIndex = (subtitleIndex + 1) % subtitles.size
        }
    }

    LaunchedEffect(isError) {
        if (isError) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                view.performHapticFeedback(HapticFeedbackConstants.REJECT)
            } else {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    val loadingComposition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.vault))
    val loadingProgress    by animateLottieCompositionAsState(
        composition = loadingComposition,
        iterations  = LottieConstants.IterateForever
    )
    val errorComposition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.error))
    val errorProgress    by animateLottieCompositionAsState(
        composition = errorComposition,
        iterations  = 1
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication        = null
            ) { /* absorb touches — prevents click-through to vault list */ }
    ) {
        Crossfade(
            targetState   = isError,
            animationSpec = tween(400),
            modifier      = Modifier.align(Alignment.Center),
            label         = "mount_overlay_state"
        ) { error ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier            = Modifier.padding(horizontal = 40.dp)
            ) {
                if (error) {
                    LottieAnimation(
                        composition = errorComposition,
                        progress    = { errorProgress },
                        modifier    = Modifier.size(180.dp)
                    )

                    Spacer(Modifier.height(36.dp))

                    Text(
                        text       = stringResource(R.string.mount_error_title),
                        style      = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color      = Color.White,
                        textAlign  = TextAlign.Center
                    )

                    Spacer(Modifier.height(14.dp))

                    Text(
                        text      = stringResource(R.string.mount_error_body),
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center
                    )

                    Spacer(Modifier.height(12.dp))

                    listOf(
                        stringResource(R.string.mount_error_reason_password),
                        stringResource(R.string.mount_error_reason_pim),
                        stringResource(R.string.mount_error_reason_prf),
                        stringResource(R.string.mount_error_reason_not_valid),
                        stringResource(R.string.mount_error_reason_old_algo),
                    ).forEach { item ->
                        Text(
                            text     = "• $item",
                            style    = MaterialTheme.typography.bodySmall,
                            color    = Color.White.copy(alpha = 0.5f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp)
                        )
                    }
                } else {
                    LottieAnimation(
                        composition = loadingComposition,
                        progress    = { loadingProgress },
                        modifier    = Modifier.size(180.dp)
                    )

                    Spacer(Modifier.height(36.dp))

                    Text(
                        text       = stringResource(R.string.mount_unlocking),
                        style      = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        color      = Color.White
                    )

                    Spacer(Modifier.height(10.dp))

                    if (logs != null) {
                        MountLogTerminal(logs = logs, modifier = Modifier.fillMaxWidth())
                    } else {
                        AnimatedContent(
                            targetState    = subtitleIndex,
                            transitionSpec = { fadeIn(tween(600)) togetherWith fadeOut(tween(400)) },
                            label          = "subtitle"
                        ) { index ->
                            Text(
                                text      = subtitles[index],
                                style     = MaterialTheme.typography.bodyMedium,
                                color     = Color.White.copy(alpha = 0.6f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }

        // Log terminal (shown in place of rotating subtitle when showMountLog is on)

        // Bottom action — anchored to the bottom
        if (isError) {
            Button(
                onClick  = onDismissError,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
            ) {
                Text(stringResource(R.string.common_ok), style = MaterialTheme.typography.labelLarge)
            }
        } else {
            TextButton(
                onClick  = onCancel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    text  = stringResource(R.string.common_cancel),
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

@Composable
private fun MountLogTerminal(logs: List<String>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
    }

    Box(
        modifier = modifier
            .heightIn(min = 120.dp, max = 220.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0D1117))
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        LazyColumn(state = listState) {
            items(logs) { line ->
                Text(
                    text  = line,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = Color(0xFF57FF81)
                )
            }
            // blinking cursor on the last line
            item {
                BlinkingCursor()
            }
        }
    }
}

@Composable
private fun BlinkingCursor() {
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(530)
            visible = !visible
        }
    }
    Text(
        text  = if (visible) "█" else " ",
        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        color = Color(0xFF57FF81)
    )
}
