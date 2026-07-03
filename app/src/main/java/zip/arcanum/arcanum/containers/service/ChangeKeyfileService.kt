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
import zip.arcanum.core.security.VaultPasswordPolicy
import zip.arcanum.core.utils.FileUtils
import zip.arcanum.crypto.CryptoResult
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject

@AndroidEntryPoint
class ChangeKeyfileService : Service() {

    @Inject lateinit var cryptoEngine: VeraCryptEngine
    @Inject lateinit var changeKeyfileParams: ChangeKeyfileParams

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    sealed class State {
        object Idle    : State()
        object Running : State()
        object Success : State()
        data class Failure(val error: String) : State()
    }

    companion object {
        const val CHANNEL_ID      = "change_keyfile"
        const val NOTIFICATION_ID = 1004

        private val _state = MutableStateFlow<State>(State.Idle)
        val state: StateFlow<State> = _state.asStateFlow()

        fun reset() { _state.value = State.Idle }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = changeKeyfileParams.take() ?: return START_NOT_STICKY

        _state.value = State.Running
        startForeground(NOTIFICATION_ID, buildNotification())

        serviceScope.launch {
            try {
                if (!VaultPasswordPolicy.isWithinVeraCryptLimit(p.password)) {
                    _state.value = State.Failure(VaultPasswordPolicy.violationMessage())
                    stopSelf()
                    return@launch
                }
                val result = if (p.safFd >= 0) {
                    cryptoEngine.changeKeyfileFd(
                        fd               = p.safFd,
                        password         = p.password,
                        oldKeyfilePaths  = p.oldKeyfilePaths,
                        pim              = p.pim,
                        newKeyfilePaths  = p.newKeyfilePaths,
                        newHashAlgorithm = p.newHashAlgorithm,
                        extraEntropy     = p.extraEntropy
                    )
                } else {
                    cryptoEngine.changeKeyfile(
                        path             = p.path,
                        password         = p.password,
                        oldKeyfilePaths  = p.oldKeyfilePaths,
                        pim              = p.pim,
                        newKeyfilePaths  = p.newKeyfilePaths,
                        newHashAlgorithm = p.newHashAlgorithm,
                        extraEntropy     = p.extraEntropy
                    )
                }

                _state.value = when (result) {
                    is CryptoResult.Success -> State.Success
                    is CryptoResult.Failure -> State.Failure(result.error.name)
                }
                stopSelf()
            } catch (e: CancellationException) {
                _state.value = State.Failure("CANCELLED")
                throw e
            } finally {
                p.safPfd?.close()
                p.oldKeyfilePaths.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
                p.newKeyfilePaths.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
                p.extraEntropy.fill(0)
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
                getString(R.string.notif_channel_change_keyfile),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_change_keyfile_desc)
                lockscreenVisibility = Notification.VISIBILITY_SECRET
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_changing_keyfile))
            .setContentText(getString(R.string.notif_changing_keyfile_desc))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .build()
}
