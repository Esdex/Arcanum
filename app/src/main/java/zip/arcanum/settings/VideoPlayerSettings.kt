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
import zip.arcanum.core.security.VideoPlayerPrefs

/**
 * The video player's extras, one switch each (#151): the gestures change what a touch on the
 * video does, and the controls add buttons to it, so each can be taken away on its own.
 */
@Composable
internal fun VideoPlayerSubScreen(
    prefs: VideoPlayerPrefs,
    onSwipeVolume: (Boolean) -> Unit,
    onSwipeBrightness: (Boolean) -> Unit,
    onHoldForSpeed: (Boolean) -> Unit,
    onLockButton: (Boolean) -> Unit,
    onSpeedMenu: (Boolean) -> Unit,
    onLoop: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    SubScreenScaffold(title = stringResource(R.string.settings_player_title), onBack = onBack) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SettingsGroup(title = stringResource(R.string.settings_player_group_gestures)) {
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_player_swipe_volume),
                        checked         = prefs.swipeVolume,
                        onCheckedChange = onSwipeVolume
                    )
                }
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_player_swipe_brightness),
                        info            = stringResource(R.string.settings_player_swipe_brightness_desc),
                        checked         = prefs.swipeBrightness,
                        onCheckedChange = onSwipeBrightness
                    )
                }
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_player_hold_speed),
                        checked         = prefs.holdForSpeed,
                        onCheckedChange = onHoldForSpeed
                    )
                }
            }

            SettingsGroup(title = stringResource(R.string.settings_player_group_controls)) {
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_player_lock),
                        info            = stringResource(R.string.settings_player_lock_desc),
                        checked         = prefs.lockButton,
                        onCheckedChange = onLockButton
                    )
                }
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_player_speed),
                        checked         = prefs.speedMenu,
                        onCheckedChange = onSpeedMenu
                    )
                }
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_player_loop),
                        checked         = prefs.loop,
                        onCheckedChange = onLoop
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}
