package zip.arcanum.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.TopAppBarDefaults
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import zip.arcanum.core.components.LocalHazeState
import zip.arcanum.core.theme.ArcanumHazeStyle
import zip.arcanum.core.theme.LocalAmoledMode
import zip.arcanum.core.components.BackButton
import zip.arcanum.core.components.rememberCollapsedLargeTopBarBehavior

// The frame every settings sub-screen is built in - its scaffold, its groups and its
// section labels. Nothing here belongs to one screen in particular.

@Composable
internal fun SubScreenGroup(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SubScreenScaffold(
    title: String,
    onBack: () -> Unit,
    content: @Composable (innerPadding: PaddingValues) -> Unit
) {
    val isAmoled  = LocalAmoledMode.current
    val hazeState = remember { HazeState() }

    CompositionLocalProvider(LocalHazeState provides hazeState) {
        // The same header as everywhere else now: large, on the left, and folded when the
        // screen opens. In AMOLED both of its colours have to go transparent, not just the
        // resting one, or it fills with grey the moment the page moves.
        val scrollBehavior = rememberCollapsedLargeTopBarBehavior()
        Scaffold(
            modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
            topBar = {
                LargeTopAppBar(
                    title          = { Text(title) },
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        BackButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp))
                    },
                    colors   = if (isAmoled) TopAppBarDefaults.largeTopAppBarColors(
                                   containerColor = Color.Transparent,
                                   scrolledContainerColor = Color.Transparent
                               )
                               else TopAppBarDefaults.largeTopAppBarColors(),
                    modifier = if (isAmoled) Modifier.hazeEffect(state = hazeState, style = ArcanumHazeStyle.topBar)
                               else Modifier
                )
            }
        ) { innerPadding ->
            Box(
                Modifier
                    .fillMaxSize()
                    .hazeSource(hazeState)
            ) {
                content(innerPadding)
            }
        }
    }
}

// ── Sub-screens ───────────────────────────────────────────────────────────────

@Composable
internal fun PanicSectionLabel(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelSmall,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)
    )
}
