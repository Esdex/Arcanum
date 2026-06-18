package zip.arcanum.core.theme

import androidx.compose.ui.graphics.Color

val SwipeEditColor    = Color(0xFF2196F3)
val SwipeArchiveColor = Color(0xFFFFC107)
val SwipeDeleteColor  = Color(0xFFE53935)

// Brand palette derived from the Arcanum logo crystal facets (VeraCrypt-inspired blue→teal→cyan)
object ArcanumColors {
    val Primary      = Color(0xFF259AD2) // blue
    val PrimaryLight = Color(0xFF6DD0E0) // light cyan — used as primary on dark/AMOLED
    val PrimaryDark  = Color(0xFF22697D) // dark teal — used as primaryContainer
    val Accent       = Color(0xFF3AA6AE) // teal — secondary
    val AccentLight  = Color(0xFF67C0B6) // mint-teal — secondary on dark, secondaryContainer on light
    val AccentDark   = Color(0xFF3A9AB1) // mid-teal — tertiary
}
