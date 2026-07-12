package zip.arcanum.arcanum.containers.domain

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.ui.graphics.vector.ImageVector
import zip.arcanum.R
import zip.arcanum.arcanum.gallery.MediaScanner

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
 * @param capacity total volume data-area size in bytes (`getDataSize`).
 * @param used     bytes per content category; FREE_SPACE is intentionally absent
 *                 and derived on demand via [freeSpace] / [bytesOf].
 */
data class StorageBreakdown(
    val capacity: Long,
    val used: Map<StorageCategory, Long>,
    val isLoading: Boolean = false,
) {
    val usedTotal: Long get() = used.values.sum()

    /** Capacity minus everything used, never negative. */
    val freeSpace: Long get() = (capacity - usedTotal).coerceAtLeast(0L)

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
 * extension sets from [MediaScanner] so the donut agrees with the gallery.
 * Kept side-effect free so it can be unit-tested without Android.
 */
object StorageCategorizer {

    /** Classify a bare file extension (without dot). Anything unknown → FILES. */
    fun categorize(extension: String): StorageCategory = when (extension.lowercase()) {
        in MediaScanner.IMAGE_EXTENSIONS -> StorageCategory.PHOTOS
        in MediaScanner.VIDEO_EXTENSIONS -> StorageCategory.VIDEOS
        in MediaScanner.AUDIO_EXTENSIONS -> StorageCategory.MUSIC
        else                             -> StorageCategory.FILES
    }

    /** Category for a file name, extracting its extension. */
    fun categorizeFile(fileName: String): StorageCategory =
        categorize(fileName.substringAfterLast('.', ""))
}
