package zip.arcanum.arcanum.backup

import android.content.Context
import androidx.annotation.StringRes
import kotlinx.coroutines.CancellationException
import zip.arcanum.R

data class BackupUploadResult(
    val location: String,
    val fileName: String,
    val warning: String? = null
)

fun interface BackupProgressCallback {
    fun onProgress(bytesTransferred: Long, totalBytes: Long, message: String)
}

interface BackupUploader {
    suspend fun validate(settings: BackupSettings)

    suspend fun upload(
        containerId: String,
        source: BackupSource,
        fileName: String,
        settings: BackupSettings,
        previousRecord: BackupRecord?,
        onProgress: BackupProgressCallback
    ): BackupUploadResult
}

class BackupValidationException : IllegalStateException {
    @StringRes
    private val messageRes: Int?
    private val formatArgs: Array<out Any>

    constructor(message: String, cause: Throwable? = null) : super(message, cause) {
        messageRes = null
        formatArgs = emptyArray()
    }

    constructor(@StringRes messageRes: Int, vararg formatArgs: Any) : super() {
        this.messageRes = messageRes
        this.formatArgs = formatArgs
    }

    fun localizedMessage(context: Context): String =
        messageRes?.let { context.getString(it, *formatArgs) }
            ?: message?.takeIf { it.isNotBlank() }
            ?: javaClass.simpleName
}

internal fun Throwable.userMessage(context: Context? = null): String = when (this) {
    is CancellationException -> context?.getString(R.string.backup_error_operation_cancelled) ?: "Operation cancelled"
    is BackupValidationException -> if (context != null) localizedMessage(context) else message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
    else -> message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
