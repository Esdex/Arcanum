package zip.arcanum.arcanum.containers.domain

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.ui.graphics.vector.ImageVector
import zip.arcanum.R
import zip.arcanum.core.utils.MediaExtensions

/**
 * The five storage buckets shown on the Info → Storage donut.
 *
 * This enum is the single source of truth for the per-type colour used both by
 * the donut segments and the legend dots. [FREE_SPACE] is derived (capacity
 * minus everything used) and is rendered grey with no flying icon.
 */
enum class StorageCategory(
    val labelRes: Int,
    val colorValue: Long,
    val icon: ImageVector?,
) {
    PHOTOS    (R.string.storage_cat_photos,     0xFF4F8DFD, Icons.Outlined.Image),
    VIDEOS    (R.string.storage_cat_videos,     0xFFAF52DE, Icons.Outlined.Movie),
    MUSIC     (R.string.storage_cat_music,      0xFF34C759, Icons.Outlined.MusicNote),
    FILES     (R.string.storage_cat_files,      0xFFFF9F0A, Icons.Outlined.InsertDriveFile),
    FREE_SPACE(R.string.storage_cat_free_space, 0xFF9E9E9E, null);

    companion object {
        /** Real content buckets, in display order (everything except derived FREE_SPACE). */
        val content: List<StorageCategory> = listOf(PHOTOS, VIDEOS, MUSIC, FILES)
    }
}

/**
 * A computed usage snapshot for one mounted vault.
 *
 * @param capacity     size of the filesystem inside the volume, in bytes - not the
 *                     volume size from the VeraCrypt header, which counts space the
 *                     filesystem cannot reach after an expand.
 * @param used         bytes per content category; FREE_SPACE is intentionally absent
 *                     and derived on demand via [freeSpace] / [bytesOf].
 * @param reportedFree free space as the filesystem itself reports it, or null when it
 *                     could not be queried.
 */
data class StorageBreakdown(
    val capacity: Long,
    val used: Map<StorageCategory, Long>,
    val isLoading: Boolean = false,
    val reportedFree: Long? = null,
) {
    val usedTotal: Long get() = used.values.sum()

    /**
     * What the filesystem says is free, falling back to capacity minus the file
     * sizes we summed. The fallback reads slightly high because it cannot see
     * cluster slack or directory overhead, so prefer the reported figure.
     */
    val freeSpace: Long get() = reportedFree ?: (capacity - usedTotal).coerceAtLeast(0L)

    /** Bytes for any category, treating FREE_SPACE as derived. */
    fun bytesOf(category: StorageCategory): Long =
        if (category == StorageCategory.FREE_SPACE) freeSpace else (used[category] ?: 0L)

    companion object {
        /** Placeholder shown while the tree walk is still running. */
        val Loading = StorageBreakdown(capacity = 0L, used = emptyMap(), isLoading = true)
    }
}

/**
 * Pure classification of a file into a [StorageCategory], reusing the canonical
 * extension sets from [MediaExtensions] so the donut agrees with every other screen.
 * Kept side-effect free so it can be unit-tested without Android.
 */
object StorageCategorizer {

    /** Classify a bare file extension (without dot). Anything unknown → FILES. */
    fun categorize(extension: String): StorageCategory = when (extension.lowercase()) {
        in MediaExtensions.IMAGE -> StorageCategory.PHOTOS
        in MediaExtensions.VIDEO -> StorageCategory.VIDEOS
        in MediaExtensions.AUDIO -> StorageCategory.MUSIC
        else                             -> StorageCategory.FILES
    }

    /** Category for a file name, extracting its extension. */
    fun categorizeFile(fileName: String): StorageCategory =
        categorize(fileName.substringAfterLast('.', ""))
}
