package zip.arcanum.arcanum.share

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * Controls whether Arcanum appears as a target in the Android Share sheet.
 *
 * A static ACTION_SEND intent-filter would put the app in *every* share sheet permanently, which
 * would leak the disguise. Instead the receiver is a disabled-by-default `<activity-alias>` that
 * this toggles on/off via the package manager, gated behind the "Receive shared files" setting.
 */
object ShareReceiver {

    private fun aliasComponent(context: Context) =
        ComponentName(context, context.packageName + ".ShareReceiverAlias")

    fun setEnabled(context: Context, enabled: Boolean) {
        val state = if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        runCatching {
            context.packageManager.setComponentEnabledSetting(
                aliasComponent(context), state, PackageManager.DONT_KILL_APP
            )
        }
    }

    /** Pulls the shared content URIs out of an ACTION_SEND / ACTION_SEND_MULTIPLE intent. */
    fun extractSharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND ->
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.let { listOf(it) } ?: emptyList()
        Intent.ACTION_SEND_MULTIPLE ->
            IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                ?.filterNotNull() ?: emptyList()
        else -> emptyList()
    }
}
