package zip.arcanum.arcanum.camera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import zip.arcanum.core.components.AppDialog

/**
 * What the Photo item calls: [onReady] straight away when the camera may be used, and
 * otherwise an explanation first, then the system's own question.
 *
 * The explanation comes before every system prompt rather than once: the system dialog only
 * names the camera, and does not say where the photos go. When the answer has become a
 * permanent no, the system shows nothing at all, so the app says where it can be changed
 * instead of doing nothing.
 */
@Composable
fun rememberCameraPermission(onReady: () -> Unit): () -> Unit {
    val context = LocalContext.current
    var explain by remember { mutableStateOf(false) }
    var refused by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> onReady()
            // No longer asked by the system: only the app's settings can change it now.
            (context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false -> refused = true
        }
    }

    if (explain) {
        AppDialog(
            onDismissRequest = { explain = false },
            title = { Text(stringResource(R.string.camera_permission_title)) },
            text  = { Text(stringResource(R.string.camera_permission_message)) },
            confirmButton = {
                TextButton(onClick = {
                    explain = false
                    launcher.launch(Manifest.permission.CAMERA)
                }) { Text(stringResource(R.string.common_continue)) }
            },
            dismissButton = {
                TextButton(onClick = { explain = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    if (refused) {
        AppDialog(
            onDismissRequest = { refused = false },
            title = { Text(stringResource(R.string.camera_permission_off_title)) },
            text  = { Text(stringResource(R.string.camera_permission_off_message)) },
            confirmButton = {
                TextButton(onClick = {
                    refused = false
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        )
                    }
                }) { Text(stringResource(R.string.camera_open_settings)) }
            },
            dismissButton = {
                TextButton(onClick = { refused = false }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }

    return {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) onReady()
        else explain = true
    }
}
