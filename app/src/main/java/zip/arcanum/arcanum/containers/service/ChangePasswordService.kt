package zip.arcanum.arcanum.containers.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import zip.arcanum.R
import zip.arcanum.core.utils.FileUtils
import zip.arcanum.crypto.CryptoResult
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject

@AndroidEntryPoint
class ChangePasswordService : Service() {

    @Inject lateinit var cryptoEngine: VeraCryptEngine
    @Inject lateinit var changePasswordParams: ChangePasswordParams

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    sealed class State {
        object Idle    : State()
        object Running : State()
        object Success : State()
        data class Failure(val error: String) : State()
    }

    companion object {
        const val CHANNEL_ID      = "change_password"
        const val NOTIFICATION_ID = 1003

        private val _state = MutableStateFlow<State>(State.Idle)
        val state: StateFlow<State> = _state.asStateFlow()

        fun reset() { _state.value = State.Idle }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = changePasswordParams.take() ?: return START_NOT_STICKY

        _state.value = State.Running
        startForeground(NOTIFICATION_ID, buildNotification())

        serviceScope.launch {
            try {
                val result = try {
                    if (p.safFd >= 0) {
                        cryptoEngine.changePasswordFd(
                            fd               = p.safFd,
                            oldPassword      = p.oldPassword,
                            oldKeyfilePaths  = p.oldKeyfilePaths,
                            oldPim           = p.oldPim,
                            newPassword      = p.newPassword,
                            newKeyfilePaths  = p.newKeyfilePaths,
                            newHashAlgorithm = p.newHashAlgorithm,
                            newPim           = p.newPim,
                            wipePassCount    = p.wipePassCount
                        )
                    } else {
                        cryptoEngine.changePassword(
                            path             = p.path,
                            oldPassword      = p.oldPassword,
                            oldKeyfilePaths  = p.oldKeyfilePaths,
                            oldPim           = p.oldPim,
                            newPassword      = p.newPassword,
                            newKeyfilePaths  = p.newKeyfilePaths,
                            newHashAlgorithm = p.newHashAlgorithm,
                            newPim           = p.newPim,
                            wipePassCount    = p.wipePassCount
                        )
                    }
                } finally {
                    p.safPfd?.close()
                    p.oldKeyfilePaths.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
                    p.newKeyfilePaths.forEach { FileUtils.secureZeroAndDelete(java.io.File(it)) }
                }

                _state.value = when (result) {
                    is CryptoResult.Success -> State.Success
                    is CryptoResult.Failure -> State.Failure(result.error.name)
                }
                stopSelf()
            } catch (e: CancellationException) {
                // Service scope cancelled (e.g. onDestroy) — mark failure so collector doesn't
                // get stuck in Running state, then re-throw so the coroutine actually cancels.
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
                getString(R.string.notif_channel_change_password),
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = getString(R.string.notif_channel_change_password_desc) }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_changing_password))
            .setContentText(getString(R.string.notif_changing_password_desc))
            .setProgress(0, 0, true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
}
