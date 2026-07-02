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
    val hasHiddenVolume: Boolean = false,
    val safUri: String = "",
    val keySize: Int = 0,
    val encryptionMode: String = "XTS",
    val blockSize: Int = 128,
    val formatVersion: Int = 2,
    val hasBackupHeader: Boolean = true,
    val pkcs5Iterations: Int = 0,
    val headerModifiedAt: Long = 0L,
    val isReadOnly: Boolean = false
)
