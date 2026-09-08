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
import androidx.compose.material3.Icon
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.Icons
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
import zip.arcanum.core.components.OperationFailure
import zip.arcanum.core.components.OperationLoading
import zip.arcanum.core.components.OperationScrim
import zip.arcanum.core.components.OperationSuccess
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
    onSave: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    var importPassword  by remember { mutableStateOf("") }
    var passwordShown   by remember { mutableStateOf(false) }

    val openLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.beginImport(uri) }

    /*
     * Restoring reports itself the way saving does: the whole screen, and it stays until Done
     * is pressed. A restore that ends in a banner is a restore nobody is sure happened - and
     * this one rewrites the settings of the app the banner would be sitting in.
     */
    if (state.busy) {
        OperationScrim {
            OperationLoading(
                title    = stringResource(R.string.settings_restore_running),
                subtitle = stringResource(R.string.settings_backup_running_sub)
            )
        }
        return
    }
    when (val result = state.result) {
        is BackupViewModel.Result.Imported -> {
            OperationScrim {
                OperationSuccess(
                    title  = stringResource(R.string.settings_restore_done_title),
                    body   = stringResource(
                        R.string.settings_backup_done_restored, result.settings, result.vaults
                    ) + if (result.skipped > 0)
                            "\n\n" + stringResource(R.string.settings_backup_done_skipped, result.skipped)
                        else "",
                    onDone = { viewModel.clearResult() }
                )
            }
            return
        }
        is BackupViewModel.Result.WrongPassword,
        is BackupViewModel.Result.TooNew,
        is BackupViewModel.Result.Malformed,
        is BackupViewModel.Result.Failed -> {
            OperationScrim {
                OperationFailure(
                    title  = stringResource(R.string.settings_restore_failed_title),
                    body   = when (result) {
                        is BackupViewModel.Result.WrongPassword -> stringResource(R.string.settings_backup_err_password)
                        is BackupViewModel.Result.TooNew        -> stringResource(R.string.settings_backup_err_too_new)
                        is BackupViewModel.Result.Malformed     -> stringResource(R.string.settings_backup_err_malformed)
                        is BackupViewModel.Result.Failed        -> result.message
                        else                                    -> ""
                    },
                    onDone = { viewModel.clearResult() }
                )
            }
            return
        }
        else -> Unit
    }

    SubScreenScaffold(title = stringResource(R.string.settings_backup_title), onBack = onBack) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // No heading over the two rows: there is one group on this screen and its title
            // would name the obvious. The explanation sits under them instead of over them -
            // what the screen offers is two actions, and what it costs is worth reading
            // second.
            SettingsGroup {
                row { shape ->
                    GroupedRow(
                        shape    = shape,
                        title    = stringResource(R.string.settings_backup_save),
                        subtitle = stringResource(R.string.settings_backup_save_desc),
                        enabled  = !state.busy,
                        leading  = {
                            Icon(
                                imageVector        = Icons.Outlined.Upload,
                                contentDescription = null,
                                tint               = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick  = onSave
                    )
                }
                row { shape ->
                    GroupedRow(
                        shape    = shape,
                        title    = stringResource(R.string.settings_backup_restore),
                        subtitle = stringResource(R.string.settings_backup_restore_desc),
                        enabled  = !state.busy,
                        leading  = {
                            Icon(
                                imageVector        = Icons.Outlined.Download,
                                contentDescription = null,
                                tint               = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        onClick  = { openLauncher.launch(arrayOf("*/*")) }
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            Text(
                text     = stringResource(R.string.settings_backup_intro),
                style    = MaterialTheme.typography.bodyMedium,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(Modifier.height(24.dp))
        }
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
                        visualTransformation = if (passwordShown) VisualTransformation.None
                                               else PasswordVisualTransformation(),
                        trailingIcon         = {
                            IconButton(onClick = { passwordShown = !passwordShown }) {
                                Icon(
                                    if (passwordShown) Icons.Outlined.VisibilityOff
                                    else Icons.Outlined.Visibility,
                                    contentDescription = null
                                )
                            }
                        },
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
}
