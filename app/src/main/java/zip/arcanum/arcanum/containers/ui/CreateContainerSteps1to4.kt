package zip.arcanum.arcanum.containers.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import zip.arcanum.R
import zip.arcanum.core.icons.ArcanumIcons

// ─── Step 1: Volume Type ─────────────────────────────────────────────────────

@Composable
fun StepVolumeType(state: CreateContainerState, onUpdate: (CreateContainerState.() -> CreateContainerState) -> Unit) {
    StepContent(title = stringResource(R.string.create_step1_title)) {
        SelectionCard(
            selected    = state.volumeType == VolumeType.STANDARD,
            icon        = ArcanumIcons.Encrypted,
            title       = stringResource(R.string.create_volume_standard),
            description = stringResource(R.string.create_volume_standard_desc),
            onClick     = { onUpdate { copy(volumeType = VolumeType.STANDARD, totalSteps = 10) } }
        )
        Spacer(Modifier.height(12.dp))
        SelectionCard(
            selected    = state.volumeType == VolumeType.HIDDEN,
            icon        = Icons.Outlined.VisibilityOff,
            title       = stringResource(R.string.create_volume_hidden),
            description = stringResource(R.string.create_volume_hidden_desc),
            onClick     = { onUpdate { copy(volumeType = VolumeType.HIDDEN, totalSteps = 16) } }
        )
    }
}

// ─── Step 2: Volume Location ─────────────────────────────────────────────────

@Composable
fun StepVolumeLocation(
    state: CreateContainerState,
    appStoragePath: String,
    appStoragePathWithBackup: String,
    onUpdate: (CreateContainerState.() -> CreateContainerState) -> Unit,
    onBrowse: () -> Unit,
    onClearSaf: () -> Unit = {}
) {
    StepContent(title = stringResource(R.string.create_step2_title)) {

        // ── Option 1: Internal Storage (via SAF) ──────────────────────
        SelectionCard(
            selected    = state.location == StorageLocation.INTERNAL_STORAGE,
            icon        = Icons.Outlined.Storage,
            title       = stringResource(R.string.create_location_internal),
            description = stringResource(R.string.create_location_internal_desc),
            onClick     = {
                onUpdate { copy(location = StorageLocation.INTERNAL_STORAGE, filePath = "") }
            }
        )
        AnimatedVisibility(visible = state.location == StorageLocation.INTERNAL_STORAGE) {
            Column {
                Row(
                    modifier          = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector        = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier           = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text     = state.fileName.ifBlank { stringResource(R.string.create_location_no_folder) },
                        style    = MaterialTheme.typography.bodySmall,
                        color    = if (state.safUri.isBlank())
                                       MaterialTheme.colorScheme.error
                                   else
                                       MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onBrowse) {
                        Text(
                            if (state.safUri.isBlank()) stringResource(R.string.create_location_browse)
                            else stringResource(R.string.create_location_change)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ── Option 2: App Storage ──────────────────────────────────────
        SelectionCard(
            selected    = state.location == StorageLocation.APP_STORAGE,
            icon        = Icons.Outlined.PhoneAndroid,
            title       = stringResource(R.string.create_location_app),
            description = stringResource(R.string.create_location_app_desc),
            onClick     = {
                onClearSaf()
                val path = if (state.includeInBackup) appStoragePathWithBackup else appStoragePath
                onUpdate { copy(location = StorageLocation.APP_STORAGE, filePath = path) }
            }
        )
        AnimatedVisibility(visible = state.location == StorageLocation.APP_STORAGE) {
            Column {
                Row(
                    modifier          = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector        = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier           = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text  = if (state.includeInBackup) appStoragePathWithBackup else appStoragePath,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value         = state.fileName,
                    onValueChange = { onUpdate { copy(fileName = it) } },
                    label         = { Text(stringResource(R.string.create_filename_label)) },
                    placeholder   = { Text(stringResource(R.string.create_filename_placeholder)) },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier          = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector        = Icons.Outlined.Backup,
                        contentDescription = null,
                        tint               = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier           = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text  = stringResource(R.string.create_location_backup_title),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text  = stringResource(R.string.create_location_backup_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked         = state.includeInBackup,
                        onCheckedChange = { enabled ->
                            val path = if (enabled) appStoragePathWithBackup else appStoragePath
                            onUpdate { copy(includeInBackup = enabled, filePath = path) }
                        }
                    )
                }
            }
        }

    }
}

// ─── Step 3: Encryption Algorithm ────────────────────────────────────────────

@Composable
fun StepEncryptionAlgorithm(state: CreateContainerState, onUpdate: (CreateContainerState.() -> CreateContainerState) -> Unit) {
    StepContent(title = stringResource(R.string.create_step3_title), subtitle = stringResource(R.string.create_step3_subtitle)) {
        Text(stringResource(R.string.create_step3_cipher), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        CipherAlgorithm.entries.forEach { algo ->
            AlgorithmRow(
                name        = algo.displayName,
                description = algo.description,
                speed       = algo.speed,
                selected    = state.algorithm == algo,
                onClick     = { onUpdate { copy(algorithm = algo) } }
            )
            Spacer(Modifier.height(6.dp))
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.create_step3_hash), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Row(
            modifier              = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HashAlgorithm.entries.forEach { hash ->
                FilterChip(
                    selected = state.hashAlgorithm == hash,
                    onClick  = { onUpdate { copy(hashAlgorithm = hash) } },
                    label    = { Text(hash.displayName) }
                )
            }
        }
    }
}

@Composable
internal fun AlgorithmRow(name: String, description: String, speed: AlgorithmSpeed, selected: Boolean, onClick: () -> Unit) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    val speedColor = when (speed) {
        AlgorithmSpeed.FAST           -> MaterialTheme.colorScheme.tertiary
        AlgorithmSpeed.MEDIUM         -> MaterialTheme.colorScheme.secondary
        AlgorithmSpeed.SLOW           -> MaterialTheme.colorScheme.error
        AlgorithmSpeed.EXTREMELY_SLOW -> MaterialTheme.colorScheme.error
        AlgorithmSpeed.PARANOIA       -> MaterialTheme.colorScheme.error
    }
    val speedLabel = when (speed) {
        AlgorithmSpeed.FAST           -> stringResource(R.string.create_size_speed_fast)
        AlgorithmSpeed.MEDIUM         -> stringResource(R.string.create_size_speed_medium)
        AlgorithmSpeed.SLOW           -> stringResource(R.string.create_size_speed_slow)
        AlgorithmSpeed.EXTREMELY_SLOW -> stringResource(R.string.create_size_speed_extremely_slow)
        AlgorithmSpeed.PARANOIA       -> stringResource(R.string.create_size_speed_paranoia)
    }
    Card(
        onClick  = onClick,
        border   = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        colors   = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                             else MaterialTheme.colorScheme.surface
        ),
        shape    = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier          = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(speedLabel, style = MaterialTheme.typography.labelSmall, color = speedColor, fontWeight = FontWeight.Medium)
        }
    }
}

// ─── Step 4: Volume Size ─────────────────────────────────────────────────────

private val presets = listOf(256L, 512L, 1024L, 2048L, 5120L, 10240L)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StepVolumeSize(
    state: CreateContainerState,
    onUpdate: (CreateContainerState.() -> CreateContainerState) -> Unit,
    availableSpaceMb: Long = Long.MAX_VALUE
) {
    var customInput by remember { mutableStateOf("") }
    var unitGb by remember { mutableStateOf(false) }
    var isCustom by remember { mutableStateOf(presets.none { it == state.sizeMb }) }

    val hasKnownSpace = availableSpaceMb != Long.MAX_VALUE
    val availableGb   = if (hasKnownSpace) availableSpaceMb / 1024f else 0f
    val notEnoughSpace = hasKnownSpace && state.sizeMb > 0L && state.sizeMb > availableSpaceMb

    val quickSecs = (state.sizeMb / 500.0).toLong().coerceAtLeast(1)
    val secureSecs = (state.sizeMb / 80.0).toLong().coerceAtLeast(1)

    StepContent(title = stringResource(R.string.create_step4_title)) {
        Row(
            modifier              = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            presets.forEach { mb ->
                val label = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"
                FilterChip(
                    selected = !isCustom && state.sizeMb == mb,
                    onClick  = { isCustom = false; onUpdate { copy(sizeMb = mb) }; customInput = "" },
                    label    = { Text(label) }
                )
            }
            FilterChip(
                selected = isCustom,
                onClick  = { isCustom = true; onUpdate { copy(sizeMb = 0L) } },
                label    = { Text(stringResource(R.string.create_size_custom)) }
            )
        }

        if (isCustom) {
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value         = customInput,
                    onValueChange = { v ->
                        customInput = v.filter { it.isDigit() }
                        val mb = customInput.toLongOrNull() ?: 0L
                        onUpdate { copy(sizeMb = if (unitGb) mb * 1024 else mb) }
                    },
                    label          = { Text(stringResource(R.string.create_size_label)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine     = true,
                    modifier       = Modifier.weight(1f)
                )
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(
                        selected = !unitGb,
                        onClick  = { unitGb = false; val mb = customInput.toLongOrNull() ?: 0L; onUpdate { copy(sizeMb = mb) } },
                        shape    = SegmentedButtonDefaults.itemShape(0, 2)
                    ) { Text("MB") }
                    SegmentedButton(
                        selected = unitGb,
                        onClick  = { unitGb = true; val mb = customInput.toLongOrNull() ?: 0L; onUpdate { copy(sizeMb = mb * 1024) } },
                        shape    = SegmentedButtonDefaults.itemShape(1, 2)
                    ) { Text("GB") }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        if (hasKnownSpace) {
            Text(
                stringResource(R.string.create_size_available, availableGb),
                style = MaterialTheme.typography.bodySmall,
                color = if (notEnoughSpace) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (notEnoughSpace) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.create_size_not_enough_space),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        if (state.isExternalSd && state.sizeMb >= 4096L) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.create_size_fat32_limit),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        } else if (state.isExternalSd) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.create_size_sd_slow),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!notEnoughSpace && state.sizeMb > 0L) {
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.create_size_quick_est, formatSecs(quickSecs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                stringResource(R.string.create_size_secure_est, formatSecs(secureSecs)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

internal fun formatSecs(secs: Long) = if (secs < 60) "$secs seconds" else "${secs / 60} min ${secs % 60} sec"

// ─── Shared helpers ───────────────────────────────────────────────────────────

@Composable
fun StepContent(
    title: String,
    subtitle: String? = null,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.imePadding().verticalScroll(rememberScrollState()).padding(top = 28.dp, bottom = 24.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(20.dp))
        content()
    }
}

@Composable
fun SelectionCard(
    selected: Boolean,
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
    locked: Boolean = false
) {
    val borderColor = when {
        selected -> MaterialTheme.colorScheme.primary
        locked   -> MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
        else     -> MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
    }
    Card(
        onClick  = onClick,
        border   = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        colors   = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                             else MaterialTheme.colorScheme.surface
        ),
        shape    = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier          = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                tint               = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier           = Modifier.size(28.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (locked) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.Lock, contentDescription = stringResource(R.string.common_pro), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.common_pro), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
