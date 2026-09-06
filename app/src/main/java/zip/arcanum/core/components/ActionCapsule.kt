package zip.arcanum.core.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * One of the wide round actions that sit under the title of a screen, after the ones on
 * Android's own App info: a filled blob with the icon inside it and the word underneath.
 *
 * [emphasised] is for the one action the screen is really about - it takes the primary
 * colour while the others stay secondary. A disabled capsule fades rather than disappears,
 * so the row keeps its shape and the action keeps its place.
 */
@Composable
fun ActionCapsule(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    emphasised: Boolean = false,
    border: BorderStroke? = null
) {
    val container = when {
        !enabled   -> MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.4f)
        emphasised -> MaterialTheme.colorScheme.primaryContainer
        else       -> MaterialTheme.colorScheme.secondaryContainer
    }
    val content = when {
        !enabled   -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        emphasised -> MaterialTheme.colorScheme.onPrimaryContainer
        else       -> MaterialTheme.colorScheme.onSecondaryContainer
    }
    val shape = RoundedCornerShape(30.dp)

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .width(104.dp)
                .height(60.dp)
                .clip(shape)
                .background(container)
                .then(if (border != null) Modifier.border(border, shape) else Modifier)
                .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = content,
                modifier           = Modifier.size(24.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text       = label,
            style      = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color      = if (enabled) MaterialTheme.colorScheme.onSurface
                         else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        )
    }
}
