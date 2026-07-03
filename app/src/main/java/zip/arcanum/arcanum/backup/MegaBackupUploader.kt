package zip.arcanum.arcanum.backup

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import zip.arcanum.R

@Singleton
class MegaBackupUploader @Inject constructor(
    @ApplicationContext private val context: Context
) : BackupUploader {
    override suspend fun validate(settings: BackupSettings) = withContext(Dispatchers.IO) {
        try {
            val client = MegaAccountClient(context)
            client.login(settings.megaEmail, settings.megaPassword)
            client.ensureFolder(settings.megaFolder)
            Unit
        } catch (t: Throwable) {
            throw BackupValidationException(context.getString(R.string.backup_error_mega_validation_failed, t.userMessage(context)), t)
        }
    }

    override suspend fun upload(
        containerId: String,
        source: BackupSource,
        fileName: String,
        settings: BackupSettings,
        previousRecord: BackupRecord?,
        onProgress: BackupProgressCallback
    ): BackupUploadResult = withContext(Dispatchers.IO) {
        val client = MegaAccountClient(context)
        client.login(settings.megaEmail, settings.megaPassword)
        val handle = client.upload(
            source = source,
            fileName = fileName,
            folderPath = settings.megaFolder,
            onProgress = onProgress
        )
        val warning = if (settings.deletePreviousAfterSuccess && previousRecord != null) {
            deletePrevious(client, previousRecord)
        } else null
        BackupUploadResult(
            location = "mega://$handle",
            fileName = fileName,
            warning = warning
        )
    }

    private fun deletePrevious(client: MegaAccountClient, record: BackupRecord): String? {
        val handle = record.location.removePrefix(MEGA_LOCATION_PREFIX)
        if (handle == record.location || handle.isBlank()) {
            return context.getString(R.string.backup_warning_delete_previous_wrong_mega)
        }
        return runCatching {
            client.deleteNode(handle)
        }.fold(
            onSuccess = { null },
            onFailure = { context.getString(R.string.backup_warning_delete_previous_failed_reason, it.userMessage(context)) }
        )
    }

    companion object {
        private const val MEGA_LOCATION_PREFIX = "mega://"
    }
}
