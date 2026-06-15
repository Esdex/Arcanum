package zip.arcanum.arcanum.gallery.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import zip.arcanum.arcanum.gallery.domain.MediaFile
import zip.arcanum.core.database.dao.MediaFileDao
import zip.arcanum.core.database.entities.MediaFileEntity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaRepository @Inject constructor(
    private val dao: MediaFileDao
) {
    fun getMediaForContainer(containerId: String): Flow<List<MediaFile>> =
        dao.getMediaForContainer(containerId).map { list -> list.map { it.toDomain() } }

    suspend fun getMediaById(id: String): MediaFile? =
        dao.getMediaById(id)?.toDomain()

    suspend fun saveMedia(file: MediaFile) =
        dao.insertMediaFile(file.toEntity())

    suspend fun deleteMedia(file: MediaFile) =
        dao.deleteMediaFile(file.toEntity())

    private fun MediaFileEntity.toDomain() = MediaFile(
        id            = id,
        containerId   = containerId,
        relativePath  = relativePath,
        fileName      = fileName,
        fileType      = fileType,
        size          = size,
        dateCreated   = dateCreated,
        dateModified  = dateModified,
        width         = width,
        height        = height,
        duration      = duration,
        thumbnailPath = thumbnailPath,
        isFavorite    = isFavorite
    )

    private fun MediaFile.toEntity() = MediaFileEntity(
        id            = id,
        containerId   = containerId,
        relativePath  = relativePath,
        fileName      = fileName,
        fileType      = fileType,
        size          = size,
        dateCreated   = dateCreated,
        dateModified  = dateModified,
        width         = width,
        height        = height,
        duration      = duration,
        thumbnailPath = thumbnailPath,
        isFavorite    = isFavorite
    )
}
