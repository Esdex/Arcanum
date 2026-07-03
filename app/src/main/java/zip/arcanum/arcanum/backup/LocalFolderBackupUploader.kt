package zip.arcanum.arcanum.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import zip.arcanum.R

@Singleton
class LocalFolderBackupUploader @Inject constructor(
    @ApplicationContext private val context: Context
) : BackupUploader {

    override suspend fun validate(settings: BackupSettings) = withContext(Dispatchers.IO) {
        val folder = settings.localFolder()
        val testName = ".arcanum_backup_test_${UUID.randomUUID()}.tmp"
        val testFile = folder.createFile("application/octet-stream", testName)
            ?: throw BackupValidationException(R.string.backup_error_local_create_test_file)
        try {
            context.contentResolver.openOutputStream(testFile.uri, "wt")?.use { out ->
                out.write(byteArrayOf(0x41))
                out.flush()
            } ?: throw BackupValidationException(R.string.backup_error_local_no_write_access)
        } finally {
            testFile.delete()
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
        val folder = settings.localFolder()
        val tempName = ".arcanum_backup_${UUID.randomUUID()}_$fileName.part"
        val tempFile = folder.createFile("application/octet-stream", tempName)
            ?: throw BackupValidationException(R.string.backup_error_local_create_backup_file)

        var written = 0L
        try {
            source.open().use { input ->
                context.contentResolver.openOutputStream(tempFile.uri, "wt")?.use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress.onProgress(written, source.sizeBytes, context.getString(R.string.backup_progress_copy_selected_folder))
                    }
                    output.flush()
                } ?: throw BackupValidationException(R.string.backup_error_local_no_write_access)
            }

            if (written != source.sizeBytes) {
                throw BackupValidationException(R.string.backup_error_local_finish_write)
            }

            if (!tempFile.renameTo(fileName)) {
                throw BackupValidationException(R.string.backup_error_local_finish_write)
            }

            val finalFile = folder.findFile(fileName)
                ?: throw BackupValidationException(R.string.backup_error_local_file_missing)
            val warning = if (settings.deletePreviousAfterSuccess && previousRecord != null) {
                deletePrevious(previousRecord)
            } else null
            BackupUploadResult(
                location = finalFile.uri.toString(),
                fileName = fileName,
                warning = warning
            )
        } catch (t: Throwable) {
            tempFile.delete()
            throw t
        }
    }

    private fun BackupSettings.localFolder(): DocumentFile {
        if (localFolderUri.isBlank()) throw BackupValidationException(R.string.backup_error_local_choose_folder)
        val uri = Uri.parse(localFolderUri)
        return DocumentFile.fromTreeUri(context, uri)
            ?: throw BackupValidationException(R.string.backup_error_local_folder_unavailable)
    }

    private fun deletePrevious(record: BackupRecord): String? {
        return runCatching {
            DocumentFile.fromSingleUri(context, Uri.parse(record.location))?.delete()
        }.fold(
            onSuccess = { deleted ->
                if (deleted == true) null else context.getString(R.string.backup_warning_delete_previous_failed)
            },
            onFailure = { context.getString(R.string.backup_warning_delete_previous_failed_reason, it.userMessage(context)) }
        )
    }
}
