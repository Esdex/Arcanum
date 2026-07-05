package zip.arcanum.arcanum.gallery.editor.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import zip.arcanum.arcanum.gallery.editor.model.CropAspectRatio

private const val HANDLE_TOUCH_PX = 60f

private enum class DragTarget { NONE, MOVE, TL, TR, BL, BR, TOP, BOTTOM, LEFT, RIGHT }

@Composable
fun CropOverlay(
    cropRect: Rect?,
    aspectRatio: CropAspectRatio,
    imageRect: Rect,
    modifier: Modifier = Modifier,
    onCropRectChange: (Rect) -> Unit
) {
    val handleColor = Color.White
    val primary = MaterialTheme.colorScheme.primary
    var size by remember { mutableStateOf(IntSize.Zero) }

    val initialRect = remember(imageRect, cropRect) {
        cropRect ?: imageRect
    }
    var rect by remember(initialRect) { mutableStateOf(initialRect) }
    var dragTarget by remember { mutableStateOf(DragTarget.NONE) }

    fun clamp(r: Rect): Rect {
        val minSide = 40f
        var l = r.left.coerceIn(imageRect.left, imageRect.right - minSide)
        var t = r.top.coerceIn(imageRect.top, imageRect.bottom - minSide)
        var ri = r.right.coerceIn(l + minSide, imageRect.right)
        var bo = r.bottom.coerceIn(t + minSide, imageRect.bottom)
        // Enforce aspect ratio
        val ar = aspectRatio.ratio
        if (ar != null) {
            val w = ri - l
            val h = bo - t
            val currentAr = w / h
            if (currentAr > ar) {
                // too wide, reduce width
                val newW = h * ar
                ri = l + newW
            } else {
                val newH = w / ar
                bo = t + newH
            }
        }
        return Rect(l, t, ri.coerceAtMost(imageRect.right), bo.coerceAtMost(imageRect.bottom))
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(imageRect, aspectRatio) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val r = rect
                        dragTarget = when {
                            Offset(r.left,  r.top).nearBy(offset)    -> DragTarget.TL
                            Offset(r.right, r.top).nearBy(offset)    -> DragTarget.TR
                            Offset(r.left,  r.bottom).nearBy(offset) -> DragTarget.BL
                            Offset(r.right, r.bottom).nearBy(offset) -> DragTarget.BR
                            offset.x in (r.left..r.right) && offset.y.nearBy(r.top)    -> DragTarget.TOP
                            offset.x in (r.left..r.right) && offset.y.nearBy(r.bottom) -> DragTarget.BOTTOM
                            offset.y in (r.top..r.bottom) && offset.x.nearBy(r.left)   -> DragTarget.LEFT
                            offset.y in (r.top..r.bottom) && offset.x.nearBy(r.right)  -> DragTarget.RIGHT
                            offset in r                               -> DragTarget.MOVE
                            else                                      -> DragTarget.NONE
                        }
                    },
                    onDrag = { _, delta ->
                        if (dragTarget == DragTarget.NONE) return@detectDragGestures
                        val r = rect
                        val dx = delta.x; val dy = delta.y
                        rect = clamp(when (dragTarget) {
                            DragTarget.TL     -> Rect(r.left+dx, r.top+dy, r.right, r.bottom)
                            DragTarget.TR     -> Rect(r.left, r.top+dy, r.right+dx, r.bottom)
                            DragTarget.BL     -> Rect(r.left+dx, r.top, r.right, r.bottom+dy)
                            DragTarget.BR     -> Rect(r.left, r.top, r.right+dx, r.bottom+dy)
                            DragTarget.TOP    -> Rect(r.left, r.top+dy, r.right, r.bottom)
                            DragTarget.BOTTOM -> Rect(r.left, r.top, r.right, r.bottom+dy)
                            DragTarget.LEFT   -> Rect(r.left+dx, r.top, r.right, r.bottom)
                            DragTarget.RIGHT  -> Rect(r.left, r.top, r.right+dx, r.bottom)
                            DragTarget.MOVE   -> Rect(
                                (r.left+dx).coerceIn(imageRect.left, imageRect.right - r.width),
                                (r.top+dy).coerceIn(imageRect.top, imageRect.bottom - r.height),
                                (r.right+dx).coerceIn(imageRect.left + r.width, imageRect.right),
                                (r.bottom+dy).coerceIn(imageRect.top + r.height, imageRect.bottom)
                            )
                            else -> r
                        })
                        onCropRectChange(rect)
                    },
                    onDragEnd   = { dragTarget = DragTarget.NONE },
                    onDragCancel = { dragTarget = DragTarget.NONE }
                )
            }
    ) {
        val r = rect
        // Dim outside
        drawRect(Color.Black.copy(alpha = 0.45f))
        drawRect(Color.Transparent, topLeft = r.topLeft, size = r.size)

        // Grid 3×3
        val gStroke = Stroke(width = 0.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
        for (i in 1..2) {
            val x = r.left + r.width * i / 3f
            val y = r.top + r.height * i / 3f
            drawLine(Color.White.copy(alpha = 0.5f), Offset(x, r.top), Offset(x, r.bottom), strokeWidth = 0.5.dp.toPx())
            drawLine(Color.White.copy(alpha = 0.5f), Offset(r.left, y), Offset(r.right, y), strokeWidth = 0.5.dp.toPx())
        }

        // Border
        drawRect(handleColor, topLeft = r.topLeft, size = r.size, style = Stroke(width = 1.5.dp.toPx()))

        // Corner handles
        val hs = 20.dp.toPx(); val hw = 3.dp.toPx()
        fun cornerH(ox: Float, oy: Float) {
            drawLine(primary, Offset(ox, oy), Offset(ox + hs * if (ox == r.left) 1 else -1, oy), hw)
            drawLine(primary, Offset(ox, oy), Offset(ox, oy + hs * if (oy == r.top) 1 else -1), hw)
        }
        cornerH(r.left, r.top); cornerH(r.right, r.top)
        cornerH(r.left, r.bottom); cornerH(r.right, r.bottom)

        // Edge midpoint handles
        val emSize = 16.dp.toPx()
        drawLine(primary, Offset(r.center.x - emSize, r.top), Offset(r.center.x + emSize, r.top), hw)
        drawLine(primary, Offset(r.center.x - emSize, r.bottom), Offset(r.center.x + emSize, r.bottom), hw)
        drawLine(primary, Offset(r.left, r.center.y - emSize), Offset(r.left, r.center.y + emSize), hw)
        drawLine(primary, Offset(r.right, r.center.y - emSize), Offset(r.right, r.center.y + emSize), hw)
    }
}

private fun Offset.nearBy(target: Offset) = (this - target).getDistance() < HANDLE_TOUCH_PX
private fun Float.nearBy(target: Float) = kotlin.math.abs(this - target) < HANDLE_TOUCH_PX
private operator fun Rect.contains(offset: Offset) = offset.x in left..right && offset.y in top..bottom
