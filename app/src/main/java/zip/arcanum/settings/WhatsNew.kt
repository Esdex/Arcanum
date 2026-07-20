package zip.arcanum.settings

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Stars
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
