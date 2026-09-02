package zip.arcanum.arcanum.files.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import zip.arcanum.R
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.components.AppSheet
import kotlin.math.roundToInt

/**
 * The whole picture of one operation, opened by tapping the line above the file list (#158).
 *
 * It outlives the work on purpose. When the operation ends the sheet stays and turns into
 * the result - what went, what was skipped, what failed, how long it took - because someone
 * who opened it to watch loses the detail exactly when it becomes final otherwise. The
 * banner still says the short version; this is the long one.
 *
 * Nothing here is estimated from nothing: speed and time remaining appear only when the
 * total size is known and enough has moved to mean something.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun OperationDetailsSheet(
    progress: FileManagerViewModel.OperationProgress,
    sheetState: SheetState,
    formatSize: (Long) -> String,
    canCancel: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    AppSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // A clock of its own, so speed and the time left keep moving between the updates
        // that the bytes themselves cause.
        var now by remember { mutableLongStateOf(android.os.SystemClock.elapsedRealtime()) }
        LaunchedEffect(progress.finished) {
            while (!progress.finished) {
                delay(500)
                now = android.os.SystemClock.elapsedRealtime()
            }
            now = android.os.SystemClock.elapsedRealtime()
        }

        /*
         * Asked with the work paused, not stopped: the import parks between chunks while the
         * question is on screen, so nothing more goes into the vault under someone who is
         * deciding whether to stop it. A dialog dismissed without an answer is a No.
         */
        var askingToCancel by remember { mutableStateOf(false) }
        if (askingToCancel) {
            AppDialog(
                onDismissRequest = { askingToCancel = false; onResume() },
                title = { Text(stringResource(R.string.files_op_cancel_title)) },
                text  = { Text(stringResource(R.string.files_op_cancel_body)) },
                confirmButton = {
                    TextButton(onClick = { askingToCancel = false; onCancel() }) {
                        Text(
                            text  = stringResource(R.string.files_op_cancel_confirm),
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(onClick = { askingToCancel = false; onResume() }) {
                        Text(stringResource(R.string.files_op_cancel_dismiss))
                    }
                }
            )
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {

            Text(
                text       = stringResource(
                    when {
                        progress.cancelled -> R.string.files_op_stopped
                        progress.finished  -> finishedTitleRes(progress.kind)
                        else               -> operationVerbRes(progress.kind)
                    }
                ),
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            if (progress.destination.isNotEmpty()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text     = stringResource(R.string.files_op_to, progress.destination),
                    style    = MaterialTheme.typography.bodySmall,
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.height(20.dp))

            if (!progress.finished) {
                RunningBody(progress, now, formatSize)
                if (canCancel) {
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick  = { askingToCancel = true; onPause() },
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor   = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.files_op_cancel))
                    }
                }
            } else {
                FinishedBody(progress, formatSize)
                Spacer(Modifier.height(20.dp))
                Button(
                    onClick  = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.files_op_done))
                }
            }

            Spacer(Modifier.height(8.dp))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun RunningBody(
    progress: FileManagerViewModel.OperationProgress,
    now: Long,
    formatSize: (Long) -> String
) {
    if (progress.preparing) {
        Text(
            text  = stringResource(R.string.files_op_preparing),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        DetailLine(
            stringResource(R.string.files_op_elapsed),
            formatDuration(progress.elapsedMs(now) / 1000)
        )
        return
    }

    if (progress.currentName.isNotEmpty()) {
        Text(
            text     = progress.currentName,
            style    = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(4.dp))
        val fileFraction = progress.currentFraction
        if (fileFraction != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text  = stringResource(
                        R.string.files_op_of,
                        formatSize(progress.currentDone),
                        formatSize(progress.currentSize)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text  = stringResource(R.string.files_import_percent, (fileFraction * 100).roundToInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(4.dp))
            val animated by animateFloatAsState(fileFraction, tween(200), label = "op_file")
            LinearProgressIndicator(progress = { animated }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(20.dp))
    }

    if (progress.total > 0) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text     = stringResource(R.string.files_op_position, progress.index, progress.total),
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            progress.totalFraction?.let {
                Text(
                    text  = stringResource(R.string.files_import_percent, (it * 100).roundToInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        val batch = progress.totalFraction
        if (batch != null) {
            val animatedBatch by animateFloatAsState(batch, tween(200), label = "op_batch")
            LinearProgressIndicator(progress = { animatedBatch }, modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(12.dp))
    }

    if (progress.folders > 0) {
        DetailLine(stringResource(R.string.files_op_folders), progress.folders.toString())
    }
    if (progress.bytesTotal > 0L) {
        DetailLine(
            stringResource(R.string.files_op_moved),
            stringResource(
                R.string.files_op_of,
                formatSize(progress.movedBytes),
                formatSize(progress.bytesTotal)
            )
        )
    }
    progress.bytesPerSecond(now)?.let {
        DetailLine(
            stringResource(R.string.files_op_speed),
            stringResource(R.string.files_op_per_second, formatSize(it))
        )
    }
    progress.secondsLeft(now)?.let {
        DetailLine(stringResource(R.string.files_op_left), formatDuration(it))
    }
    DetailLine(
        stringResource(R.string.files_op_elapsed),
        formatDuration(progress.elapsedMs(now) / 1000)
    )
    Tallies(progress)
}

@Composable
private fun FinishedBody(
    progress: FileManagerViewModel.OperationProgress,
    formatSize: (Long) -> String
) {
    DetailLine(stringResource(finishedCountRes(progress.kind)), progress.done.toString())
    Tallies(progress)
    if (progress.bytesDone > 0L) {
        DetailLine(stringResource(R.string.files_op_moved), formatSize(progress.bytesDone))
    }
    DetailLine(
        stringResource(R.string.files_op_took),
        formatDuration(progress.elapsedMs(progress.endedAtMs) / 1000)
    )
}

/** What did not simply go through. Silent when there is nothing to say. */
@Composable
private fun Tallies(progress: FileManagerViewModel.OperationProgress) {
    if (progress.skipped > 0)
        DetailLine(stringResource(R.string.files_op_skipped), progress.skipped.toString())
    if (progress.leftBehind > 0)
        DetailLine(stringResource(R.string.files_op_left_behind), progress.leftBehind.toString())
    if (progress.failed > 0)
        DetailLine(
            stringResource(R.string.files_op_failed),
            progress.failed.toString(),
            emphasis = true
        )
}

@Composable
private fun DetailLine(label: String, value: String, emphasis: Boolean = false) {
    Row(
        modifier          = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text     = label,
            style    = MaterialTheme.typography.bodySmall,
            color    = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text  = value,
            style = MaterialTheme.typography.bodySmall,
            color = if (emphasis) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface
        )
    }
}

/** "45 s", "2 min 11 s", "1 h 4 min" - never "0 h 0 min 45 s". */
@Composable
private fun formatDuration(seconds: Long): String = when {
    seconds < 60  -> stringResource(R.string.files_op_seconds, seconds)
    seconds < 3600 -> stringResource(R.string.files_op_minutes, seconds / 60, seconds % 60)
    else -> stringResource(R.string.files_op_hours, seconds / 3600, (seconds % 3600) / 60)
}

internal fun operationVerbRes(kind: FileManagerViewModel.OperationKind): Int = when (kind) {
    FileManagerViewModel.OperationKind.IMPORT -> R.string.files_op_importing
    FileManagerViewModel.OperationKind.EXPORT -> R.string.files_op_exporting
    FileManagerViewModel.OperationKind.COPY   -> R.string.files_op_copying
    FileManagerViewModel.OperationKind.MOVE   -> R.string.files_op_moving
    FileManagerViewModel.OperationKind.DELETE -> R.string.files_op_deleting
    FileManagerViewModel.OperationKind.LINK   -> R.string.files_op_linking
}

private fun finishedTitleRes(kind: FileManagerViewModel.OperationKind): Int = when (kind) {
    FileManagerViewModel.OperationKind.IMPORT -> R.string.files_op_import_finished
    FileManagerViewModel.OperationKind.EXPORT -> R.string.files_op_export_finished
    FileManagerViewModel.OperationKind.COPY   -> R.string.files_op_copy_finished
    FileManagerViewModel.OperationKind.MOVE   -> R.string.files_op_move_finished
    FileManagerViewModel.OperationKind.DELETE -> R.string.files_op_delete_finished
    FileManagerViewModel.OperationKind.LINK   -> R.string.files_op_link_finished
}

private fun finishedCountRes(kind: FileManagerViewModel.OperationKind): Int = when (kind) {
    FileManagerViewModel.OperationKind.IMPORT -> R.string.files_op_count_imported
    FileManagerViewModel.OperationKind.EXPORT -> R.string.files_op_count_exported
    FileManagerViewModel.OperationKind.COPY   -> R.string.files_op_count_copied
    FileManagerViewModel.OperationKind.MOVE   -> R.string.files_op_count_moved
    FileManagerViewModel.OperationKind.DELETE -> R.string.files_op_count_deleted
    FileManagerViewModel.OperationKind.LINK   -> R.string.files_op_count_linked
}
