package zip.arcanum.core.navigation_components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import kotlinx.coroutines.delay

@Composable
fun FloatingBottomBar(
    items: List<BottomNavItem>,
    currentRoute: String?,
    hazeState: HazeState,
    isAmoled: Boolean,
    onItemClick: (BottomNavItem) -> Unit,
    modifier: Modifier = Modifier,
    rotatingItem: BottomNavItem? = null
) {
    val pillShape    = RoundedCornerShape(50)
    val surfaceColor = MaterialTheme.colorScheme.surface

    Box(modifier = modifier.clip(pillShape)) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .then(
                    if (isAmoled) Modifier.hazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            blurRadius      = 24.dp,
                            backgroundColor = surfaceColor,
                            tints           = listOf(HazeTint(surfaceColor.copy(alpha = 0.75f)))
                        )
                    ) else Modifier.background(surfaceColor)
                )
        )
        Box(
            modifier = Modifier.border(
                BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
                pillShape
            )
        ) {
            Row(
                modifier              = Modifier
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                items.forEach { item ->
                    FloatingBarItem(
                        icon         = item.icon,
                        label        = stringResource(item.labelRes),
                        selected     = currentRoute == item.route,
                        withRotation = item == rotatingItem,
                        onClick      = { onItemClick(item) }
                    )
                }
            }
        }
    }
}

@Composable
private fun FloatingBarItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    withRotation: Boolean = false,
    modifier: Modifier = Modifier
) {
    val bgColor by androidx.compose.animation.animateColorAsState(
        targetValue   = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else Color.Transparent,
        animationSpec = tween(durationMillis = 200),
        label         = "tab_bg"
    )

    var previousSelected by remember { mutableStateOf(selected) }
    var triggerAnimation by remember { mutableStateOf(false) }

    LaunchedEffect(selected) {
        if (selected && !previousSelected) {
            triggerAnimation = true
            delay(300)
            triggerAnimation = false
        }
        previousSelected = selected
    }

    val scale by animateFloatAsState(
        targetValue   = if (triggerAnimation) 1.25f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label         = "icon_scale"
    )
    val rotation by animateFloatAsState(
        targetValue   = if (triggerAnimation && withRotation) 30f else 0f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label         = "icon_rotation"
    )

    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
        modifier              = modifier
            .clip(RoundedCornerShape(50))
            .background(bgColor)
            .clickable(onClick = onClick)
            .padding(horizontal = if (selected) 16.dp else 12.dp, vertical = 10.dp)
    ) {
        Icon(
            imageVector        = icon,
            contentDescription = label,
            tint               = if (selected) MaterialTheme.colorScheme.primary
                                 else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier           = Modifier
                .size(28.dp)
                .scale(scale)
                .rotate(rotation)
        )
        AnimatedVisibility(
            visible = selected,
            enter   = fadeIn(tween(200)) + expandHorizontally(tween(300)),
            exit    = fadeOut(tween(150)) + shrinkHorizontally(tween(350))
        ) {
            Row {
                Spacer(Modifier.width(6.dp))
                Text(
                    text       = label,
                    style      = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color      = MaterialTheme.colorScheme.primary,
                    maxLines   = 1
                )
            }
        }
    }
}
