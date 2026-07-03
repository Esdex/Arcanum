package zip.arcanum.arcanum.gallery

import android.media.MediaMetadataRetriever
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import zip.arcanum.core.database.dao.MediaFileDao
import zip.arcanum.core.database.entities.MediaFileEntity
import zip.arcanum.core.database.entities.MediaFileType
import zip.arcanum.crypto.NativeFileInfo
import zip.arcanum.crypto.VeraCryptEngine
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaScanner @Inject constructor(
    private val engine: VeraCryptEngine,
    private val dao: MediaFileDao,
    private val exifReader: ExifReader
) {
    data class ScanProgress(
        val scannedFiles: Int,
        val totalFound: Int,
        val currentPath: String,
        val isComplete: Boolean,
        val mediaFiles: List<MediaFileEntity>
    )

    companion object {
        val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")
        val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "avi", "mov", "m4v", "3gp", "webm")
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "aac", "ogg", "wav", "m4a")

        // Formats returned by MediaMetadataRetriever.METADATA_KEY_DATE
        private val MEDIA_DATE_FORMATS = listOf(
            SimpleDateFormat("yyyyMMdd'T'HHmmss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'",     Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") },
            SimpleDateFormat("yyyyMMdd'T'HHmmss",        Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss",   Locale.US),
            SimpleDateFormat("yyyy MM dd HH:mm:ss",     Locale.US)
        )
    }

    fun scanContainer(handle: Long, containerId: String): Flow<ScanProgress> = flow {
        // Load existing DB entries so we can preserve user-edited fields across remounts.
        val existing: Map<String, MediaFileEntity> =
            dao.getAllForContainerOnce(containerId).associateBy { it.relativePath }

        val found      = mutableListOf<MediaFileEntity>()
        val foundPaths = mutableSetOf<String>()
        var scanned    = 0

        suspend fun scanDir(path: String) {
            val entries = try {
                engine.nativeListFiles(handle, path)
            } catch (_: Exception) {
                return
            }
            for (entry in entries.filterNotNull()) {
                if (entry.isDirectory) {
                    emit(ScanProgress(scanned, found.size, entry.path, false, found.toList()))
                    scanDir(entry.path)
                } else {
                    scanned++
                    val type = visualTypeForName(entry.name) ?: continue

                    foundPaths += entry.path
                    val prev = existing[entry.path]

                    val entity = buildEntity(
                        handle          = handle,
                        containerId     = containerId,
                        entry           = entry,
                        type            = type,
                        previous        = prev,
                        readEmbeddedDate = true
                    )
                    found.add(entity)
                    dao.insertMediaFile(entity)
                    emit(ScanProgress(scanned, found.size, entry.path, false, found.toList()))
                }
            }
        }

        scanDir("/")

        // Remove DB entries for files that no longer exist in the container.
        existing.values
            .filter { it.relativePath !in foundPaths }
            .forEach { dao.deleteMediaFile(it) }

        emit(ScanProgress(scanned, found.size, "", true, found.toList()))
    }

    suspend fun indexVisualFile(
        handle: Long,
        containerId: String,
        entry: NativeFileInfo
    ): MediaFileEntity? {
        if (entry.isDirectory) return null
        val type = visualTypeForName(entry.name) ?: return null
        val previous = dao.getMediaByContainerPath(containerId, entry.path)
        val entity = buildEntity(
            handle           = handle,
            containerId      = containerId,
            entry            = entry,
            type             = type,
            previous         = previous,
            readEmbeddedDate = false
        )
        dao.insertMediaFile(entity)
        return entity
    }

    suspend fun indexVisualFiles(
        handle: Long,
        containerId: String,
        entries: List<NativeFileInfo>
    ): List<MediaFileEntity> {
        if (entries.isEmpty()) return emptyList()
        val visualEntries = entries.filter { !it.isDirectory && visualTypeForName(it.name) != null }
        if (visualEntries.isEmpty()) return emptyList()

        val existing = dao.getAllForContainerOnce(containerId).associateBy { it.relativePath }
        val entities = visualEntries.mapNotNull { entry ->
            val type = visualTypeForName(entry.name) ?: return@mapNotNull null
            buildEntity(
                handle           = handle,
                containerId      = containerId,
                entry            = entry,
                type             = type,
                previous         = existing[entry.path],
                readEmbeddedDate = false
            )
        }
        if (entities.isNotEmpty()) dao.insertMediaFiles(entities)
        return entities
    }

    private fun visualTypeForName(name: String): MediaFileType? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            in IMAGE_EXTENSIONS -> MediaFileType.IMAGE
            in VIDEO_EXTENSIONS -> MediaFileType.VIDEO
            else -> null
        }
    }

    private fun buildEntity(
        handle: Long,
        containerId: String,
        entry: NativeFileInfo,
        type: MediaFileType,
        previous: MediaFileEntity?,
        readEmbeddedDate: Boolean
    ): MediaFileEntity {
        // Preserve user-edited metadata for known files. Single-file indexing from Files
        // avoids embedded-date reads so large folders stay responsive while scrolling.
        val fallback = entry.lastModified.takeIf { it > 0L } ?: System.currentTimeMillis()
        val date = previous?.dateCreated
            ?: if (readEmbeddedDate) extractDate(handle, entry.path, entry.size, type, fallback) else fallback

        return MediaFileEntity(
            id           = previous?.id ?: UUID.randomUUID().toString(),
            containerId  = containerId,
            relativePath = entry.path,
            fileName     = entry.name,
            fileType     = type,
            size         = entry.size,
            dateCreated  = date,
            dateModified = fallback,
            isFavorite   = previous?.isFavorite ?: false,
            description  = previous?.description ?: ""
        )
    }

    private fun extractDate(
        handle: Long,
        path: String,
        size: Long,
        type: MediaFileType,
        fallback: Long
    ): Long {
        return when (type) {
            MediaFileType.IMAGE -> extractImageDate(handle, path, fallback)
            MediaFileType.VIDEO,
            MediaFileType.AUDIO -> extractMediaDate(handle, path, size, fallback)
        }
    }

    private fun extractImageDate(handle: Long, path: String, fallback: Long): Long {
        val bytes = try {
            engine.nativeReadFile(handle, path, 0L, 65_536) ?: return fallback
        } catch (_: Exception) { return fallback }
        val exifDate = exifReader.readDate(bytes)
        return if (exifDate > 0L) exifDate else fallback
    }

    private fun extractMediaDate(handle: Long, path: String, size: Long, fallback: Long): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(NativeMediaDataSource(engine, handle, path, size))
            val dateStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
            retriever.release()
            parseMediaDate(dateStr).takeIf { it > 0L } ?: fallback
        } catch (_: Exception) { fallback }
    }

    private fun parseMediaDate(dateStr: String?): Long {
        if (dateStr.isNullOrBlank()) return 0L
        return MEDIA_DATE_FORMATS.firstNotNullOfOrNull { fmt ->
            runCatching { fmt.parse(dateStr)?.time?.takeIf { it > 0L } }.getOrNull()
        } ?: 0L
    }
}
