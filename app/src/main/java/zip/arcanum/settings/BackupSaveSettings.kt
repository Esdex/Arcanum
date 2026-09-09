package zip.arcanum.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import zip.arcanum.R
import zip.arcanum.core.backup.BackupCodec
import zip.arcanum.core.components.WarningOverlay
import zip.arcanum.core.components.OperationFailure
import zip.arcanum.core.components.OperationScrim
import zip.arcanum.core.components.OperationLoading
import zip.arcanum.core.components.OperationSuccess
import zip.arcanum.core.components.GroupedSwitch
import zip.arcanum.core.components.SettingsGroup
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Settings, Backup, Save to a file: what goes in and what protects it (#63).
 *
 * A page rather than a dialog, Esdex's call and the right one. Two switches whose
 * explanations live behind an "i", a password field, and a warning that has to be read
 * rather than glanced past do not fit in a box with two buttons under them - and a page has
 * room for the thing the dialog could not say at all: how much there is to save, counted
 * before anybody chooses where to put it.
 */
@Composable
internal fun BackupSaveSubScreen(
    onBack: () -> Unit,
    onSaving: () -> Unit,
    viewModel: BackupViewModel = hiltViewModel()
) {
    var includeVaults by remember { mutableStateOf(true) }
    var usePassword   by remember { mutableStateOf(true) }
    var password      by remember { mutableStateOf("") }
    var passwordShown by remember { mutableStateOf(false) }
    var showHiddenWarning by remember { mutableStateOf(false) }

    val preview by viewModel.preview.collectAsState()
    LaunchedEffect(includeVaults) { viewModel.refreshPreview(includeVaults) }

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
            password = ""
        }
    }

    val state by viewModel.state.collectAsState()

    /*
     * Writing takes about a second - deriving a key from a password is meant to be slow - and
     * the result is a full screen rather than a banner: a backup either exists or it does
     * not, and that is worth a page. It does not dismiss itself; Done is what leaves.
     */
    if (state.busy) {
        OperationScrim {
            OperationLoading(
                title    = stringResource(R.string.settings_backup_running),
                subtitle = stringResource(R.string.settings_backup_running_sub)
            )
        }
        return
    }
    when (val result = state.result) {
        is BackupViewModel.Result.Exported -> {
            OperationScrim {
            OperationSuccess(
                title  = stringResource(R.string.settings_backup_saved_title),
                body   = stringResource(
                    R.string.settings_backup_done_saved, result.settings, result.vaults
                ) + if (result.encrypted) "" else
                    "\n\n" + stringResource(R.string.settings_backup_no_password_warning),
                onDone = { viewModel.clearResult(); onSaving() }
            )
            }
            return
        }
        is BackupViewModel.Result.Failed -> {
            OperationScrim {
            OperationFailure(
                title  = stringResource(R.string.settings_backup_failed_title),
                body   = result.message,
                onDone = { viewModel.clearResult(); onSaving() }
            )
            }
            return
        }
        else -> Unit
    }

    SubScreenScaffold(title = stringResource(R.string.settings_backup_save), onBack = onBack) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
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

                Spacer(Modifier.height(16.dp))

                if (usePassword) {
                    OutlinedTextField(
                        value                = password,
                        onValueChange        = { password = it },
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
                        supportingText       = { Text(stringResource(R.string.settings_backup_password_hint)) },
                        modifier             = Modifier.fillMaxWidth()
                    )
                } else {
                    Text(
                        text     = stringResource(R.string.settings_backup_no_password_warning),
                        style    = MaterialTheme.typography.bodyMedium,
                        color    = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 8.dp)
                    )
                }

                Spacer(Modifier.height(24.dp))

                // What the file will hold, counted rather than described.
                Text(
                    text       = stringResource(R.string.settings_backup_contents),
                    style      = MaterialTheme.typography.labelLarge,
                    color      = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                    modifier   = Modifier.padding(start = 8.dp, bottom = 8.dp)
                )
                Text(
                    text     = preview?.let {
                        stringResource(R.string.settings_backup_contents_body, it.settings, it.vaults)
                    } ?: "",
                    style    = MaterialTheme.typography.bodyMedium,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(Modifier.height(24.dp))
            }

            Column(
                modifier            = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Button(
                    /*
                     * A file with no password is plain text, and a vault set to protect a
                     * hidden volume says so in it. That is worth stopping for once: the
                     * warning is shown before the picker rather than after the file exists.
                     */
                    onClick  = {
                        val unprotected = !usePassword || password.isBlank()
                        if (unprotected && (preview?.hiddenProtected ?: 0) > 0) showHiddenWarning = true
                        else saveLauncher.launch(suggestedName)
                    },
                    enabled  = !usePassword || password.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.settings_backup_choose_file))
                }
            }
        }
    }

    if (showHiddenWarning) {
        WarningOverlay(
            title        = stringResource(R.string.settings_backup_hidden_warn_title),
            body         = stringResource(R.string.settings_backup_hidden_warn_body),
            confirmLabel = stringResource(R.string.settings_backup_hidden_warn_confirm),
            onConfirm    = {
                showHiddenWarning = false
                saveLauncher.launch(suggestedName)
            },
            onDismiss    = { showHiddenWarning = false }
        )
    }
}
