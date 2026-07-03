package zip.arcanum.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import zip.arcanum.core.database.entities.MediaFileEntity
import zip.arcanum.core.database.entities.MediaFileType

@Dao
interface MediaFileDao {
    @Query("SELECT * FROM media_files WHERE containerId = :containerId ORDER BY dateCreated DESC")
    fun getMediaForContainer(containerId: String): Flow<List<MediaFileEntity>>

    @Query("SELECT * FROM media_files WHERE containerId = :containerId AND fileType = :type ORDER BY dateCreated DESC")
    fun getMediaByType(containerId: String, type: MediaFileType): Flow<List<MediaFileEntity>>

    @Query("SELECT * FROM media_files WHERE containerId = :containerId AND isFavorite = 1")
    fun getFavoriteMedia(containerId: String): Flow<List<MediaFileEntity>>

    @Query("SELECT * FROM media_files WHERE id = :id")
    suspend fun getMediaById(id: String): MediaFileEntity?

    @Query("SELECT * FROM media_files WHERE id IN (:ids)")
    suspend fun getMediaByIds(ids: List<String>): List<MediaFileEntity>

    @Query("SELECT * FROM media_files WHERE containerId = :containerId AND relativePath = :relativePath LIMIT 1")
    suspend fun getMediaByContainerPath(containerId: String, relativePath: String): MediaFileEntity?

    @Query("SELECT * FROM media_files WHERE containerId = :containerId AND fileType = :type ORDER BY dateCreated DESC")
    suspend fun getMediaByTypeOnce(containerId: String, type: MediaFileType): List<MediaFileEntity>

    @Query("SELECT * FROM media_files WHERE containerId = :containerId AND fileType != 'AUDIO' ORDER BY dateCreated DESC")
    suspend fun getVisualMediaOnce(containerId: String): List<MediaFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaFile(file: MediaFileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaFiles(files: List<MediaFileEntity>)

    @Update
    suspend fun updateMediaFile(file: MediaFileEntity)

    @Delete
    suspend fun deleteMediaFile(file: MediaFileEntity)

    @Query("SELECT * FROM media_files WHERE containerId = :containerId AND fileName LIKE '%' || :query || '%' ORDER BY dateCreated DESC")
    fun searchMedia(containerId: String, query: String): Flow<List<MediaFileEntity>>

    @Query("SELECT * FROM media_files WHERE containerId = :containerId")
    suspend fun getAllForContainerOnce(containerId: String): List<MediaFileEntity>

    @Query("DELETE FROM media_files WHERE containerId = :containerId")
    suspend fun deleteAllForContainer(containerId: String)

    @Query("DELETE FROM media_files WHERE containerId = :containerId AND relativePath = :relativePath")
    suspend fun deleteMediaByContainerPath(containerId: String, relativePath: String)

    @Query(
        "DELETE FROM media_files WHERE containerId = :containerId " +
            "AND (relativePath = :relativePath OR relativePath LIKE :relativePath || '/%')"
    )
    suspend fun deleteMediaByContainerPathPrefix(containerId: String, relativePath: String)
}
