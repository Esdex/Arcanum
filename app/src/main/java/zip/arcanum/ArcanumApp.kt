package zip.arcanum

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import zip.arcanum.R
import zip.arcanum.arcanum.containers.service.ContainerCreationService
import zip.arcanum.crypto.NativeCrashHandler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@HiltAndroidApp
class ArcanumApp : Application() {

    @Inject lateinit var workerFactory: HiltWorkerFactory

    override fun onCreate() {
        super.onCreate()
        setupCrashCapture()
        WorkManager.initialize(
            this,
            Configuration.Builder()
                .setWorkerFactory(workerFactory)
                .build()
        )
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

    // Capture crashes to a file so they can be shown and shared from the debug screen. Native
    // crashes are the reason this exists (see NativeCrashHandler); the Java handler is a cheap
    // add-on that also chains to the platform's default so the normal crash still happens.
    private fun setupCrashCapture() {
        val dir = File(filesDir, CRASH_DIR_NAME).apply { mkdirs() }
        val meta = "app ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) | " +
            "abi ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"} | sdk ${Build.VERSION.SDK_INT} | " +
            "${Build.MANUFACTURER} ${Build.MODEL}"

        runCatching {
            NativeCrashHandler.install(File(dir, NATIVE_CRASH_FILE).absolutePath, meta)
        }

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                File(dir, JAVA_CRASH_FILE).writeText(
                    buildString {
                        appendLine("=== Arcanum crash ===")
                        appendLine(meta)
                        appendLine("time: $ts")
                        appendLine("thread: ${thread.name}")
                        appendLine()
                        append(android.util.Log.getStackTraceString(throwable))
                    }
                )
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        const val CRASH_DIR_NAME    = "crash"
        const val NATIVE_CRASH_FILE = "native_crash.txt"
        const val JAVA_CRASH_FILE   = "java_crash.txt"
    }
}
