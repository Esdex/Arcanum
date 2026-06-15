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
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class ContainerCreationService : Service() {

    @Inject lateinit var cryptoEngine: VeraCryptEngine

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

        const val EXTRA_PATH         = "path"
        const val EXTRA_SIZE_BYTES   = "sizeBytes"
        const val EXTRA_PASSWORD     = "password"
        const val EXTRA_ALGORITHM    = "algorithm"
        const val EXTRA_HASH_ALG     = "hashAlgorithm"
        const val EXTRA_FILESYSTEM   = "filesystem"
        const val EXTRA_QUICK_FORMAT = "quickFormat"
        const val EXTRA_ENTROPY      = "entropy"
        const val EXTRA_KEYFILE_PATHS  = "keyfilePaths"
        const val EXTRA_PIM            = "pim"

        private val _progress = MutableStateFlow<CreationProgress?>(null)
        val progress: StateFlow<CreationProgress?> = _progress.asStateFlow()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val path        = intent?.getStringExtra(EXTRA_PATH)       ?: return START_NOT_STICKY
        val sizeBytes   = intent.getLongExtra(EXTRA_SIZE_BYTES, 0L)
        val password    = intent.getStringExtra(EXTRA_PASSWORD)    ?: return START_NOT_STICKY
        val algorithm   = intent.getIntExtra(EXTRA_ALGORITHM, 0)
        val hashAlg     = intent.getIntExtra(EXTRA_HASH_ALG, 0)
        val filesystem  = intent.getIntExtra(EXTRA_FILESYSTEM, 0)
        val quick        = intent.getBooleanExtra(EXTRA_QUICK_FORMAT, true)
        val entropy      = intent.getByteArrayExtra(EXTRA_ENTROPY) ?: ByteArray(0)
        val pim          = intent.getIntExtra(EXTRA_PIM, 0)
        val keyfilePaths = intent.getStringArrayListExtra(EXTRA_KEYFILE_PATHS) ?: arrayListOf()

        _progress.value = CreationProgress(totalBytes = sizeBytes)
        startForeground(NOTIFICATION_ID, buildNotification(0f))

        serviceScope.launch {
            val listener = object : VeraCryptEngine.CreationProgressListener {
                override fun onProgress(progressFraction: Float, speedMbps: Float, bytesWritten: Long) {
                    _progress.value = CreationProgress(
                        fraction     = progressFraction,
                        speedMbps    = speedMbps,
                        bytesWritten = bytesWritten,
                        totalBytes   = sizeBytes
                    )
                    updateNotification(progressFraction)
                }
            }

            val result = cryptoEngine.createContainer(
                path             = path,
                sizeBytes        = sizeBytes,
                password         = password,
                algorithm        = algorithm,
                hashAlgorithm    = hashAlg,
                filesystem       = filesystem,
                quickFormat      = quick,
                entropyBytes     = entropy,
                keyfilePaths     = keyfilePaths.toList(),
                progressListener = listener,
                pim              = pim
            )

            // Delete temp keyfile cache after use regardless of result
            keyfilePaths.forEach { java.io.File(it).delete() }

            if (result is zip.arcanum.crypto.CryptoResult.Success) {
                _progress.value = CreationProgress(
                    fraction     = 1f,
                    isComplete   = true,
                    bytesWritten = sizeBytes,
                    totalBytes   = sizeBytes
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
