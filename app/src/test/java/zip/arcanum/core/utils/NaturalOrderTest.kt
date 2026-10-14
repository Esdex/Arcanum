package zip.arcanum.core.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class NaturalOrderTest {

    private fun sorted(vararg names: String) = names.toList().shuffled(Random(7)).sortedWith(NaturalOrder)

    @Test
    fun numbersSortByValue() {
        // The reporter's case in #151: 1..100 used to come out as 1, 10, 100, 11, ...
        val names = (1..100).map { it.toString() }
        assertEquals(names, names.shuffled(Random(1)).sortedWith(NaturalOrder))
    }

    @Test
    fun numbersInsideNames() {
        assertEquals(
            listOf("IMG_2.jpg", "IMG_9.jpg", "IMG_10.jpg", "img_11.jpg", "IMG_100.jpg"),
            sorted("IMG_100.jpg", "IMG_10.jpg", "img_11.jpg", "IMG_9.jpg", "IMG_2.jpg")
        )
        assertEquals(
            listOf("v1.2", "v1.10", "v2.0"),
            sorted("v2.0", "v1.10", "v1.2")
        )
    }

    @Test
    fun paddedAndUnpaddedNamesInterleaveByValue() {
        // The workaround the reporter had to use keeps working, and mixes with plain numbers.
        assertEquals(
            listOf("001", "2", "003", "10", "042", "0420"),
            sorted("0420", "10", "003", "2", "042", "001")
        )
    }

    @Test
    fun equalValuesHaveAFixedOrder() {
        // Same number: fewer leading zeros first; same text but case: a fixed order too.
        assertEquals(listOf("7", "07", "007"), sorted("007", "7", "07"))
        assertTrue(NaturalOrder.compare("a", "A") != 0)
        assertEquals(0, NaturalOrder.compare("same", "same"))
    }

    @Test
    fun caseIsIgnoredBeforeTheTieBreak() {
        assertEquals(listOf("apple", "Banana", "cherry"), sorted("cherry", "Banana", "apple"))
    }

    @Test
    fun longNumbersDoNotOverflow() {
        val big = "9".repeat(40)
        val bigger = "1" + "0".repeat(40)
        assertEquals(listOf("2", big, bigger), sorted(bigger, "2", big))
    }

    @Test
    fun prefixComesFirst() {
        assertEquals(listOf("file", "file1", "file1a", "file2"), sorted("file2", "file1a", "file", "file1"))
    }

    @Test
    fun contractHoldsOnRandomNames() {
        // A comparator that breaks antisymmetry or transitivity makes TimSort throw on large
        // lists, so the property is checked directly on names built from the awkward parts.
        val parts = listOf("0", "00", "1", "01", "9", "10", "a", "A", "b", "_", "-", ".", " ", "z")
        val rnd = Random(151)
        val names = List(300) { List(rnd.nextInt(1, 5)) { parts.random(rnd) }.joinToString("") }
        for (a in names) for (b in names) {
            assertEquals("antisymmetry $a / $b",
                Integer.signum(NaturalOrder.compare(a, b)), -Integer.signum(NaturalOrder.compare(b, a)))
        }
        for (n in 0 until 20000) {
            val (a, b, c) = List(3) { names.random(rnd) }
            if (NaturalOrder.compare(a, b) <= 0 && NaturalOrder.compare(b, c) <= 0) {
                assertTrue("transitivity $a <= $b <= $c", NaturalOrder.compare(a, c) <= 0)
            }
        }
        // And the sort itself, on a list long enough for TimSort's merge checks.
        List(5000) { names.random(rnd) }.sortedWith(NaturalOrder)
    }
}
