package zip.arcanum.core.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "containers")
data class ContainerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val algorithm: String,
    @ColumnInfo(defaultValue = "—") val prf: String = "—",
    @ColumnInfo(defaultValue = "—") val filesystem: String = "—",
    val createdAt: Long,
    val lastAccessedAt: Long,
    val isFavorite: Boolean = false,
    val isMounted: Boolean = false,
    @ColumnInfo(defaultValue = "0") val hasBiometric: Boolean = false,
    @ColumnInfo(defaultValue = "0") val unmountOnLock: Boolean = false,
    @ColumnInfo(defaultValue = "0") val unmountOnBackground: Boolean = false,
    @ColumnInfo(defaultValue = "") val safUri: String = ""
)
