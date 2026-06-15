package zip.arcanum.arcanum.gallery.domain

import zip.arcanum.core.database.entities.MediaFileType

data class MediaFile(
    val id: String,
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
    val isFavorite: Boolean = false
)
