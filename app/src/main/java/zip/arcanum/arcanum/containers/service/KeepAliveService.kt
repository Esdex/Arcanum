package zip.arcanum.arcanum.containers.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import zip.arcanum.MainActivity
import zip.arcanum.R

/**
 * Holds the app in memory while a vault is open (#102).
 *
 * A mount lives entirely inside this process - the JNI handles and the descriptor behind them
 * - so when Android reclaims memory by killing a backgrounded app, the vault closes with it.
 * A foreground service is the only thing that moves the process out of the bucket the killer
 * takes from first. It is not a guarantee and is not sold as one: a phone under real pressure,
 * or a manufacturer's own killer, still wins.
 *
 * The service holds nothing itself. It has no handles, no coroutines and no state beyond the
 * notification: what it does is exist, and existing is the whole feature. Everything about
 * when it should exist is [zip.arcanum.core.security.LockController]'s, which starts it when
 * a vault is mounted with the setting on and stops it when the last one closes.
 *
 * **Type.** `specialUse`, which is what a service that keeps a process alive honestly is.
 * `dataSync` - the type the other three services here use - is capped at six hours a day
 * since Android 15 and stopped by the system after that, which is precisely what this must
 * not do.
 *
 * **The notification.** The app declares no POST_NOTIFICATIONS permission, so on Android 13
 * and later nothing of this is shown at all; on 12 and earlier it is, and it wears whatever
 * face the app is wearing - a calculator while the disguise is on. It never names a vault,
 * for the same reason the media session does not ([zip.arcanum.core.security.AppPreferences]).
 */
class KeepAliveService : Service() {

    companion object {
        const val CHANNEL_ID      = "vault_keepalive"
        const val NOTIFICATION_ID = 1004
        /** Set by the controller from the calculator-disguise preference. */
        const val EXTRA_DISGUISED = "disguised"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val disguised = intent?.getBooleanExtra(EXTRA_DISGUISED, false) ?: false
        createChannel()
        // No type argument: the platform takes the types this service declares in the
        // manifest, which keeps one declaration rather than two that can disagree.
        startForeground(NOTIFICATION_ID, buildNotification(disguised))
        // Not sticky. A process that was killed anyway has no mount left to protect, and a
        // service restarted into that state would be a notification with nothing behind it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    /**
     * Android 15 can time a foreground service out. `specialUse` is not one of the types it
     * does that to today, but the callback costs three lines and the alternative to handling
     * it is an ANR: if the system ever says stop, stop. The vault is not touched - it goes
     * back to closing with the process, which is where it was before this feature.
     */
    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_keepalive),
                    // The lowest importance a foreground service may have: no sound, no
                    // heads-up, and collapsed into the silent section of the shade.
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    description = getString(R.string.notif_channel_keepalive_desc)
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(disguised: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(
                if (disguised) R.drawable.ic_launcher_calc_mono else R.drawable.ic_notification
            )
            .setContentTitle(
                getString(if (disguised) R.string.app_name_calculator else R.string.notif_keepalive_title)
            )
            .setContentText(
                getString(if (disguised) R.string.notif_keepalive_disguised_text
                          else R.string.notif_keepalive_text)
            )
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }
}
