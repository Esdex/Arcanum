package zip.arcanum.core.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint

object ArcanumHazeStyle {
    val topBar = HazeStyle(
        blurRadius      = 20.dp,
        backgroundColor = Color.Black,
        tints           = listOf(HazeTint(Color.Black.copy(alpha = 0.75f)))
    )
    val sheet = HazeStyle(
        blurRadius      = 20.dp,
        backgroundColor = Color.Black.copy(alpha = 0.4f),
        tints           = listOf(HazeTint(Color.White.copy(alpha = 0.05f)))
    )
    val bar = HazeStyle(
        blurRadius      = 24.dp,
        backgroundColor = Color.Black.copy(alpha = 0.6f),
        tints           = listOf(HazeTint(Color.White.copy(alpha = 0.03f)))
    )
    val card = HazeStyle(
        blurRadius      = 16.dp,
        backgroundColor = Color.Black.copy(alpha = 0.5f),
        tints           = listOf(HazeTint(Color.White.copy(alpha = 0.03f)))
    )
    val dialog = HazeStyle(
        blurRadius      = 24.dp,
        backgroundColor = Color.Black.copy(alpha = 0.7f),
        tints           = listOf(HazeTint(Color.White.copy(alpha = 0.05f)))
    )
}
