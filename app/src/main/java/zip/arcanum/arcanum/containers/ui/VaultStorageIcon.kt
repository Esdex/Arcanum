package zip.arcanum.arcanum.containers.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
/**
 * The icon that says where a vault's file lives: on a USB drive, inside the app's own
 * private storage, or on the phone's shared storage.
 *
 * One rule in one place because it is shown in more than one: the vault's own screen and
 * the destination sheet that copy, move and Create link all open. Two copies of a rule
 * like this drift, and the drift is invisible - both look deliberate.
 *
 * A USB vault is the one that occupies a whole device, which is what a salt hash records
 * (#95). App storage is a path under filesDir or noBackupFilesDir with no SAF uri: a
 * document picked through SAF can point anywhere and is not ours to claim.
 *
 * Takes the three fields rather than an object, because the two callers hold the vault in
 * two different shapes - the domain Container and the Room entity - and a rule this small
 * should not force either of them to convert.
 */
@Composable
fun vaultStorageIcon(path: String, safUri: String, usbSaltHash: String): ImageVector {
    val context = LocalContext.current
    val isInAppStorage = remember(path, safUri) {
        safUri.isEmpty() &&
            (path.startsWith(context.filesDir.absolutePath) ||
             path.startsWith(context.noBackupFilesDir.absolutePath))
    }
    return when {
        usbSaltHash.isNotEmpty() -> Icons.Outlined.Usb
        isInAppStorage           -> Icons.Outlined.PhoneAndroid
        else                     -> Icons.Outlined.Storage
    }
}
