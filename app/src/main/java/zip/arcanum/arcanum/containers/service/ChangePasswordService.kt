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
    @Inject lateinit var usbVolumes: zip.arcanum.usb.UsbVolumeManager
    @Inject lateinit var containerRepo: zip.arcanum.arcanum.containers.data.ContainerRepository

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
                // CryptoError has no value meaning "wrong drive" or "no drive", and
                // reporting those as IO_ERROR tells the user nothing they can act on -
                // the exact complaint behind #114. The reason is carried alongside.
                var usbError: String? = null
                val result = try {
                    if (p.usbSaltHash.isNotEmpty()) {
                        when (val op = usbVolumes.withMatchingVolume(p.usbSaltHash, readOnly = false, startHint = p.usbStartByte, refingerprint = true) { dev, span ->
                            cryptoEngine.changePasswordUsb(
                                transport        = zip.arcanum.usb.UsbPartitionView(dev, span.startByte, span.sizeBytes),
                                deviceSize       = span.sizeBytes,
                                oldPassword      = p.oldPassword,
                                oldKeyfileData  = p.oldKeyfileData,
                                oldPim           = p.oldPim,
                                newPassword      = p.newPassword,
                                newKeyfileData  = p.newKeyfileData,
                                newHashAlgorithm = p.newHashAlgorithm,
                                newPim           = p.newPim,
                                wipePassCount    = p.wipePassCount,
                                extraEntropy     = p.extraEntropy
                            )
                        }) {
                            is zip.arcanum.usb.UsbVolumeManager.VolumeOp.Done -> {
                                // A rewritten header has a fresh salt: without this the
                                // vault stops recognising the very drive it just changed.
                                if (op.value is zip.arcanum.crypto.CryptoResult.Success) {
                                    op.newSaltHash?.let { fresh ->
                                        containerRepo.updateUsbSaltHash(p.usbSaltHash, fresh)
                                    }
                                }
                                op.value
                            }
                            zip.arcanum.usb.UsbVolumeManager.VolumeOp.NoDrive -> {
                                usbError = "USB drive not connected\nConnect the drive holding this vault and try again."
                                zip.arcanum.crypto.CryptoResult.Failure(zip.arcanum.crypto.CryptoError.IO_ERROR)
                            }
                            is zip.arcanum.usb.UsbVolumeManager.VolumeOp.WrongVolume -> {
                                usbError = "Wrong USB device\nThe connected drive does not hold this vault."
                                zip.arcanum.crypto.CryptoResult.Failure(zip.arcanum.crypto.CryptoError.IO_ERROR)
                            }
                            is zip.arcanum.usb.UsbVolumeManager.VolumeOp.Failed -> {
                                usbError = op.reason
                                zip.arcanum.crypto.CryptoResult.Failure(zip.arcanum.crypto.CryptoError.IO_ERROR)
                            }
                        }
                    } else if (p.safFd >= 0) {
                        cryptoEngine.changePasswordFd(
                            fd               = p.safFd,
                            oldPassword      = p.oldPassword,
                            oldKeyfileData  = p.oldKeyfileData,
                            oldPim           = p.oldPim,
                            newPassword      = p.newPassword,
                            newKeyfileData  = p.newKeyfileData,
                            newHashAlgorithm = p.newHashAlgorithm,
                            newPim           = p.newPim,
                            wipePassCount    = p.wipePassCount,
                            extraEntropy     = p.extraEntropy
                        )
                    } else {
                        cryptoEngine.changePassword(
                            path             = p.path,
                            oldPassword      = p.oldPassword,
                            oldKeyfileData  = p.oldKeyfileData,
                            oldPim           = p.oldPim,
                            newPassword      = p.newPassword,
                            newKeyfileData  = p.newKeyfileData,
                            newHashAlgorithm = p.newHashAlgorithm,
                            newPim           = p.newPim,
                            wipePassCount    = p.wipePassCount,
                            extraEntropy     = p.extraEntropy
                        )
                    }
                } finally {
                    p.safPfd?.close()
                    p.oldKeyfileData.forEach { it.fill(0) }
                    p.newKeyfileData.forEach { it.fill(0) }
                    p.extraEntropy.fill(0)
                }

                _state.value = when (result) {
                    is CryptoResult.Success -> State.Success
                    is CryptoResult.Failure -> State.Failure(usbError ?: result.error.name)
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
