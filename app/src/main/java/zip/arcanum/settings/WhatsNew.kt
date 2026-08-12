package zip.arcanum.settings

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What's New changelog, loaded at runtime from `assets/whatsnew.json`.
 *
 * The JSON is the single source of truth for the in-app What's New screen, the
 * F-Droid changelogs, and the GitHub release notes (the latter two are generated
 * by `release.sh`). See issue #80.
 */
@Serializable
data class WhatsNewData(
    val versions: List<WhatsNewVersion> = emptyList()
)

@Serializable
data class WhatsNewVersion(
    val version: String,
    val versionCode: Int,
    val entries: List<WhatsNewEntryData> = emptyList()
)

@Serializable
data class WhatsNewEntryData(
    val type: String,
    val title: String,
    val description: String? = null
)

/** Icon + accent color for an entry [type]. Unknown types fall back to "new". */
fun whatsNewVisualsFor(type: String): Pair<ImageVector, Color> = when (type) {
    "security"    -> Icons.Outlined.Security  to Color(0xFF22C55E)
    "improvement" -> Icons.Outlined.Refresh   to Color(0xFF3B82F6)
    "fix"         -> Icons.Outlined.BugReport  to Color(0xFFEF4444)
    else          -> Icons.Outlined.Stars      to Color(0xFFFFC107)
}

/**
 * Display rank for an entry [type]: features, then improvements, then security,
 * then fixes.
 *
 * Kept next to [whatsNewVisualsFor] so the two never disagree about what a type
 * means, and unknown types rank with "new" for the same reason they are drawn
 * as it.
 */
private fun whatsNewRankFor(type: String): Int = when (type) {
    "improvement" -> 1
    "security"    -> 2
    "fix"         -> 3
    else          -> 0
}

/**
 * The entries in the order they should be shown. [sortedBy] is stable, so
 * entries keep the order they were authored in within their own group -
 * whatsnew.json stays the place to control that.
 */
fun List<WhatsNewEntryData>.inDisplayOrder(): List<WhatsNewEntryData> =
    sortedBy { whatsNewRankFor(it.type) }

private val whatsNewJson = Json { ignoreUnknownKeys = true }

/**
 * Parses `assets/whatsnew.json`. Returns an empty changelog if the asset is
 * missing or malformed rather than crashing the settings screen.
 */
fun loadWhatsNew(context: Context): WhatsNewData = runCatching {
    context.assets.open("whatsnew.json").bufferedReader().use { reader ->
        whatsNewJson.decodeFromString<WhatsNewData>(reader.readText())
    }
}.getOrElse { WhatsNewData() }

/**
 * Bare URLs the release notes mention, made tappable.
 *
 * Kept deliberately narrow: only an explicit scheme, a www host, or this project's own
 * docs domain. A general "looks like a domain" pattern would light up "e.g." and version
 * numbers, and a release note is prose - the cost of a false positive is a link that
 * goes nowhere.
 */
private val LINK_PATTERN = Regex("""https?://\S+|www\.\S+|arcanum\.zip/\S+""")

/** Trailing punctuation belongs to the sentence, not to the address. */
private const val TRAILING = ".,;:!?)"

@Composable
fun linkified(text: String): AnnotatedString {
    val styles = TextLinkStyles(
        style = SpanStyle(
            color = MaterialTheme.colorScheme.primary,
            textDecoration = TextDecoration.Underline
        )
    )
    return buildAnnotatedString {
        var cursor = 0
        for (m in LINK_PATTERN.findAll(text)) {
            val shown = m.value.trimEnd { it in TRAILING }
            if (shown.isEmpty()) continue
            append(text.substring(cursor, m.range.first))
            val href = if (shown.startsWith("http")) shown else "https://$shown"
            withLink(LinkAnnotation.Url(href, styles)) { append(shown) }
            cursor = m.range.first + shown.length
        }
        append(text.substring(cursor))
    }
}
