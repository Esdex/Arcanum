package zip.arcanum.crypto

/**
 * Capacity and free space of the filesystem inside a mounted volume, in bytes.
 *
 * Distinct from the volume size in the VeraCrypt header on purpose: expanding a
 * container grows the volume but leaves the filesystem at its original size, so
 * the header size can describe space no write will ever reach.
 */
data class FsUsage(
    val totalBytes: Long,
    val freeBytes: Long,
)
