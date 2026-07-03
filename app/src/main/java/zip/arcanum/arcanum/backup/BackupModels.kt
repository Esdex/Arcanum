package zip.arcanum.arcanum.backup

import kotlinx.serialization.Serializable

enum class BackupProvider {
    LOCAL,
    S3,
    MEGA
}

@Serializable
data class BackupSettings(
    val provider: BackupProvider = BackupProvider.LOCAL,
    val deletePreviousAfterSuccess: Boolean = false,
    val localFolderUri: String = "",
    val s3Endpoint: String = "",
    val s3Region: String = "us-east-1",
    val s3Bucket: String = "",
    val s3Prefix: String = "",
    val s3AccessKey: String = "",
    val s3SecretKey: String = "",
    val s3SessionToken: String = "",
    val s3PathStyle: Boolean = true,
    val s3ForceV4Signer: Boolean = false,
    val megaEmail: String = "",
    val megaPassword: String = "",
    val megaFolder: String = "/"
) {
    fun hasUsableDestination(): Boolean = when (provider) {
        BackupProvider.LOCAL -> localFolderUri.isNotBlank()
        BackupProvider.S3    -> s3Bucket.isNotBlank() &&
            s3AccessKey.isNotBlank() &&
            s3SecretKey.isNotBlank() &&
            (s3Endpoint.isNotBlank() || s3Region.isNotBlank())
        BackupProvider.MEGA  -> megaEmail.isNotBlank() &&
            megaPassword.isNotBlank()
    }

    fun hasSensitiveCredentials(provider: BackupProvider = this.provider): Boolean = when (provider) {
        BackupProvider.LOCAL -> false
        BackupProvider.S3    -> s3AccessKey.isNotBlank() ||
            s3SecretKey.isNotBlank() ||
            s3SessionToken.isNotBlank()
        BackupProvider.MEGA  -> megaEmail.isNotBlank() ||
            megaPassword.isNotBlank()
    }
}

@Serializable
data class BackupRecord(
    val provider: BackupProvider,
    val location: String,
    val fileName: String,
    val sizeBytes: Long,
    val completedAt: Long
)

@Serializable
data class S3MultipartResumeState(
    val containerId: String,
    val bucket: String,
    val key: String,
    val uploadId: String,
    val partSize: Long,
    val completedParts: List<S3CompletedPart> = emptyList()
)

@Serializable
data class S3CompletedPart(
    val partNumber: Int,
    val eTag: String
)

data class BackupProgressState(
    val containerId: String = "",
    val provider: BackupProvider = BackupProvider.LOCAL,
    val fileName: String = "",
    val status: BackupStatus = BackupStatus.IDLE,
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val attempt: Int = 0,
    val speedBytesPerSecond: Long = 0L,
    val message: String = "",
    val error: String? = null
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (bytesTransferred.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)

    val isRunning: Boolean
        get() = status == BackupStatus.RUNNING || status == BackupStatus.VALIDATING || status == BackupStatus.STOPPING
}

enum class BackupStatus {
    IDLE,
    VALIDATING,
    RUNNING,
    STOPPING,
    SUCCESS,
    PAUSED,
    FAILED,
    CANCELLED
}
