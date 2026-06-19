package zip.arcanum.core.security

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class PanicWipeWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val panicManager: PanicManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Settings are read from DataStore inside executeWipe() — nothing sensitive
        // is stored in WorkManager's plaintext SQLite database.
        return runCatching {
            panicManager.executeWipe()
            Result.success()
        }.getOrElse {
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        }
    }

    companion object {
        // Neutral name — does not reveal purpose in WorkManager's plaintext SQLite DB
        const val WORK_NAME = "arcanum_maintenance_sweep"
    }
}
