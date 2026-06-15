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

val LocalHazeState = compositionLocalOf { HazeState() }

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
    val bgModifier = if (isAmoled) Modifier.hazeEffect(
        state = hazeState,
        style = HazeStyle(
            blurRadius      = 24.dp,
            backgroundColor = sheetColor,
            tints           = listOf(HazeTint(sheetColor.copy(alpha = 0.75f)))
        )
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
