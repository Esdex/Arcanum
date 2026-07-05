package zip.arcanum.arcanum.gallery.editor.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import zip.arcanum.arcanum.gallery.editor.model.EditorTab

@Composable
fun EditorTabSelector(
    selected: EditorTab,
    modifier: Modifier = Modifier,
    onSelect: (EditorTab) -> Unit
) {
    val listState = rememberLazyListState()

    LaunchedEffect(selected) {
        val index = EditorTab.entries.indexOf(selected)
        if (index < 0) return@LaunchedEffect

        val info = listState.layoutInfo
        val viewportW = info.viewportEndOffset - info.viewportStartOffset
        val item = info.visibleItemsInfo.firstOrNull { it.index == index }

        if (item != null && viewportW > 0) {
            // Item is visible — animate directly to center position
            val delta = item.offset - (viewportW - item.size) / 2
            listState.animateScrollBy(delta.toFloat())
        } else {
            // Item off-screen — scroll to it first, then center
            listState.animateScrollToItem(index)
            val info2 = listState.layoutInfo
            val item2 = info2.visibleItemsInfo.firstOrNull { it.index == index }
                ?: return@LaunchedEffect
            val vw = info2.viewportEndOffset - info2.viewportStartOffset
            if (vw > 0) listState.animateScrollBy(-(vw - item2.size) / 2f)
        }
    }

    LazyRow(
        state                 = listState,
        modifier              = modifier,
        contentPadding        = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment     = Alignment.CenterVertically
    ) {
        items(EditorTab.entries) { tab ->
            val isSelected = tab == selected
            val bg by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.surfaceContainerHighest
                              else Color.Transparent,
                label = "tabBg"
            )
            val textColor by animateColorAsState(
                targetValue = if (isSelected) MaterialTheme.colorScheme.onSurface
                              else MaterialTheme.colorScheme.onSurfaceVariant,
                label = "tabText"
            )
            Text(
                text     = tab.label,
                style    = MaterialTheme.typography.bodyLarge,
                color    = textColor,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(bg)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
    }
}
