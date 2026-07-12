package zip.arcanum.arcanum.containers.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageBreakdownTest {

    // ── Classification ───────────────────────────────────────────────────────

    @Test
    fun categorize_mapsKnownExtensionsToBuckets() {
        assertEquals(StorageCategory.PHOTOS, StorageCategorizer.categorize("jpg"))
        assertEquals(StorageCategory.PHOTOS, StorageCategorizer.categorize("heic"))
        assertEquals(StorageCategory.VIDEOS, StorageCategorizer.categorize("mp4"))
        assertEquals(StorageCategory.MUSIC,  StorageCategorizer.categorize("flac"))
    }

    @Test
    fun categorize_isCaseInsensitive() {
        assertEquals(StorageCategory.PHOTOS, StorageCategorizer.categorize("JPG"))
        assertEquals(StorageCategory.VIDEOS, StorageCategorizer.categorize("MoV"))
    }

    @Test
    fun categorize_unknownAndEmptyFallBackToFiles() {
        assertEquals(StorageCategory.FILES, StorageCategorizer.categorize("txt"))
        assertEquals(StorageCategory.FILES, StorageCategorizer.categorize("zip"))
        assertEquals(StorageCategory.FILES, StorageCategorizer.categorize(""))
    }

    @Test
    fun categorizeFile_extractsExtension_andHandlesNoExtension() {
        assertEquals(StorageCategory.MUSIC, StorageCategorizer.categorizeFile("song.FLAC"))
        assertEquals(StorageCategory.PHOTOS, StorageCategorizer.categorizeFile("holiday.trip.jpeg"))
        // No dot → whole thing has empty extension → FILES.
        assertEquals(StorageCategory.FILES, StorageCategorizer.categorizeFile("README"))
    }

    // ── Breakdown math ───────────────────────────────────────────────────────

    private val sample = StorageBreakdown(
        capacity = 1000L,
        used = mapOf(
            StorageCategory.PHOTOS to 200L,
            StorageCategory.VIDEOS to 300L,
            StorageCategory.MUSIC  to 100L,
            // FILES intentionally absent → treated as 0.
        ),
        isLoading = false
    )

    @Test
    fun usedTotal_sumsOnlyContentBuckets() {
        assertEquals(600L, sample.usedTotal)
    }

    @Test
    fun freeSpace_isCapacityMinusUsed() {
        assertEquals(400L, sample.freeSpace)
    }

    @Test
    fun bytesOf_derivesFreeSpace_andDefaultsMissingToZero() {
        assertEquals(400L, sample.bytesOf(StorageCategory.FREE_SPACE))
        assertEquals(0L, sample.bytesOf(StorageCategory.FILES))
        assertEquals(200L, sample.bytesOf(StorageCategory.PHOTOS))
    }

    @Test
    fun freeSpace_neverNegative_whenUsedExceedsCapacity() {
        val over = StorageBreakdown(
            capacity = 100L,
            used = mapOf(StorageCategory.FILES to 250L),
            isLoading = false
        )
        assertEquals(0L, over.freeSpace)
        assertEquals(0L, over.bytesOf(StorageCategory.FREE_SPACE))
    }
}
