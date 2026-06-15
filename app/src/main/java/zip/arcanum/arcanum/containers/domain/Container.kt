package zip.arcanum.arcanum.containers.domain

data class Container(
    val id: String,
    val name: String,
    val path: String,
    val size: Long,
    val algorithm: String,
    val prf: String = "—",
    val filesystem: String = "—",
    val pim: Int = 0,
    val createdAt: Long,
    val lastAccessedAt: Long,
    val isFavorite: Boolean = false,
    val isMounted: Boolean = false,
    val isHiddenVolume: Boolean = false,
    val hasHiddenVolume: Boolean = false
)
