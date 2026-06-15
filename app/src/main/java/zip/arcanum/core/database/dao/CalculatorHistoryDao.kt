package zip.arcanum.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import zip.arcanum.core.database.entities.CalculationEntity

@Dao
interface CalculatorHistoryDao {
    @Query("SELECT * FROM calculations ORDER BY timestamp DESC")
    fun getHistory(): Flow<List<CalculationEntity>>

    @Query("SELECT * FROM calculations ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentHistory(limit: Int = 50): List<CalculationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCalculation(calculation: CalculationEntity)

    @Query("DELETE FROM calculations")
    suspend fun clearHistory()

    @Query("DELETE FROM calculations WHERE id = :id")
    suspend fun deleteCalculation(id: Long)
}
