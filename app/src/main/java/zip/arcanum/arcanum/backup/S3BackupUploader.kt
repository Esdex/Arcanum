package zip.arcanum.arcanum.backup

import android.content.Context
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.CompleteMultipartUploadRequest
import com.amazonaws.services.s3.model.InitiateMultipartUploadRequest
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.model.PartETag
import com.amazonaws.services.s3.model.PutObjectRequest
import com.amazonaws.services.s3.model.UploadPartRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlin.math.min
import zip.arcanum.R

@Singleton
class S3BackupUploader @Inject constructor(
    private val settingsRepository: BackupSettingsRepository,
    @ApplicationContext private val context: Context
) : BackupUploader {

    override suspend fun validate(settings: BackupSettings) = withContext(Dispatchers.IO) {
        val client = S3BackupClientFactory.client(settings)
        val key = S3BackupClientFactory.key(settings, ".arcanum_backup_test_${UUID.randomUUID()}.tmp")
        val bytes = byteArrayOf(0x41)
        val metadata = ObjectMetadata().apply { contentLength = bytes.size.toLong() }
        try {
            client.putObject(PutObjectRequest(settings.s3Bucket.trim(), key, ByteArrayInputStream(bytes), metadata))
            client.deleteObject(settings.s3Bucket.trim(), key)
        } catch (t: Throwable) {
            throw BackupValidationException(context.getString(R.string.backup_error_s3_validation_failed, t.userMessage(context)), t)
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
        val client = S3BackupClientFactory.client(settings)
        val bucket = settings.s3Bucket.trim()
        val loadedResume = settingsRepository.loadS3ResumeState(containerId)
        val existing = loadedResume?.takeIf {
            source.supportsStableResume &&
                it.bucket == bucket &&
                it.sourceSizeBytes == source.sizeBytes &&
                it.sourceFingerprint == source.resumeFingerprint
        }
        if (loadedResume != null && existing == null) {
            settingsRepository.clearS3ResumeState(containerId)
        }
        val key = existing?.key ?: S3BackupClientFactory.key(settings, fileName)
        val uploadId = existing?.uploadId ?: client.initiateMultipartUpload(
            InitiateMultipartUploadRequest(bucket, key).apply {
                objectMetadata = ObjectMetadata().apply {
                    contentLength = source.sizeBytes
                    userMetadata = mapOf("arcanum-size" to source.sizeBytes.toString())
                }
            }
        ).uploadId
        val partSize = existing?.partSize ?: PART_SIZE_BYTES
        val completed = existing?.completedParts.orEmpty().associateBy { it.partNumber }.toMutableMap()
        var uploadedBytes = completed.keys.sumOf { part ->
            val offset = (part - 1L) * partSize
            min(partSize, source.sizeBytes - offset).coerceAtLeast(0L)
        }

        settingsRepository.saveS3ResumeState(
            S3MultipartResumeState(
                containerId = containerId,
                bucket = bucket,
                key = key,
                uploadId = uploadId,
                partSize = partSize,
                sourceSizeBytes = source.sizeBytes,
                sourceFingerprint = source.resumeFingerprint,
                completedParts = completed.values.sortedBy { it.partNumber }
            )
        )

        val parallelism = if (source.container.safUri.isBlank()) S3_PARALLEL_PARTS else S3_PARALLEL_PARTS_FOR_SAF
        coroutineScope {
            val inFlight = linkedMapOf<Int, Deferred<S3CompletedPart>>()
            var nextPartNumber = 1
            while (partOffset(nextPartNumber, partSize) < source.sizeBytes || inFlight.isNotEmpty()) {
                while (inFlight.size < parallelism && partOffset(nextPartNumber, partSize) < source.sizeBytes) {
                    coroutineContext.ensureActive()
                    val currentPart = nextPartNumber
                    val offset = partOffset(currentPart, partSize)
                    val length = min(partSize, source.sizeBytes - offset)
                    if (completed.containsKey(currentPart)) {
                        onProgress.onProgress(
                            uploadedBytes,
                            source.sizeBytes,
                            context.getString(R.string.backup_progress_s3_resume)
                        )
                    } else {
                        inFlight[currentPart] = async(Dispatchers.IO) {
                            uploadPart(
                                client = client,
                                bucket = bucket,
                                key = key,
                                uploadId = uploadId,
                                source = source,
                                partNumber = currentPart,
                                offset = offset,
                                length = length
                            )
                        }
                    }
                    nextPartNumber++
                }

                val entry = inFlight.entries.firstOrNull() ?: continue
                val completedPart = entry.value.await()
                inFlight.remove(entry.key)
                completed[completedPart.partNumber] = completedPart
                uploadedBytes += partLength(completedPart.partNumber, partSize, source.sizeBytes)
                settingsRepository.saveS3ResumeState(
                    S3MultipartResumeState(
                        containerId = containerId,
                        bucket = bucket,
                        key = key,
                        uploadId = uploadId,
                        partSize = partSize,
                        sourceSizeBytes = source.sizeBytes,
                        sourceFingerprint = source.resumeFingerprint,
                        completedParts = completed.values.sortedBy { it.partNumber }
                    )
                )
                onProgress.onProgress(
                    uploadedBytes,
                    source.sizeBytes,
                    context.getString(R.string.backup_progress_s3_parallel, parallelism, completed.size)
                )
            }
        }

        val partETags = completed.values
            .sortedBy { it.partNumber }
            .map { PartETag(it.partNumber, it.eTag) }
        client.completeMultipartUpload(CompleteMultipartUploadRequest(bucket, key, uploadId, partETags))
        settingsRepository.clearS3ResumeState(containerId)

        val metadata = client.getObjectMetadata(bucket, key)
        if (metadata.contentLength != source.sizeBytes) {
            throw BackupValidationException(R.string.backup_error_s3_wrong_uploaded_size)
        }

        val warning = if (settings.deletePreviousAfterSuccess && previousRecord != null) {
            deletePrevious(client, bucket, previousRecord)
        } else null
        BackupUploadResult(
            location = "s3://$bucket/$key",
            fileName = key.substringAfterLast('/'),
            warning = warning
        )
    }

    private fun deletePrevious(client: AmazonS3Client, bucket: String, record: BackupRecord): String? {
        val prefix = "s3://$bucket/"
        val key = record.location.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
            ?: return context.getString(R.string.backup_warning_delete_previous_wrong_s3)
        return runCatching {
            client.deleteObject(bucket, key)
        }.fold(
            onSuccess = { null },
            onFailure = { context.getString(R.string.backup_warning_delete_previous_failed_reason, it.userMessage(context)) }
        )
    }

    private fun uploadPart(
        client: AmazonS3Client,
        bucket: String,
        key: String,
        uploadId: String,
        source: BackupSource,
        partNumber: Int,
        offset: Long,
        length: Long
    ): S3CompletedPart {
        source.open(offset).use { raw ->
            val limited = LimitedInputStream(raw, length)
            val response = client.uploadPart(
                UploadPartRequest()
                    .withBucketName(bucket)
                    .withKey(key)
                    .withUploadId(uploadId)
                    .withPartNumber(partNumber)
                    .withPartSize(length)
                    .withInputStream(limited)
            )
            if (!limited.isFullyRead) {
                throw EOFException("Backup source ended before the requested part was read")
            }
            return S3CompletedPart(partNumber, response.partETag.eTag)
        }
    }

    private fun partOffset(partNumber: Int, partSize: Long): Long =
        (partNumber - 1L) * partSize

    private fun partLength(partNumber: Int, partSize: Long, totalBytes: Long): Long {
        val offset = partOffset(partNumber, partSize)
        return min(partSize, totalBytes - offset).coerceAtLeast(0L)
    }

    private class LimitedInputStream(input: InputStream, private var remaining: Long) : FilterInputStream(input) {
        val isFullyRead: Boolean
            get() = remaining == 0L

        override fun read(): Int {
            if (remaining <= 0L) return -1
            val value = super.read()
            if (value < 0) throw EOFException("Backup source ended before the requested part was read")
            if (value >= 0) remaining--
            return value
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (remaining <= 0L) return -1
            val toRead = min(length.toLong(), remaining).toInt()
            val read = super.read(buffer, offset, toRead)
            if (read < 0) throw EOFException("Backup source ended before the requested part was read")
            if (read > 0) remaining -= read.toLong()
            return read
        }
    }

    companion object {
        private const val PART_SIZE_BYTES = 8L * 1024L * 1024L
        private const val S3_PARALLEL_PARTS = 4
        private const val S3_PARALLEL_PARTS_FOR_SAF = 2
    }
}
