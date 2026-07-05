package zip.arcanum.arcanum.gallery.editor.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.FilterBAndW
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.ui.graphics.vector.ImageVector

enum class EditorTab(val label: String, val icon: ImageVector) {
    CROP("Crop",     Icons.Outlined.Crop),
    LIGHTING("Lighting", Icons.Outlined.WbSunny),
    COLOUR("Colour", Icons.Outlined.ColorLens),
    FILTERS("Filters", Icons.Outlined.FilterBAndW),
    EFFECTS("Effects", Icons.Outlined.Tune),
    MARKUP("Markup", Icons.Outlined.Brush)
}
