package zip.arcanum.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlin.math.roundToInt
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import zip.arcanum.R

private val ITEM_HEIGHT = 60.dp

@Composable
fun WheelDateTimePicker(
    initialMillis: Long,
    onDismiss: () -> Unit,
    onSave: (Long) -> Unit
) {
    val zdt = remember(initialMillis) {
        Instant.ofEpochMilli(initialMillis).atZone(ZoneId.systemDefault())
    }

    val months = remember {
        (1..12).map { m ->
            java.time.Month.of(m).getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase()
        }
    }
    val years      = remember { (1970..2099).map { it.toString() } }
    val hourItems  = remember { (1..12).map { it.toString() } }
    val minItems   = remember { (0..59).map { "%02d".format(it) } }
    val amPmItems  = listOf("AM", "PM")

    var selMonth  by remember { mutableIntStateOf(zdt.monthValue - 1) }
    var selDay    by remember { mutableIntStateOf(zdt.dayOfMonth - 1) }
    var selYear   by remember { mutableIntStateOf(years.indexOf(zdt.year.toString()).coerceAtLeast(0)) }

    val initHour = zdt.hour
    val initH12  = when (initHour) { 0 -> 12; in 1..11 -> initHour; 12 -> 12; else -> initHour - 12 }
    var selHour   by remember { mutableIntStateOf(initH12 - 1) }
    var selMin    by remember { mutableIntStateOf(zdt.minute) }
    var selAmPm   by remember { mutableIntStateOf(if (initHour < 12) 0 else 1) }

    val daysInMonth by remember(selMonth, selYear) {
        derivedStateOf { YearMonth.of(years[selYear].toInt(), selMonth + 1).lengthOfMonth() }
    }
    val dayItems by remember(daysInMonth) {
        derivedStateOf { (1..daysInMonth).map { "%02d".format(it) } }
    }

    LaunchedEffect(daysInMonth) {
        if (selDay >= daysInMonth) selDay = daysInMonth - 1
    }

    var activeTab by remember { mutableIntStateOf(0) }

    val dateLabel = "${months[selMonth]} ${selDay + 1}"
    val timeLabel = "${selHour + 1}:${minItems[selMin]} ${amPmItems[selAmPm]}"

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp)
    ) {
        Text(
            text       = stringResource(R.string.viewer_edit_date_title),
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier   = Modifier.padding(vertical = 16.dp)
        )

        // ── Tab switcher ──────────────────────────────────────────────────
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            WheelTab(
                label    = "Date",
                subtitle = dateLabel,
                active   = activeTab == 0,
                onClick  = { activeTab = 0 },
                modifier = Modifier.weight(1f)
            )
            WheelTab(
                label    = "Time",
                subtitle = timeLabel,
                active   = activeTab == 1,
                onClick  = { activeTab = 1 },
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Wheel area ────────────────────────────────────────────────────
        val dividerColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
        Box(modifier = Modifier.fillMaxWidth().height(ITEM_HEIGHT * 3)) {
            if (activeTab == 0) {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    WheelColumn(
                        items             = months,
                        selectedIndex     = selMonth,
                        onSelectedChanged = { selMonth = it },
                        modifier          = Modifier.weight(2f)
                    )
                    WheelColumn(
                        items             = dayItems,
                        selectedIndex     = selDay.coerceAtMost(dayItems.lastIndex),
                        onSelectedChanged = { selDay = it },
                        modifier          = Modifier.weight(1.5f)
                    )
                    WheelColumn(
                        items             = years,
                        selectedIndex     = selYear,
                        onSelectedChanged = { selYear = it },
                        modifier          = Modifier.weight(2f)
                    )
                }
            } else {
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    WheelColumn(
                        items             = hourItems,
                        selectedIndex     = selHour,
                        onSelectedChanged = { selHour = it },
                        modifier          = Modifier.weight(1.5f)
                    )
                    WheelColumn(
                        items             = minItems,
                        selectedIndex     = selMin,
                        onSelectedChanged = { selMin = it },
                        modifier          = Modifier.weight(1.5f)
                    )
                    WheelColumn(
                        items             = amPmItems,
                        selectedIndex     = selAmPm,
                        onSelectedChanged = { selAmPm = it },
                        modifier          = Modifier.weight(1.5f)
                    )
                }
            }

            // Selection indicator lines above and below center cell
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth().offset(y = ITEM_HEIGHT),
                color    = dividerColor
            )
            HorizontalDivider(
                modifier = Modifier.fillMaxWidth().offset(y = ITEM_HEIGHT * 2),
                color    = dividerColor
            )
        }

        Spacer(Modifier.height(12.dp))

        Text(
            text     = stringResource(R.string.viewer_edit_date_warning),
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = {
                val year   = years[selYear].toInt()
                val month  = selMonth + 1
                val day    = (selDay + 1).coerceAtMost(daysInMonth)
                val hVal   = selHour + 1  // 1..12
                val hour24 = when {
                    selAmPm == 0 && hVal == 12 -> 0
                    selAmPm == 0               -> hVal
                    hVal == 12                 -> 12
                    else                       -> hVal + 12
                }
                val millis = java.time.LocalDateTime.of(year, month, day, hour24, selMin, 0)
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                onSave(millis)
            }) { Text(stringResource(R.string.common_save)) }
        }
    }
}

@Composable
private fun WheelTab(
    label: String,
    subtitle: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bg = if (active) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
             else        androidx.compose.ui.graphics.Color.Transparent
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 16.dp)
    ) {
        Column {
            Text(
                text       = label,
                style      = MaterialTheme.typography.labelLarge,
                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                color      = if (active) MaterialTheme.colorScheme.onSurface
                             else        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            )
            Text(
                text  = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (active) MaterialTheme.colorScheme.primary
                        else        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
            )
        }
    }
}

@Composable
private fun WheelColumn(
    items: List<String>,
    selectedIndex: Int,
    onSelectedChanged: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (items.isEmpty()) return
    val safeIndex = selectedIndex.coerceIn(0, items.lastIndex)

    // initialFirstVisibleItemIndex = safeIndex puts the top-spacer (list index 0) or
    // actual item safeIndex-1 (list index safeIndex) at y=0, so actual item safeIndex
    // (list index safeIndex+1) lands at y=ITEM_HEIGHT — exactly the viewport center.
    // This avoids the off-by-one that contentPadding introduces in firstVisibleItemIndex.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = safeIndex)
    val fling     = rememberSnapFlingBehavior(listState)

    // Pixel-based center: find the actual item closest to viewport center.
    // List layout: index 0 = top spacer, 1..items.size = actual items, items.size+1 = bottom spacer.
    val centerFloat by remember {
        derivedStateOf {
            val info     = listState.layoutInfo
            val vpCenter = info.viewportEndOffset / 2f
            // Only consider actual items (not spacers)
            val nearest  = info.visibleItemsInfo
                .filter { it.index in 1..items.size }
                .minByOrNull { abs(it.offset + it.size / 2f - vpCenter) }
            if (nearest != null) {
                val actualIdx  = nearest.index - 1   // convert list index → actual index
                val itemCenter = nearest.offset + nearest.size / 2f
                actualIdx + (vpCenter - itemCenter) / nearest.size.toFloat()
            } else {
                safeIndex.toFloat()
            }
        }
    }
    val centeredIndex by remember {
        derivedStateOf { centerFloat.roundToInt().coerceIn(0, items.lastIndex) }
    }

    // Notify caller when scroll settles
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to centeredIndex }
            .distinctUntilChanged()
            .filter { !it.first }
            .collect { (_, idx) -> onSelectedChanged(idx) }
    }

    // Sync programmatic changes (e.g., day clamped when month shrinks).
    // animateScrollToItem(safeIndex) places list index safeIndex at y=0, centering actual item safeIndex.
    LaunchedEffect(safeIndex) {
        if (centeredIndex != safeIndex && !listState.isScrollInProgress) {
            listState.animateScrollToItem(safeIndex)
        }
    }

    LazyColumn(
        state               = listState,
        flingBehavior       = fling,
        // No contentPadding — spacer items handle top/bottom room instead
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier            = modifier.height(ITEM_HEIGHT * 3)
    ) {
        // Top spacer: keeps actual item 0 centerable
        item(key = "top_spacer") { Spacer(Modifier.fillMaxWidth().height(ITEM_HEIGHT)) }

        itemsIndexed(items) { index, item ->
            val dist   = abs(index - centerFloat)
            val alpha  = (1f - dist * 0.65f).coerceIn(0.28f, 1f)
            val weight = if (dist < 0.5f) FontWeight.Bold else FontWeight.Normal
            Box(
                contentAlignment = Alignment.Center,
                modifier         = Modifier.fillMaxWidth().height(ITEM_HEIGHT)
            ) {
                Text(
                    text       = item,
                    style      = MaterialTheme.typography.headlineSmall,
                    fontWeight = weight,
                    modifier   = Modifier.alpha(alpha)
                )
            }
        }

        // Bottom spacer: keeps the last actual item centerable
        item(key = "bottom_spacer") { Spacer(Modifier.fillMaxWidth().height(ITEM_HEIGHT)) }
    }
}
