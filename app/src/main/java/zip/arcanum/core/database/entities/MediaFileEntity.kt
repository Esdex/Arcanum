package zip.arcanum.core.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MediaFileType { IMAGE, VIDEO, AUDIO }

@Entity(
    tableName = "media_files",
    indices = [
        Index(value = ["containerId"]),
        Index(value = ["containerId", "relativePath"], unique = true)
    ]
)
data class MediaFileEntity(
    @PrimaryKey val id: String,
    val containerId: String,
    val relativePath: String,
    val fileName: String,
    val fileType: MediaFileType,
    val size: Long,
    val dateCreated: Long,
    val dateModified: Long,
    val width: Int = 0,
    val height: Int = 0,
    val duration: Long = 0L,
    val thumbnailPath: String? = null,
    val isFavorite: Boolean = false,
    val description: String = ""
)
