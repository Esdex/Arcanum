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
    @ColumnInfo(defaultValue = "") val safUri: String = "",
    @ColumnInfo(defaultValue = "0") val keySize: Int = 0,
    @ColumnInfo(defaultValue = "XTS") val encryptionMode: String = "XTS",
    @ColumnInfo(defaultValue = "128") val blockSize: Int = 128,
    @ColumnInfo(defaultValue = "2") val formatVersion: Int = 2,
    @ColumnInfo(defaultValue = "1") val hasBackupHeader: Boolean = true,
    @ColumnInfo(defaultValue = "0") val pkcs5Iterations: Int = 0,
    @ColumnInfo(defaultValue = "0") val headerModifiedAt: Long = 0L
)
