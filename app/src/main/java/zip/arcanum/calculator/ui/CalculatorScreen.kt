package zip.arcanum.calculator.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.withTimeoutOrNull

private val buttons = listOf(
    listOf("AC", "+/-", "%", "÷"),
    listOf("7",  "8",  "9",  "×"),
    listOf("4",  "5",  "6",  "-"),
    listOf("1",  "2",  "3",  "+"),
    listOf("⌫",  "0",  ".",  "=")
)

private const val CALCULATOR_UNLOCK_HOLD_MS = 7_000L

@Composable
fun CalculatorScreen(
    onAuthenticated: () -> Unit,
    viewModel: CalculatorViewModel = hiltViewModel()
) {
    val displayUiState by viewModel.displayUiState.collectAsState()
    val isVerifying    by viewModel.isVerifying.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                CalculatorEvent.NavigateToArcanum -> onAuthenticated()
                CalculatorEvent.TriggerPanic      -> Unit
            }
        }
    }

    // Line-1 color: accent when showing a fresh result, default otherwise
    val line1Color by animateColorAsState(
        targetValue   = if (displayUiState.isResult) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onBackground,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "line1Color"
    )

    BoxWithConstraints(
        modifier            = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding()
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        val buttonSpacing = 6.dp
        val buttonByWidth = (maxWidth - buttonSpacing * 3) / 4
        val buttonByHeight = (maxHeight * 0.66f - buttonSpacing * 4) / 5
        val buttonSize = minOf(buttonByWidth, buttonByHeight).coerceAtMost(88.dp)

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Bottom
        ) {
            // ── Display area ──────────────────────────────────────────────────
            AnimatedContent(
                targetState   = displayUiState.isResult,
                transitionSpec = {
                    if (targetState) {
                        // "=" pressed → result slides up from line-2 position
                        (slideInVertically(
                            initialOffsetY = { it / 2 },
                            animationSpec  = spring(
                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                stiffness    = Spring.StiffnessMedium
                            )
                        ) + fadeIn(animationSpec = tween(250))) togetherWith
                        (slideOutVertically(
                            targetOffsetY = { -it / 4 },
                            animationSpec = tween(200)
                        ) + fadeOut(animationSpec = tween(200)))
                    } else {
                        // User starts typing after "=" → new expression fades in
                        fadeIn(animationSpec = tween(180)) togetherWith
                        fadeOut(animationSpec = tween(150))
                    }
                },
                modifier         = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                contentAlignment = Alignment.BottomEnd,
                label            = "displayContent"
            ) { isResultState ->
                Column(
                    modifier            = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End
                ) {
                    // Line 1 — expression or fresh result (large, auto-shrink)
                    val fontSizes = listOf(64.sp, 52.sp, 42.sp, 34.sp)
                    var fontIndex by remember { mutableIntStateOf(0) }
                    val prevLen = remember { mutableIntStateOf(displayUiState.expressionText.length) }
                    if (displayUiState.expressionText.length < prevLen.intValue) fontIndex = 0
                    prevLen.intValue = displayUiState.expressionText.length

                    Text(
                        text         = displayUiState.expressionText,
                        style        = MaterialTheme.typography.displayLarge.copy(
                            fontSize = fontSizes[fontIndex]
                        ),
                        fontWeight   = FontWeight.Light,
                        color        = line1Color,
                        textAlign    = TextAlign.End,
                        maxLines     = 1,
                        softWrap     = false,
                        overflow     = TextOverflow.Ellipsis,
                        onTextLayout = { result ->
                            if (result.hasVisualOverflow && fontIndex < fontSizes.lastIndex) {
                                fontIndex++
                            }
                        }
                    )

                    // Line 2 — live result preview (visible while typing, hidden after "=")
                    if (!isResultState && displayUiState.resultText.isNotEmpty()) {
                        Text(
                            text      = displayUiState.resultText,
                            style     = MaterialTheme.typography.headlineMedium,
                            color     = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.End,
                            maxLines  = 1,
                            softWrap  = false,
                            overflow  = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // ── Button grid ───────────────────────────────────────────────────
            Column(verticalArrangement = Arrangement.spacedBy(buttonSpacing)) {
                buttons.forEach { row ->
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(buttonSpacing, Alignment.CenterHorizontally)
                    ) {
                        row.forEach { label ->
                            if (label == "=") {
                                EqualsButton(
                                    isVerifying = isVerifying,
                                    onClick     = { viewModel.onInput("=") },
                                    onLongPress = { viewModel.onLongPressEquals() },
                                    modifier    = Modifier.size(buttonSize)
                                )
                            } else {
                                CalculatorButton(
                                    label    = label,
                                    modifier = Modifier.size(buttonSize),
                                    enabled  = !isVerifying,
                                    onClick  = { viewModel.onInput(label) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// Suppress: same pattern as Compose's own detectTapGestures — two sequential
// awaitPointerEventScope blocks are safe here because the first exits immediately
// after DOWN and the second begins before any subsequent event can arrive.
@Suppress("MultipleAwaitPointerEventScopes")
@Composable
private fun EqualsButton(
    isVerifying: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg     = MaterialTheme.colorScheme.primary
    val fg     = MaterialTheme.colorScheme.onPrimary
    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(if (isVerifying) bg.copy(alpha = 0.6f) else bg)
            .pointerInput(isVerifying) {
                if (isVerifying) return@pointerInput
                while (true) {
                    // Wait for finger down
                    awaitPointerEventScope {
                        awaitFirstDown(requireUnconsumed = false)
                    }
                    // Race: finger release vs deliberate hidden-entry hold.
                    val released = withTimeoutOrNull(CALCULATOR_UNLOCK_HOLD_MS) {
                        awaitPointerEventScope { waitForUpOrCancellation() }
                    }
                    when {
                        released != null -> {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClick()
                        }
                        else -> {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onLongPress()
                            awaitPointerEventScope { waitForUpOrCancellation() }
                        }
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        if (isVerifying) {
            CircularProgressIndicator(
                modifier    = Modifier.size(28.dp),
                color       = fg,
                strokeWidth = 2.5.dp
            )
        } else {
            Text(
                text       = "=",
                style      = MaterialTheme.typography.headlineSmall.copy(fontSize = 40.sp),
                color      = fg,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun CalculatorButton(
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val isOperator = label in listOf("÷", "×", "-", "+")
    val isFunction = label in listOf("AC", "+/-", "%")

    val bg = when {
        isOperator -> MaterialTheme.colorScheme.primaryContainer
        isFunction -> MaterialTheme.colorScheme.secondaryContainer
        else       -> MaterialTheme.colorScheme.surfaceVariant
    }
    val fg = when {
        isOperator -> MaterialTheme.colorScheme.onPrimaryContainer
        isFunction -> MaterialTheme.colorScheme.onSecondaryContainer
        else       -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    val haptic = LocalHapticFeedback.current

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .clickable(enabled = enabled) {
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text       = label,
            style      = MaterialTheme.typography.headlineSmall.copy(fontSize = 40.sp),
            color      = if (enabled) fg else fg.copy(alpha = 0.4f),
            fontWeight = FontWeight.Medium
        )
    }
}
