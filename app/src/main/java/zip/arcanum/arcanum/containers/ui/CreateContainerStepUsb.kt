package zip.arcanum.arcanum.containers.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import zip.arcanum.R
import zip.arcanum.core.components.AppDialog
import zip.arcanum.usb.UsbPartitioner
import java.text.DecimalFormat

/**
 * The drive and its partitions, as a step of the wizard (#131).
 *
 * This is where the user says what the vault will fill: one of the drive's partitions,
 * the whole drive, or one created here out of the unallocated space. Nothing on this page
 * can tell whether a partition already holds a volume - a VeraCrypt header is
 * indistinguishable from random data without the password - so it describes only what is
 * visible.
 */
@Composable
fun StepUsbPartitions(
    state: CreateContainerState,
    onSelect: (startByte: Long, sizeBytes: Long) -> Unit,
    onBeginNew: () -> Unit,
    onCancelNew: () -> Unit,
    onSetForVault: (Boolean) -> Unit,
    onCreate: (Long) -> Unit,
    onRequestWholeDrive: () -> Unit,
    onConfirmWholeDrive: () -> Unit,
    onCancelWholeDrive: () -> Unit,
    onRequestDelete: (slot: Int) -> Unit,
    onConfirmDelete: () -> Unit,
    onCancelDelete: () -> Unit,
) {
    StepContent(title = stringResource(R.string.create_step_usb_title)) {

        Text(
            text  = stringResource(
                R.string.create_step_usb_drive,
                state.usbDeviceLabel,
                state.usbWholeSize.fmtUsbSize()
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // What no partition covers. On a freshly partitioned drive this is the 1 MiB
        // alignment gap, which is what desktop partitioners show too; after deleting a
        // partition it is that partition's room, and it is what a new one comes out of.
        // Every drive this supports reports 512 (UsbBlockDevice refuses anything else),
        // and a drive with no partitions has none to ask.
        val sectorSize = state.usbPartitions.firstOrNull()?.sectorSize ?: 512
        val extents = UsbPartitioner.freeExtents(
            state.usbWholeSize, sectorSize, state.usbPartitions
        )
        val largestFree = extents.maxOfOrNull { it.sizeBytes } ?: 0L
        val unallocated = (state.usbWholeSize - state.usbPartitions.sumOf { it.sizeBytes })
            .coerceAtLeast(0L)

        if (state.usbNewPartitionStep) {
            NewPartitionEditor(state, largestFree, onCancelNew, onSetForVault, onCreate)
            return@StepContent
        }

        state.usbPartitions.forEach { part ->
            PartitionCard(
                selected    = state.usbTargetStart == part.startByte &&
                              state.usbTargetSize == part.sizeBytes,
                title       = stringResource(R.string.usb_partition_n, part.slot + 1),
                description = "${part.typeName} - ${part.sizeBytes.fmtUsbSize()}",
                enabled     = !state.usbPartitioning,
                onClick     = { onSelect(part.startByte, part.sizeBytes) },
                onDelete    = { onRequestDelete(part.slot) }
            )
            if (state.usbTargetStart == part.startByte &&
                part.typeByte != UsbPartitioner.VAULT_TYPE
            ) {
                Text(
                    text     = stringResource(R.string.usb_retype_note, part.typeName),
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        SelectionCard(
            selected    = state.usbTargetStart == 0L && state.usbTargetSize == state.usbWholeSize &&
                          state.usbTargetSize > 0L,
            icon        = Icons.Outlined.Usb,
            title       = stringResource(R.string.usb_whole_drive),
            description = if (state.usbPartitions.isEmpty())
                stringResource(R.string.create_step_usb_whole_desc, state.usbWholeSize.fmtUsbSize())
            else
                stringResource(R.string.usb_whole_wipes, state.usbPartitions.size),
            onClick     = {
                // Taking the whole drive means overwriting its table, so it is asked for
                // the same way a deletion is - once the drive actually has something on it.
                if (state.usbPartitions.isEmpty()) onSelect(0L, state.usbWholeSize)
                else onRequestWholeDrive()
            }
        )
        Spacer(Modifier.height(10.dp))

        val slotsFull = state.usbPartitions.size >= 4
        val canCreate = largestFree > 0L && !slotsFull
        SelectionCard(
            selected    = false,
            icon        = Icons.Outlined.ContentCut,
            title       = stringResource(R.string.usb_new_partition),
            description = when {
                slotsFull      -> stringResource(R.string.usb_new_partition_full)
                largestFree <= 0L -> stringResource(R.string.usb_new_partition_none)
                else           -> stringResource(R.string.usb_new_partition_hint)
            },
            onClick     = onBeginNew,
            enabled     = canCreate
        )

        Spacer(Modifier.height(10.dp))
        UnallocatedRow(unallocated)

        if (state.usbPartitionError.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text  = state.usbPartitionError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    if (state.usbWholeConfirm) {
        AppDialog(
            onDismissRequest = onCancelWholeDrive,
            title            = { Text(stringResource(R.string.usb_whole_confirm_title)) },
            text             = { Text(stringResource(R.string.usb_whole_confirm_body)) },
            confirmButton    = {
                TextButton(onClick = onConfirmWholeDrive) {
                    Text(
                        text  = stringResource(R.string.usb_whole_confirm_go),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton    = {
                TextButton(onClick = onCancelWholeDrive) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (state.usbDeleteSlot != null) {
        AppDialog(
            onDismissRequest = onCancelDelete,
            title            = { Text(stringResource(R.string.usb_delete_title)) },
            text             = { Text(stringResource(R.string.usb_delete_body)) },
            confirmButton    = {
                TextButton(onClick = onConfirmDelete) {
                    Text(
                        text  = stringResource(R.string.common_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton    = {
                TextButton(onClick = onCancelDelete) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

/** A partition: selectable like every other card, with its own way out of the table. */
@Composable
private fun PartitionCard(
    selected: Boolean,
    title: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        onClick  = onClick,
        enabled  = enabled,
        border   = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
        ),
        colors   = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                             else MaterialTheme.colorScheme.surface
        ),
        shape    = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier              = Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 8.dp),
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector        = Icons.Outlined.Storage,
                contentDescription = null,
                tint               = if (selected) MaterialTheme.colorScheme.primary
                                     else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text  = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete, enabled = enabled) {
                Icon(
                    imageVector        = Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.common_delete),
                    tint               = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/** Not a choice, just what is left over - so it is deliberately not a card. */
@Composable
private fun UnallocatedRow(bytes: Long) {
    Row(
        modifier              = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            imageVector        = Icons.Outlined.Storage,
            contentDescription = null,
            tint               = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            modifier           = Modifier.size(24.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text  = stringResource(R.string.usb_unallocated),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text  = stringResource(R.string.usb_unallocated_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        Text(
            text  = bytes.fmtUsbSize(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/** A new partition out of the free space: how big, and what for. */
@Composable
private fun NewPartitionEditor(
    state: CreateContainerState,
    largestFree: Long,
    onCancel: () -> Unit,
    onSetForVault: (Boolean) -> Unit,
    onCreate: (Long) -> Unit,
) {
    val minSize = UsbPartitioner.MIN_PLAIN_BYTES
    val maxSize = largestFree.coerceAtLeast(minSize)
    var size by remember(largestFree) { mutableStateOf(maxSize.toFloat()) }

    Text(
        text  = stringResource(R.string.usb_new_free, largestFree.fmtUsbSize()),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Only when there is nothing to lose a table over. Writing one lands on sector 0,
    // which is exactly where a whole-device volume keeps its salt.
    if (state.usbPartitions.isEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            text  = stringResource(R.string.usb_new_no_table),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    Spacer(Modifier.height(16.dp))
    Slider(
        value         = size,
        onValueChange = { size = it },
        valueRange    = minSize.toFloat()..maxSize.toFloat(),
        enabled       = !state.usbPartitioning
    )
    Text(
        text  = stringResource(R.string.usb_new_size, size.toLong().fmtUsbSize()),
        style = MaterialTheme.typography.bodyMedium
    )

    Spacer(Modifier.height(16.dp))
    Text(
        text       = stringResource(R.string.usb_new_purpose),
        style      = MaterialTheme.typography.titleSmall
    )
    Spacer(Modifier.height(8.dp))
    SelectionCard(
        selected    = state.usbNewForVault,
        icon        = Icons.Outlined.Lock,
        title       = stringResource(R.string.usb_new_for_vault),
        description = stringResource(R.string.usb_new_for_vault_desc),
        onClick     = { onSetForVault(true) }
    )
    Spacer(Modifier.height(10.dp))
    SelectionCard(
        selected    = !state.usbNewForVault,
        icon        = Icons.Outlined.Storage,
        title       = stringResource(R.string.usb_new_for_files),
        description = stringResource(R.string.usb_new_for_files_desc),
        onClick     = { onSetForVault(false) }
    )

    if (state.usbPartitioning) {
        Spacer(Modifier.height(12.dp))
        Text(
            text  = stringResource(R.string.usb_creating),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    if (state.usbPartitionError.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        Text(
            text  = state.usbPartitionError,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }

    Spacer(Modifier.height(16.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = onCancel, enabled = !state.usbPartitioning) {
            Text(stringResource(R.string.common_cancel))
        }
        Button(
            onClick  = { onCreate(size.toLong()) },
            enabled  = !state.usbPartitioning,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.usb_new_go)) }
    }
}

/** Local to this page: the wizard's other size labels are built the same way. */
internal fun Long.fmtUsbSize(): String {
    val gb = this / (1024.0 * 1024.0 * 1024.0)
    val mb = this / (1024.0 * 1024.0)
    val fmt = DecimalFormat("#.#")
    return when {
        gb >= 1.0 -> "${fmt.format(gb)} GB"
        mb >= 1.0 -> "${fmt.format(mb)} MB"
        else      -> "${fmt.format(this / 1024.0)} KB"
    }
}
