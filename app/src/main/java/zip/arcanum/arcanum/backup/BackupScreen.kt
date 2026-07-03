package zip.arcanum.arcanum.backup

import android.net.Uri
import android.provider.DocumentsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import zip.arcanum.R
import zip.arcanum.core.database.entities.ContainerEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val progress by viewModel.progress.collectAsState()
    val latestProgress by rememberUpdatedState(progress)
    val activity = LocalContext.current as? FragmentActivity
    val view = LocalView.current

    val folderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.onLocalFolderSelected(uri)
    }

    DisposableEffect(progress.isRunning) {
        if (progress.isRunning) view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(Unit) {
        onDispose {
            if (latestProgress.isRunning) viewModel.cancelBackup()
        }
    }

    BackHandler(enabled = progress.isRunning) {
        viewModel.cancelBackup()
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backup_title)) },
                navigationIcon = {
                    IconButton(onClick = {
                        if (progress.isRunning) viewModel.cancelBackup()
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            state.container?.let { ContainerSummary(it) }

            if (state.container?.isMounted == true) {
                Text(
                    text = stringResource(R.string.backup_mounted_auto_unmount),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            ProviderSelector(
                provider = state.settings.provider,
                enabled = !progress.isRunning && !state.isSaving,
                onProvider = { provider -> viewModel.updateSettings { it.copy(provider = provider) } }
            )

            when (state.settings.provider) {
                BackupProvider.LOCAL -> LocalSettings(
                    settings = state.settings,
                    enabled = !progress.isRunning && !state.isSaving,
                    onChoose = { folderLauncher.launch(null) }
                )
                BackupProvider.S3 -> {
                    if (state.settings.hasSensitiveCredentials(BackupProvider.S3) && !state.secretsUnlocked) {
                        CredentialLockedPanel(
                            providerName = "S3",
                            enabled = !progress.isRunning && !state.isSaving,
                            onUnlock = { viewModel.unlockCredentialEditing(activity) }
                        )
                    } else {
                        S3Settings(
                            settings = state.settings,
                            enabled = !progress.isRunning && !state.isSaving,
                            onChange = { viewModel.updateSettings(it) }
                        )
                    }
                }
                BackupProvider.MEGA -> {
                    if (state.settings.hasSensitiveCredentials(BackupProvider.MEGA) && !state.secretsUnlocked) {
                        CredentialLockedPanel(
                            providerName = "Mega.nz",
                            enabled = !progress.isRunning && !state.isSaving,
                            onUnlock = { viewModel.unlockCredentialEditing(activity) }
                        )
                    } else {
                        MegaSettings(
                            settings = state.settings,
                            enabled = !progress.isRunning && !state.isSaving,
                            onChange = { viewModel.updateSettings(it) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.backup_delete_previous), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.backup_delete_previous_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.settings.deletePreviousAfterSuccess,
                    enabled = !progress.isRunning && !state.isSaving,
                    onCheckedChange = { checked ->
                        viewModel.updateSettings { it.copy(deletePreviousAfterSuccess = checked) }
                    }
                )
            }

            state.lastRecord?.let {
                Text(
                    text = stringResource(R.string.backup_last_copy, it.fileName),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            state.message?.let {
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            BackupProgress(progress)

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { viewModel.save() },
                    enabled = !progress.isRunning && !state.isSaving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.Save, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(
                        text = if (state.isSaving) stringResource(R.string.backup_checking) else stringResource(R.string.backup_check_save),
                        maxLines = 1
                    )
                }
                Button(
                    onClick = { viewModel.startBackup() },
                    enabled = state.canStart && !progress.isRunning && !state.isSaving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.CloudUpload, contentDescription = null)
                    Spacer(Modifier.padding(horizontal = 4.dp))
                    Text(stringResource(R.string.backup_create), maxLines = 1)
                }
            }

            if (progress.isRunning) {
                OutlinedButton(
                    onClick = { viewModel.cancelBackup() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.backup_stop))
                }
            }
        }
    }
}

@Composable
private fun CredentialLockedPanel(
    providerName: String,
    enabled: Boolean,
    onUnlock: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.backup_credentials_saved, providerName), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.backup_credentials_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = onUnlock, enabled = enabled) {
            Icon(Icons.Outlined.Lock, contentDescription = null)
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(stringResource(R.string.backup_unlock))
        }
    }
}

@Composable
private fun ContainerSummary(container: ContainerEntity) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(container.name, style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.backup_container_summary, formatBytes(container.size)),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    HorizontalDivider()
}

@Composable
private fun ProviderSelector(
    provider: BackupProvider,
    enabled: Boolean,
    onProvider: (BackupProvider) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        FilterChip(
            selected = provider == BackupProvider.LOCAL,
            enabled = enabled,
            onClick = { onProvider(BackupProvider.LOCAL) },
            label = { Text(stringResource(R.string.backup_provider_folder)) },
            leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) }
        )
        FilterChip(
            selected = provider == BackupProvider.S3,
            enabled = enabled,
            onClick = { onProvider(BackupProvider.S3) },
            label = { Text(stringResource(R.string.backup_provider_s3)) },
            leadingIcon = { Icon(Icons.Outlined.CloudUpload, contentDescription = null) }
        )
        FilterChip(
            selected = provider == BackupProvider.MEGA,
            enabled = enabled,
            onClick = { onProvider(BackupProvider.MEGA) },
            label = { Text(stringResource(R.string.backup_provider_mega)) },
            leadingIcon = { Icon(Icons.Outlined.CloudUpload, contentDescription = null) }
        )
    }
}

@Composable
private fun LocalSettings(
    settings: BackupSettings,
    enabled: Boolean,
    onChoose: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.backup_local_folder), style = MaterialTheme.typography.titleMedium)
        Text(
            if (settings.localFolderUri.isBlank()) {
                stringResource(R.string.backup_folder_not_selected)
            } else {
                stringResource(R.string.backup_folder_path, formatLocalFolderUri(settings.localFolderUri))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        OutlinedButton(onClick = onChoose, enabled = enabled) {
            Icon(Icons.Outlined.Folder, contentDescription = null)
            Spacer(Modifier.padding(horizontal = 4.dp))
            Text(stringResource(R.string.backup_choose_folder))
        }
    }
}

@Composable
private fun S3Settings(
    settings: BackupSettings,
    enabled: Boolean,
    onChange: ((BackupSettings) -> BackupSettings) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.backup_s3_storage), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = settings.s3Endpoint,
            onValueChange = { value -> onChange { it.copy(s3Endpoint = value) } },
            enabled = enabled,
            label = { Text(stringResource(R.string.backup_s3_endpoint_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = settings.s3Region,
            onValueChange = { value -> onChange { it.copy(s3Region = value) } },
            enabled = enabled,
            label = { Text(stringResource(R.string.backup_label_region)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = settings.s3Bucket,
            onValueChange = { value -> onChange { it.copy(s3Bucket = value) } },
            enabled = enabled,
            label = { Text(stringResource(R.string.backup_label_bucket)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = settings.s3Prefix,
            onValueChange = { value -> onChange { it.copy(s3Prefix = value) } },
            enabled = enabled,
            label = { Text(stringResource(R.string.backup_s3_prefix_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = settings.s3AccessKey,
            onValueChange = { value -> onChange { it.copy(s3AccessKey = value) } },
            enabled = enabled,
            label = { Text(stringResource(R.string.backup_label_access_key)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = settings.s3SecretKey,
            onValueChange = { value -> onChange { it.copy(s3SecretKey = value) } },
            enabled = enabled,
            label = { Text(stringResource(R.string.backup_label_secret_key)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        OutlinedTextField(
            value = settings.s3SessionToken,
            onValueChange = { value -> onChange { it.copy(s3SessionToken = value) } },
            enabled = enabled,
            label = { Text(stringResource(R.string.backup_s3_session_token_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.backup_path_style_access), style = MaterialTheme.typography.bodyLarge)
            Switch(
                checked = settings.s3PathStyle,
                enabled = enabled,
                onCheckedChange = { checked -> onChange { it.copy(s3PathStyle = checked) } }
            )
        }
    }
}

@Composable
private fun MegaSettings(
    settings: BackupSettings,
    enabled: Boolean,
    onChange: ((BackupSettings) -> BackupSettings) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.backup_mega_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.backup_mega_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(
            value = settings.megaEmail,
            onValueChange = { value -> onChange { it.copy(megaEmail = value) } },
            enabled = enabled,
            label = { Text(stringResource(R.string.backup_label_email)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
        )
        OutlinedTextField(
            value = settings.megaPassword,
            onValueChange = { value -> onChange { it.copy(megaPassword = value) } },
            enabled = enabled,
            label = { Text(stringResource(R.string.backup_password_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = PasswordVisualTransformation()
        )
        OutlinedTextField(
            value = settings.megaFolder,
            onValueChange = { value -> onChange { it.copy(megaFolder = value) } },
            enabled = enabled,
            label = { Text(stringResource(R.string.backup_mega_folder_label)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

@Composable
private fun BackupProgress(progress: BackupProgressState) {
    if (progress.status == BackupStatus.IDLE) return

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = when (progress.status) {
                BackupStatus.VALIDATING -> stringResource(R.string.backup_status_preparing)
                BackupStatus.RUNNING    -> stringResource(R.string.backup_status_running)
                BackupStatus.STOPPING   -> stringResource(R.string.backup_status_stopping)
                BackupStatus.SUCCESS    -> stringResource(R.string.backup_status_success)
                BackupStatus.PAUSED     -> stringResource(R.string.backup_status_paused)
                BackupStatus.FAILED     -> stringResource(R.string.backup_status_failed)
                BackupStatus.CANCELLED  -> stringResource(R.string.backup_status_cancelled)
                BackupStatus.IDLE       -> ""
            },
            style = MaterialTheme.typography.titleMedium
        )
        LinearProgressIndicator(
            progress = { progress.fraction },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "${formatBytes(progress.bytesTransferred)} / ${formatBytes(progress.totalBytes)}" +
                if (progress.speedBytesPerSecond > 0L) " • ${stringResource(R.string.backup_speed_suffix, formatBytes(progress.speedBytesPerSecond))}" else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (progress.message.isNotBlank()) {
            Text(progress.message, style = MaterialTheme.typography.bodyMedium)
        }
        progress.error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun formatBytes(bytes: Long): String {
    if (bytes <= 0L) return "0 ${stringResource(R.string.backup_unit_kb)}"
    val kb = stringResource(R.string.backup_unit_kb)
    val mbUnit = stringResource(R.string.backup_unit_mb)
    val gbUnit = stringResource(R.string.backup_unit_gb)
    val gb = bytes / (1024.0 * 1024.0 * 1024.0)
    val mb = bytes / (1024.0 * 1024.0)
    return when {
        gb >= 1.0 -> String.format("%.1f %s", gb, gbUnit)
        mb >= 1.0 -> String.format("%.1f %s", mb, mbUnit)
        else      -> String.format("%.1f %s", bytes / 1024.0, kb)
    }
}

@Composable
private fun formatLocalFolderUri(value: String): String {
    val uri = runCatching { Uri.parse(value) }.getOrNull() ?: return value
    val treeId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    if (!treeId.isNullOrBlank()) return formatDocumentTreeId(treeId)
    if (uri.scheme == "file") return uri.path ?: value
    return Uri.decode(value)
}

@Composable
private fun formatDocumentTreeId(treeId: String): String {
    val decoded = Uri.decode(treeId)
    return when {
        decoded == "primary:" -> stringResource(R.string.backup_internal_storage)
        decoded.startsWith("primary:") -> stringResource(R.string.backup_internal_storage) + decoded.removePrefix("primary:").trimStart('/')
        decoded == "home:" -> stringResource(R.string.backup_documents)
        decoded.startsWith("home:") -> stringResource(R.string.backup_documents) + decoded.removePrefix("home:").trimStart('/')
        else -> decoded.replace(':', '/')
    }
}
