package zip.arcanum.arcanum.gallery.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.HideImage
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Lyrics
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import zip.arcanum.R
import zip.arcanum.arcanum.gallery.domain.MediaFile
import zip.arcanum.arcanum.gallery.tags.Id3v2Writer
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.components.AppSheet
import zip.arcanum.core.components.BackButton
import java.text.DateFormat
import java.util.Date

/**
 * File info for a track, laid out after AIMP's: the cover with the file's format beside it,
 * then its tags, then where it lies in the vault. The pencil turns the tags into fields, and
 * the save button writes them and goes back to the player, which shows them at once.
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
    val editor by viewModel.editor.collectAsState()
    val refusal by viewModel.refusal.collectAsState()
    var askDiscard by remember { mutableStateOf(false) }

    // Back out of the editor, not out of the screen; edits are not dropped on a stray touch,
    // and nothing leaves while a save is being written.
    fun back() {
        val e = editor
        when {
            e == null -> onBack()
            e.saving -> Unit
            viewModel.hasChanges() -> askDiscard = true
            else -> viewModel.cancelEditing()
        }
    }
    BackHandler(enabled = editor != null) { back() }

    if (askDiscard) {
        AppDialog(
            onDismissRequest = { askDiscard = false },
            title = { Text(stringResource(R.string.track_info_discard_title)) },
            text  = { Text(stringResource(R.string.track_info_discard_message)) },
            confirmButton = {
                TextButton(onClick = { askDiscard = false; viewModel.cancelEditing() }) {
                    Text(stringResource(R.string.track_info_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { askDiscard = false }) {
                    Text(stringResource(R.string.track_info_keep_editing))
                }
            }
        )
    }
    editor?.failure?.let { failure ->
        AppDialog(
            onDismissRequest = viewModel::dismissSaveFailure,
            title = { Text(stringResource(R.string.track_info_save_failed_title)) },
            text  = {
                Text(stringResource(
                    if (failure == TrackInfoViewModel.SaveFailure.LINKED) R.string.track_info_save_linked_message
                    else R.string.track_info_save_failed_message
                ))
            },
            confirmButton = {
                TextButton(onClick = viewModel::dismissSaveFailure) { Text(stringResource(R.string.common_ok)) }
            }
        )
    }
    if (editor?.coverRefused == true) {
        AppDialog(
            onDismissRequest = viewModel::dismissCoverRefused,
            title = { Text(stringResource(R.string.track_info_cover_refused_title)) },
            text  = { Text(stringResource(R.string.track_info_cover_refused_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissCoverRefused) { Text(stringResource(R.string.common_ok)) }
            }
        )
    }
    if (refusal != null) {
        AppDialog(
            onDismissRequest = viewModel::dismissRefusal,
            title = { Text(stringResource(R.string.track_info_unsupported_title)) },
            text  = { Text(stringResource(R.string.track_info_unsupported_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissRefusal) { Text(stringResource(R.string.common_ok)) }
            }
        )
    }

    // A hand-rolled header goes black on a dark theme without a Surface to take its colour from.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                // No navigationBarsPadding: the tab bar at the bottom takes the inset itself,
                // and the keyboard's inset is taken here so the field being typed in stays in view.
                .imePadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackButton(onClick = ::back)
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
                Box(Modifier.size(48.dp), Alignment.Center) {
                    val e = editor
                    val loaded = state as? TrackInfoViewModel.State.Loaded
                    when {
                        e?.saving == true -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        e != null -> FilledTonalIconButton(onClick = { viewModel.save(onSaved = onBack) }, enabled = !e.preparingCover) {
                            Icon(Icons.Outlined.Save, stringResource(R.string.common_save))
                        }
                        // No pencil where there is no writer; the space keeps the titles centred.
                        loaded != null && loaded.editability != TrackInfoViewModel.Editability.NO_WRITER ->
                            FilledTonalIconButton(onClick = viewModel::startEditing) {
                                Icon(Icons.Outlined.Edit, stringResource(R.string.common_edit))
                            }
                    }
                }
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
                    Box(Modifier.weight(1f)) { TrackInfoContent(s.info, viewModel, tab, editor) }
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
private fun TrackInfoContent(
    info: TrackInfoViewModel.TrackInfo,
    viewModel: TrackInfoViewModel,
    tab: Int,
    editor: TrackInfoViewModel.Editor?
) {
    var coverSheet by remember { mutableStateOf(false) }
    var vaultSheet by remember { mutableStateOf(false) }
    val phonePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(viewModel::pickPhoneImage)
    }
    if (coverSheet && editor != null) {
        val hasCover = when (editor.cover) {
            is Id3v2Writer.CoverEdit.Replace -> true
            Id3v2Writer.CoverEdit.Remove -> false
            Id3v2Writer.CoverEdit.Keep -> info.cover != null
        }
        CoverSheet(
            hasCover  = hasCover,
            onVault   = { coverSheet = false; vaultSheet = true },
            onPhone   = { coverSheet = false; phonePicker.launch(arrayOf("image/*")) },
            onRemove  = { coverSheet = false; viewModel.removeCover() },
            onDismiss = { coverSheet = false }
        )
    }
    if (vaultSheet) {
        VaultPictureSheet(
            viewModel = viewModel,
            onPick    = { vaultSheet = false; viewModel.pickVaultImage(it) },
            onDismiss = { vaultSheet = false }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Cover(
                info, editor,
                onClick = editor?.takeIf { !it.saving && !it.preparingCover }?.let { { coverSheet = true } }
            )
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

        if (editor != null) {
            EditFields(editor, tab, viewModel::onEdit)
        } else if (tab == TAB_BASIC) {
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

/** The cover: the file's own, or the one picked in the editor, where a tap changes it. */
@Composable
private fun Cover(info: TrackInfoViewModel.TrackInfo, editor: TrackInfoViewModel.Editor?, onClick: (() -> Unit)?) {
    val cover: Bitmap? = when (editor?.cover) {
        is Id3v2Writer.CoverEdit.Replace -> editor.coverPreview
        Id3v2Writer.CoverEdit.Remove -> null
        else -> info.cover
    }
    val changeLabel = stringResource(R.string.track_info_cover_change)
    Box(
        modifier = Modifier
            .size(150.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = changeLabel, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center
    ) {
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = stringResource(R.string.audio_cd_album_art),
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                Icons.Outlined.MusicNote,
                contentDescription = stringResource(R.string.track_info_no_cover),
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        // In the editor the cover says that it can be changed, and shows when a picked one is being made ready.
        if (editor != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.55f)),
                contentAlignment = Alignment.Center
            ) {
                if (editor.preparingCover) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Where a new cover comes from, and the way to have none. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CoverSheet(
    hasCover: Boolean,
    onVault: () -> Unit,
    onPhone: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit
) {
    AppSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(bottom = 32.dp)) {
            SheetTitle(R.string.track_info_cover_title)
            SheetOption(Icons.Outlined.PhotoLibrary, R.string.track_info_cover_from_vault, onVault)
            SheetOption(Icons.Outlined.Smartphone, R.string.track_info_cover_from_phone, onPhone)
            if (hasCover) SheetOption(Icons.Outlined.HideImage, R.string.track_info_cover_remove, onRemove)
        }
    }
}

/**
 * The pictures in this vault's gallery, newest first. The grid bounds and scrolls itself: a
 * sheet scrolls nothing of its own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VaultPictureSheet(viewModel: TrackInfoViewModel, onPick: (MediaFile) -> Unit, onDismiss: () -> Unit) {
    val pictures by viewModel.vaultImages.collectAsState()
    AppSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        SheetTitle(R.string.track_info_cover_from_vault)
        if (pictures.isEmpty()) {
            Text(
                stringResource(R.string.track_info_cover_vault_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp).padding(horizontal = 12.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(pictures, key = { it.id }) { picture ->
                    val thumb by produceState<Bitmap?>(null, picture.id) { value = viewModel.thumbnail(picture) }
                    Box(
                        Modifier
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .clickable { onPick(picture) }
                    ) {
                        thumb?.let {
                            Image(it.asImageBitmap(), picture.fileName, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetTitle(@StringRes title: Int) {
    Text(
        stringResource(title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun SheetOption(icon: ImageVector, @StringRes label: Int, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun TechLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The tags as fields: the same rows, in the same order, as the screen shows them. */
@Composable
private fun EditFields(editor: TrackInfoViewModel.Editor, tab: Int, onEdit: (Id3v2Writer.Values) -> Unit) {
    val v = editor.values
    val enabled = !editor.saving
    if (tab == TAB_BASIC) {
        EditRow(Icons.Outlined.MusicNote, R.string.track_info_tag_title, v.title, enabled) { onEdit(v.copy(title = it)) }
        EditRow(Icons.Outlined.Person, R.string.track_info_tag_artist, v.artist, enabled) { onEdit(v.copy(artist = it)) }
        EditRow(Icons.Outlined.Album, R.string.track_info_tag_album, v.album, enabled) { onEdit(v.copy(album = it)) }
        EditRow(Icons.Outlined.Groups, R.string.track_info_tag_album_artist, v.albumArtist, enabled) { onEdit(v.copy(albumArtist = it)) }
        EditRow(Icons.Outlined.LibraryMusic, R.string.track_info_tag_genre, v.genre, enabled) { onEdit(v.copy(genre = it)) }
        EditRow(Icons.Outlined.CalendarToday, R.string.track_info_tag_year, v.year, enabled, KeyboardType.Number) { onEdit(v.copy(year = it)) }
        EditRow(Icons.Outlined.Tag, R.string.track_info_tag_track, v.trackNumber, enabled, KeyboardType.Number) { onEdit(v.copy(trackNumber = it)) }
    } else {
        EditRow(Icons.Outlined.EditNote, R.string.track_info_tag_lyricist, v.lyricist, enabled) { onEdit(v.copy(lyricist = it)) }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = v.lyrics.orEmpty(),
            onValueChange = { onEdit(v.copy(lyrics = it)) },
            label = { Text(stringResource(R.string.track_info_tab_lyrics)) },
            enabled = enabled,
            minLines = 8,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun EditRow(
    icon: ImageVector,
    @StringRes label: Int,
    value: String?,
    enabled: Boolean,
    keyboard: KeyboardType = KeyboardType.Text,
    onChange: (String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(20.dp))
        OutlinedTextField(
            value = value.orEmpty(),
            onValueChange = onChange,
            label = { Text(stringResource(label)) },
            enabled = enabled,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            modifier = Modifier.weight(1f)
        )
    }
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
