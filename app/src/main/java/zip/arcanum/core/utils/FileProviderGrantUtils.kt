package zip.arcanum.core.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper

object FileProviderGrantUtils {
    private const val REVOKE_DELAY_MS = 5 * 60 * 1000L
    private val activeGrantUris = linkedSetOf<String>()

    fun startReadOnlyIntent(
        context: Context,
        intent: Intent,
        uris: List<Uri>,
        chooserTitle: String? = null
    ): Boolean {
        if (uris.isEmpty()) return false
        val uniqueUris = uris.distinct()
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val launchIntent = if (chooserTitle != null) {
            Intent.createChooser(intent, chooserTitle).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            intent
        }

        return runCatching {
            context.startActivity(launchIntent)
        }.onSuccess {
            rememberGrants(uniqueUris)
            scheduleRevoke(context.applicationContext, uniqueUris)
        }.onFailure {
            revokeReadPermissions(context.applicationContext, uniqueUris)
        }.isSuccess
    }

    fun revokeActiveGrants(context: Context) {
        val uris = synchronized(activeGrantUris) {
            activeGrantUris.map(Uri::parse)
        }
        revokeReadPermissions(context.applicationContext, uris)
    }

    fun revokeReadPermissions(context: Context, uris: List<Uri>) {
        if (uris.isEmpty()) return
        uris.distinct().forEach { uri ->
            runCatching {
                context.revokeUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        synchronized(activeGrantUris) {
            uris.forEach { uri -> activeGrantUris.remove(uri.toString()) }
        }
    }

    private fun rememberGrants(uris: List<Uri>) {
        synchronized(activeGrantUris) {
            uris.forEach { uri -> activeGrantUris.add(uri.toString()) }
        }
    }

    private fun scheduleRevoke(context: Context, uris: List<Uri>) {
        Handler(Looper.getMainLooper()).postDelayed({
            revokeReadPermissions(context, uris)
        }, REVOKE_DELAY_MS)
    }
}
