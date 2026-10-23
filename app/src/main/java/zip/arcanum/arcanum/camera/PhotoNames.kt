package zip.arcanum.arcanum.camera

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The name a new photo gets: IMG_20261023_183012.jpg, the time it was taken, the way a phone's
 * own camera names it. Two photos in the same second, or a name already in the folder, get a
 * number: IMG_20261023_183012_1.jpg. Names are compared ignoring case, because FAT and exFAT
 * vaults do.
 */
internal fun photoName(takenAt: Date, taken: Set<String>): String {
    val stem = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(takenAt)
    val lower = taken.mapTo(HashSet()) { it.lowercase(Locale.ROOT) }
    var name = "$stem.jpg"
    var n = 1
    while (name.lowercase(Locale.ROOT) in lower) name = "${stem}_${n++}.jpg"
    return name
}
