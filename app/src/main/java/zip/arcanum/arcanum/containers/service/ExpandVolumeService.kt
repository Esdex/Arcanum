package zip.arcanum.arcanum.containers.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import zip.arcanum.R
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.core.utils.FileUtils
import zip.arcanum.crypto.CryptoResult
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject

@AndroidEntryPoint
class ExpandVolumeService : Service() {

    @Inject lateinit var cryptoEngine: VeraCryptEngine
    @Inject lateinit var expandVolumeParams: ExpandVolumeParams
    @Inject lateinit var repo: ContainerRepository  // M4: DB update bound to service lifecycle

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    sealed class State {
        object Idle    : State()
        data class Running(val progress: Float = 0f, val speedMbps: Float = 0f) : State()
        object Success : State()
        data class Failure(val error: String) : State()
    }

    companion object {
        const val CHANNEL_ID      = "expand_volume"
        const val NOTIFICATION_ID = 1005

        private val _state = MutableStateFlow<State>(State.Idle)
        val state: StateFlow<State> = _state.asStateFlow()

        fun reset() { _state.value = State.Idle }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // L1: if params were already consumed (service restarted by OS), stop gracefully
        val p = expandVolumeParams.take() ?: run {
            _state.value = State.Idle
            stopSelf(startId)
            return START_NOT_STICKY
        }

        _state.value = State.Running()
        startForeground(NOTIFICATION_ID, buildNotification(0f))

        serviceScope.launch {
            try {
                val listener = object : VeraCryptEngine.CreationProgressListener {
                    override fun onProgress(progressFraction: Float, speedMbps: Float, bytesWritten: Long) {
                        _state.value = State.Running(progressFraction, speedMbps)
                        updateNotification(progressFraction)
                    }
                }

                val result = try {
                    if (p.safFd >= 0) {
                        cryptoEngine.expandVolumeFd(
                            fd               = p.safFd,
                            password         = p.password,
                            keyfilePaths     = p.keyfilePaths,
                            pim              = p.pim,
                            newSizeBytes     = p.newSizeBytes,
                            progressListener = listener
                        )
                    } else {
                        cryptoEngine.expandVolume(
                            path             = p.path,
                            password         = p.password,
                            keyfilePaths     = p.keyfilePaths,
                            pim              = p.pim,
                            newSizeBytes     = p.newSizeBytes,
                            progressListener = listener
                        )
                    }
                } finally {
                    p.safPfd?.close()
                    p.keyfilePaths.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
                }

                // M4: persist new size from within the service's own scope so it survives ViewModel teardown
                _state.value = when (result) {
                    is CryptoResult.Success -> {
                        repo.updateSize(p.containerId, p.newSizeBytes - 262144L)
                        State.Success
                    }
                    is CryptoResult.Failure -> State.Failure(result.error.name)
                }
                stopSelf()
            } catch (e: CancellationException) {
                _state.value = State.Failure("CANCELLED")
                throw e
            }
        }

        return START_NOT_STICKY
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_expand_volume),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notif_channel_expand_volume_desc) }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(progress: Float): Notification {
        val pct = (progress * 100).toInt()
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_expanding_volume))
            .setContentText(
                if (progress > 0f) getString(R.string.notif_expanding_volume_pct, pct)
                else getString(R.string.notif_expanding_volume_desc)
            )
            .setProgress(100, pct, progress == 0f)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(progress: Float) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(progress))
    }
}
