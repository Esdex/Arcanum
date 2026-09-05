package zip.arcanum.arcanum.containers.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import zip.arcanum.R
import zip.arcanum.core.components.AppDialog

/**
 * Which filesystem to suggest for a volume of [sizeMb].
 *
 * 4 GB is FAT's per-file limit, and the only number that makes this choice mean anything:
 * a volume smaller than that cannot hold a file FAT would refuse, so exFAT buys nothing
 * and costs compatibility. (This once read two terabytes, so exFAT was never recommended
 * to anyone.) The hidden volume asks the same question about its own size.
 */
fun recommendedFilesystemFor(sizeMb: Long): FilesystemType =
    if (sizeMb > 4L * 1024L) FilesystemType.EXFAT else FilesystemType.FAT32

/**
 * The three filesystem cards and the dialog behind their info buttons.
 *
 * Shared because the outer volume and the hidden volume ask the identical question - and
 * they are independent answers, as in VeraCrypt: a FAT outer volume can hold an ext4
 * hidden one and the other way round.
 */
@Composable
fun FilesystemPicker(
    selected: FilesystemType,
    recommended: FilesystemType,
    onSelect: (FilesystemType) -> Unit
) {
    var infoFs by remember { mutableStateOf<FilesystemType?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        FilesystemType.entries.forEach { fs ->
            FilesystemCard(
                fs          = fs,
                selected    = selected == fs,
                recommended = fs == recommended,
                onClick     = { onSelect(fs) },
                onInfo      = { infoFs = fs }
            )
        }
    }

    if (infoFs != null) {
        AppDialog(
            onDismissRequest = { infoFs = null },
            title            = { Text(infoFs!!.displayName) },
            text             = { Text(infoFs!!.info) },
            confirmButton    = {
                TextButton(onClick = { infoFs = null }) { Text(stringResource(R.string.create_fs_got_it)) }
            }
        )
    }
}

@Composable
private fun FilesystemCard(
    fs: FilesystemType,
    selected: Boolean,
    recommended: Boolean,
    onClick: () -> Unit,
    onInfo: () -> Unit
) {
    val borderColor    = if (selected) MaterialTheme.colorScheme.primary
                         else MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    val containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                         else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    Card(
        colors   = CardDefaults.cardColors(containerColor = containerColor),
        border   = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        shape    = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier          = Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text       = fs.displayName,
                        style      = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color      = MaterialTheme.colorScheme.onSurface
                    )
                    if (recommended) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(99.dp)
                        ) {
                            Text(
                                text     = stringResource(R.string.create_fs_recommended),
                                style    = MaterialTheme.typography.labelSmall,
                                color    = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = fs.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text  = stringResource(R.string.create_fs_max_file_size, fs.maxFileSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (fs) {
                        FilesystemType.FAT32 -> {
                            OsChip("Windows", true)
                            OsChip("macOS", true)
                            OsChip("Linux", true)
                        }
                        FilesystemType.EXFAT -> {
                            OsChip("Windows", true)
                            OsChip("macOS", true)
                            OsChip("Linux", true)
                        }
                        FilesystemType.EXT4 -> {
                            OsChip("Windows", false)
                            OsChip("macOS", false)
                            OsChip("Linux", true)
                        }
                    }
                }
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onInfo, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = stringResource(R.string.create_fs_info_cd),
                    tint     = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun OsChip(os: String, support: Boolean?) {
    val (icon, bg, fg) = when (support) {
        true -> Triple("✅", Color(0xFF16A34A).copy(alpha = 0.12f), Color(0xFF16A34A))
        null -> Triple("⚠️", Color(0xFFF59E0B).copy(alpha = 0.12f), Color(0xFFB45309))
        else -> Triple("❌", MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), MaterialTheme.colorScheme.error)
    }
    Surface(color = bg, shape = RoundedCornerShape(99.dp)) {
        Text(
            text     = "$icon $os${if (support == null) stringResource(R.string.create_fs_os_read_only) else ""}",
            style    = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            color    = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
        )
    }
}
