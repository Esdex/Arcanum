package zip.arcanum.core.utils

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun Long.toFormattedDate(pattern: String = "MMM d, yyyy"): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(this))

fun Long.toFormattedDateTime(pattern: String = "MMM d, yyyy HH:mm"): String =
    SimpleDateFormat(pattern, Locale.getDefault()).format(Date(this))

fun String.truncateMiddle(maxLength: Int): String {
    if (length <= maxLength) return this
    val half = maxLength / 2
    return "${take(half)}…${takeLast(half)}"
}
