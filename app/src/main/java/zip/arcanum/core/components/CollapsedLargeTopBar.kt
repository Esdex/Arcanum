package zip.arcanum.core.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first

/**
 * A large top bar that starts **collapsed**, the way Android's own settings screens open: the
 * title sits in the bar beside the back button, and pulling the page down is what unfolds it.
 *
 * The bar cannot be collapsed before it has been measured - `heightOffsetLimit` is what its
 * own layout reports, and it is zero until then - so the first non-zero value is waited for
 * rather than assumed. After that the behaviour is the ordinary exit-until-collapsed one: the
 * big title comes back only when the content is at its top again.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberCollapsedLargeTopBarBehavior(): TopAppBarScrollBehavior {
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    LaunchedEffect(behavior) {
        val limit = snapshotFlow { behavior.state.heightOffsetLimit }.filter { it < 0f }.first()
        behavior.state.heightOffset = limit
    }
    return behavior
}
