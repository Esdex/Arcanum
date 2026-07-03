package zip.arcanum.arcanum.containers.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.HideSource
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import zip.arcanum.R
import zip.arcanum.core.utils.FileUtils
import java.text.DecimalFormat
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.detectDragGestures

private const val EXPAND_ENTROPY_REQUIRED_POINTS = 500

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpandVolumeScreen(
    onBack: () -> Unit,
    viewModel: ExpandVolumeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val keyfileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val (path, name) = FileUtils.copyUriToCache(context, uri) ?: return@rememberLauncherForActivityResult
        viewModel.addKeyfile(path, name)
    }
    val hiddenKeyfileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val (path, name) = FileUtils.copyUriToCache(context, uri) ?: return@rememberLauncherForActivityResult
        viewModel.addHiddenKeyfile(path, name)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.expand_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !state.isRunning) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            val container = state.container
            if (container == null) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(container.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.expand_current_file_size, container.size.formatSize()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.expand_new_size), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                SizeInput(
                    value = state.targetSizeInput,
                    unitGb = state.targetUnitGb,
                    enabled = !state.isRunning,
                    onValueChange = viewModel::updateTarget,
                    onUnitChange = viewModel::setTargetUnitGb
                )
                Text(
                    stringResource(R.string.expand_usable_size_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(
                stringResource(R.string.expand_strategy_safe_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.expand_credentials), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::updatePassword,
                    enabled = !state.isRunning,
                    label = { Text(stringResource(R.string.expand_password)) },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.pim,
                    onValueChange = viewModel::updatePim,
                    enabled = !state.isRunning,
                    label = { Text(stringResource(R.string.vault_mount_pim_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                KeyfileList(
                    names = state.keyfileDisplayNames,
                    enabled = !state.isRunning,
                    onAdd = { keyfileLauncher.launch("*/*") },
                    onRemove = viewModel::removeKeyfile
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.HideSource, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        stringResource(R.string.expand_include_hidden),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f).padding(start = 8.dp)
                    )
                    Switch(
                        checked = state.includeHidden,
                        enabled = !state.isRunning,
                        onCheckedChange = viewModel::setIncludeHidden
                    )
                }
                AnimatedVisibility(state.includeHidden) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SizeInput(
                            value = state.hiddenTargetSizeInput,
                            unitGb = state.hiddenTargetUnitGb,
                            enabled = !state.isRunning,
                            onValueChange = viewModel::updateHiddenTarget,
                            onUnitChange = viewModel::setHiddenTargetUnitGb
                        )
                        OutlinedTextField(
                            value = state.hiddenPassword,
                            onValueChange = viewModel::updateHiddenPassword,
                            enabled = !state.isRunning,
                            label = { Text(stringResource(R.string.expand_hidden_password)) },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = state.hiddenPim,
                            onValueChange = viewModel::updateHiddenPim,
                            enabled = !state.isRunning,
                            label = { Text(stringResource(R.string.vault_mount_pim_label)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        KeyfileList(
                            names = state.hiddenKeyfileDisplayNames,
                            enabled = !state.isRunning,
                            onAdd = { hiddenKeyfileLauncher.launch("*/*") },
                            onRemove = viewModel::removeHiddenKeyfile
                        )
                    }
                }
            }

            ExpandEntropySection(
                entropyPoints = state.entropyPoints,
                enabled = !state.isRunning && !state.isSuccess,
                onAddPoint = viewModel::addEntropyPoint
            )

            AnimatedVisibility(state.isRunning || state.progressMessage.isNotBlank()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LinearProgressIndicator(
                        progress = { state.progressFraction.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        state.progressMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            state.error?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
            if (state.isSuccess) {
                Text(
                    stringResource(R.string.expand_success),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Button(
                onClick = viewModel::start,
                enabled = !state.isRunning && !state.isSuccess && state.entropyPoints >= EXPAND_ENTROPY_REQUIRED_POINTS,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.OpenInFull, contentDescription = null)
                Text(stringResource(R.string.expand_start), modifier = Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

private data class ExpandEntropyParticle(val offset: Offset, val birthMs: Long)

@Composable
private fun ExpandEntropySection(
    entropyPoints: Int,
    enabled: Boolean,
    onAddPoint: (Int, Int) -> Unit
) {
    val particles = remember { mutableStateListOf<ExpandEntropyParticle>() }
    val uniquePoints = remember { mutableSetOf<Pair<Int, Int>>() }
    val progress = (entropyPoints / EXPAND_ENTROPY_REQUIRED_POINTS.toFloat()).coerceIn(0f, 1f)
    val done = progress >= 1f
    val accentColor = MaterialTheme.colorScheme.primary
    val view = LocalView.current

    LaunchedEffect(Unit) {
        while (true) {
            delay(50)
            val now = System.currentTimeMillis()
            particles.removeAll { now - it.birthMs > 1000L }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.create_entropy_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.create_entropy_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(20.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.2f)),
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(enabled, done) {
                        if (!enabled || done) return@pointerInput
                        detectDragGestures { change, _ ->
                            val pos = change.position
                            val gridX = (pos.x / 10).roundToInt()
                            val gridY = (pos.y / 10).roundToInt()
                            if (uniquePoints.add(gridX to gridY)) {
                                onAddPoint(gridX, gridY)
                                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            particles.add(ExpandEntropyParticle(pos, System.currentTimeMillis()))
                        }
                    }
            ) {
                val now = System.currentTimeMillis()
                particles.forEach { p ->
                    val age = (now - p.birthMs) / 1000f
                    val alpha = (1f - age).coerceIn(0f, 1f)
                    val radius = 6f * (1f - age * 0.5f)
                    drawCircle(color = accentColor.copy(alpha = alpha), radius = radius, center = p.offset)
                }
            }
        }
        LinearProgressIndicator(
            progress = { progress },
            color = if (done) Color(0xFF16A34A) else accentColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(99.dp))
        )
        Text(
            text = if (done) stringResource(R.string.create_entropy_done)
                   else stringResource(R.string.create_entropy_progress, (progress * 100).toInt()),
            style = MaterialTheme.typography.bodySmall,
            color = if (done) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SizeInput(
    value: String,
    unitGb: Boolean,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onUnitChange: (Boolean) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            label = { Text(stringResource(R.string.create_size_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        StrategyButton(
            selected = !unitGb,
            enabled = enabled,
            text = "MB",
            onClick = { onUnitChange(false) }
        )
        StrategyButton(
            selected = unitGb,
            enabled = enabled,
            text = "GB",
            onClick = { onUnitChange(true) }
        )
    }
}

@Composable
private fun StrategyButton(
    selected: Boolean,
    enabled: Boolean,
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    if (selected) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier) { Text(text) }
    }
}

@Composable
private fun KeyfileList(
    names: List<String>,
    enabled: Boolean,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        names.forEachIndexed { index, name ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.VpnKey, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f).padding(start = 8.dp))
                IconButton(onClick = { onRemove(index) }, enabled = enabled) {
                    Icon(Icons.Outlined.Close, contentDescription = null)
                }
            }
        }
        TextButton(onClick = onAdd, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Outlined.Lock, contentDescription = null)
            Text(stringResource(R.string.vault_mount_add_keyfile), modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private fun Long.formatSize(): String {
    val gb = this / (1024.0 * 1024.0 * 1024.0)
    val mb = this / (1024.0 * 1024.0)
    val fmt = DecimalFormat("#.#")
    return when {
        gb >= 1.0 -> "${fmt.format(gb)} GB"
        mb >= 1.0 -> "${fmt.format(mb)} MB"
        else -> "${fmt.format(this / 1024.0)} KB"
    }
}
