package zip.arcanum.core.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * The black ground an operation stands on.
 *
 * [OperationLoading], [OperationSuccess] and [OperationFailure] paint no background of their
 * own - they are written to sit inside whatever screen runs them, and at a change of vault
 * password that is the right thing. When an operation takes the screen over instead, it wants
 * the app's own idiom for that: black, the way the not-found and wrong-volume overlays are.
 *
 * The content colour is set here with it, deliberately. White text is not a property of the
 * text; it is a property of what the text is written on, and a screen that hardcodes one
 * without the other is how black-on-black happens.
 */
@Composable
fun OperationScrim(content: @Composable () -> Unit) {
    Surface(
        modifier     = Modifier.fillMaxSize(),
        color        = Color.Black,
        contentColor = Color.White,
        content      = content
    )
}
