package zip.arcanum.core.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import zip.arcanum.core.database.entities.ContainerEntity

@Dao
interface ContainerDao {
    @Query("SELECT * FROM containers ORDER BY lastAccessedAt DESC")
    fun getAllContainers(): Flow<List<ContainerEntity>>

    @Query("SELECT * FROM containers WHERE id = :id")
    suspend fun getContainerById(id: String): ContainerEntity?

    @Query("SELECT * FROM containers WHERE isFavorite = 1 ORDER BY lastAccessedAt DESC")
    fun getFavoriteContainers(): Flow<List<ContainerEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertContainer(container: ContainerEntity)

    @Update
    suspend fun updateContainer(container: ContainerEntity)

    @Delete
    suspend fun deleteContainer(container: ContainerEntity)

    @Query("DELETE FROM containers WHERE id = :id")
    suspend fun deleteContainerById(id: String)

    @Query("UPDATE containers SET isMounted = :mounted WHERE id = :id")
    suspend fun setMounted(id: String, mounted: Boolean)

    @Query("UPDATE containers SET lastAccessedAt = :time WHERE id = :id")
    suspend fun updateLastAccessed(id: String, time: Long)

    @Query("UPDATE containers SET algorithm = :algorithm WHERE id = :id")
    suspend fun updateAlgorithm(id: String, algorithm: String)

    @Query("UPDATE containers SET prf = :prf WHERE id = :id")
    suspend fun updatePrf(id: String, prf: String)

    @Query("UPDATE containers SET filesystem = :filesystem WHERE id = :id")
    suspend fun updateFilesystem(id: String, filesystem: String)

    @Query("UPDATE containers SET isMounted = 0")
    suspend fun setAllUnmounted()

    @Query("UPDATE containers SET hasBiometric = :hasBiometric WHERE id = :id")
    suspend fun updateBiometric(id: String, hasBiometric: Boolean)

    @Query("SELECT COUNT(*) FROM containers WHERE path = :path")
    suspend fun countByPath(path: String): Int

    @Query("UPDATE containers SET unmountOnLock = :value WHERE id = :id")
    suspend fun updateUnmountOnLock(id: String, value: Boolean)

    @Query("UPDATE containers SET unmountOnBackground = :value WHERE id = :id")
    suspend fun updateUnmountOnBackground(id: String, value: Boolean)

    @Query("SELECT * FROM containers")
    suspend fun getAllContainersOnce(): List<ContainerEntity>

    @Query("UPDATE containers SET hasBiometric = 0")
    suspend fun clearAllBiometric()

    @Query("DELETE FROM containers")
    suspend fun deleteAll()

    @Query("UPDATE containers SET path = :path WHERE id = :id")
    suspend fun updatePath(id: String, path: String)
}
