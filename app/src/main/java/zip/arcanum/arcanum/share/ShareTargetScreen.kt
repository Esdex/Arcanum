@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package zip.arcanum.arcanum.share

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import zip.arcanum.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareTargetScreen(
    onDone: () -> Unit,
    onCancel: () -> Unit,
    viewModel: ShareTargetViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    BackHandler { onCancel() }

    LaunchedEffect(state.savedCount) {
        state.savedCount?.let { count ->
            Toast.makeText(
                context,
                context.resources.getQuantityString(R.plurals.share_saved_toast, count, count),
                Toast.LENGTH_SHORT
            ).show()
            onDone()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.share_target_title)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.vaults.isEmpty() ->
                    CenterMessage(stringResource(R.string.share_target_no_vaults))

                state.selectedVaultId == null ->
                    VaultPicker(state) { viewModel.selectVault(it) }

                else ->
                    FolderBrowser(
                        state          = state,
                        onEnter        = viewModel::enterDirectory,
                        onUp           = viewModel::navigateUp,
                        onSave         = viewModel::saveHere,
                        onSwitchVault  = viewModel::clearVault,
                        canSwitchVault = state.vaults.size > 1
                    )
            }

            if (state.isSaving) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun VaultPicker(state: ShareTargetViewModel.State, onPick: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(
                text     = pluralStringResource(R.plurals.share_target_pick_vault, state.fileCount, state.fileCount),
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
        }
        items(state.vaults, key = { it.id }) { v ->
            ListItem(
                headlineContent = { Text(v.name) },
                leadingContent  = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                modifier        = Modifier.clickable { onPick(v.id) }
            )
        }
    }
}

@Composable
private fun FolderBrowser(
    state: ShareTargetViewModel.State,
    onEnter: (String) -> Unit,
    onUp: () -> Unit,
    onSave: () -> Unit,
    onSwitchVault: () -> Unit,
    canSwitchVault: Boolean
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (state.currentPath != "/") {
                IconButton(onClick = onUp) {
                    Icon(Icons.Outlined.ArrowUpward, contentDescription = stringResource(R.string.common_back))
                }
            }
            Text(
                text     = state.currentPath,
                style    = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (canSwitchVault) {
                androidx.compose.material3.TextButton(onClick = onSwitchVault) {
                    Text(stringResource(R.string.share_target_change_vault))
                }
            }
        }

        LazyColumn(Modifier.weight(1f)) {
            if (state.directories.isEmpty()) {
                item {
                    Text(
                        text     = stringResource(R.string.share_target_no_subfolders),
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
            items(state.directories, key = { it }) { dir ->
                ListItem(
                    headlineContent = { Text(dir) },
                    leadingContent  = { Icon(Icons.Outlined.Folder, contentDescription = null) },
                    modifier        = Modifier.clickable { onEnter(dir) }
                )
            }
        }

        Button(
            onClick  = onSave,
            enabled  = !state.isSaving,
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .navigationBarsPadding()
        ) {
            Text(pluralStringResource(R.plurals.share_target_save_here, state.fileCount, state.fileCount))
        }
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
