package zip.arcanum.arcanum.gallery.editor.model

import androidx.compose.ui.graphics.Color

enum class DrawMode { PEN, HIGHLIGHTER, ERASER }

data class PathProperties(
    val color: Color = Color.Red,
    val strokeWidth: Float = 8f,
    val alpha: Float = 1f,
    val isEraser: Boolean = false
)

val markupColors = listOf(
    Color.White,
    Color(0xFFFF3B30),   // Red
    Color(0xFFFF9500),   // Orange
    Color(0xFFFFCC00),   // Yellow
    Color(0xFF34C759),   // Green
    Color(0xFF007AFF),   // Blue
    Color(0xFF5856D6),   // Purple
    Color(0xFFFF2D55),   // Pink
    Color.Black
)
