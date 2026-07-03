package zip.arcanum.arcanum.backup

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.R
import zip.arcanum.core.database.dao.ContainerDao
import zip.arcanum.core.database.entities.ContainerEntity
import zip.arcanum.crypto.VeraCryptEngine
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import kotlin.math.roundToInt

@AndroidEntryPoint
class BackupService : Service(), DefaultLifecycleObserver {

    @Inject lateinit var containerDao: ContainerDao
    @Inject lateinit var containerRepository: ContainerRepository
    @Inject lateinit var cryptoEngine: VeraCryptEngine
    @Inject lateinit var settingsRepository: BackupSettingsRepository
    @Inject lateinit var localUploader: LocalFolderBackupUploader
    @Inject lateinit var s3Uploader: S3BackupUploader
    @Inject lateinit var megaUploader: MegaBackupUploader

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null
    private var stopAsPause = false

    override fun onCreate() {
        super<Service>.onCreate()
        createNotificationChannel()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                stopAsPause = false
                requestStop(BackupStatus.CANCELLED, getString(R.string.backup_cancelled))
            }
            ACTION_START -> {
                val containerId = intent.getStringExtra(EXTRA_CONTAINER_ID) ?: return START_NOT_STICKY
                if (job?.isActive == true) return START_NOT_STICKY
                startForeground(NOTIFICATION_ID, buildNotification(_progress.value))
                startBackup(containerId)
            }
        }
        return START_NOT_STICKY
    }

    override fun onStop(owner: LifecycleOwner) {
        if (job?.isActive == true) {
            stopAsPause = true
            requestStop(BackupStatus.PAUSED, getString(R.string.backup_paused_background))
        }
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(this)
        serviceScope.cancel()
        super<Service>.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startBackup(containerId: String) {
        job = serviceScope.launch {
            val startedState = BackupProgressState(
                containerId = containerId,
                status = BackupStatus.VALIDATING,
                message = getString(R.string.backup_preparing)
            )
            updateProgress(startedState)

            try {
                val container = containerDao.getContainerById(containerId)
                    ?: throw BackupValidationException(getString(R.string.backup_error_container_missing))
                val backupContainer = unmountForBackupIfNeeded(container)
                val settings = settingsRepository.loadSettings(containerId)
                if (!settings.hasUsableDestination()) {
                    throw BackupValidationException(getString(R.string.backup_error_settings_missing))
                }
                val uploader = uploaderFor(settings.provider)
                val source = BackupSource(this@BackupService, backupContainer)
                val fileName = backupFileName(source)
                val previous = settingsRepository.loadLastRecord(containerId, settings.provider)

                val initial = BackupProgressState(
                    containerId = containerId,
                    provider = settings.provider,
                    fileName = fileName,
                    status = BackupStatus.RUNNING,
                    totalBytes = source.sizeBytes,
                    attempt = 1,
                    message = getString(R.string.backup_uploading)
                )
                updateProgress(initial)

                var lastBytes = 0L
                var lastNanos = System.nanoTime()
                var attempt = 1
                var lastError: Throwable? = null
                while (attempt <= MAX_ATTEMPTS) {
                    try {
                        val result = uploader.upload(
                            containerId = containerId,
                            source = source,
                            fileName = fileName,
                            settings = settings,
                            previousRecord = previous,
                            onProgress = BackupProgressCallback { bytes, total, message ->
                                val now = System.nanoTime()
                                val elapsed = (now - lastNanos).coerceAtLeast(1L)
                                val speed = (((bytes - lastBytes).coerceAtLeast(0L) * 1_000_000_000L) / elapsed)
                                lastBytes = bytes
                                lastNanos = now
                                updateProgress(
                                    BackupProgressState(
                                        containerId = containerId,
                                        provider = settings.provider,
                                        fileName = fileName,
                                        status = BackupStatus.RUNNING,
                                        bytesTransferred = bytes,
                                        totalBytes = total,
                                        attempt = attempt,
                                        speedBytesPerSecond = speed,
                                        message = message
                                    )
                                )
                            }
                        )
                        settingsRepository.saveLastRecord(
                            containerId,
                            BackupRecord(
                                provider = settings.provider,
                                location = result.location,
                                fileName = result.fileName,
                                sizeBytes = source.sizeBytes,
                                completedAt = System.currentTimeMillis()
                            )
                        )
                        updateProgress(
                            BackupProgressState(
                                containerId = containerId,
                                provider = settings.provider,
                                fileName = result.fileName,
                                status = BackupStatus.SUCCESS,
                                bytesTransferred = source.sizeBytes,
                                totalBytes = source.sizeBytes,
                                attempt = attempt,
                                message = result.warning ?: getString(R.string.backup_success)
                            )
                        )
                        stopSelf()
                        return@launch
                    } catch (t: CancellationException) {
                        throw t
                    } catch (t: Throwable) {
                        lastError = t
                        if (attempt >= MAX_ATTEMPTS) break
                        updateProgress(
                            _progress.value.copy(
                                status = BackupStatus.RUNNING,
                                attempt = attempt + 1,
                                message = getString(R.string.backup_retrying, attempt + 1),
                                error = t.userMessage(this@BackupService)
                            )
                        )
                        delay(RETRY_DELAY_MS * attempt)
                        attempt++
                    }
                }
                throw lastError ?: BackupValidationException(getString(R.string.backup_error_unknown))
            } catch (_: CancellationException) {
                val status = if (stopAsPause) BackupStatus.PAUSED else BackupStatus.CANCELLED
                val message = if (stopAsPause) getString(R.string.backup_paused_background) else getString(R.string.backup_cancelled)
                updateProgress(_progress.value.copy(status = status, message = message, error = null))
                stopSelf()
            } catch (t: Throwable) {
                updateProgress(
                    _progress.value.copy(
                        status = BackupStatus.FAILED,
                        message = getString(R.string.backup_failed),
                        error = t.userMessage(this@BackupService)
                    )
                )
                stopSelf()
            }
        }
    }

    private suspend fun unmountForBackupIfNeeded(container: ContainerEntity): ContainerEntity {
        if (!container.isMounted) return container

        updateProgress(
            _progress.value.copy(
                status = BackupStatus.VALIDATING,
                message = getString(R.string.backup_unmounting),
                error = null
            )
        )

        val handle = containerRepository.getContainerHandle(container.id)
        if (handle != null) cryptoEngine.unmountContainer(handle)
        containerRepository.unmountContainer(container.id)

        return containerDao.getContainerById(container.id)
            ?: throw BackupValidationException(getString(R.string.backup_error_container_missing))
    }

    private fun requestStop(status: BackupStatus, message: String) {
        updateProgress(_progress.value.copy(status = BackupStatus.STOPPING, message = message))
        job?.cancel(CancellationException(status.name))
    }

    private fun uploaderFor(provider: BackupProvider): BackupUploader = when (provider) {
        BackupProvider.LOCAL -> localUploader
        BackupProvider.S3    -> s3Uploader
        BackupProvider.MEGA  -> megaUploader
    }

    private fun backupFileName(source: BackupSource): String {
        val timestamp = LocalDateTime.now().format(FILE_TS)
        val base = source.baseFileName
        val dot = base.lastIndexOf('.')
        return if (dot > 0 && dot < base.lastIndex) {
            "${base.substring(0, dot)}_$timestamp${base.substring(dot)}"
        } else {
            "${base}_$timestamp"
        }
    }

    private fun createNotificationChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel_backup),
                    NotificationManager.IMPORTANCE_LOW
                ).apply { description = getString(R.string.notif_channel_backup_desc) }
            )
        }
    }

    private fun updateProgress(state: BackupProgressState) {
        _progress.value = state
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(state))
    }

    private fun buildNotification(state: BackupProgressState): Notification {
        val pct = (state.fraction * 100f).roundToInt()
        val running = state.isRunning
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_backup_title))
            .setContentText(state.error ?: state.message.ifBlank { getString(R.string.backup_uploading) })
            .setProgress(100, pct, running && pct == 0)
            .setOngoing(running)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                getString(R.string.backup_cancel),
                android.app.PendingIntent.getService(
                    this,
                    0,
                    Intent(this, BackupService::class.java).setAction(ACTION_CANCEL),
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
    }

    companion object {
        const val CHANNEL_ID = "backup"
        private const val NOTIFICATION_ID = 1040
        private const val ACTION_START = "zip.arcanum.backup.START"
        private const val ACTION_CANCEL = "zip.arcanum.backup.CANCEL"
        private const val EXTRA_CONTAINER_ID = "container_id"
        private const val MAX_ATTEMPTS = 3
        private const val RETRY_DELAY_MS = 1_500L
        private val FILE_TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        private val _progress = MutableStateFlow(BackupProgressState())
        val progress: StateFlow<BackupProgressState> = _progress.asStateFlow()

        fun start(context: Context, containerId: String) {
            val intent = Intent(context, BackupService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONTAINER_ID, containerId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context) {
            val intent = Intent(context, BackupService::class.java).setAction(ACTION_CANCEL)
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
