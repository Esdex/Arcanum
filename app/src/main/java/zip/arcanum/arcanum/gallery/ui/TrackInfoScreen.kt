package zip.arcanum.arcanum.gallery.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import zip.arcanum.R
import zip.arcanum.core.components.BackButton
import java.text.DateFormat
import java.util.Date

/**
 * File info for a track, laid out after AIMP's: the cover with the file's format beside it,
 * then its tags, then where it lies in the vault. Read-only for now; editing comes next.
 *
 * There is no play count and no "last played", which AIMP keeps in its library: keeping them
 * would mean a record, outside the vault, of what was listened to inside it. And no button to
 * copy it all: the clipboard is open to the keyboard and to other apps.
 */
@Composable
fun TrackInfoScreen(
    onBack: () -> Unit,
    viewModel: TrackInfoViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    // A hand-rolled header goes black on a dark theme without a Surface to take its colour from.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                // No navigationBarsPadding: the tab bar at the bottom takes the inset itself.
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onClick = onBack)
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.track_info_title),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        viewModel.name,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // Keeps the titles centred until the edit button takes this place.
                Spacer(Modifier.width(48.dp))
            }

            when (val s = state) {
                TrackInfoViewModel.State.Loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator()
                }
                TrackInfoViewModel.State.Failed -> Box(Modifier.fillMaxSize().padding(32.dp), Alignment.Center) {
                    Text(
                        stringResource(R.string.track_info_failed),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is TrackInfoViewModel.State.Loaded -> {
                    var tab by rememberSaveable { mutableIntStateOf(TAB_BASIC) }
                    Box(Modifier.weight(1f)) { TrackInfoContent(s.info, viewModel, tab) }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        NavigationBarItem(
                            selected = tab == TAB_BASIC,
                            onClick  = { tab = TAB_BASIC },
                            icon     = { Icon(Icons.Outlined.MusicNote, null) },
                            label    = { Text(stringResource(R.string.track_info_tab_basic)) }
                        )
                        NavigationBarItem(
                            selected = tab == TAB_LYRICS,
                            onClick  = { tab = TAB_LYRICS },
                            icon     = { Icon(Icons.Outlined.Lyrics, null) },
                            label    = { Text(stringResource(R.string.track_info_tab_lyrics)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackInfoContent(info: TrackInfoViewModel.TrackInfo, viewModel: TrackInfoViewModel, tab: Int) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Cover(info)
            Spacer(Modifier.width(16.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                info.durationMs?.let {
                    Text(formatDuration(it), style = MaterialTheme.typography.headlineSmall)
                }
                formatLine(info)?.let { TechLine(it) }
                TechLine(android.text.format.Formatter.formatShortFileSize(LocalContext.current, viewModel.size))
                if (viewModel.format.isNotEmpty()) TechLine(viewModel.format)
                info.modifiedMs?.let {
                    TechLine(stringResource(R.string.track_info_modified,
                        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))))
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        if (tab == TAB_BASIC) {
            TagRow(Icons.Outlined.MusicNote, R.string.track_info_tag_title, info.title)
            TagRow(Icons.Outlined.Person, R.string.track_info_tag_artist, info.artist)
            TagRow(Icons.Outlined.Album, R.string.track_info_tag_album, info.album)
            TagRow(Icons.Outlined.Groups, R.string.track_info_tag_album_artist, info.albumArtist)
            TagRow(Icons.Outlined.LibraryMusic, R.string.track_info_tag_genre, info.genre)
            TagRow(Icons.Outlined.CalendarToday, R.string.track_info_tag_year, info.year)
            TagRow(Icons.Outlined.Tag, R.string.track_info_tag_track, info.trackNumber)
        } else {
            TagRow(Icons.Outlined.EditNote, R.string.track_info_tag_lyricist, info.lyricist)
            Spacer(Modifier.height(8.dp))
            // Not selectable on purpose: a selection offers Copy, and the clipboard is not ours.
            Text(
                info.lyrics ?: stringResource(R.string.track_info_no_lyrics),
                style = MaterialTheme.typography.bodyLarge,
                color = if (info.lyrics == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(Modifier.height(24.dp))
        Text(
            viewModel.path,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun Cover(info: TrackInfoViewModel.TrackInfo) {
    val shape = RoundedCornerShape(20.dp)
    val cover = info.cover
    if (cover != null) {
        Image(
            bitmap = cover.asImageBitmap(),
            contentDescription = stringResource(R.string.audio_cd_album_art),
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(150.dp).clip(shape)
        )
    } else {
        Box(
            modifier = Modifier
                .size(150.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Outlined.MusicNote,
                contentDescription = stringResource(R.string.track_info_no_cover),
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun TechLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One tag: its icon and its value, with the tag's name read out for TalkBack. */
@Composable
private fun TagRow(icon: ImageVector, @StringRes label: Int, value: String?) {
    val name = stringResource(label)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .semantics(mergeDescendants = true) { contentDescription = "$name: ${value ?: "-"}" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(
            value ?: "—",
            style = MaterialTheme.typography.bodyLarge,
            color = if (value == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
        )
    }
}

private const val TAB_BASIC = 0
private const val TAB_LYRICS = 1

/** "48 kHz, 320 kbps, Stereo", from whatever of it the file reports. */
@Composable
private fun formatLine(info: TrackInfoViewModel.TrackInfo): String? {
    val parts = buildList {
        info.sampleRateHz?.let {
            val khz = if (it % 1000 == 0) (it / 1000).toString() else "%.1f".format(it / 1000.0)
            add(stringResource(R.string.track_info_khz, khz))
        }
        info.bitrateBps?.let { add(stringResource(R.string.track_info_kbps, (it + 500) / 1000)) }
        info.channels?.let {
            add(when (it) {
                1 -> stringResource(R.string.track_info_mono)
                2 -> stringResource(R.string.track_info_stereo)
                else -> pluralStringResource(R.plurals.track_info_channels, it, it)
            })
        }
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

/** 2:16.800, as AIMP shows it - to the millisecond, with hours only when there are any. */
private fun formatDuration(ms: Long): String {
    val h = ms / 3_600_000
    val m = ms / 60_000 % 60
    val s = ms / 1000 % 60
    val milli = ms % 1000
    return if (h > 0) "%d:%02d:%02d.%03d".format(h, m, s, milli) else "%d:%02d.%03d".format(m, s, milli)
}
