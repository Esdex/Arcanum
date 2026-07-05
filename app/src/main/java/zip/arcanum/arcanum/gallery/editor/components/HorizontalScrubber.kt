/*
 * Portions of this file are derived from the Gallery project by IacobIonut01.
 * https://github.com/IacobIonut01/Gallery
 *
 * Copyright 2023 IacobIonut01
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modifications: added resetKey for per-tool state isolation, programmaticScroll
 * guard for undo animations, refactored valueToIndex/indexToValue. — Arcanum project, 2026.
 */

package zip.arcanum.arcanum.gallery.editor.components

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private fun Modifier.horizontalFadingEdge(fraction: Float = 0.2f) = this.composed {
    graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val fadeW = size.width * fraction
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent, 1f to Color.Black,
                    startX = 0f, endX = fadeW
                ),
                blendMode = BlendMode.DstIn
            )
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Black, 1f to Color.Transparent,
                    startX = size.width - fadeW, endX = size.width
                ),
                blendMode = BlendMode.DstIn
            )
        }
}

@Composable
fun HorizontalScrubber(
    modifier: Modifier = Modifier,
    resetKey: Any = Unit,
    allowNegative: Boolean = true,
    minValue: Float = -1f,
    maxValue: Float = 1f,
    defaultValue: Float = 0f,
    currentValue: Float = defaultValue,
    displayValue: (Float) -> String = { (it * 100).roundToInt().toString() },
    spacerWidth: Dp = 3.dp,
    normalWidth: Dp = 1.dp,
    normalHeight: Dp = 10.dp,
    arrowHeight: Dp = 20.dp,
    normalColor: Color = Color.White.copy(alpha = 0.2f),
    highlightedColor: Color = Color.White,
    arrowColor: Color = MaterialTheme.colorScheme.primary,
    onValueChanged: (isScrolling: Boolean, newValue: Float) -> Unit
) {
    val clampedCurrent = currentValue.coerceIn(minValue, maxValue)
    val view = LocalView.current

    val steps    = if (allowNegative) 200 else 100
    val midIndex = if (allowNegative) steps / 2 else 0
    val range    = maxValue - minValue

    // Maps a value in [minValue, maxValue] to a tick index.
    fun valueToIndex(v: Float): Int = if (allowNegative) {
        val half = steps / 2
        if (v >= defaultValue) half + ((v - defaultValue) / (maxValue - defaultValue) * half).roundToInt()
        else half - ((defaultValue - v) / (defaultValue - minValue) * half).roundToInt()
    } else {
        ((v.coerceIn(minValue, maxValue) - minValue) / range * steps).roundToInt()
    }

    // Maps a tick index back to a value in [minValue, maxValue].
    fun indexToValue(i: Int): Float = if (allowNegative) {
        val rawRatio = i.toFloat() / midIndex
        when {
            i < midIndex -> defaultValue - (defaultValue - minValue) * (1f - rawRatio)
            i > midIndex -> defaultValue + (maxValue - defaultValue) * (rawRatio - 1f)
            else         -> defaultValue
        }
    } else {
        minValue + i.toFloat() / steps * range
    }

    // All state is keyed on resetKey so switching filter tools starts fresh.
    var currentInternal by remember(resetKey) { mutableFloatStateOf(clampedCurrent) }
    val state        = remember(resetKey) { LazyListState(firstVisibleItemIndex = valueToIndex(clampedCurrent)) }
    val defaultIndex = remember(resetKey) { valueToIndex(defaultValue) }
    // Prevents snapshotFlow from processing emissions during animateScrollToItem (undo).
    val programmaticScroll = remember(resetKey) { booleanArrayOf(false) }

    LaunchedEffect(state) {
        var userHasScrolled = false
        snapshotFlow { state.firstVisibleItemIndex to state.isScrollInProgress }
            .collect { (index, scrolling) ->
                if (programmaticScroll[0]) return@collect
                if (!userHasScrolled) {
                    if (scrolling) userHasScrolled = true else return@collect
                }
                currentInternal = indexToValue(index)
                onValueChanged(scrolling, currentInternal)
                if (!scrolling && index == defaultIndex)
                    view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
    }

    // Handles external value changes (e.g. undo) by animating the scrubber to the new position.
    LaunchedEffect(clampedCurrent) {
        if (clampedCurrent != currentInternal) {
            programmaticScroll[0] = true
            currentInternal = clampedCurrent
            state.animateScrollToItem(valueToIndex(clampedCurrent))
            programmaticScroll[0] = false
        }
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text     = displayValue(currentInternal),
            style    = MaterialTheme.typography.titleMedium,
            color    = Color.White,
            modifier = Modifier.padding(bottom = 8.dp).align(Alignment.CenterHorizontally)
        )
        BoxWithConstraints(contentAlignment = Alignment.BottomCenter, modifier = Modifier.fillMaxWidth()) {
            val halfW = maxWidth / 2
            LazyRow(
                state          = state,
                modifier       = Modifier.wrapContentWidth().horizontalFadingEdge(0.25f),
                contentPadding = PaddingValues(horizontal = halfW),
                verticalAlignment = Alignment.Bottom,
                flingBehavior  = rememberSnapFlingBehavior(state)
            ) {
                val groups = if (allowNegative) 10 else 5
                repeat(groups) { g ->
                    item {
                        Spacer(Modifier.height(if (g == if (allowNegative) 5 else 0) arrowHeight else normalHeight)
                            .width(normalWidth).background(highlightedColor))
                    }
                    item { Spacer(Modifier.height(normalHeight).width(spacerWidth)) }
                    repeat(9) {
                        item { Spacer(Modifier.height(normalHeight).width(normalWidth).background(normalColor)) }
                        item { Spacer(Modifier.height(normalHeight).width(spacerWidth)) }
                    }
                }
                item { Spacer(Modifier.height(normalHeight).width(normalWidth).background(highlightedColor)) }
            }
            // Center indicator
            Spacer(Modifier.height(arrowHeight).width(normalWidth).background(arrowColor))
        }
    }
}
