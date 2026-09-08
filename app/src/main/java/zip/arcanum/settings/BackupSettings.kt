package zip.arcanum.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import zip.arcanum.R
import zip.arcanum.core.backup.BackupCodec
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.components.GroupedRow
import zip.arcanum.core.components.GroupedSwitch
import zip.arcanum.core.components.SettingsGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings, Backup: writing the settings to a file and reading them back (#63).
 *
 * The screen says what travels and what does not before it offers to do anything, because
 * the answer is the interesting part: a restored vault still has to be pointed at its file,
 * and a PIN never travels at all.
 */
@Composable
internal fun BackupSubScreen(
    onBack: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    var showExportSheet by remember { mutableStateOf(false) }
    var includeVaults   by remember { mutableStateOf(true) }
    var usePassword     by remember { mutableStateOf(true) }
    var password        by remember { mutableStateOf("") }
    var importPassword  by remember { mutableStateOf("") }

    val suggestedName = remember {
        val day = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        "arcanum-settings-$day.${BackupCodec.FILE_EXTENSION}"
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            viewModel.export(
                target        = uri,
                includeVaults = includeVaults,
                password      = if (usePassword && password.isNotBlank()) password.toCharArray() else null
            )
        }
        password = ""
    }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.beginImport(uri) }

    SubScreenScaffold(title = stringResource(R.string.settings_backup_title), onBack = onBack) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text     = stringResource(R.string.settings_backup_intro),
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp)
            )

            SettingsGroup(title = stringResource(R.string.settings_backup_group_file)) {
                row { shape ->
                    GroupedRow(
                        shape    = shape,
                        title    = stringResource(R.string.settings_backup_save),
                        subtitle = stringResource(R.string.settings_backup_save_desc),
                        enabled  = !state.busy,
                        onClick  = { showExportSheet = true }
                    )
                }
                row { shape ->
                    GroupedRow(
                        shape    = shape,
                        title    = stringResource(R.string.settings_backup_restore),
                        subtitle = stringResource(R.string.settings_backup_restore_desc),
                        enabled  = !state.busy,
                        onClick  = { openLauncher.launch(arrayOf("*/*")) }
                    )
                }
            }

            if (state.busy) {
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(Modifier.padding(start = 16.dp).height(24.dp))
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    // The options are asked for BEFORE the file dialog: a password typed after choosing where
    // to save reads as a second thought, and the choice of what goes in changes what the file
    // is worth protecting.
    if (showExportSheet) {
        AppDialog(
            onDismissRequest = { showExportSheet = false },
            title = { Text(stringResource(R.string.settings_backup_save)) },
            text  = {
                Column {
                    SettingsGroup {
                        row { shape ->
                            GroupedSwitch(
                                shape           = shape,
                                title           = stringResource(R.string.settings_backup_include_vaults),
                                info            = stringResource(R.string.settings_backup_include_vaults_desc),
                                checked         = includeVaults,
                                onCheckedChange = { includeVaults = it }
                            )
                        }
                        row { shape ->
                            GroupedSwitch(
                                shape           = shape,
                                title           = stringResource(R.string.settings_backup_protect),
                                info            = stringResource(R.string.settings_backup_protect_desc),
                                checked         = usePassword,
                                onCheckedChange = { usePassword = it }
                            )
                        }
                    }
                    if (usePassword) {
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value                = password,
                            onValueChange        = { password = it },
                            label                = { Text(stringResource(R.string.settings_backup_password)) },
                            singleLine           = true,
                            visualTransformation  = PasswordVisualTransformation(),
                            keyboardOptions      = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier             = Modifier.fillMaxWidth()
                        )
                    } else {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text  = stringResource(R.string.settings_backup_no_password_warning),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !usePassword || password.isNotBlank(),
                    onClick = {
                        showExportSheet = false
                        saveLauncher.launch(suggestedName)
                    }
                ) { Text(stringResource(R.string.settings_backup_choose_file)) }
            },
            dismissButton = {
                TextButton(onClick = { showExportSheet = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    state.pendingImport?.let {
        AppDialog(
            onDismissRequest = { importPassword = ""; viewModel.cancelPendingImport() },
            title = { Text(stringResource(R.string.settings_backup_password_needed_title)) },
            text  = {
                Column {
                    Text(
                        text  = stringResource(R.string.settings_backup_password_needed_body),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value                = importPassword,
                        onValueChange        = { importPassword = it },
                        label                = { Text(stringResource(R.string.settings_backup_password)) },
                        singleLine           = true,
                        visualTransformation  = PasswordVisualTransformation(),
                        keyboardOptions      = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier             = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = importPassword.isNotBlank(),
                    onClick = {
                        viewModel.finishImport(importPassword.toCharArray())
                        importPassword = ""
                    }
                ) { Text(stringResource(R.string.settings_backup_restore)) }
            },
            dismissButton = {
                TextButton(onClick = { importPassword = ""; viewModel.cancelPendingImport() }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    state.result?.let { result ->
        val message = when (result) {
            is BackupViewModel.Result.Exported ->
                stringResource(R.string.settings_backup_done_saved, result.settings, result.vaults) +
                    if (result.encrypted) "" else "\n\n" + stringResource(R.string.settings_backup_no_password_warning)
            is BackupViewModel.Result.Imported ->
                stringResource(R.string.settings_backup_done_restored, result.settings, result.vaults) +
                    if (result.skipped > 0) "\n\n" + stringResource(R.string.settings_backup_done_skipped, result.skipped) else ""
            is BackupViewModel.Result.WrongPassword -> stringResource(R.string.settings_backup_err_password)
            is BackupViewModel.Result.TooNew        -> stringResource(R.string.settings_backup_err_too_new)
            is BackupViewModel.Result.Malformed     -> stringResource(R.string.settings_backup_err_malformed)
            is BackupViewModel.Result.Failed        -> result.message
        }
        AppDialog(
            onDismissRequest = { viewModel.clearResult() },
            title = { Text(stringResource(R.string.settings_backup_title)) },
            text  = { Text(message) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearResult() }) {
                    Text(stringResource(R.string.common_ok))
                }
            }
        )
    }
}
