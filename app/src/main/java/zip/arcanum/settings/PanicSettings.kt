package zip.arcanum.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import zip.arcanum.R
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.components.SettingsRow
import zip.arcanum.core.components.SettingsSwitch
import zip.arcanum.core.database.entities.ContainerEntity
import zip.arcanum.core.security.VaultPanicAction

// Settings / Panic mode: what the duress PIN does, and to which vaults.

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PanicModeSubScreen(
    onBack: () -> Unit,
    onSetPanicPin: () -> Unit,
    viewModel: PanicModeViewModel
) {
    val settings   by viewModel.settings.collectAsState()
    val containers by viewModel.containers.collectAsState()

    var showDisableDialog by remember { mutableStateOf(false) }

    if (showDisableDialog) {
        AppDialog(
            onDismissRequest = { showDisableDialog = false },
            title   = { Text(stringResource(R.string.settings_panic_disable_title)) },
            text    = { Text(stringResource(R.string.settings_panic_disable_body)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.disablePanicMode()
                    showDisableDialog = false
                }) {
                    Text(stringResource(R.string.settings_panic_disable_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableDialog = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    SubScreenScaffold(title = stringResource(R.string.settings_panic_title), onBack = onBack) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp)
        ) {
            SubScreenGroup {
                SettingsSwitch(
                    title           = stringResource(R.string.settings_panic_switch_title),
                    subtitle        = if (settings.enabled) stringResource(R.string.settings_panic_switch_desc)
                                      else stringResource(R.string.settings_panic_setup_hint),
                    checked         = settings.enabled,
                    onCheckedChange = { enabling ->
                        if (enabling) onSetPanicPin()
                        else showDisableDialog = true
                    }
                )
            }

            // Everything below only visible when enabled
            AnimatedVisibility(visible = settings.enabled) {
                Column {
                    PanicSectionLabel(stringResource(R.string.settings_panic_pin_section))
                    SubScreenGroup {
                        SettingsRow(title = stringResource(R.string.settings_panic_change_pin), onClick = onSetPanicPin)
                    }

                    PanicSectionLabel(stringResource(R.string.settings_panic_wipe_section))
                    SubScreenGroup {
                        SettingsSwitch(
                            title           = stringResource(R.string.settings_panic_full_wipe),
                            subtitle        = stringResource(R.string.settings_panic_full_wipe_desc),
                            checked         = settings.fullWipe,
                            onCheckedChange = { viewModel.setFullWipe(it) }
                        )
                    }

                    SubScreenGroup {
                        SettingsSwitch(
                            title           = stringResource(R.string.settings_panic_clear_settings),
                            checked         = settings.clearSettings,
                            onCheckedChange = { viewModel.setClearSettings(it) },
                            enabled         = !settings.fullWipe
                        )
                        SettingsSwitch(
                            title           = stringResource(R.string.settings_panic_clear_history),
                            checked         = settings.clearCalculatorHistory,
                            onCheckedChange = { viewModel.setClearHistory(it) },
                            enabled         = !settings.fullWipe
                        )
                        SettingsSwitch(
                            title           = stringResource(R.string.settings_panic_disable_biometric),
                            subtitle        = stringResource(R.string.settings_panic_disable_biometric_desc),
                            checked         = settings.disableBiometric,
                            onCheckedChange = { viewModel.setDisableBiometric(it) },
                            enabled         = !settings.fullWipe
                        )
                    }

                    if (containers.isNotEmpty() && !settings.fullWipe) {
                        PanicSectionLabel(stringResource(R.string.settings_panic_vaults_section))
                        SubScreenGroup {
                            containers.forEach { container ->
                                VaultPanicRow(
                                    container = container,
                                    action    = settings.vaultActions[container.id] ?: VaultPanicAction.KEEP,
                                    onChange  = { viewModel.setVaultAction(container.id, it) }
                                )
                            }
                        }
                    }

                    Text(
                        text     = stringResource(R.string.settings_panic_warning),
                        style    = MaterialTheme.typography.bodySmall,
                        color    = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultPanicRow(
    container: ContainerEntity,
    action: VaultPanicAction,
    onChange: (VaultPanicAction) -> Unit
) {
    val options = listOf(VaultPanicAction.DELETE, VaultPanicAction.FORGET, VaultPanicAction.KEEP)
    val labels  = listOf(
        stringResource(R.string.settings_panic_vault_delete),
        stringResource(R.string.settings_panic_vault_forget),
        stringResource(R.string.settings_panic_vault_keep)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            text  = container.name,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, opt ->
                SegmentedButton(
                    selected = action == opt,
                    onClick  = { onChange(opt) },
                    shape    = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label    = { Text(labels[index], style = MaterialTheme.typography.labelMedium) }
                )
            }
        }
    }
}
