package zip.arcanum.core.notifications

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.AutoStories
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.NewReleases
import androidx.compose.material.icons.outlined.NoEncryption
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import android.content.Context
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import zip.arcanum.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

private data class NotificationDisplayConfig(
    val backgroundColor: Color,
    val icon: ImageVector,
    val title: String,
    val subtitle: String
)

@Composable
fun InAppNotificationBanner(
    notification: InAppNotification?,
    onDismiss: () -> Unit,
    onAction: (InAppNotification) -> Unit,
    modifier: Modifier = Modifier
) {
    val visible = notification != null && notification !is InAppNotification.PanicExecuted

    LaunchedEffect(notification) {
        if (notification != null && !notification.persistent) {
            delay(5_000)
            onDismiss()
        }
    }

    val context = LocalContext.current

    AnimatedVisibility(
        visible  = visible,
        enter    = slideInVertically(initialOffsetY = { -it }, animationSpec = tween(400)) + fadeIn(tween(300)),
        exit     = slideOutHorizontally(targetOffsetX = { it }, animationSpec = tween(350)) + fadeOut(tween(250)),
        modifier = modifier
    ) {
        val notif = notification ?: return@AnimatedVisibility
        val config = notif.toDisplayConfig(context) ?: return@AnimatedVisibility

        var offsetX by remember { mutableFloatStateOf(0f) }
        val animatedOffset by animateFloatAsState(
            targetValue   = offsetX,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label         = "banner_swipe"
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .pointerInput(notif) {
                    detectHorizontalDragGestures(
                        onDragEnd        = { if (abs(offsetX) > 150f) onDismiss() else offsetX = 0f },
                        onHorizontalDrag = { _, dragAmount -> offsetX += dragAmount }
                    )
                }
        ) {
            Card(
                onClick   = { onAction(notif) },
                shape     = RoundedCornerShape(16.dp),
                colors    = CardDefaults.cardColors(containerColor = config.backgroundColor),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier  = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier              = Modifier.padding(12.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier         = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            imageVector        = config.icon,
                            contentDescription = null,
                            tint               = Color.White,
                            modifier           = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text       = config.title,
                            style      = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color      = Color.White
                        )
                        Text(
                            text      = config.subtitle,
                            style     = MaterialTheme.typography.bodySmall,
                            color     = Color.White.copy(alpha = 0.85f),
                            maxLines  = 1,
                            overflow  = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

private fun InAppNotification.toDisplayConfig(ctx: Context): NotificationDisplayConfig? = when (this) {
    is InAppNotification.VaultMounted -> NotificationDisplayConfig(
        backgroundColor = Color(0xFF16A34A),
        icon            = Icons.Outlined.LockOpen,
        title           = ctx.getString(R.string.notif_vault_mounted),
        subtitle        = vaultName
    )
    is InAppNotification.VaultUnmounted -> NotificationDisplayConfig(
        backgroundColor = Color(0xFF2563EB),
        icon            = Icons.Outlined.NoEncryption,
        title           = ctx.getString(R.string.notif_vault_unmounted),
        subtitle        = vaultName
    )
    is InAppNotification.VaultError -> NotificationDisplayConfig(
        backgroundColor = Color(0xFFDC2626),
        icon            = Icons.Outlined.Warning,
        title           = ctx.getString(R.string.notif_vault_error),
        subtitle        = message
    )
    is InAppNotification.ExportSuccess -> NotificationDisplayConfig(
        backgroundColor = Color(0xFF16A34A),
        icon            = Icons.Outlined.CheckCircle,
        title           = ctx.getString(R.string.notif_export_success),
        subtitle        = fileName
    )
    is InAppNotification.VaultAdded -> NotificationDisplayConfig(
        backgroundColor = Color(0xFF16A34A),
        icon            = Icons.Outlined.FolderZip,
        title           = ctx.getString(R.string.notif_vault_added),
        subtitle        = ctx.getString(R.string.notif_vault_added_subtitle, this.fileName)
    )
    is InAppNotification.VaultAlreadyExists -> NotificationDisplayConfig(
        backgroundColor = Color(0xFFD97706),
        icon            = Icons.Outlined.Warning,
        title           = ctx.getString(R.string.notif_vault_already_exists),
        subtitle        = this.fileName
    )
    InAppNotification.VaultInvalidFile -> NotificationDisplayConfig(
        backgroundColor = Color(0xFFDC2626),
        icon            = Icons.Outlined.Warning,
        title           = ctx.getString(R.string.notif_vault_invalid_file),
        subtitle        = ctx.getString(R.string.notif_vault_invalid_file_subtitle)
    )
    is InAppNotification.VaultAddError -> NotificationDisplayConfig(
        backgroundColor = Color(0xFFDC2626),
        icon            = Icons.Outlined.Warning,
        title           = ctx.getString(R.string.notif_vault_add_error),
        subtitle        = this.message
    )
    InAppNotification.DateUpdated -> NotificationDisplayConfig(
        backgroundColor = Color(0xFF16A34A),
        icon            = Icons.Outlined.CheckCircle,
        title           = ctx.getString(R.string.notif_date_updated),
        subtitle        = ctx.getString(R.string.notif_date_updated_subtitle)
    )
    is InAppNotification.FileRenamed -> NotificationDisplayConfig(
        backgroundColor = Color(0xFF16A34A),
        icon            = Icons.Outlined.CheckCircle,
        title           = ctx.getString(R.string.notif_file_renamed),
        subtitle        = this.newName
    )
    is InAppNotification.FilesPasted -> NotificationDisplayConfig(
        backgroundColor = Color(0xFF16A34A),
        icon            = Icons.Outlined.CheckCircle,
        title           = ctx.resources.getQuantityString(R.plurals.notif_items_copied, this.count, this.count),
        subtitle        = ctx.getString(R.string.notif_files_pasted_subtitle)
    )
    is InAppNotification.FilesMoved -> NotificationDisplayConfig(
        backgroundColor = Color(0xFF16A34A),
        icon            = Icons.AutoMirrored.Outlined.DriveFileMove,
        title           = ctx.resources.getQuantityString(R.plurals.notif_items_moved, this.count, this.count),
        subtitle        = ctx.getString(R.string.notif_files_moved_subtitle, this.destinationName)
    )
    is InAppNotification.FilesDeleted -> NotificationDisplayConfig(
        backgroundColor = Color(0xFF6B7280),
        icon            = Icons.Outlined.CheckCircle,
        title           = ctx.resources.getQuantityString(R.plurals.notif_items_deleted, this.count, this.count),
        subtitle        = ctx.getString(R.string.notif_files_deleted_subtitle)
    )
    is InAppNotification.FolderCreated -> NotificationDisplayConfig(
        backgroundColor = Color(0xFF16A34A),
        icon            = Icons.Outlined.CheckCircle,
        title           = ctx.getString(R.string.notif_folder_created),
        subtitle        = this.name
    )
    is InAppNotification.FilesImported -> NotificationDisplayConfig(
        backgroundColor = Color(0xFF16A34A),
        icon            = Icons.Outlined.CheckCircle,
        title           = ctx.resources.getQuantityString(R.plurals.notif_files_imported, this.count, this.count),
        subtitle        = ctx.getString(R.string.notif_files_imported_subtitle)
    )
    is InAppNotification.FilesExported -> NotificationDisplayConfig(
        backgroundColor = Color(0xFF16A34A),
        icon            = Icons.Outlined.CheckCircle,
        title           = ctx.resources.getQuantityString(R.plurals.notif_files_exported, this.count, this.count),
        subtitle        = ctx.getString(R.string.notif_files_exported_subtitle)
    )
    InAppNotification.HiddenVolumeWriteProtection -> NotificationDisplayConfig(
        backgroundColor = Color(0xFFDC2626),
        icon            = Icons.Outlined.Warning,
        title           = ctx.getString(R.string.notif_hidden_write_protection),
        subtitle        = ctx.getString(R.string.notif_hidden_write_protection_subtitle)
    )
    is InAppNotification.ImportFailed -> NotificationDisplayConfig(
        backgroundColor = Color(0xFFDC2626),
        icon            = Icons.Outlined.Warning,
        title           = ctx.getString(R.string.notif_import_failed),
        subtitle        = ctx.getString(
            when (this.reason) {
                ImportFailureReason.DIRECTORY_FULL -> R.string.notif_import_failed_dir_full
                ImportFailureReason.NO_SPACE       -> R.string.notif_import_failed_no_space
                ImportFailureReason.READ_ONLY      -> R.string.notif_import_failed_read_only
                ImportFailureReason.UNKNOWN        -> R.string.notif_import_failed_unknown
            }
        )
    )
    InAppNotification.ReadOnlyError -> NotificationDisplayConfig(
        backgroundColor = Color(0xFFDC2626),
        icon            = Icons.Outlined.AutoStories,
        title           = ctx.getString(R.string.notif_read_only_error),
        subtitle        = ctx.getString(R.string.notif_read_only_error_subtitle)
    )
    InAppNotification.AppUpdated -> NotificationDisplayConfig(
        backgroundColor = Color(0xFF7C3AED),
        icon            = Icons.Outlined.NewReleases,
        title           = ctx.getString(R.string.notif_app_updated),
        subtitle        = ctx.getString(R.string.notif_app_updated_subtitle)
    )
    InAppNotification.PanicExecuted -> null
    InAppNotification.SupportDeveloper -> NotificationDisplayConfig(
        backgroundColor = Color(0xFF7C3AED),
        icon            = Icons.Outlined.Favorite,
        title           = ctx.getString(R.string.notif_support_developer),
        subtitle        = ctx.getString(R.string.notif_support_developer_subtitle)
    )
}
