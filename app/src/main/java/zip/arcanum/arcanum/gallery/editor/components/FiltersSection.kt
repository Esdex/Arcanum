package zip.arcanum.arcanum.gallery.editor.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorMatrixColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import zip.arcanum.arcanum.gallery.editor.adjustments.lerpColorMatrix
import zip.arcanum.arcanum.gallery.editor.adjustments.namedFilters
import androidx.compose.ui.graphics.ColorMatrix

private fun makeThumbnail(src: Bitmap, matrix: FloatArray?, intensity: Float = 1f): Bitmap? {
    if (src.width == 0 || src.height == 0) return null
    val size = 80
    val scale = minOf(size.toFloat() / src.width, size.toFloat() / src.height)
    val w = (src.width * scale).toInt().coerceAtLeast(1)
    val h = (src.height * scale).toInt().coerceAtLeast(1)
    val thumb = Bitmap.createScaledBitmap(src, w, h, true)
    if (matrix == null) return thumb
    val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cm = if (intensity >= 1f) ColorMatrix(matrix)
    else lerpColorMatrix(ColorMatrix(), ColorMatrix(matrix), intensity)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        colorFilter = android.graphics.ColorMatrixColorFilter(cm.values)
    }
    Canvas(result).drawBitmap(thumb, 0f, 0f, paint)
    return result
}

@Composable
fun FiltersSection(
    workBitmap: Bitmap?,
    selectedIndex: Int,
    intensity: Float,
    onSelectFilter: (Int) -> Unit,
    onIntensityChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // Intensity slider (only when a filter is selected and it's not Original)
        if (selectedIndex != 0) {
            Slider(
                value             = intensity,
                onValueChange     = onIntensityChange,
                valueRange        = 0f..1f,
                colors            = SliderDefaults.colors(
                    thumbColor        = MaterialTheme.colorScheme.primary,
                    activeTrackColor  = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
            )
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(namedFilters) { index, filter ->
                val isSelected = index == selectedIndex
                val borderWidth by animateDpAsState(if (isSelected) 2.5.dp else 0.dp, tween(150), label = "filterBorder")
                val borderColor by animateColorAsState(
                    if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    tween(150), label = "filterBorderColor"
                )
                val thumb = remember(workBitmap, filter.matrix, intensity) {
                    workBitmap?.let { makeThumbnail(it, filter.matrix, if (isSelected) intensity else 1f) }
                }

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(72.dp).clickable { onSelectFilter(index) }
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
                            .background(Color.DarkGray)
                    ) {
                        if (thumb != null) {
                            Image(
                                bitmap       = thumb.asImageBitmap(),
                                contentDescription = filter.name,
                                contentScale = ContentScale.Crop,
                                modifier     = Modifier.matchParentSize()
                            )
                        }
                    }
                    Text(
                        text      = filter.name,
                        style     = MaterialTheme.typography.labelSmall,
                        color     = if (isSelected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.8f),
                        textAlign = TextAlign.Center,
                        modifier  = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}
