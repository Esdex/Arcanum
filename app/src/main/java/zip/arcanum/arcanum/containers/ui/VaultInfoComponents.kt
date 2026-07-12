package zip.arcanum.arcanum.containers.ui

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import zip.arcanum.R
import zip.arcanum.arcanum.containers.domain.Container
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// Shared building blocks for vault General/Encryption/Storage info, used by both
// the Storage tab and the "Vault details" bottom sheet.

/** General info as a labelled card. */
@Composable
internal fun ColumnScope.VaultGeneralSection(container: Container, locationDisplay: String) {
    InfoSection(stringResource(R.string.vault_info_section_general)) {
        InfoRow(stringResource(R.string.vault_info_label_name), container.name)
        InfoDivider()
        InfoRow(stringResource(R.string.vault_info_label_location), locationDisplay)
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
}

/** Dates info as a labelled card. */
@Composable
internal fun ColumnScope.VaultDatesSection(container: Container) {
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

/** Encryption section as a labelled card. */
@Composable
internal fun ColumnScope.VaultEncryptionSection(container: Container) {
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
}

@Composable
internal fun InfoSection(title: String, content: @Composable ColumnScope.() -> Unit) {
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
internal fun InfoDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 16.dp),
        color    = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    )
}

@Composable
internal fun InfoRow(label: String, value: String) {
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
            textAlign  = TextAlign.End,
            modifier   = Modifier.weight(1f).padding(start = 16.dp)
        )
    }
}

/** Human-friendly location for a container's on-disk path or SAF URI. */
internal fun vaultLocationDisplay(context: Context, container: Container): String = when {
    container.path.isNotBlank() -> {
        val path = container.path
        val appDataDir = context.filesDir.parentFile?.absolutePath ?: ""
        when {
            path.startsWith(context.filesDir.absolutePath) ||
            path.startsWith(context.noBackupFilesDir.absolutePath) -> {
                val relative = if (appDataDir.isNotEmpty())
                    path.removePrefix(appDataDir).trimStart('/')
                else path
                "App Storage/$relative"
            }
            path.startsWith("/storage/emulated/0/") ->
                "Internal/" + path.removePrefix("/storage/emulated/0/")
            path.startsWith("/sdcard/") ->
                "Internal/" + path.removePrefix("/sdcard/")
            else -> path
        }
    }
    container.safUri.isNotBlank() -> {
        val seg = Uri.decode(Uri.parse(container.safUri).lastPathSegment ?: "")
        val after = seg.substringAfter(':')
        if (after.isNotEmpty()) "Internal/$after" else seg
    }
    else -> "—"
}

// "AES-256-XTS" → "AES", "AES-Twofish-Serpent-256-XTS" → "AES-Twofish-Serpent"
internal fun String.cipherName(): String = replace(Regex("-\\d+.*$"), "").ifEmpty { this }

internal fun Long.formatStorageSize(): String {
    val gb  = this / (1024.0 * 1024.0 * 1024.0)
    val mb  = this / (1024.0 * 1024.0)
    val fmt = DecimalFormat("#.#")
    return when {
        gb >= 1.0 -> "${fmt.format(gb)} GB"
        mb >= 1.0 -> "${fmt.format(mb)} MB"
        else      -> "${fmt.format(this / 1024.0)} KB"
    }
}

internal fun Long.formatDate(): String {
    if (this == 0L) return "—"
    return Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG))
}
