package zip.arcanum.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mikepenz.aboutlibraries.ui.compose.android.produceLibraries
import com.mikepenz.aboutlibraries.ui.compose.m3.LibrariesContainer
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import androidx.compose.ui.res.stringResource
import zip.arcanum.R
import zip.arcanum.core.theme.ArcanumHazeStyle
import zip.arcanum.core.theme.LocalAmoledMode
import zip.arcanum.core.components.BackButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val isAmoled  = LocalAmoledMode.current
    val hazeState = remember { HazeState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title          = { Text(stringResource(R.string.settings_about_licenses)) },
                navigationIcon = {
                    BackButton(onClick = onBack)
                },
                colors   = if (isAmoled) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                           else TopAppBarDefaults.topAppBarColors(),
                modifier = if (isAmoled) Modifier.hazeEffect(state = hazeState, style = ArcanumHazeStyle.topBar)
                           else Modifier
            )
        }
    ) { innerPadding ->
        val libraries by produceLibraries()
        LibrariesContainer(
            libraries = libraries,
            modifier = Modifier
                .fillMaxSize()
                .then(if (isAmoled) Modifier.hazeSource(hazeState) else Modifier),
            contentPadding = innerPadding
        )
    }
}
