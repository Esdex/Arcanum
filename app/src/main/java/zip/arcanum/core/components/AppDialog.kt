package zip.arcanum.core.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import android.view.ViewGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import zip.arcanum.core.theme.LocalAmoledMode

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

        // Use a full-screen Dialog window so hazeEffect's local coords match screen coords.
        // BasicAlertDialog creates a window sized to content — Haze can't map those coords
        // back to the hazeSource in the main window, so blur never appears.
        Dialog(
            onDismissRequest = onDismissRequest,
            properties       = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            // Force MATCH_PARENT height so hazeEffect's local coords == screen coords.
            // Without this, the dialog window is WRAP_CONTENT height and positioned at
            // (0, screenCenter), causing Haze to sample the wrong region of hazeSource.
            val dialogView = LocalView.current
            SideEffect {
                (dialogView.parent as? DialogWindowProvider)?.window
                    ?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }

            Box(
                modifier         = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
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
            } // Box
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
