package zip.arcanum.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import zip.arcanum.BuildConfig
import zip.arcanum.R
import zip.arcanum.core.theme.ArcanumHazeStyle
import zip.arcanum.core.theme.LocalAmoledMode

private data class WhatsNewEntry(
    val icon: ImageVector,
    val iconTint: Color,
    val title: String,
    val description: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsNewScreen(onBack: () -> Unit) {
    val isAmoled  = LocalAmoledMode.current
    val hazeState = remember { HazeState() }

    val entries = remember {
        listOf(
            WhatsNewEntry(
                icon        = Icons.Outlined.Terminal,
                iconTint    = Color(0xFF57FF81),
                title       = "Live mount log",
                description = "Enable in debug settings to watch every cipher and PRF combination tried in real time as your container is being unlocked."
            ),
            WhatsNewEntry(
                icon        = Icons.Outlined.NewReleases,
                iconTint    = Color(0xFF7C3AED),
                title       = "Update notifications",
                description = "A banner appears the first time you open the app after an update. Tap it to open this screen and see what changed."
            ),
            WhatsNewEntry(
                icon        = Icons.Outlined.Edit,
                iconTint    = Color(0xFF0891B2),
                title       = "Container rename",
                description = "Rename your containers directly from the container config sheet without having to delete and recreate them."
            ),
            WhatsNewEntry(
                icon        = Icons.Outlined.Fingerprint,
                iconTint    = Color(0xFF16A34A),
                title       = "Biometric after auto-lock",
                description = "Fixed biometric prompt not appearing when the app auto-locked itself in the background — no more forced PIN fallback."
            ),
            WhatsNewEntry(
                icon        = Icons.Outlined.AutoAwesome,
                iconTint    = Color(0xFFD97706),
                title       = "Smoother UI",
                description = "Tab switches are now animated, and password fields no longer show IME suggestions for better privacy."
            ),
            WhatsNewEntry(
                icon        = Icons.Outlined.BugReport,
                iconTint    = Color(0xFFEF4444),
                title       = "Bug fixes",
                description = "• Keyboard and text cursor now close when tapping the mount button\n• Swipe-dismissing the update banner no longer permanently marks it as seen — it returns on next launch"
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title  = { Text(stringResource(R.string.whats_new_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors   = if (isAmoled) TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                           else TopAppBarDefaults.topAppBarColors(),
                modifier = if (isAmoled) Modifier.hazeEffect(state = hazeState, style = ArcanumHazeStyle.topBar)
                           else Modifier
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier      = Modifier
                .fillMaxSize()
                .then(if (isAmoled) Modifier.hazeSource(hazeState) else Modifier),
            contentPadding = PaddingValues(
                start  = 16.dp,
                end    = 16.dp,
                top    = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                VersionHeader()
                Spacer(Modifier.height(8.dp))
            }
            items(entries) { entry ->
                WhatsNewCard(entry)
            }
        }
    }
}

@Composable
private fun VersionHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector        = Icons.Outlined.NewReleases,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.primary,
            modifier           = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(
                text       = stringResource(R.string.whats_new_version, BuildConfig.VERSION_NAME),
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text  = stringResource(R.string.whats_new_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WhatsNewCard(entry: WhatsNewEntry) {
    Card(
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier          = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector        = entry.icon,
                contentDescription = null,
                tint               = entry.iconTint,
                modifier           = Modifier.size(24.dp)
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text       = entry.title,
                    style      = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text  = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
