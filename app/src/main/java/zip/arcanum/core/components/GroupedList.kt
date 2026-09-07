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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import zip.arcanum.R

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

/**
 * One card in a group: a title, whatever belongs on the right, and - for a setting that needs
 * explaining - an "i" beside it that opens the explanation.
 *
 * [subtitle] is for what the row **is**: the language now chosen, a version number, "Arcanum
 * Pro feature". [info] is for what the setting **does**, and it does not sit in the row at all
 * - a tap on the "i" opens it in a dialog. Keeping the prose out of the list is what lets a
 * screen of eight settings be taken in at a glance, and it also lifts the length limit an
 * explanation had while it lived in the row.
 */
@Composable
fun GroupedRow(
    shape: Shape,
    title: String,
    subtitle: String? = null,
    info: String? = null,
    enabled: Boolean = true,
    titleColor: Color? = null,
    border: BorderStroke? = null,
    onClick: (() -> Unit)? = null,
    onDisabledClick: (() -> Unit)? = null,
    leading: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val fade = if (enabled) 1f else 0.38f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // A row that is only a title is shorter than one carrying a switch or an "i", and
            // in a group of both the step is visible. The floor is that switch's own height.
            .heightIn(min = ROW_MIN_HEIGHT)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (border != null) Modifier.border(border, shape) else Modifier)
            .then(
                when {
                    onClick != null && enabled -> Modifier.clickable(onClick = onClick)
                    // A locked row still answers a tap - saying why beats doing nothing - and
                    // the "i" inside it goes on taking its own, which is where the reason is.
                    onDisabledClick != null    -> Modifier.clickable(onClick = onDisabledClick)
                    else                       -> Modifier
                }
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
        if (info != null) {
            var showInfo by remember { mutableStateOf(false) }
            Spacer(Modifier.width(4.dp))
            // Never faded with the rest of the row: on a setting that cannot be moved this is
            // the one thing still worth pressing.
            IconButton(
                onClick  = { showInfo = true },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    imageVector        = Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.common_info),
                    tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier           = Modifier.size(20.dp)
                )
            }
            if (showInfo) {
                InfoDialog(title = title, text = info, onDismiss = { showInfo = false })
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(if (info != null) 8.dp else 16.dp))
            trailing()
        }
    }
}

/** What the "i" opens: the row's own title, the explanation under it, and a way out. */
@Composable
private fun InfoDialog(title: String, text: String, onDismiss: () -> Unit) {
    AppDialog(
        onDismissRequest = onDismiss,
        title            = { Text(title) },
        text             = { Text(text) },
        confirmButton    = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_ok)) }
        }
    )
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
    info: String? = null,
    enabled: Boolean = true,
    onDisabledClick: (() -> Unit)? = null
) {
    GroupedRow(
        shape           = shape,
        title           = title,
        subtitle        = subtitle,
        info            = info,
        enabled         = enabled,
        onClick         = { onCheckedChange(!checked) },
        onDisabledClick = onDisabledClick,
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

// The height of a Material switch plus the row's padding: what a row is when it holds one.
private val ROW_MIN_HEIGHT = 64.dp
