package zip.arcanum.arcanum.gallery.editor.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import zip.arcanum.arcanum.gallery.editor.adjustments.VarFilterDef
import kotlin.math.roundToInt

@Composable
fun AdjustSection(
    filters: List<VarFilterDef>,
    sliders: Map<String, Float>,
    activeKey: String?,
    onFilterClick: (String) -> Unit,
    onSliderChange: (String, Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Scrubber for active filter
        AnimatedVisibility(
            visible = activeKey != null,
            enter   = fadeIn() + slideInVertically { it / 2 },
            exit    = fadeOut() + slideOutVertically { it / 2 }
        ) {
            val def = filters.find { it.key == activeKey }
            if (def != null) {
                val value = sliders[def.key] ?: def.default
                HorizontalScrubber(
                    modifier      = Modifier.padding(vertical = 8.dp),
                    resetKey      = def.key,
                    allowNegative = def.allowNegative,
                    minValue      = def.min,
                    maxValue      = def.max,
                    defaultValue  = def.default,
                    currentValue  = value,
                    displayValue  = { v ->
                        if (def.allowNegative) (v * 100).roundToInt().toString()
                        else if (def.max <= 1f) (v * 100).roundToInt().toString()
                        else v.roundToInt().toString()
                    },
                    onValueChanged = { _, v -> onSliderChange(def.key, v) }
                )
            }
        }

        // Single scrollable row of filter tools
        LazyRow(
            modifier       = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(filters) { def ->
                val isActive   = def.key == activeKey
                val value      = sliders[def.key] ?: def.default
                val isModified = value != def.default
                val tint = if (isActive) MaterialTheme.colorScheme.primary
                    else if (isModified) MaterialTheme.colorScheme.secondary
                    else Color.White.copy(alpha = 0.7f)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable { onFilterClick(def.key) }
                        .padding(vertical = 8.dp, horizontal = 8.dp)
                ) {
                    Icon(def.icon, null, tint = tint, modifier = Modifier.size(24.dp))
                    Text(
                        text      = def.label,
                        style     = MaterialTheme.typography.labelSmall,
                        color     = tint,
                        textAlign = TextAlign.Center,
                        maxLines  = 1,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}
