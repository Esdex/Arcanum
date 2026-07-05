package zip.arcanum.arcanum.gallery.editor.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.BorderColor
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import zip.arcanum.arcanum.gallery.editor.model.DrawMode
import zip.arcanum.arcanum.gallery.editor.model.PathProperties
import zip.arcanum.arcanum.gallery.editor.model.markupColors

@Composable
fun MarkupBottomBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier          = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = onCancel) { Icon(Icons.Outlined.Close, "Cancel markup", tint = Color.White) }
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Text("↶", style = MaterialTheme.typography.titleLarge,
                    color = if (canUndo) Color.White else Color.White.copy(alpha = 0.3f))
            }
            Text("Markup", style = MaterialTheme.typography.titleMedium, color = Color.White,
                modifier = Modifier.align(Alignment.CenterVertically))
            IconButton(onClick = onRedo, enabled = canRedo) {
                Text("↷", style = MaterialTheme.typography.titleLarge,
                    color = if (canRedo) Color.White else Color.White.copy(alpha = 0.3f))
            }
        }
        IconButton(onClick = onDone) { Icon(Icons.Outlined.Done, "Apply markup", tint = MaterialTheme.colorScheme.primary) }
    }
}

@Composable
fun MarkupToolbar(
    drawMode: DrawMode,
    selectedColor: Color,
    strokeWidth: Float,
    onModeChange: (DrawMode) -> Unit,
    onColorChange: (Color) -> Unit,
    onStrokeChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        // Mode selector
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment     = Alignment.CenterVertically,
            modifier              = Modifier.fillMaxWidth().padding(vertical = 4.dp)
        ) {
            DrawModeButton(Icons.Outlined.Edit, "Pen", drawMode == DrawMode.PEN) {
                onModeChange(DrawMode.PEN)
            }
            DrawModeButton(Icons.Outlined.BorderColor, "Highlighter", drawMode == DrawMode.HIGHLIGHTER) {
                onModeChange(DrawMode.HIGHLIGHTER)
            }
            DrawModeButton(Icons.Outlined.AutoFixHigh, "Eraser", drawMode == DrawMode.ERASER) {
                onModeChange(DrawMode.ERASER)
            }
            Spacer(Modifier.weight(1f))
            // Stroke size preview
            Box(
                modifier = Modifier
                    .size((strokeWidth / 2f).coerceIn(6f, 30f).dp)
                    .clip(CircleShape)
                    .background(selectedColor)
            )
        }

        // Stroke width slider
        Slider(
            value             = strokeWidth,
            onValueChange     = onStrokeChange,
            valueRange        = 4f..40f,
            colors            = SliderDefaults.colors(
                thumbColor         = selectedColor,
                activeTrackColor   = selectedColor,
                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
            ),
            modifier = Modifier.fillMaxWidth()
        )

        // Color picker
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            items(markupColors) { color ->
                val isSelected = color == selectedColor
                Box(
                    modifier = Modifier
                        .size(if (isSelected) 32.dp else 28.dp)
                        .clip(CircleShape)
                        .background(color)
                        .then(if (isSelected) Modifier.border(2.5.dp, Color.White, CircleShape) else Modifier)
                        .clickable { onColorChange(color) }
                )
            }
        }
    }
}

@Composable
private fun DrawModeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Icon(icon, label, tint = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f))
    }
}

@Composable
fun MarkupCanvas(
    paths: List<Pair<Path, PathProperties>>,
    currentPath: Path?,
    currentProps: PathProperties,
    modifier: Modifier = Modifier
) {
    // CompositingStrategy.Offscreen gives the canvas its own layer so BlendMode.Clear
    // erases markup pixels and reveals the image below instead of punching through everything.
    androidx.compose.foundation.Canvas(
        modifier = modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    ) {
        fun drawMarkupPath(path: Path, props: PathProperties) {
            val stroke = Stroke(width = props.strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
            if (props.isEraser) {
                drawPath(path, Color.Transparent, style = stroke, blendMode = BlendMode.Clear)
            } else {
                drawPath(path, props.color.copy(alpha = props.alpha), style = stroke)
            }
        }
        for ((path, props) in paths) drawMarkupPath(path, props)
        if (currentPath != null) drawMarkupPath(currentPath, currentProps)
    }
}
