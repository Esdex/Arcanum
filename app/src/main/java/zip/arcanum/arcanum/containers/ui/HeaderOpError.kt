package zip.arcanum.arcanum.containers.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import zip.arcanum.R

/**
 * What a failed header backup or restore says to the person in front of it.
 *
 * The two screens used to print whatever string the ViewModel put in the state, and the
 * ViewModel put `CryptoError.name` there - so a vault that was still mounted refused the
 * restore and the screen said "BUSY". The native layer had a reason; the screen had a code.
 *
 * So the state carries a token now and this turns it into a sentence, in one place because
 * both screens must say the same thing. An unknown token still falls through to the generic
 * line WITH the token in it: a code nobody planned for is worth showing rather than hiding,
 * it is just never the whole message.
 */
@Composable
internal fun headerOpErrorText(token: String, restore: Boolean): String = when (token) {
    // The vault is open. The native layer refuses this too (ERR_BUSY), so the same token
    // arrives whether the check upstairs or the one downstairs caught it.
    "BUSY"                -> stringResource(
        if (restore) R.string.header_err_busy_restore else R.string.header_err_busy_backup
    )
    "WRONG_PASSWORD"      -> stringResource(
        if (restore) R.string.restore_header_error_wrong_password
        else         R.string.backup_header_error_wrong_password
    )
    "OPEN_VOLUME"         -> stringResource(R.string.header_err_open_volume)
    "OPEN_BACKUP"         -> stringResource(R.string.header_err_open_backup)
    "OPEN_OUTPUT"         -> stringResource(R.string.header_err_open_output)
    "CORRUPTED_CONTAINER" -> stringResource(R.string.header_err_corrupted)
    "IO_ERROR"            -> stringResource(R.string.header_err_io)
    "NO_SPACE"            -> stringResource(R.string.header_err_no_space)
    "READ_ONLY"           -> stringResource(R.string.header_err_read_only)
    "USB_NOT_CONNECTED"   -> stringResource(R.string.usb_not_connected_body)
    "USB_WRONG_DEVICE"    -> stringResource(R.string.usb_wrong_device)
    else                  -> stringResource(
        if (restore) R.string.restore_header_error_generic else R.string.backup_header_error_generic,
        token
    )
}
