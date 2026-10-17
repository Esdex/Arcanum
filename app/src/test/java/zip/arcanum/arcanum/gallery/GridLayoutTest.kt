package zip.arcanum.arcanum.gallery

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import zip.arcanum.arcanum.gallery.ui.GalleryViewModel
import zip.arcanum.arcanum.gallery.ui.GridIndex
import zip.arcanum.arcanum.gallery.ui.layOutGrid
import zip.arcanum.core.database.entities.MediaFileEntity
import zip.arcanum.core.database.entities.MediaFileType
import java.time.LocalDate

class GridLayoutTest {

    private var next = 0
    private fun photos(n: Int) = List(n) {
        val i = next++
        MediaFileEntity("id$i", "c", "/p$i.jpg", "p$i.jpg", MediaFileType.IMAGE, 1L, 0L, 0L)
    }
    private fun day(n: Int, year: Int = 2026) = GalleryViewModel.DayGroup(LocalDate.of(year, 1, 1), "d", photos(n))
    private fun month(name: String, vararg days: Int) = GalleryViewModel.MonthGroup(name, days.map { day(it) })
    private fun monthOf(year: Int, vararg days: Int) =
        GalleryViewModel.MonthGroup("Jan $year", days.map { day(it, year) })

    @Test
    fun headersTakeARowAndEachDayStartsOne() {
        // March: header, day header, 4 pictures (2 rows), day header, 2 pictures (1 row).
        val l = layOutGrid(listOf(month("March", 4, 2)), grouped = true, leadingItems = 0)
        assertArrayEquals(intArrayOf(0, 1, 2, 2, 2, 3, 4, 5, 5, 6), l.lineOf)
        assertEquals(7, l.totalLines)
        assertEquals(l.lineOf.size, l.files.size)
    }

    @Test
    fun withoutHeadersPicturesRunOnAcrossDays() {
        // 2 + 2 + 2 pictures with no headers between them pack into two rows, then the spacer.
        val l = layOutGrid(listOf(month("March", 2, 2), month("April", 2)), grouped = false, leadingItems = 0)
        assertArrayEquals(intArrayOf(0, 0, 0, 1, 1, 1, 2), l.lineOf)
        assertEquals(3, l.totalLines)
    }

    @Test
    fun leadingRowsComeFirst() {
        val l = layOutGrid(listOf(month("March", 1)), grouped = true, leadingItems = 2)
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4, 5), l.lineOf)
        assertEquals(listOf(null, null, "March", "March", "March", null), l.monthOf)
    }

    @Test
    fun anEmptyGridIsTheSpacerAlone() {
        val l = layOutGrid(emptyList(), grouped = true, leadingItems = 0)
        assertArrayEquals(intArrayOf(0), l.lineOf)
        assertEquals(1, l.totalLines)
    }

    @Test
    fun itemAtLineFindsTheFirstItemOnTheRow() {
        val l = layOutGrid(listOf(month("March", 4, 2)), grouped = true, leadingItems = 0)
        val index = GridIndex(l.lineOf, l.totalLines) { null }
        assertEquals(0, index.itemAtLine(0))
        assertEquals(2, index.itemAtLine(2))   // the first of the three on row 2
        assertEquals(5, index.itemAtLine(3))   // the fourth picture, alone on row 3
        assertEquals(9, index.itemAtLine(99))  // past the end: the last item
    }

    @Test
    fun eachYearStartsWhereItsFirstMonthDoes() {
        // With headers, after one leading row: 2026's month header is row 1, its day header row 2,
        // four pictures rows 3-4, so 2025's month header lands on row 5.
        val grouped = layOutGrid(listOf(monthOf(2026, 4), monthOf(2025, 1), monthOf(2025, 1)), grouped = true, leadingItems = 1)
        assertEquals(listOf(2026 to 1, 2025 to 5), grouped.yearStarts)
        // Without headers the first picture of 2025 joins the unfinished row of 2026.
        val flat = layOutGrid(listOf(monthOf(2026, 4), monthOf(2025, 2)), grouped = false, leadingItems = 0)
        assertEquals(listOf(2026 to 0, 2025 to 1), flat.yearStarts)
        assertEquals(1, flat.lineOf[4])   // that first 2025 picture
    }
}
