package zip.arcanum.arcanum.containers.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import zip.arcanum.R
import zip.arcanum.arcanum.containers.domain.Container
import zip.arcanum.arcanum.containers.domain.StorageBreakdown
import zip.arcanum.arcanum.containers.domain.StorageCategory
import kotlin.math.roundToInt

/**
 * The container's Storage tab: a usage donut plus a checkable category legend.
 * The "boring" General/Encryption info now lives behind the Vault details sheet
 * in Vault Config (see [VaultDetailsSheet]).
 */
@Composable
fun VaultStorageScreen(
    container: Container?,
    breakdown: StorageBreakdown,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    storageTabActive: Boolean = true,
) {
    // Categories the user has unchecked (excluded from the donut). None initially.
    var excluded by remember { mutableStateOf(emptySet<StorageCategory>()) }
    // Category currently being peeked (press-and-hold on its donut slice).
    var peekedCategory by remember { mutableStateOf<StorageCategory?>(null) }

    if (container == null) {
        Column(
            modifier = Modifier.fillMaxSize().padding(contentPadding).padding(16.dp)
        ) {
            Text(stringResource(R.string.vault_info_loading), style = MaterialTheme.typography.bodyMedium)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        // Every category with a non-zero size becomes a segment; `included` toggles it
        // in/out so the donut can animate the shrink/grow instead of rebuilding.
        val allCategories = StorageCategory.content + StorageCategory.FREE_SPACE
        val segments = allCategories
            .filter { breakdown.bytesOf(it) > 0L }
            .map { StorageSegment(it, breakdown.bytesOf(it), included = it !in excluded) }

        // Final value only — the donut's AnimatedContent flies the old value out and
        // the new one in; no intermediate count-up.
        val centerText = segments.filter { it.included }.sumOf { it.bytes }.formatStorageSize()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            contentAlignment = Alignment.Center
        ) {
            if (breakdown.isLoading) {
                CircularProgressIndicator()
            } else {
                StorageDonutChart(
                    segments = segments,
                    centerText = centerText,
                    active = storageTabActive,
                    onPeekChange = { peekedCategory = it },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        // Muted filesystem caption directly under the donut (dims during a peek).
        val captionAlpha by animateFloatAsState(
            targetValue = if (peekedCategory != null) 0.25f else 1f,
            animationSpec = tween(220, easing = FastOutSlowInEasing),
            label = "caption_alpha"
        )
        Text(
            text = stringResource(R.string.vault_storage_filesystem_caption, container.filesystem),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().alpha(captionAlpha)
        )

        // The vault list shows the volume size while this screen shows what the filesystem
        // can actually hold. Those are normally within a fraction of a percent of each
        // other - FAT metadata is the only difference - but after an expand the filesystem
        // keeps its original size and the two diverge for good. Name the gap where it
        // exists rather than leaving two screens quietly disagreeing.
        //
        // 5% is well clear of the metadata overhead, which stays under 1% for every
        // geometry Arcanum formats, so this can only fire on a genuine gap.
        val volumeSize = container.size
        if (!breakdown.isLoading && volumeSize > 0L &&
            breakdown.capacity in 1 until (volumeSize - volumeSize / 20)) {
            Text(
                text = stringResource(
                    R.string.vault_storage_volume_gap_caption,
                    breakdown.capacity.formatStorageSize(),
                    volumeSize.formatStorageSize()
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().alpha(captionAlpha)
            )
        }

        Spacer(Modifier.height(8.dp))

        // Checkable legend — bare rows, all checked initially; tap toggles a bucket.
        // Percentage is of the whole vault capacity, so all buckets sum to ~100%.
        val capacity = breakdown.capacity
        allCategories.forEach { cat ->
            val bytes = breakdown.bytesOf(cat)
            StorageLegendRow(
                category = cat,
                percentText = if (capacity > 0L) "${(bytes * 100.0 / capacity).roundToInt()}%" else "",
                sizeText = bytes.formatStorageSize(),
                checked = cat !in excluded,
                enabled = !breakdown.isLoading,
                dimmed = peekedCategory != null && peekedCategory != cat,
                onToggle = { excluded = if (cat in excluded) excluded - cat else excluded + cat },
            )
        }
    }
}

@Composable
private fun StorageLegendRow(
    category: StorageCategory,
    percentText: String,
    sizeText: String,
    checked: Boolean,
    enabled: Boolean,
    dimmed: Boolean,
    onToggle: () -> Unit,
) {
    val color = Color(category.colorValue)
    val rowAlpha by animateFloatAsState(
        targetValue = if (dimmed) 0.25f else 1f,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "legend_row_alpha"
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(rowAlpha)
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Round checkbox tinted with the category colour: colour + check in one.
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (checked) color else Color.Transparent)
                .border(2.dp, color, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (checked) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp)
                )
            }
        }
        Text(
            text = stringResource(category.labelRes),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        if (percentText.isNotEmpty()) {
            Text(
                text = percentText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = sizeText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}
