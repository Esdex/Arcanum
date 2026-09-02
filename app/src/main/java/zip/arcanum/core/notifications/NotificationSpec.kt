package zip.arcanum.core.notifications

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Eject
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import zip.arcanum.R

/**
 * How loud a notification is. Drives the colour, and through [rank] decides who waits for
 * whom when two of them want the screen at the same time - see [NotificationCenter].
 */
enum class NotificationSeverity(val rank: Int) {
    /* SUCCESS and INFO share a rank on purpose: "3 files deleted" and "no vault is open"
     * are both quiet, and neither has any business cutting the other short. Only a more
     * serious class interrupts. */
    SUCCESS(0), INFO(0), WARNING(1), ERROR(2)
}

/**
 * How a notification leaves.
 *
 * [TRANSIENT] goes away on its own after [dwellMillis].
 * [STICKY] stays until the user dismisses it. Every error is sticky: "the vault is out of
 *   space" and "file renamed" used to share the same five seconds, and the one that matters
 *   is the one you are likelier to miss.
 * [ANNOUNCEMENT] is not about anything the user just did - a new version, a donation
 *   prompt. It never interrupts and never queues ahead of work.
 */
enum class NotificationBehaviour { TRANSIENT, STICKY, ANNOUNCEMENT }

/** Zero means "until dismissed". */
private const val UNTIL_DISMISSED = 0L

private val Green  = Color(0xFF16A34A)
private val Grey   = Color(0xFF6B7280)
private val Amber  = Color(0xFFD97706)
private val Red    = Color(0xFFDC2626)
private val Purple = Color(0xFF7C3AED)
private val Gold   = Color(0xFFF5B301)

/**
 * Severity is a property of the *instance*, not of the type: an export that lost part of a
 * file is an error, and the same class with nothing missing is a success. That is the whole
 * point of #170, and the reason this is a function rather than a field on the class.
 */
val InAppNotification.severity: NotificationSeverity
    get() = when (this) {
        is InAppNotification.VaultError,
        is InAppNotification.VaultAddError,
        is InAppNotification.VaultInvalidFile,
        is InAppNotification.ReadOnlyError,
        is InAppNotification.ImportFailed,
        is InAppNotification.FilesPasteFailed        -> NotificationSeverity.ERROR

        is InAppNotification.FilesExported           ->
            if (failed > 0) NotificationSeverity.ERROR
            else if (skipped > 0 || duplicates > 0) NotificationSeverity.WARNING
            else NotificationSeverity.SUCCESS

        is InAppNotification.FilesPasted             ->
            if (leftBehind > 0 || skipped > 0) NotificationSeverity.WARNING
            else NotificationSeverity.SUCCESS

        is InAppNotification.FilesMoved              ->
            if (leftBehind > 0 || skipped > 0) NotificationSeverity.WARNING
            else NotificationSeverity.SUCCESS

        is InAppNotification.FilesImported           ->
            if (skipped > 0) NotificationSeverity.WARNING else NotificationSeverity.SUCCESS

        is InAppNotification.VaultNeedsCheck,
        is InAppNotification.VaultAlreadyExists,
        is InAppNotification.UsbSafeToRemove,
        is InAppNotification.HiddenVolumeWriteProtection,
        is InAppNotification.OperationRefusedLocked  -> NotificationSeverity.WARNING

        /* Prompts, not failures. All three used to be red, which put "no vault is open" in
         * the same colour as "the write failed". */
        is InAppNotification.DetailsNeedMount,
        is InAppNotification.DisguiseAlreadyApplied,
        is InAppNotification.MountNeedsCredentials,
        is InAppNotification.FilesAlreadyHere,
        is InAppNotification.FilesLinked,
        is InAppNotification.SupportDeveloper,
        is InAppNotification.GoPremium,
        is InAppNotification.AppUpdated              -> NotificationSeverity.INFO

        else                                         -> NotificationSeverity.SUCCESS
    }

val InAppNotification.behaviour: NotificationBehaviour
    get() = when {
        this is InAppNotification.SupportDeveloper ||
        this is InAppNotification.GoPremium        ||
        this is InAppNotification.AppUpdated            -> NotificationBehaviour.ANNOUNCEMENT
        /* The drive is still plugged in until the user acts on this one, so it cannot be
         * allowed to time out unseen. */
        this is InAppNotification.UsbSafeToRemove       -> NotificationBehaviour.STICKY
        severity == NotificationSeverity.ERROR          -> NotificationBehaviour.STICKY
        else                                            -> NotificationBehaviour.TRANSIENT
    }

/** How long it stays if it leaves on its own. Zero means it waits to be dismissed. */
val InAppNotification.dwellMillis: Long
    get() = when {
        behaviour == NotificationBehaviour.STICKY       -> UNTIL_DISMISSED
        this is InAppNotification.AppUpdated            -> UNTIL_DISMISSED
        behaviour == NotificationBehaviour.ANNOUNCEMENT -> 8_000L
        severity == NotificationSeverity.WARNING        -> 6_000L
        severity == NotificationSeverity.INFO           -> 4_000L
        else                                            -> 3_000L
    }

/** Colour follows severity, except for the announcements, which are not about an operation. */
val InAppNotification.color: Color
    get() = when (this) {
        is InAppNotification.SupportDeveloper, is InAppNotification.AppUpdated -> Purple
        is InAppNotification.GoPremium                                        -> Gold
        else -> when (severity) {
            NotificationSeverity.SUCCESS -> Green
            NotificationSeverity.INFO    -> Grey
            NotificationSeverity.WARNING -> Amber
            NotificationSeverity.ERROR   -> Red
        }
    }

/** Everything the banner needs to draw one notification. */
data class NotificationSpec(
    val severity: NotificationSeverity,
    val behaviour: NotificationBehaviour,
    val dwellMillis: Long,
    val color: Color,
    val icon: ImageVector,
    val title: String,
    val subtitle: String
)

fun InAppNotification.spec(ctx: Context) = NotificationSpec(
    severity    = severity,
    behaviour   = behaviour,
    dwellMillis = dwellMillis,
    color       = color,
    icon        = icon,
    title       = title(ctx),
    subtitle    = subtitle(ctx)
)

private val InAppNotification.icon: ImageVector
    get() = when (this) {
        is InAppNotification.VaultNeedsCheck,
        is InAppNotification.VaultAlreadyExists,
        is InAppNotification.VaultInvalidFile,
        is InAppNotification.VaultAddError,
        is InAppNotification.VaultError,
        is InAppNotification.DetailsNeedMount,
        is InAppNotification.DisguiseAlreadyApplied,
        is InAppNotification.MountNeedsCredentials,
        is InAppNotification.OperationRefusedLocked,
        is InAppNotification.HiddenVolumeWriteProtection,
        is InAppNotification.ImportFailed,
        is InAppNotification.FilesPasteFailed        -> Icons.Outlined.Warning
        is InAppNotification.FilesExported           ->
            if (failed > 0) Icons.Outlined.Warning else Icons.Outlined.CheckCircle
        is InAppNotification.UsbSafeToRemove         -> Icons.Outlined.Eject
        is InAppNotification.VaultAdded              -> Icons.Outlined.FolderZip
        is InAppNotification.AddressCopied           -> Icons.Outlined.ContentCopy
        is InAppNotification.DisguiseAlreadyApplied  -> Icons.Outlined.Calculate
        is InAppNotification.FilesLinked             -> Icons.Outlined.Link
        is InAppNotification.FilesMoved              -> Icons.AutoMirrored.Outlined.DriveFileMove
        is InAppNotification.ReadOnlyError           -> Icons.Outlined.AutoStories
        is InAppNotification.AppUpdated              -> Icons.Outlined.NewReleases
        is InAppNotification.SupportDeveloper        -> Icons.Outlined.Favorite
        is InAppNotification.GoPremium               -> Icons.Outlined.Star
        else                                         -> Icons.Outlined.CheckCircle
    }

private fun InAppNotification.title(ctx: Context): String = when (this) {
    is InAppNotification.VaultNeedsCheck        -> ctx.getString(R.string.notif_vault_needs_check)
    is InAppNotification.UsbSafeToRemove        -> ctx.getString(R.string.notif_usb_safe_to_remove)
    is InAppNotification.DetailsNeedMount       -> ctx.getString(R.string.notif_details_need_mount)
    is InAppNotification.OperationRefusedLocked -> ctx.getString(R.string.notif_locked_refused)
    is InAppNotification.MountNeedsCredentials  -> ctx.getString(R.string.notif_mount_try_again)
    is InAppNotification.VaultError             -> ctx.getString(R.string.notif_vault_error)
    is InAppNotification.ExportSuccess          -> ctx.getString(R.string.notif_export_success)
    is InAppNotification.VaultAdded             -> ctx.getString(R.string.notif_vault_added)
    is InAppNotification.VaultAlreadyExists     -> ctx.getString(R.string.notif_vault_already_exists)
    is InAppNotification.VaultInvalidFile       -> ctx.getString(R.string.notif_vault_invalid_file)
    is InAppNotification.VaultAddError          -> ctx.getString(R.string.notif_vault_add_error)
    is InAppNotification.DateUpdated            -> ctx.getString(R.string.notif_date_updated)
    is InAppNotification.AddressCopied          -> ctx.getString(R.string.donations_copied, label)
    is InAppNotification.DisguiseAlreadyApplied -> ctx.getString(R.string.settings_security_disguise_toast)
    is InAppNotification.FileRenamed            -> ctx.getString(R.string.notif_file_renamed)
    is InAppNotification.FilesAlreadyHere       -> ctx.getString(R.string.notif_already_here)
    is InAppNotification.FolderCreated          -> ctx.getString(R.string.notif_folder_created)
    is InAppNotification.HiddenVolumeWriteProtection -> ctx.getString(R.string.notif_hidden_write_protection)
    is InAppNotification.ImportFailed           -> ctx.getString(R.string.notif_import_failed)
    is InAppNotification.ReadOnlyError          -> ctx.getString(R.string.notif_read_only_error)
    is InAppNotification.AppUpdated             -> ctx.getString(R.string.notif_app_updated)
    is InAppNotification.SupportDeveloper       -> ctx.getString(R.string.notif_support_developer)
    is InAppNotification.GoPremium              -> ctx.getString(R.string.notif_go_premium)
    is InAppNotification.FilesPasteFailed       -> ctx.getString(R.string.notif_paste_failed, failed, total)
    is InAppNotification.FilesPasted   -> ctx.resources.getQuantityString(R.plurals.notif_items_copied, count, count)
    is InAppNotification.FilesMoved    -> ctx.resources.getQuantityString(R.plurals.notif_items_moved, count, count)
    is InAppNotification.FilesDeleted  -> ctx.resources.getQuantityString(R.plurals.notif_items_deleted, count, count)
    is InAppNotification.FilesLinked   -> ctx.resources.getQuantityString(R.plurals.notif_items_linked, count, count)
    is InAppNotification.FilesImported -> ctx.resources.getQuantityString(R.plurals.notif_files_imported, count, count)
    is InAppNotification.FilesExported -> ctx.resources.getQuantityString(R.plurals.notif_files_exported, count, count)
}

private fun InAppNotification.subtitle(ctx: Context): String = when (this) {
    is InAppNotification.VaultNeedsCheck        -> ctx.getString(R.string.notif_vault_needs_check_body)
    is InAppNotification.UsbSafeToRemove        -> vaultName
    is InAppNotification.DetailsNeedMount       -> ctx.getString(R.string.notif_details_need_mount_body)
    is InAppNotification.OperationRefusedLocked -> ctx.getString(R.string.notif_locked_refused_body)
    is InAppNotification.MountNeedsCredentials  -> ctx.getString(R.string.notif_mount_try_again_body)
    is InAppNotification.VaultError             -> message
    is InAppNotification.ExportSuccess          -> fileName
    is InAppNotification.VaultAdded             -> ctx.getString(R.string.notif_vault_added_subtitle, fileName)
    is InAppNotification.VaultAlreadyExists     -> fileName
    is InAppNotification.VaultInvalidFile       -> ctx.getString(R.string.notif_vault_invalid_file_subtitle)
    is InAppNotification.VaultAddError          -> message
    is InAppNotification.DateUpdated            -> ctx.getString(R.string.notif_date_updated_subtitle)
    is InAppNotification.AddressCopied          -> ctx.getString(R.string.notif_address_copied_subtitle)
    is InAppNotification.DisguiseAlreadyApplied -> ctx.getString(R.string.notif_disguise_applied_subtitle)
    is InAppNotification.FileRenamed            -> newName
    is InAppNotification.FilesAlreadyHere       -> ctx.getString(R.string.notif_already_here_subtitle)
    is InAppNotification.FolderCreated          -> name
    is InAppNotification.FilesDeleted           -> ctx.getString(R.string.notif_files_deleted_subtitle)
    is InAppNotification.HiddenVolumeWriteProtection -> ctx.getString(R.string.notif_hidden_write_protection_subtitle)
    is InAppNotification.ReadOnlyError          -> ctx.getString(R.string.notif_read_only_error_subtitle)
    is InAppNotification.AppUpdated             -> ctx.getString(R.string.notif_app_updated_subtitle)
    is InAppNotification.SupportDeveloper       -> ctx.getString(R.string.notif_support_developer_subtitle)
    is InAppNotification.GoPremium              -> ctx.getString(R.string.notif_go_premium_subtitle)
    is InAppNotification.FilesPasteFailed       -> ctx.getString(R.string.notif_paste_failed_subtitle)

    is InAppNotification.FilesLinked -> ctx.getString(when (kind) {
        InAppNotification.LinkedKind.FILES   -> R.string.notif_linked_files
        InAppNotification.LinkedKind.FOLDERS -> R.string.notif_linked_folders
        InAppNotification.LinkedKind.MIXED   -> R.string.notif_linked_mixed
    })

    is InAppNotification.ImportFailed -> ctx.getString(when (reason) {
        ImportFailureReason.DIRECTORY_FULL -> R.string.notif_import_failed_dir_full
        ImportFailureReason.NO_SPACE       -> R.string.notif_import_failed_no_space
        ImportFailureReason.TOO_FRAGMENTED -> R.string.notif_import_failed_fragmented
        ImportFailureReason.READ_ONLY      -> R.string.notif_import_failed_read_only
        ImportFailureReason.UNKNOWN        -> R.string.notif_import_failed_unknown
    })

    /* An item that could not travel outranks one the user chose to leave: the first is
     * news, the second they already know. */
    is InAppNotification.FilesPasted -> when {
        leftBehind > 0 -> ctx.resources.getQuantityString(R.plurals.notif_items_left_behind, leftBehind, leftBehind)
        skipped > 0    -> ctx.resources.getQuantityString(R.plurals.notif_files_skipped, skipped, skipped)
        else           -> ctx.getString(R.string.notif_files_pasted_subtitle)
    }

    /* What stayed behind outranks where the rest went: the destination is on screen anyway. */
    is InAppNotification.FilesMoved -> when {
        leftBehind > 0 -> ctx.resources.getQuantityString(R.plurals.notif_items_left_behind, leftBehind, leftBehind)
        skipped > 0    -> ctx.resources.getQuantityString(R.plurals.notif_files_skipped, skipped, skipped)
        else           -> ctx.getString(R.string.notif_files_moved_subtitle, destinationName)
    }

    /* When names were left alone, say how many rather than reporting only what landed: 40 out
     * of 112 is otherwise indistinguishable from a failure. */
    is InAppNotification.FilesImported ->
        if (skipped > 0) ctx.resources.getQuantityString(R.plurals.notif_files_skipped, skipped, skipped)
        else ctx.getString(R.string.notif_files_imported_subtitle)

    /* One line, so when both are true the skipped items come first: something the user picked
     * is not out there at all, which is worth more than knowing something went out twice. */
    is InAppNotification.FilesExported -> when {
        failed > 0 -> ctx.resources.getQuantityString(R.plurals.notif_files_exported_failed, failed, failed)
        skipped > 0 && duplicates > 0 ->
            ctx.getString(R.string.notif_files_exported_skipped_and_copies, skipped, duplicates)
        skipped > 0    -> ctx.resources.getQuantityString(R.plurals.notif_files_exported_skipped, skipped, skipped)
        duplicates > 0 -> ctx.resources.getQuantityString(R.plurals.notif_files_exported_copies, duplicates, duplicates)
        else           -> ctx.getString(R.string.notif_files_exported_subtitle)
    }
}
