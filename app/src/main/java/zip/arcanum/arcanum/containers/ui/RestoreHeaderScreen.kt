package zip.arcanum.arcanum.containers.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import zip.arcanum.core.utils.DotVisualTransformation
import zip.arcanum.R
import zip.arcanum.core.icons.ArcanumIcons
import zip.arcanum.core.utils.FileUtils

@Composable
fun RestoreHeaderScreen(
    containerId: String,
    onBack: () -> Unit
) {
    val viewModel: RestoreHeaderViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(containerId) { viewModel.init(containerId) }

    val keyfileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val (bytes, name) = FileUtils.readKeyfileBytes(context, it) ?: return@rememberLauncherForActivityResult
            viewModel.addKeyfile(bytes, name)
        }
    }

    val backupFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val name = context.contentResolver.query(it,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "backup"
            viewModel.setBackupFile(it.toString(), name)
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            // ── Top bar ──────────────────────────────────────────────────────
            Row(
                modifier          = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, enabled = !state.isRunning) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    text       = stringResource(R.string.restore_header_title),
                    style      = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier   = Modifier.weight(1f)
                )
            }

            AnimatedContent(
                targetState = when {
                    state.isRunning  -> "loading"
                    state.isSuccess  -> "success"
                    else             -> "form"
                },
                label = "restore_header_content",
                modifier = Modifier.weight(1f)
            ) { screen ->
                when (screen) {
                    "loading" -> RestoreLoadingContent()
                    "success" -> RestoreSuccessContent(onBack)
                    else      -> RestoreFormContent(
                        state           = state,
                        onUpdate        = viewModel::update,
                        onAddKeyfile    = { keyfileLauncher.launch("*/*") },
                        onRemoveKeyfile = viewModel::removeKeyfile,
                        onChooseBackup  = { backupFileLauncher.launch(arrayOf("*/*")) }
                    )
                }
            }

            // ── Error message ─────────────────────────────────────────────────
            if (state.error != null && !state.isRunning) {
                val errorMsg = when (state.error) {
                    "WRONG_PASSWORD" -> stringResource(R.string.restore_header_error_wrong_password)
                    else             -> stringResource(R.string.restore_header_error_generic, state.error ?: "")
                }
                Text(
                    text     = errorMsg,
                    color    = MaterialTheme.colorScheme.error,
                    style    = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            // ── Action button ─────────────────────────────────────────────────
            if (!state.isRunning && !state.isSuccess) {
                val canRestore = state.password.isNotEmpty() &&
                    (!state.fromExternal || state.backupUri.isNotEmpty())
                Button(
                    onClick  = viewModel::startRestore,
                    enabled  = canRestore,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Text(stringResource(R.string.restore_header_btn))
                }
            }
        }
    }
}

@Composable
private fun RestoreFormContent(
    state: RestoreHeaderState,
    onUpdate: (RestoreHeaderState.() -> RestoreHeaderState) -> Unit,
    onAddKeyfile: () -> Unit,
    onRemoveKeyfile: (Int) -> Unit,
    onChooseBackup: () -> Unit
) {
    val context = LocalContext.current

    var showPim by remember { mutableStateOf(false) }
    var pimText by remember { mutableStateOf(if (state.pim > 0) state.pim.toString() else "") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(Modifier.height(8.dp))

        // ── Warning card ──────────────────────────────────────────────────
        Card(
            colors   = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
            ),
            shape    = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Warning, contentDescription = null,
                    tint     = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp).padding(top = 2.dp))
                Text(stringResource(R.string.restore_header_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Source selection ──────────────────────────────────────────────
        Text(
            text  = stringResource(R.string.restore_header_source_section),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(4.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            RadioButton(
                selected = !state.fromExternal,
                onClick  = { onUpdate { copy(fromExternal = false, backupUri = "", backupFileName = "") } }
            )
            Text(stringResource(R.string.restore_header_embedded),
                style    = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            RadioButton(
                selected = state.fromExternal,
                onClick  = { onUpdate { copy(fromExternal = true) } }
            )
            Text(stringResource(R.string.restore_header_external),
                style    = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f))
        }

        // ── External file picker ──────────────────────────────────────────
        AnimatedVisibility(
            visible = state.fromExternal,
            enter   = fadeIn(tween(150)) + expandVertically(tween(200)),
            exit    = fadeOut(tween(100)) + shrinkVertically(tween(150))
        ) {
            Column {
                Spacer(Modifier.height(8.dp))
                if (state.backupFileName.isNotEmpty()) {
                    Card(
                        colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape    = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier          = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(ArcanumIcons.Keyfile, contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint     = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            Text(state.backupFileName,
                                style    = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
                OutlinedButton(onClick = onChooseBackup, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.restore_header_choose_file))
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        // ── Password ──────────────────────────────────────────────────────
        OutlinedTextField(
            value         = state.password,
            onValueChange = { onUpdate { copy(password = it) } },
            label         = { Text(stringResource(R.string.common_password)) },
            singleLine    = true,
            visualTransformation = if (state.showPassword) VisualTransformation.None
                                   else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon  = {
                IconButton(onClick = { onUpdate { copy(showPassword = !showPassword) } }) {
                    Icon(if (state.showPassword) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = null)
                }
            },
            modifier = Modifier
                .fillMaxWidth()
        )

        Spacer(Modifier.height(12.dp))

        // ── PIM ───────────────────────────────────────────────────────────
        OutlinedTextField(
            value         = pimText,
            onValueChange = {
                if (it.all { c -> c.isDigit() } && it.length <= 7) {
                    val v = it.toLongOrNull() ?: 0L
                    if (it.isEmpty() || v in 1L..2_147_468L) {
                        pimText = it
                        onUpdate { copy(pim = v.toInt()) }
                    }
                }
            },
            label                = { Text(stringResource(R.string.vault_mount_pim_label)) },
            placeholder          = { Text(stringResource(R.string.vault_mount_pim_placeholder)) },
            visualTransformation = if (showPim) VisualTransformation.None else DotVisualTransformation(),
            trailingIcon         = {
                IconButton(onClick = { showPim = !showPim }) {
                    Icon(if (showPim) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = null)
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine      = true,
            modifier        = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(8.dp))

        // ── Keyfiles ──────────────────────────────────────────────────────
        KeyfileSectionCompact(
            displayNames = state.keyfileDisplayNames,
            onAdd        = onAddKeyfile,
            onRemove     = onRemoveKeyfile
        )

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RestoreLoadingContent() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            Text(stringResource(R.string.restore_header_running),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun RestoreSuccessContent(onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier            = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.restore_header_success_title),
                style      = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.restore_header_success_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.common_done))
            }
        }
    }
}
