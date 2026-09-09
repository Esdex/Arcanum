package zip.arcanum.core.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction

/**
 * The search box that expands inside a top bar.
 *
 * One component because there were three of these - the Gallery tab's own bar, the bar
 * NavGraph draws for the Gallery inside a vault, and the Files bar - and they had already
 * drifted: the caret was invisible on the dark theme in two of them, and only one took the
 * focus on its own. A fix applied to one copy looked like a fix and shipped nothing.
 *
 * The focus cannot be asked for on the frame this composes. Every caller puts the field
 * inside a container that animates or swaps its content, so the node is not attached yet,
 * and requestFocus on an unattached requester throws - hence a frame at a time, up to three.
 */
@Composable
fun TopBarSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Default
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard       = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        repeat(3) {
            withFrameNanos { }
            if (runCatching { focusRequester.requestFocus() }.isSuccess) {
                keyboard?.show()
                return@LaunchedEffect
            }
        }
    }
    BasicTextField(
        value           = query,
        onValueChange   = onQueryChange,
        singleLine      = true,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        textStyle       = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        /* BasicTextField paints its caret black unless told otherwise, and black on the
           dark theme is a caret nobody can see. */
        cursorBrush     = SolidColor(MaterialTheme.colorScheme.primary),
        modifier        = modifier.focusRequester(focusRequester),
        decorationBox   = { inner ->
            Box {
                if (query.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                inner()
            }
        }
    )
}
