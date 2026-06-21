package zip.arcanum.arcanum.containers.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import zip.arcanum.R
import zip.arcanum.core.utils.FileUtils
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class ContainerCreationService : Service() {

    @Inject lateinit var cryptoEngine: VeraCryptEngine
    @Inject lateinit var creationParams: ContainerCreationParams

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    data class CreationProgress(
        val fraction: Float     = 0f,
        val speedMbps: Float    = 0f,
        val bytesWritten: Long  = 0L,
        val totalBytes: Long    = 0L,
        val isComplete: Boolean = false,
        val error: String?      = null
    )

    companion object {
        const val CHANNEL_ID      = "container_creation"
        const val NOTIFICATION_ID = 1001

        private val _progress = MutableStateFlow<CreationProgress?>(null)
        val progress: StateFlow<CreationProgress?> = _progress.asStateFlow()

        fun resetProgress() { _progress.value = null }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = creationParams.take() ?: return START_NOT_STICKY

        _progress.value = CreationProgress(totalBytes = p.sizeBytes)
        startForeground(NOTIFICATION_ID, buildNotification(0f))

        serviceScope.launch {
            val listener = object : VeraCryptEngine.CreationProgressListener {
                override fun onProgress(progressFraction: Float, speedMbps: Float, bytesWritten: Long) {
                    _progress.value = CreationProgress(
                        fraction     = progressFraction,
                        speedMbps    = speedMbps,
                        bytesWritten = bytesWritten,
                        totalBytes   = p.sizeBytes
                    )
                    updateNotification(progressFraction)
                }
            }

            val result = try {
                if (p.safFd >= 0) {
                    cryptoEngine.createContainerFd(
                        fd               = p.safFd,
                        sizeBytes        = p.sizeBytes,
                        password         = p.password,
                        algorithm        = p.algorithm,
                        hashAlgorithm    = p.hashAlgorithm,
                        filesystem       = p.filesystem,
                        quickFormat      = p.quickFormat,
                        entropyBytes     = p.entropyBytes,
                        keyfilePaths     = p.keyfilePaths,
                        progressListener = listener,
                        pim              = p.pim
                    )
                } else {
                    cryptoEngine.createContainer(
                        path             = p.path,
                        sizeBytes        = p.sizeBytes,
                        password         = p.password,
                        algorithm        = p.algorithm,
                        hashAlgorithm    = p.hashAlgorithm,
                        filesystem       = p.filesystem,
                        quickFormat      = p.quickFormat,
                        entropyBytes     = p.entropyBytes,
                        keyfilePaths     = p.keyfilePaths,
                        progressListener = listener,
                        pim              = p.pim
                    )
                }
            } finally {
                // Always release resources regardless of success, failure, or cancellation
                p.safPfd?.close()
                p.keyfilePaths.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
            }

            if (result is zip.arcanum.crypto.CryptoResult.Success) {
                _progress.value = CreationProgress(
                    fraction     = 1f,
                    isComplete   = true,
                    bytesWritten = p.sizeBytes,
                    totalBytes   = p.sizeBytes
                )
            } else {
                val err = (result as? zip.arcanum.crypto.CryptoResult.Failure)?.error?.name
                _progress.value = CreationProgress(
                    fraction   = 0f,
                    isComplete = true,
                    error      = err ?: "Unknown error"
                )
            }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notifications ──────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_vault_creation),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notif_channel_vault_creation_desc) }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(fraction: Float): Notification {
        val pct = (fraction * 100).roundToInt()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_creating_vault))
            .setContentText(getString(R.string.notif_creation_progress, pct))
            .setProgress(100, pct, pct == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(fraction: Float) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(fraction))
    }
}
