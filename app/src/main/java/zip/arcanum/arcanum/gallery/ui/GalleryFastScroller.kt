package zip.arcanum.arcanum.gallery.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import zip.arcanum.core.database.entities.MediaFileEntity

/**
 * Where each item of the gallery grid sits, for the fast scroller (#191).
 *
 * A lazy grid knows only the items it has laid out, so the scroller cannot ask it how far down
 * the middle of a vault of thousands is. This is that answer, worked out from the same groups the
 * grid is built from: [lineOf] is the row each item index falls on, and [labelOf] what to call
 * the place while the handle is being dragged.
 */
internal class GridIndex(
    val lineOf: IntArray,
    val totalLines: Int,
    /** Where each year starts, as (year, row), in the order the grid shows them. */
    val yearMarks: List<Pair<String, Int>> = emptyList(),
    val labelOf: (Int) -> String?
) {
    /** The year the row [line] belongs to, if the grid is a timeline. */
    fun yearAt(line: Int): String? = yearMarks.lastOrNull { it.second <= line }?.first

    /** The first item on [line] or after it. */
    fun itemAtLine(line: Int): Int {
        var lo = 0
        var hi = lineOf.size - 1
        if (hi < 0) return 0
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (lineOf[mid] < line) lo = mid + 1 else hi = mid
        }
        return lo
    }
}

/** What [layOutGrid] found: the row of every grid item, and which picture and month it is. */
internal class GridLayout(
    val lineOf: IntArray,
    val totalLines: Int,
    val files: List<MediaFileEntity?>,
    val monthOf: List<String?>,
    /** The row each year's first month starts on, as (year, row). */
    val yearStarts: List<Pair<Int, Int>>
)

/**
 * The grid of the gallery, row by row, in the order it emits its items, so it has to follow the
 * same rules: whatever stands before the media takes a whole row each ([leadingItems]), and so
 * does every header; with headers ([grouped]) a day starts a new row, without them the pictures
 * run on three to a row across days. The trailing spacer is the last row.
 */
internal fun layOutGrid(
    months: List<GalleryViewModel.MonthGroup>,
    grouped: Boolean,
    leadingItems: Int,
    columns: Int = 3
): GridLayout {
    val lines   = ArrayList<Int>()
    val files   = ArrayList<MediaFileEntity?>()
    val monthOf = ArrayList<String?>()
    var line = 0
    var run  = 0   // pictures laid since the last full-width row
    fun fullRow(month: String?) {
        if (run > 0) { line += (run + columns - 1) / columns; run = 0 }
        lines += line++; files += null; monthOf += month
    }
    val years = ArrayList<Pair<Int, Int>>()
    repeat(leadingItems) { fullRow(null) }
    for (month in months) {
        val year = month.days.firstOrNull()?.date?.year
        if (year != null && year != years.lastOrNull()?.first) {
            // The row this month's first item lands on: its header comes after the unfinished
            // row is closed; without headers its first picture joins that row.
            years += year to (if (grouped) line + (run + columns - 1) / columns else line + run / columns)
        }
        if (grouped) fullRow(month.month)
        for (day in month.days) {
            if (grouped) fullRow(month.month)
            for (file in day.photos) {
                lines += line + run / columns; files += file; monthOf += month.month
                run++
            }
        }
    }
    fullRow(null)
    return GridLayout(lines.toIntArray(), line, files, monthOf, years)
}

private val HANDLE_TOUCH  = 48.dp
private val HANDLE_HEIGHT = 44.dp
private const val HIDE_AFTER_MS = 1500L
private val YEAR_MIN_GAP    = 22.dp
private val YEAR_LABEL_HALF = 10.dp
/** Room the years take to the left of the handle, so the month label clears them. */
private val YEAR_COLUMN     = 64.dp
/** Below this many screens of content there is nothing for a fast scroller to save. */
private const val MIN_SCREENS = 3

/**
 * A handle on the right edge that drags through the whole grid (#191), the way a phone's own
 * gallery does. It shows itself while the grid scrolls and goes again a moment after; only then
 * does it take touches, so the right-hand column stays tappable the rest of the time.
 */
@Composable
internal fun GalleryFastScroller(
    state: LazyGridState,
    index: GridIndex,
    topPadding: Dp,
    bottomPadding: Dp,
    modifier: Modifier = Modifier
) {
    val scope   = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    val visibleLines by remember(index) {
        derivedStateOf {
            state.layoutInfo.visibleItemsInfo
                .mapNotNull { index.lineOf.getOrNull(it.index) }
                .distinct().size.coerceAtLeast(1)
        }
    }
    val worthIt  = index.totalLines >= visibleLines * MIN_SCREENS
    val range    = (index.totalLines - visibleLines).coerceAtLeast(1)

    // How far down the grid is, 0..1, from the row of the first item on screen and how much of
    // that item has scrolled past.
    val progress by remember(index, range) {
        derivedStateOf {
            if (!state.canScrollForward) return@derivedStateOf 1f
            val first = state.layoutInfo.visibleItemsInfo.firstOrNull() ?: return@derivedStateOf 0f
            val line  = index.lineOf.getOrElse(first.index) { 0 }
            val part  = if (first.size.height > 0)
                (-first.offset.y).toFloat().coerceAtLeast(0f) / first.size.height else 0f
            ((line + part.coerceAtMost(1f)) / range).coerceIn(0f, 1f)
        }
    }

    var dragging     by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }
    /* Fixed for the length of one drag. How many rows fit on screen changes as headers pass,
       and everything the gesture is keyed on restarts it - the handle stopped dead a little way
       into every drag until the finger was lifted, found on device. */
    var dragRange    by remember { mutableIntStateOf(1) }
    var label        by remember { mutableStateOf<String?>(null) }
    var shown        by remember { mutableStateOf(false) }

    LaunchedEffect(state.isScrollInProgress, dragging, worthIt) {
        when {
            dragging                          -> shown = true
            !worthIt                          -> shown = false
            state.isScrollInProgress          -> shown = true
            else                              -> { delay(HIDE_AFTER_MS); shown = false }
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, label = "fastScrollAlpha")
    val rulerAlpha by animateFloatAsState(if (dragging) 1f else 0f, label = "fastScrollRuler")
    val handleWidth by animateDpAsState(if (dragging) 10.dp else 6.dp, label = "fastScrollWidth")
    if (!shown && alpha == 0f) return

    // Read by the gesture as it runs, so that the gesture itself never has to restart.
    val latestIndex    by rememberUpdatedState(index)
    val latestRange    by rememberUpdatedState(range)
    val latestProgress by rememberUpdatedState(progress)

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .padding(top = topPadding + 8.dp, bottom = bottomPadding + 8.dp)
    ) {
        val density   = LocalDensity.current
        val trackPx   = with(density) { (maxHeight - HANDLE_TOUCH).toPx() }.coerceAtLeast(1f)
        val latestTrack by rememberUpdatedState(trackPx)
        val shownAt   = if (dragging) dragProgress else progress
        val handleY   = (shownAt * trackPx).roundToInt()
        val nowRange  = if (dragging) dragRange else range
        val currentYear = index.yearAt((shownAt * nowRange).roundToInt())

        // The ruler: a line down the edge and the years along it, each where it begins (#191).
        if (rulerAlpha > 0f && index.yearMarks.isNotEmpty()) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 6.dp, top = HANDLE_TOUCH / 2)
                    .size(width = 2.dp, height = with(density) { trackPx.toDp() })
                    .alpha(rulerAlpha)
                    .background(MaterialTheme.colorScheme.outlineVariant)
            )
            val minGap = with(density) { YEAR_MIN_GAP.toPx() }
            val half   = with(density) { (HANDLE_TOUCH / 2 - YEAR_LABEL_HALF).toPx() }
            var lastY  = Float.NEGATIVE_INFINITY
            for ((year, line) in index.yearMarks) {
                val y = (line.toFloat() / nowRange).coerceIn(0f, 1f) * trackPx
                // Years too close to the one above are left out rather than drawn over it;
                // the one being dragged through always shows.
                if (y - lastY < minGap && year != currentYear) continue
                lastY = y
                val current = year == currentYear
                Surface(
                    shape    = RoundedCornerShape(10.dp),
                    color    = if (current) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.92f),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(-HANDLE_TOUCH.roundToPx(), (y + half).roundToInt()) }
                        .alpha(rulerAlpha)
                ) {
                    Text(
                        text       = year,
                        style      = MaterialTheme.typography.labelMedium,
                        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                        color      = if (current) MaterialTheme.colorScheme.onPrimary
                                     else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier   = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }

        // The place being dragged to, beside the handle - left of the years, when there are any.
        val text = label
        if (dragging && text != null) {
            val clear = if (index.yearMarks.isNotEmpty()) HANDLE_TOUCH + YEAR_COLUMN else HANDLE_TOUCH + 4.dp
            Surface(
                shape    = RoundedCornerShape(16.dp),
                color    = MaterialTheme.colorScheme.primaryContainer,
                shadowElevation = 4.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(-clear.roundToPx(), handleY) }
            ) {
                Text(
                    text       = text,
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color      = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier   = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }

        Box(
            contentAlignment = Alignment.CenterEnd,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, handleY) }
                .size(HANDLE_TOUCH)
                .alpha(alpha)
                .then(
                    // Only a handle that can be seen takes touches.
                    if (!shown) Modifier else Modifier.pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = {
                                dragRange    = latestRange
                                dragProgress = latestProgress
                                dragging     = true
                                label = latestIndex.labelOf(latestIndex.itemAtLine((dragProgress * dragRange).roundToInt()))
                            },
                            onDragEnd    = { dragging = false },
                            onDragCancel = { dragging = false },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                dragProgress = (dragProgress + dy / latestTrack).coerceIn(0f, 1f)
                                val idx  = latestIndex
                                val item = idx.itemAtLine((dragProgress * dragRange).roundToInt())
                                val now  = idx.labelOf(item)
                                if (now != label) {
                                    if (label != null && now != null) {
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                    label = now
                                }
                                scope.launch { state.scrollToItem(item) }
                            }
                        )
                    }
                )
        ) {
            Box(
                Modifier
                    .padding(end = 4.dp)
                    .size(width = handleWidth, height = HANDLE_HEIGHT)
                    .clip(RoundedCornerShape(50))
                    .background(
                        if (dragging) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
            )
        }
    }
}
