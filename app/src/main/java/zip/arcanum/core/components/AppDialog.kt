package zip.arcanum.core.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import zip.arcanum.core.theme.LocalAmoledMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDialog(
    onDismissRequest: () -> Unit,
    title: @Composable () -> Unit,
    text: (@Composable () -> Unit)? = null,
    confirmButton: @Composable () -> Unit,
    dismissButton: (@Composable () -> Unit)? = null
) {
    val isAmoled = LocalAmoledMode.current

    if (isAmoled) {
        val hazeState   = LocalHazeState.current
        val dialogShape = RoundedCornerShape(28.dp)

        BasicAlertDialog(onDismissRequest = onDismissRequest) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(dialogShape)
                    .hazeEffect(
                        state = hazeState,
                        style = HazeStyle(
                            blurRadius      = 24.dp,
                            backgroundColor = Color.Black,
                            tints           = listOf(HazeTint(Color.Black.copy(alpha = 0.75f)))
                        )
                    )
                    .border(0.5.dp, Color.White.copy(alpha = 0.12f), dialogShape)
                    .padding(24.dp)
            ) {
                CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                    ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
                    if (text != null) {
                        Spacer(Modifier.height(16.dp))
                        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurfaceVariant) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) { text() }
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment     = Alignment.CenterVertically
                    ) {
                        dismissButton?.invoke()
                        confirmButton()
                    }
                }
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = onDismissRequest,
            title            = title,
            text             = text,
            confirmButton    = confirmButton,
            dismissButton    = dismissButton
        )
    }
}
