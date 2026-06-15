package zip.arcanum.core.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "calculations")
data class CalculationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val expression: String,
    val result: String,
    val timestamp: Long
)
