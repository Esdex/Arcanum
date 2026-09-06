package zip.arcanum.core.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Rows that belong together, drawn as one block of cards.
 *
 * The shape is the whole point: the corners on the outside of a group are rounded far more
 * than the ones inside it, so several cards read as one thing without a border round them.
 * It follows the grouped lists Android's own settings use.
 *
 * Rows are declared through [GroupBuilder] rather than as plain children because each one
 * has to know where in the group it sits, and counting composed children is not something to
 * rely on.
 *
 * ```
 * SettingsGroup(title = "Access") {
 *     row { shape -> GroupedRow(shape, "Change password", "...") { } }
 *     row { shape -> GroupedRow(shape, "Change keyfiles", "...") { } }
 * }
 * ```
 */
@Composable
fun SettingsGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: GroupBuilder.() -> Unit
) {
    val rows = GroupBuilder().apply(content).rows
    if (rows.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                text       = title,
                style      = MaterialTheme.typography.labelLarge,
                color      = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp)
            )
        }
        // No side padding of its own: the page decides how wide its content is, and a group
        // that inset itself would sit narrower than everything else on the screen.
        Column(verticalArrangement = Arrangement.spacedBy(GAP)) {
            rows.forEachIndexed { index, row -> row(groupShape(index, rows.size)) }
        }
    }
}

class GroupBuilder internal constructor() {
    internal val rows = mutableListOf<@Composable (Shape) -> Unit>()

    /** One row of the group. The shape it is handed already knows where it sits. */
    fun row(content: @Composable (Shape) -> Unit) {
        rows += content
    }
}

/** One card in a group: a title, a line under it, and whatever belongs on the right. */
@Composable
fun GroupedRow(
    shape: Shape,
    title: String,
    subtitle: String? = null,
    enabled: Boolean = true,
    titleColor: Color? = null,
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val fade = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .then(
                if (onClick != null && enabled) Modifier.clickable(onClick = onClick) else Modifier
            )
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(16.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text       = title,
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color      = (titleColor ?: MaterialTheme.colorScheme.onSurface).copy(alpha = fade)
            )
            if (subtitle != null) {
                Text(
                    text  = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = fade)
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(16.dp))
            trailing()
        }
    }
}

/**
 * A row of a group with a switch on its right.
 *
 * The switch carries a tick in its thumb when it is on, the way Android's own switches do -
 * it is what tells the two states apart at a glance for anyone who reads position slower than
 * colour - and a padlock when the setting cannot be moved at all. The whole row is the
 * target, not just the switch.
 */
@Composable
fun GroupedSwitch(
    shape: Shape,
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    subtitle: String? = null,
    enabled: Boolean = true
) {
    GroupedRow(
        shape    = shape,
        title    = title,
        subtitle = subtitle,
        enabled  = enabled,
        onClick  = { onCheckedChange(!checked) },
        trailing = {
            Switch(
                checked         = checked,
                onCheckedChange = onCheckedChange,
                enabled         = enabled,
                // A tick when it is on, a padlock when it cannot be moved at all - the same
                // two glyphs Android uses, and the only sign a greyed switch gives that it
                // is locked rather than merely off.
                thumbContent    = when {
                    !enabled -> {
                        {
                            Icon(
                                imageVector        = Icons.Filled.Lock,
                                contentDescription = null,
                                modifier           = Modifier.size(SwitchDefaults.IconSize)
                            )
                        }
                    }
                    checked  -> {
                        {
                            Icon(
                                imageVector        = Icons.Filled.Check,
                                contentDescription = null,
                                modifier           = Modifier.size(SwitchDefaults.IconSize)
                            )
                        }
                    }
                    else     -> null
                }
            )
        }
    )
}

/** Room for something of its own inside a group, shaped like the rows around it. */
@Composable
fun GroupedBox(
    shape: Shape,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        content = content
    )
}

/**
 * The round, filled icon that stands at the head of a top-level row - the shape Android's own
 * Settings uses for its sections. Rows inside a screen have no icon at all; this is for the
 * lists that lead somewhere else.
 *
 * The glyph takes black or white by the brightness of the circle behind it, so a palette can
 * be picked for how it looks rather than for what will still be readable on it. Pass
 * [iconColor] where the pair is known - a deep tone of the circle's own hue reads warmer than
 * black, which is what Android's own icons do.
 */
@Composable
fun GroupedRoundIcon(
    icon: ImageVector,
    color: Color,
    iconColor: Color? = null,
    enabled: Boolean = true
) {
    val fade = if (enabled) 1f else 0.38f
    Box(
        modifier         = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = fade)),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = null,
            tint               = (iconColor
                ?: if (color.luminance() > 0.45f) Color.Black else Color.White).copy(alpha = fade),
            modifier           = Modifier.size(22.dp)
        )
    }
}

/** Round on the outside of the group, nearly square within it. */
fun groupShape(index: Int, count: Int): Shape {
    val outer = OUTER_RADIUS
    val inner = INNER_RADIUS
    return when {
        count == 1      -> RoundedCornerShape(outer)
        index == 0      -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
        index == count - 1 -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
        else            -> RoundedCornerShape(inner)
    }
}

private val OUTER_RADIUS = 28.dp
private val INNER_RADIUS = 6.dp
private val GAP = 3.dp
