package zip.arcanum.arcanum.containers.ui

import java.io.File

data class OrphanInternalVaultCandidate(
    val path: String,
    val fileName: String,
    val size: Long
)

internal object AppStorageVaultRecovery {
    fun candidateForFile(file: File, isKnownPath: Boolean): OrphanInternalVaultCandidate? {
        if (isKnownPath || !isRecoverableVaultFile(file)) return null
        return OrphanInternalVaultCandidate(
            path = file.absolutePath,
            fileName = file.name,
            size = file.length()
        )
    }

    fun isRecoverableVaultFile(file: File): Boolean {
        val size = file.length()
        return file.isFile && size >= MIN_CONTAINER_BYTES && size % SECTOR_SIZE_BYTES == 0L
    }

    fun pathFor(folderPath: String, fileName: String): String =
        File(folderPath, fileName).absolutePath

    private const val SECTOR_SIZE_BYTES = 512L
    private const val MIN_CONTAINER_BYTES = 512L
}
