package zip.arcanum.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import zip.arcanum.R
import zip.arcanum.core.components.GroupedSwitch
import zip.arcanum.core.components.SettingsGroup

/**
 * The gallery's own settings. One switch so far, the fast scroller (#191): it changes what a
 * touch on the right edge of the grid does while it is showing, so it can be taken away.
 */
@Composable
internal fun GallerySubScreen(
    fastScroll: Boolean,
    onFastScroll: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    SubScreenScaffold(title = stringResource(R.string.settings_gallery_title), onBack = onBack) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SettingsGroup(title = stringResource(R.string.settings_gallery_group_scrolling)) {
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_gallery_fast_scroll),
                        info            = stringResource(R.string.settings_gallery_fast_scroll_desc),
                        checked         = fastScroll,
                        onCheckedChange = onFastScroll
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
