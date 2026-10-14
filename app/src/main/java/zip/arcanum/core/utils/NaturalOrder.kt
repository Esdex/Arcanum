package zip.arcanum.core.utils

/**
 * Orders names the way people number things: "2" before "10", "IMG_9" before "IMG_10" (#151).
 *
 * A plain string sort compares character by character, so "10" lands between "1" and "2" and
 * the only way to get a numbered folder in order was to pad every name with zeros by hand.
 * Here a run of digits is compared as the number it spells, and everything else as text,
 * ignoring case as the name sorts always have.
 *
 * Numbers of any length are compared without parsing them, so a 40-digit name cannot
 * overflow: leading zeros are set aside, then the longer number is the larger one, then the
 * digits decide. Names that are still equal after that ("7" and "007", "a" and "A") are put
 * in a fixed order - fewer leading zeros first, then by the exact text - so that two lists
 * sorted from different starting orders always come out the same.
 */
object NaturalOrder : Comparator<String> {

    override fun compare(a: String, b: String): Int {
        val x = a.lowercase()
        val y = b.lowercase()
        var i = 0
        var j = 0
        var zerosTieBreak = 0
        while (i < x.length && j < y.length) {
            val cx = x[i]
            val cy = y[j]
            if (cx.isAsciiDigit() && cy.isAsciiDigit()) {
                val startX = i
                val startY = j
                while (i < x.length && x[i] == '0') i++
                while (j < y.length && y[j] == '0') j++
                val sigX = i
                val sigY = j
                while (i < x.length && x[i].isAsciiDigit()) i++
                while (j < y.length && y[j].isAsciiDigit()) j++
                val lenX = i - sigX
                val lenY = j - sigY
                if (lenX != lenY) return lenX.compareTo(lenY)
                for (k in 0 until lenX) {
                    val d = x[sigX + k].compareTo(y[sigY + k])
                    if (d != 0) return d
                }
                if (zerosTieBreak == 0) zerosTieBreak = (sigX - startX).compareTo(sigY - startY)
            } else {
                if (cx != cy) return cx.compareTo(cy)
                i++
                j++
            }
        }
        val rest = (x.length - i).compareTo(y.length - j)
        if (rest != 0) return rest
        if (zerosTieBreak != 0) return zerosTieBreak
        return a.compareTo(b)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}
