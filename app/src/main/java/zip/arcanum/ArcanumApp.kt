package zip.arcanum

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import zip.arcanum.R
import zip.arcanum.arcanum.containers.service.ContainerCreationService

@HiltAndroidApp
class ArcanumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                ContainerCreationService.CHANNEL_ID,
                getString(R.string.notif_channel_vault_creation),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notif_channel_vault_creation_desc) }
        )
    }
}
