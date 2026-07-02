package zip.arcanum.arcanum.containers.ui

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        if (container == null) {
            Text(stringResource(R.string.vault_info_loading), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }

        Surface(
            shape    = RoundedCornerShape(16.dp),
            color    = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                InfoRow(
                    label = stringResource(R.string.vault_info_label_name),
                    value = container.name
                )
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
                    label = stringResource(R.string.vault_info_label_algorithm),
                    value = container.algorithm
                )
                InfoDivider()
                InfoRow(
                    label = stringResource(R.string.vault_info_label_prf),
                    value = container.prf
                )
                InfoDivider()
                InfoRow(
                    label = stringResource(R.string.vault_info_label_filesystem),
                    value = container.filesystem
                )
                InfoDivider()
                InfoRow(
                    label = stringResource(R.string.vault_info_label_pim),
                    value = if (container.pim > 0) stringResource(R.string.vault_info_pim_custom)
                            else stringResource(R.string.vault_info_pim_default)
                )
                InfoDivider()
                InfoRow(
                    label = stringResource(R.string.vault_info_label_size),
                    value = container.size.formatSize()
                )
                InfoDivider()
                InfoRow(
                    label = stringResource(R.string.vault_info_label_created),
                    value = container.createdAt.formatDate()
                )
                InfoDivider()
                InfoRow(
                    label = stringResource(R.string.vault_info_label_location),
                    value = container.locationDisplay()
                )
            }
        }
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
