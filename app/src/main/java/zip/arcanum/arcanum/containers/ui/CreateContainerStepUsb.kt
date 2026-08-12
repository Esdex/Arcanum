package zip.arcanum.arcanum.containers.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import zip.arcanum.R
import java.text.DecimalFormat

/**
 * The drive and its partitions, as a page of the wizard rather than a dialog (#131).
 *
 * This is where the user says what the vault will fill: one of the drive's partitions,
 * the whole drive, or a fresh split made here. Nothing on this page can tell whether a
 * partition already holds a volume - a VeraCrypt header is indistinguishable from random
 * data without the password - so it describes only what is visible.
 */
@Composable
fun StepUsbPartitions(
    state: CreateContainerState,
    onSelect: (startByte: Long, sizeBytes: Long) -> Unit,
    onBeginSplit: () -> Unit,
    onCancelSplit: () -> Unit,
    onApplySplit: (Long) -> Unit,
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

        if (state.usbSplitStep) {
            SplitEditor(state, onCancelSplit, onApplySplit)
            return@StepContent
        }

        state.usbPartitions.forEach { part ->
            SelectionCard(
                selected    = state.usbTargetStart == part.startByte && state.usbTargetSize == part.sizeBytes,
                icon        = Icons.Outlined.Storage,
                title       = stringResource(R.string.usb_partition_n, part.slot + 1),
                description = "${part.typeName} - ${part.sizeBytes.fmtUsbSize()}",
                onClick     = { onSelect(part.startByte, part.sizeBytes) }
            )
            Spacer(Modifier.height(10.dp))
        }

        SelectionCard(
            selected    = state.usbTargetStart == 0L && state.usbTargetSize == state.usbWholeSize &&
                          state.usbTargetSize > 0L,
            icon        = Icons.Outlined.Usb,
            title       = stringResource(R.string.usb_whole_drive),
            description = stringResource(R.string.create_step_usb_whole_desc, state.usbWholeSize.fmtUsbSize()),
            onClick     = { onSelect(0L, state.usbWholeSize) }
        )
        Spacer(Modifier.height(10.dp))

        SelectionCard(
            selected    = false,
            icon        = Icons.Outlined.ContentCut,
            title       = stringResource(R.string.usb_split_option),
            description = stringResource(R.string.usb_split_option_hint),
            onClick     = onBeginSplit
        )

        if (state.usbPartitionError.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(
                text  = state.usbPartitionError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

/** The split itself: how much stays an ordinary partition, and what the vault gets. */
@Composable
private fun SplitEditor(
    state: CreateContainerState,
    onCancel: () -> Unit,
    onApply: (Long) -> Unit,
) {
    val minPlain = 64L * 1024 * 1024
    // Leave room for a vault worth having, so the slider cannot ask for a split that
    // UsbPartitioner would only refuse.
    val maxPlain = (state.usbWholeSize - 1024L * 1024 * 1024).coerceAtLeast(minPlain)
    var plainBytes by remember(state.usbWholeSize) {
        mutableStateOf((state.usbWholeSize / 4).coerceIn(minPlain, maxPlain).toFloat())
    }
    val vaultBytes = (state.usbWholeSize - plainBytes.toLong()).coerceAtLeast(0L)

    Text(
        text  = stringResource(R.string.usb_split_body),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(16.dp))

    Slider(
        value         = plainBytes,
        onValueChange = { plainBytes = it },
        valueRange    = minPlain.toFloat()..maxPlain.toFloat(),
        enabled       = !state.usbPartitioning
    )
    Text(
        text  = stringResource(R.string.usb_split_plain, plainBytes.toLong().fmtUsbSize()),
        style = MaterialTheme.typography.bodyMedium
    )
    Text(
        text  = stringResource(R.string.usb_split_vault, vaultBytes.fmtUsbSize()),
        style = MaterialTheme.typography.bodyMedium
    )

    if (state.usbPartitioning) {
        Spacer(Modifier.height(12.dp))
        Text(
            text  = stringResource(R.string.usb_splitting),
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
            onClick = { onApply(plainBytes.toLong()) },
            enabled = !state.usbPartitioning,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.usb_split_go)) }
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
