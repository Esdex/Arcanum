package zip.arcanum.arcanum.backup

import com.amazonaws.ClientConfiguration
import com.amazonaws.auth.AWSCredentials
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.auth.BasicSessionCredentials
import com.amazonaws.regions.Region
import com.amazonaws.regions.Regions
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.S3ClientOptions
import zip.arcanum.R

internal object S3BackupClientFactory {
    fun client(settings: BackupSettings): AmazonS3Client {
        val credentials: AWSCredentials = if (settings.s3SessionToken.isNotBlank()) {
            BasicSessionCredentials(settings.s3AccessKey.trim(), settings.s3SecretKey, settings.s3SessionToken.trim())
        } else {
            BasicAWSCredentials(settings.s3AccessKey.trim(), settings.s3SecretKey)
        }
        val client = AmazonS3Client(
            credentials,
            ClientConfiguration().apply {
                connectionTimeout = 20_000
                socketTimeout = 60_000
                maxConnections = 12
                maxErrorRetry = 0
            }
        )
        if (settings.s3Endpoint.isNotBlank()) {
            if (settings.s3ForceV4Signer) {
                client.setSignerRegionOverride(settings.s3Region.trim().ifBlank { "us-east-1" })
            }
            client.endpoint = normalizeEndpoint(settings.s3Endpoint)
        } else {
            client.setRegion(Region.getRegion(Regions.fromName(settings.s3Region.trim())))
        }
        client.setS3ClientOptions(
            S3ClientOptions.builder()
                .setPathStyleAccess(settings.s3PathStyle)
                .build()
        )
        return client
    }

    fun key(settings: BackupSettings, fileName: String): String {
        val cleanPrefix = settings.s3Prefix.trim().trim('/')
        return if (cleanPrefix.isBlank()) fileName else "$cleanPrefix/$fileName"
    }

    fun normalizeEndpoint(value: String): String {
        val trimmed = value.trim().trimEnd('/')
        if (trimmed.startsWith("http://", ignoreCase = true)) {
            throw BackupValidationException(R.string.backup_error_s3_https_required)
        }
        return if (trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "https://$trimmed"
        }
    }
}
