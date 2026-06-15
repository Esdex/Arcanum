package zip.arcanum.core.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "mount_points")
data class MountPointEntity(
    @PrimaryKey val containerId: String,
    val mountPath: String,
    val mountedAt: Long
)
