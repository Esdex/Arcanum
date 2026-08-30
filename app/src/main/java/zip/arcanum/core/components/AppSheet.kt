package zip.arcanum.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import zip.arcanum.core.theme.LocalAmoledMode

/*
 * The HazeState a screen puts up for the frosted-glass overlays inside it, or null when
 * it has none.
 *
 * Null, and NOT an empty HazeState, deliberately. The default used to be `HazeState()`:
 * a screen that forgot to provide one still got a perfectly valid object, with nothing
 * registered as a source. AppDialog and AppSheet then blurred that nothing, in AMOLED
 * only, and came out completely transparent - which is how the mount screen shipped a
 * see-through dialog nobody noticed until it was used on a device. A null says outright
 * that there is nothing behind this to blur, and both consumers paint a solid surface
 * instead. A screen that wants the glass provides a state and puts hazeSource on its
 * content, as VaultConfigScreen does.
 */
val LocalHazeState = compositionLocalOf<HazeState?> { null }

/**
 * Frosted glass over what is behind, or a solid fill when there is nothing behind to
 * blur.
 *
 * Every overlay that wants the AMOLED glass goes through this rather than calling
 * hazeEffect with [LocalHazeState] directly. hazeEffect over a state with no source
 * draws nothing at all, so a screen that forgot to provide one gets a transparent top
 * bar, sheet or dialog - visible only in AMOLED, and only to someone using it. Making
 * the state nullable turned that into a compile error at every call site; this is what
 * those sites use to answer it, so the answer is one decision in one place instead of
 * eleven.
 */
fun Modifier.hazeOrSolid(state: HazeState?, style: HazeStyle, solid: Color): Modifier =
    if (state != null) this.hazeEffect(state = state, style = style)
    else this.background(solid)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    content: @Composable ColumnScope.() -> Unit
) {
    val isAmoled     = LocalAmoledMode.current
    val hazeState    = LocalHazeState.current
    val surfaceColor = MaterialTheme.colorScheme.surface

    val sheetColor = if (isAmoled) Color.Black else surfaceColor
    /* surfaceVariant is what AppTheme lifts to 0xFF1A1A1A in AMOLED, so a sheet with
     * nothing to blur still reads as a raised surface against the pure-black
     * background rather than vanishing into it. */
    val bgModifier = if (isAmoled) Modifier.hazeOrSolid(
        state = hazeState,
        style = HazeStyle(
            blurRadius      = 24.dp,
            backgroundColor = sheetColor,
            tints           = listOf(HazeTint(sheetColor.copy(alpha = 0.75f)))
        ),
        solid = MaterialTheme.colorScheme.surfaceVariant
    ) else Modifier.background(sheetColor)

    val scrimColor = if (isAmoled) Color.Black.copy(alpha = 0.72f)
                     else Color.Black.copy(alpha = 0.32f)
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp, bottomStart = 40.dp, bottomEnd = 40.dp)

    val noUpwardScroll = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (available.y < 0f) available else Offset.Zero
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                if (available.y < 0f) available else Velocity.Zero
        }
    }

    // Correct text color for the sheet background (transparent containerColor would give black)
    val contentColor = if (isAmoled) Color.White else MaterialTheme.colorScheme.onSurface

    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState       = sheetState,
        shape            = sheetShape,
        containerColor   = Color.Transparent,
        scrimColor       = scrimColor,
        dragHandle       = null,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .nestedScroll(noUpwardScroll)
                    .then(bgModifier)
                    .border(
                        width = 0.5.dp,
                        color = Color.White.copy(alpha = if (isAmoled) 0.12f else 0.08f),
                        shape = sheetShape
                    )
            ) {
                Box(
                    modifier         = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 32.dp, height = 4.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
                    )
                }
                content()
            }
        }
    }
}
