package zip.arcanum.core.components

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import zip.arcanum.R

/**
 * Runs a file-reading action, having first made sure Android will hand over the whole file.
 *
 * Without ACCESS_MEDIA_LOCATION the platform zeroes a photo's Exif GPS tags before the app
 * ever sees the bytes, so a vaulted photo loses the place it was taken - silently, and for
 * good once the original is deleted (#149).
 */
class MediaLocationGate internal constructor(
    private val request: (() -> Unit) -> Unit
) {
    operator fun invoke(read: () -> Unit) = request(read)
}

/**
 * @param promptShown       whether the explanation has already been through once
 * @param onMarkPromptShown called when it has now been through
 *
 * The explanation exists because the system dialog asks a much broader question than it
 * answers: from Android 14 the permission sits in the READ_MEDIA_VISUAL group, so the
 * dialog says "photos and videos" while what is granted is the location plus access to the
 * items the user picks. It is shown once. A denial sets USER_FIXED and the system dialog
 * never returns, and explaining a dialog that will not appear is worse than saying nothing.
 */
@Composable
fun rememberMediaLocationGate(
    promptShown: Boolean,
    onMarkPromptShown: () -> Unit
): MediaLocationGate {
    val context = LocalContext.current
    var pending  by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showInfo by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { pending?.invoke(); pending = null }

    if (showInfo) {
        AppDialog(
            // Dismissing without Continue cancels: nothing is asked, the flag is not set, and
            // the explanation comes back next time.
            onDismissRequest = { showInfo = false; pending = null },
            title  = { Text(stringResource(R.string.files_media_location_title)) },
            text   = { Text(stringResource(R.string.files_media_location_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showInfo = false
                    onMarkPromptShown()
                    launcher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                }) { Text(stringResource(R.string.common_continue)) }
            }
        )
    }

    return remember(promptShown, launcher) {
        MediaLocationGate { read ->
            val holdsIt = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_MEDIA_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            when {
                holdsIt -> read()
                !promptShown -> { pending = read; showInfo = true }
                // Already explained. Granted is handled above; denied returns silently and
                // the read goes ahead without the location.
                else -> { pending = read; launcher.launch(Manifest.permission.ACCESS_MEDIA_LOCATION) }
            }
        }
    }
}
