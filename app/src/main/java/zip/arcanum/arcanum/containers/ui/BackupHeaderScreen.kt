package zip.arcanum.arcanum.containers.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import kotlinx.coroutines.launch
import zip.arcanum.core.utils.DotVisualTransformation
import zip.arcanum.R
import zip.arcanum.core.icons.ArcanumIcons
import zip.arcanum.core.utils.FileUtils
import zip.arcanum.core.components.OperationSuccess
import zip.arcanum.core.components.OperationLoading

@Composable
fun BackupHeaderScreen(
    containerId: String,
    onBack: () -> Unit
) {
    val viewModel: BackupHeaderViewModel = hiltViewModel()
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

    val outputLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        uri?.let {
            val name = context.contentResolver.query(it,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null } ?: "header_backup.hbk"
            viewModel.setOutputFile(it.toString(), name)
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
                    text       = stringResource(R.string.backup_header_title),
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
                label = "backup_header_content",
                modifier = Modifier.weight(1f)
            ) { screen ->
                when (screen) {
                    "loading" -> BackupLoadingContent()
                    "success" -> BackupSuccessContent(onBack)
                    else      -> BackupFormContent(
                        state           = state,
                        onUpdate        = viewModel::update,
                        onAddKeyfile    = { keyfileLauncher.launch("*/*") },
                        onRemoveKeyfile = viewModel::removeKeyfile,
                        onChooseOutput  = { outputLauncher.launch("header_backup.hbk") }
                    )
                }
            }

            // ── Error message ─────────────────────────────────────────────────
            if (state.error != null && !state.isRunning) {
                val errorMsg = when (state.error) {
                    "WRONG_PASSWORD" -> stringResource(R.string.backup_header_error_wrong_password)
                    else             -> stringResource(R.string.backup_header_error_generic, state.error ?: "")
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
                Button(
                    onClick  = viewModel::startBackup,
                    enabled  = state.password.isNotEmpty() && state.outputUri.isNotEmpty(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 16.dp)
                ) {
                    Text(stringResource(R.string.backup_header_btn))
                }
            }
        }
    }
}

@Composable
private fun BackupFormContent(
    state: BackupHeaderState,
    onUpdate: (BackupHeaderState.() -> BackupHeaderState) -> Unit,
    onAddKeyfile: () -> Unit,
    onRemoveKeyfile: (Int) -> Unit,
    onChooseOutput: () -> Unit
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

        // ── Info card ─────────────────────────────────────────────────────
        Card(
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape    = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(Icons.Outlined.Info, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp).padding(top = 2.dp))
                Text(stringResource(R.string.backup_header_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Spacer(Modifier.height(24.dp))

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

        Spacer(Modifier.height(24.dp))

        // ── Output file ───────────────────────────────────────────────────
        Text(
            text     = stringResource(R.string.backup_header_output_section),
            style    = MaterialTheme.typography.labelLarge,
            color    = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        if (state.outputFileName.isNotEmpty()) {
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
                    Text(
                        text     = state.outputFileName,
                        style    = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        OutlinedButton(
            onClick  = onChooseOutput,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.backup_header_choose_output))
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun BackupLoadingContent() = OperationLoading(stringResource(R.string.backup_header_running))

@Composable
private fun BackupSuccessContent(onBack: () -> Unit) = OperationSuccess(
    title  = stringResource(R.string.backup_header_success_title),
    body   = stringResource(R.string.backup_header_success_body),
    onDone = onBack
)

// ── Shared keyfile section ─────────────────────────────────────────────────────

@Composable
internal fun KeyfileSectionCompact(
    displayNames : List<String>,
    onAdd        : () -> Unit,
    onRemove     : (Int) -> Unit
) {
    if (displayNames.isNotEmpty()) {
        Card(
            colors   = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape    = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                displayNames.forEachIndexed { index, name ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Icon(ArcanumIcons.Keyfile, contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint     = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        IconButton(onClick = { onRemove(index) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Outlined.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
    TextButton(
        onClick  = onAdd,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(ArcanumIcons.Keyfile, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(stringResource(R.string.vault_mount_add_keyfile))
    }
}
