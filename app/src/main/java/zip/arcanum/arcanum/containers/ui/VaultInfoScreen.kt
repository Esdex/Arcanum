package zip.arcanum.arcanum.containers.ui

import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.SaveAlt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import zip.arcanum.R
import zip.arcanum.arcanum.containers.domain.Container
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.components.AppSheet
import zip.arcanum.core.theme.LocalDynamicColor
import java.text.DecimalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VaultInfoScreen(
    container: Container?,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onUnmount: () -> Unit = {}
) {
    val context           = LocalContext.current
    val comingSoon        = stringResource(R.string.common_coming_soon)
    val isDynamic         = LocalDynamicColor.current
    var showUnmountDialog by remember { mutableStateOf(false) }
    var showInfoSheet     by remember { mutableStateOf(false) }

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

        VaultCard(
            title     = stringResource(R.string.vault_info_section_title),
            subtitle  = stringResource(R.string.vault_card_info_desc),
            icon      = Icons.Outlined.Info,
            rawColor  = Color(0xFF1E88E5),
            isDynamic = isDynamic,
            onClick   = { showInfoSheet = true }
        )
        VaultCard(
            title     = stringResource(R.string.vault_info_op_expand_volume),
            subtitle  = stringResource(R.string.vault_card_expand_desc),
            icon      = Icons.Outlined.OpenInFull,
            rawColor  = Color(0xFF8E24AA),
            isDynamic = isDynamic,
            onClick   = { Toast.makeText(context, comingSoon, Toast.LENGTH_SHORT).show() }
        )
        VaultCard(
            title     = stringResource(R.string.vault_info_op_backup_header),
            subtitle  = stringResource(R.string.vault_card_backup_desc),
            icon      = Icons.Outlined.SaveAlt,
            rawColor  = Color(0xFFE65100),
            isDynamic = isDynamic,
            onClick   = { Toast.makeText(context, comingSoon, Toast.LENGTH_SHORT).show() }
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick  = { showUnmountDialog = true },
            colors   = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor   = MaterialTheme.colorScheme.onError
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector        = Icons.Outlined.PowerSettingsNew,
                contentDescription = null,
                modifier           = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.vault_info_unmount_button))
        }
    }

    if (showInfoSheet && container != null) {
        AppSheet(
            onDismissRequest = { showInfoSheet = false },
            sheetState       = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            Text(
                text       = stringResource(R.string.vault_info_section_title),
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Surface(
                    shape    = RoundedCornerShape(16.dp),
                    color    = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(
                        modifier          = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        InfoRow(stringResource(R.string.vault_info_label_name),        container.name)
                        InfoRow(stringResource(R.string.vault_info_label_volume_type), when {
                            container.isHiddenVolume  -> stringResource(R.string.vault_info_type_hidden)
                            container.hasHiddenVolume -> stringResource(R.string.vault_info_type_outer)
                            else                      -> stringResource(R.string.vault_info_type_standard)
                        })
                        InfoRow(stringResource(R.string.vault_info_label_algorithm),   container.algorithm)
                        InfoRow(stringResource(R.string.vault_info_label_prf),         container.prf)
                        InfoRow(stringResource(R.string.vault_info_label_filesystem),  container.filesystem)
                        InfoRow(stringResource(R.string.vault_info_label_pim),         if (container.pim > 0) stringResource(R.string.vault_info_pim_custom) else stringResource(R.string.vault_info_pim_default))
                        InfoRow(stringResource(R.string.vault_info_label_size),        container.size.formatSize())
                        InfoRow(stringResource(R.string.vault_info_label_created),     container.createdAt.formatDate())
                        InfoRow(stringResource(R.string.vault_info_label_location),    container.locationDisplay())
                    }
                }
                Spacer(Modifier.navigationBarsPadding())
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    if (showUnmountDialog) {
        val fallbackName = stringResource(R.string.vault_info_fallback_name)
        AppDialog(
            onDismissRequest = { showUnmountDialog = false },
            title            = { Text(stringResource(R.string.vault_info_unmount_title, container?.name ?: fallbackName)) },
            text             = { Text(stringResource(R.string.vault_info_unmount_body)) },
            confirmButton    = {
                TextButton(onClick = { showUnmountDialog = false; onUnmount() }) {
                    Text(stringResource(R.string.vault_unmount_confirm), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton    = {
                TextButton(onClick = { showUnmountDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun VaultCard(
    title    : String,
    subtitle : String,
    icon     : ImageVector,
    rawColor : Color,
    isDynamic: Boolean,
    onClick  : () -> Unit
) {
    val iconColor         = if (isDynamic) MaterialTheme.colorScheme.primary else rawColor
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed         by interactionSource.collectIsPressedAsState()
    val scale             by animateFloatAsState(
        targetValue   = if (isPressed) 0.97f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label         = "vault_card_scale"
    )

    Card(
        onClick           = onClick,
        interactionSource = interactionSource,
        shape             = RoundedCornerShape(16.dp),
        colors            = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation         = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier          = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .scale(scale)
    ) {
        Row(
            modifier          = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier         = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(iconColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    tint               = iconColor,
                    modifier           = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text       = title,
                    style      = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text  = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                imageVector        = Icons.Outlined.KeyboardArrowRight,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            text  = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
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
