package zip.arcanum.arcanum.containers.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import zip.arcanum.R
import zip.arcanum.arcanum.containers.domain.Container
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun VaultInfoScreen(
    container: Container?,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (container == null) {
            Text(stringResource(R.string.vault_info_loading), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        // ── General ──────────────────────────────────────────────────────────
        InfoSection(stringResource(R.string.vault_info_section_general)) {
            InfoRow(stringResource(R.string.vault_info_label_name), container.name)
            InfoDivider()
            InfoRow(
                label = stringResource(R.string.vault_info_label_volume_type),
                value = when {
                    container.isHiddenVolume  -> stringResource(R.string.vault_info_type_hidden)
                    container.hasHiddenVolume -> stringResource(R.string.vault_info_type_outer)
                    else                      -> stringResource(R.string.vault_info_type_standard)
                }
            )
            InfoDivider()
            InfoRow(
                label = stringResource(R.string.vault_info_label_read_only),
                value = if (container.isReadOnly) stringResource(R.string.vault_info_yes)
                        else stringResource(R.string.vault_info_no)
            )
            InfoDivider()
            InfoRow(
                label = stringResource(R.string.vault_info_label_format_version),
                value = container.formatVersion.toString()
            )
            InfoDivider()
            InfoRow(
                label = stringResource(R.string.vault_info_label_backup_header),
                value = if (container.hasBackupHeader) stringResource(R.string.vault_info_yes)
                        else stringResource(R.string.vault_info_no)
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Encryption ───────────────────────────────────────────────────────
        InfoSection(stringResource(R.string.vault_info_section_encryption)) {
            InfoRow(stringResource(R.string.vault_info_label_algorithm), container.algorithm.cipherName())
            InfoDivider()
            InfoRow(stringResource(R.string.vault_info_label_mode), container.encryptionMode)
            InfoDivider()
            InfoRow(
                label = stringResource(R.string.vault_info_label_key_size),
                value = if (container.keySize > 0) "${container.keySize} bits" else "—"
            )
            if (container.encryptionMode == "XTS" && container.keySize > 0) {
                InfoDivider()
                InfoRow(
                    label = stringResource(R.string.vault_info_label_key_size_secondary),
                    value = "${container.keySize} bits"
                )
            }
            InfoDivider()
            InfoRow(
                label = stringResource(R.string.vault_info_label_block_size),
                value = "${container.blockSize} bits"
            )
            InfoDivider()
            InfoRow(stringResource(R.string.vault_info_label_prf), container.prf)
            InfoDivider()
            InfoRow(
                label = stringResource(R.string.vault_info_label_iterations),
                value = if (container.pkcs5Iterations > 0)
                            "%,d".format(container.pkcs5Iterations)
                        else "—"
            )
            InfoDivider()
            InfoRow(
                label = stringResource(R.string.vault_info_label_pim),
                value = if (container.pim > 0) stringResource(R.string.vault_info_pim_custom)
                        else stringResource(R.string.vault_info_pim_default)
            )
        }

        Spacer(Modifier.height(12.dp))

        // ── Storage ──────────────────────────────────────────────────────────
        InfoSection(stringResource(R.string.vault_info_section_storage)) {
            InfoRow(stringResource(R.string.vault_info_label_filesystem), container.filesystem)
            InfoDivider()
            InfoRow(stringResource(R.string.vault_info_label_size), container.size.formatSize())
            InfoDivider()
            InfoRow(stringResource(R.string.vault_info_label_location), container.locationDisplay())
        }

        Spacer(Modifier.height(12.dp))

        // ── Dates ────────────────────────────────────────────────────────────
        InfoSection(stringResource(R.string.vault_info_section_dates)) {
            InfoRow(stringResource(R.string.vault_info_label_created), container.createdAt.formatDate())
            if (container.headerModifiedAt > 0L) {
                InfoDivider()
                InfoRow(
                    label = stringResource(R.string.vault_info_label_header_modified),
                    value = container.headerModifiedAt.formatDate()
                )
            }
        }
    }
}

@Composable
private fun InfoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Text(
        text     = title.uppercase(),
        style    = MaterialTheme.typography.labelSmall,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
    )
    Surface(
        shape    = RoundedCornerShape(16.dp),
        color    = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(content = content)
    }
}

@Composable
private fun InfoDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color    = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodyMedium,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text       = value,
            style      = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier   = Modifier.padding(start = 16.dp)
        )
    }
}

// "AES-256-XTS" → "AES", "AES-Twofish-Serpent-256-XTS" → "AES-Twofish-Serpent"
private fun String.cipherName(): String = replace(Regex("-\\d+.*$"), "").ifEmpty { this }

private fun Long.formatSize(): String {
    val gb  = this / (1024.0 * 1024.0 * 1024.0)
    val mb  = this / (1024.0 * 1024.0)
    val fmt = DecimalFormat("#.#")
    return when {
        gb >= 1.0 -> "${fmt.format(gb)} GB"
        mb >= 1.0 -> "${fmt.format(mb)} MB"
        else      -> "${fmt.format(this / 1024.0)} KB"
    }
}

private fun Long.formatDate(): String {
    if (this == 0L) return "—"
    return Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
}

private fun Container.locationDisplay(): String {
    if (path.isNotEmpty()) return path
    if (safUri.isNotEmpty()) {
        val segment = Uri.parse(safUri).lastPathSegment ?: return "External Storage"
        return segment.removePrefix("primary:").removePrefix("document/primary:")
    }
    return "—"
}
