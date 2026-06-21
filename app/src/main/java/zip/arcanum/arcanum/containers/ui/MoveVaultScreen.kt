package zip.arcanum.arcanum.containers.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.animateLottieCompositionAsState
import com.airbnb.lottie.compose.rememberLottieComposition
import zip.arcanum.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveVaultScreen(
    onBack: () -> Unit,
    viewModel: MoveVaultViewModel = hiltViewModel()
) {
    val state       by viewModel.state.collectAsState()
    val storageInfo by viewModel.storageInfo.collectAsState()
    val toApp        = viewModel.toApp
    val isMoving     = state is MoveVaultViewModel.State.Moving

    BackHandler(enabled = isMoving) { /* blocked during operation */ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (toApp) stringResource(R.string.move_vault_title_to_app) else stringResource(R.string.move_vault_title_to_internal))
                },
                navigationIcon = {
                    if (!isMoving) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (val s = state) {
                is MoveVaultViewModel.State.Idle    -> InfoScreen(
                    toApp       = toApp,
                    storageInfo = storageInfo,
                    onStart     = { backup, uri -> viewModel.startMove(backup, uri) },
                    onRefreshFreeSpace = { backup, uri -> viewModel.refreshFreeSpace(backup, uri) }
                )
                is MoveVaultViewModel.State.Moving  -> MovingScreen(s)
                is MoveVaultViewModel.State.Success -> SuccessScreen(onDone = onBack)
                is MoveVaultViewModel.State.Failure -> FailureScreen(
                    message = s.message,
                    onRetry = viewModel::reset,
                    onBack  = onBack
                )
            }
        }
    }
}

// ── Info screen ───────────────────────────────────────────────────────────────

@Composable
private fun InfoScreen(
    toApp: Boolean,
    storageInfo: MoveVaultViewModel.StorageInfo?,
    onStart: (includeInBackup: Boolean, destUri: Uri?) -> Unit,
    onRefreshFreeSpace: (includeInBackup: Boolean, destUri: Uri?) -> Unit
) {
    var includeInBackup by rememberSaveable { mutableStateOf(false) }
    var pickedUri       by remember { mutableStateOf<Uri?>(null) }
    var pickedDirLabel  by rememberSaveable { mutableStateOf("") }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        pickedUri      = uri
        pickedDirLabel = uri?.lastPathSegment ?: ""
    }

    // Re-query free space whenever backup toggle or destination changes
    LaunchedEffect(includeInBackup, pickedUri) {
        onRefreshFreeSpace(includeInBackup, pickedUri)
    }

    val hasEnoughSpace = storageInfo?.let { it.destinationFreeBytes >= it.containerSize } ?: true

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // ── Storage summary card ──────────────────────────────────────────
        storageInfo?.let { info ->
            Card(
                colors   = CardDefaults.cardColors(
                    containerColor = if (hasEnoughSpace)
                        MaterialTheme.colorScheme.surfaceVariant
                    else
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(
                        text       = info.containerName,
                        style      = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    StorageRow(label = stringResource(R.string.move_vault_label_vault_size),  value = formatBytes(info.containerSize))
                    StorageRow(label = stringResource(R.string.move_vault_label_available),    value = formatBytes(info.destinationFreeBytes))

                    if (!hasEnoughSpace) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector        = Icons.Outlined.Warning,
                                contentDescription = null,
                                tint               = MaterialTheme.colorScheme.error,
                                modifier           = Modifier.size(16.dp)
                            )
                            Text(
                                text  = stringResource(R.string.move_vault_not_enough_space, formatBytes(info.containerSize - info.destinationFreeBytes)),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // Benefits section
        SectionLabel(stringResource(R.string.move_vault_section_what))
        if (toApp) {
            InfoItem(stringResource(R.string.move_vault_info_app_1))
            InfoItem(stringResource(R.string.move_vault_info_app_2))
            InfoItem(stringResource(R.string.move_vault_info_app_3))
        } else {
            InfoItem(stringResource(R.string.move_vault_info_int_1))
            InfoItem(stringResource(R.string.move_vault_info_int_2))
            InfoItem(stringResource(R.string.move_vault_info_int_3))
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        // Warnings section
        SectionLabel(stringResource(R.string.move_vault_section_before))
        InfoItem(stringResource(R.string.move_vault_before_1))
        InfoItem(stringResource(R.string.move_vault_before_2))

        Spacer(Modifier.height(16.dp))
        HorizontalDivider()
        Spacer(Modifier.height(16.dp))

        if (toApp) {
            Row(
                verticalAlignment     = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier              = Modifier.fillMaxWidth()
            ) {
                Checkbox(
                    checked         = includeInBackup,
                    onCheckedChange = { includeInBackup = it }
                )
                Column {
                    Text(
                        text       = stringResource(R.string.move_vault_backup_label),
                        style      = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text  = stringResource(R.string.move_vault_backup_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        } else {
            // Folder picker
            SectionLabel(stringResource(R.string.move_vault_section_dest_folder))
            Spacer(Modifier.height(8.dp))
            OutlinedCard(
                onClick  = { folderPicker.launch(null) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier              = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector        = Icons.Outlined.FolderOpen,
                        contentDescription = null,
                        tint               = MaterialTheme.colorScheme.primary
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text  = if (pickedUri == null) stringResource(R.string.move_vault_choose_folder) else stringResource(R.string.move_vault_selected_folder),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text  = if (pickedDirLabel.isBlank()) stringResource(R.string.move_vault_default_folder)
                                    else pickedDirLabel,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick  = { onStart(includeInBackup, pickedUri) },
            enabled  = hasEnoughSpace,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.move_vault_start_move))
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun StorageRow(label: String, value: String) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text  = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text       = value,
            style      = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelMedium,
        color    = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

@Composable
private fun InfoItem(text: String) {
    Row(
        modifier              = Modifier.padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text  = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ── Moving screen ─────────────────────────────────────────────────────────────

@Composable
private fun MovingScreen(state: MoveVaultViewModel.State.Moving) {
    val view = LocalView.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.loading))
    val progress    by animateLottieCompositionAsState(
        composition = composition,
        iterations  = LottieConstants.IterateForever
    )

    Column(
        modifier              = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp)
            .statusBarsPadding()
            .navigationBarsPadding()
            .offset(y = (-20).dp),
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.Center
    ) {
        LottieAnimation(
            composition = composition,
            progress    = { progress },
            modifier    = Modifier.size(184.dp)
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text      = stringResource(R.string.move_vault_moving_title),
            style     = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        val pct = (state.progress * 100).toInt()
        val mb  = state.bytesDone / 1_048_576f
        val tot = state.totalBytes / 1_048_576f
        Text(
            text  = stringResource(R.string.move_vault_moving_progress, pct, mb, tot),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text      = stringResource(R.string.move_vault_moving_body),
            style     = MaterialTheme.typography.bodySmall,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

// ── Success screen ────────────────────────────────────────────────────────────

@Composable
private fun SuccessScreen(onDone: () -> Unit) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.success_check))
    val progress    by animateLottieCompositionAsState(composition = composition, iterations = 1)

    Column(
        modifier              = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp)
            .navigationBarsPadding(),
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.Center
    ) {
        LottieAnimation(
            composition = composition,
            progress    = { progress },
            modifier    = Modifier.size(160.dp)
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text       = stringResource(R.string.move_vault_success_title),
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign  = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text      = stringResource(R.string.move_vault_success_body),
            style     = MaterialTheme.typography.bodyMedium,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick  = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.common_done))
        }
    }
}

// ── Failure screen ────────────────────────────────────────────────────────────

@Composable
private fun FailureScreen(message: String, onRetry: () -> Unit, onBack: () -> Unit) {
    val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.error))
    val progress    by animateLottieCompositionAsState(composition = composition, iterations = 1)

    Column(
        modifier              = Modifier
            .fillMaxSize()
            .padding(horizontal = 40.dp)
            .navigationBarsPadding(),
        horizontalAlignment   = Alignment.CenterHorizontally,
        verticalArrangement   = Arrangement.Center
    ) {
        LottieAnimation(
            composition = composition,
            progress    = { progress },
            modifier    = Modifier.size(160.dp)
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text       = stringResource(R.string.move_vault_failure_title),
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign  = TextAlign.Center
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text      = message,
            style     = MaterialTheme.typography.bodySmall,
            color     = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(32.dp))

        Button(
            onClick  = onRetry,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.move_vault_retry))
        }

        Spacer(Modifier.height(8.dp))

        TextButton(
            onClick  = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.common_back))
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.2f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L     -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L         -> "%.0f KB".format(bytes / 1_024.0)
    else                    -> "$bytes B"
}
