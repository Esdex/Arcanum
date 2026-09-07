package zip.arcanum.arcanum.files.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import zip.arcanum.R

/**
 * What the FAB's "New" opens: one sheet that walks from what to create, through what kind of
 * document it is, to what it is called.
 *
 * It is one sheet on purpose rather than a menu that opens a dialog. Choosing a document type
 * and naming the file are two halves of the same decision, and a sheet that drops away to be
 * replaced by a dialog reads as two separate things happening. Each step slides the content
 * sideways and the way back is where it always is - the arrow, or the system's own back.
 *
 * Nothing is written until [onCreateDocument] or [onCreateFolder] is called, so leaving the
 * sheet at any point leaves the vault exactly as it was.
 */
@Composable
fun CreateNewSheetContent(
    takenNames: Set<String>,
    onCreateFolder: (String) -> Unit,
    onCreateDocument: (String) -> Unit
) {
    var stage     by rememberSaveable { mutableStateOf(NewStage.CHOICE) }
    var isFolder  by rememberSaveable { mutableStateOf(false) }
    var extension by rememberSaveable { mutableStateOf("") }
    var customExt by rememberSaveable { mutableStateOf(false) }
    var name      by rememberSaveable { mutableStateOf("") }

    val focus = remember { FocusRequester() }

    fun back() {
        stage = when (stage) {
            NewStage.CHOICE -> NewStage.CHOICE
            NewStage.TYPE   -> NewStage.CHOICE
            NewStage.NAME   -> if (isFolder) NewStage.CHOICE else NewStage.TYPE
        }
    }
    BackHandler(enabled = stage != NewStage.CHOICE) { back() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // The name step brings the keyboard up over the sheet, and the Create button is
            // the thing under it.
            .imePadding()
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // A plain arrow, not the app's round BackButton: this steps back inside the
            // sheet, it is not the way out of a screen.
            if (stage != NewStage.CHOICE) {
                IconButton(onClick = { back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                }
            } else {
                Spacer(Modifier.width(16.dp))
            }
            Text(
                text       = when {
                    stage == NewStage.CHOICE -> stringResource(R.string.files_new_sheet_title)
                    stage == NewStage.TYPE   -> stringResource(R.string.files_new_type_title)
                    isFolder                 -> stringResource(R.string.files_new_name_folder)
                    else                     -> stringResource(R.string.files_new_name_document)
                },
                style      = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier   = Modifier.padding(vertical = 12.dp)
            )
        }

        AnimatedContent(
            targetState    = stage,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val width   = if (forward) 1 else -1
                (slideInHorizontally(tween(220)) { it / 3 * width } + fadeIn(tween(160))) togetherWith
                    (slideOutHorizontally(tween(220)) { -it / 3 * width } + fadeOut(tween(120)))
            },
            label = "new_item_stage"
        ) { current ->
            when (current) {
                NewStage.CHOICE -> Column {
                    NewRow(
                        icon  = Icons.Outlined.CreateNewFolder,
                        title = stringResource(R.string.files_action_new_folder)
                    ) {
                        isFolder = true; extension = ""; customExt = false; stage = NewStage.NAME
                    }
                    NewRow(
                        icon  = Icons.Outlined.Description,
                        title = stringResource(R.string.files_new_document)
                    ) {
                        isFolder = false; stage = NewStage.TYPE
                    }
                }

                NewStage.TYPE -> Column {
                    NewRow(icon = Icons.AutoMirrored.Outlined.Notes, title = ".txt", subtitle = stringResource(R.string.files_new_type_txt)) {
                        extension = "txt"; customExt = false; stage = NewStage.NAME
                    }
                    NewRow(icon = Icons.Outlined.Tag, title = ".md", subtitle = stringResource(R.string.files_new_type_md)) {
                        extension = "md"; customExt = false; stage = NewStage.NAME
                    }
                    NewRow(icon = Icons.Outlined.MoreHoriz, title = stringResource(R.string.files_new_type_other)) {
                        extension = ""; customExt = true; stage = NewStage.NAME
                    }
                }

                NewStage.NAME -> NameStep(
                    isFolder    = isFolder,
                    customExt   = customExt,
                    extension   = extension,
                    onExtension = { extension = it },
                    name        = name,
                    onName      = { name = it },
                    takenNames  = takenNames,
                    focus       = focus,
                    onCreate    = { finalName ->
                        if (isFolder) onCreateFolder(finalName) else onCreateDocument(finalName)
                    }
                )
            }
        }

        Spacer(Modifier.navigationBarsPadding().height(8.dp))
    }
}

enum class NewStage { CHOICE, TYPE, NAME }

@Composable
private fun NewRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit
) {
    ListItem(
        colors           = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent   = { Icon(icon, contentDescription = null) },
        headlineContent  = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent  = {
            Icon(
                Icons.Outlined.ChevronRight,
                contentDescription = null,
                tint               = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        modifier         = Modifier.clickable(onClick = onClick)
    )
}

@Composable
private fun NameStep(
    isFolder: Boolean,
    customExt: Boolean,
    extension: String,
    onExtension: (String) -> Unit,
    name: String,
    onName: (String) -> Unit,
    takenNames: Set<String>,
    focus: FocusRequester,
    onCreate: (String) -> Unit
) {
    LaunchedEffect(Unit) { focus.requestFocus() }

    val trimmed  = name.trim()
    val ext      = extension.trim().removePrefix(".")
    val fullName = if (isFolder || ext.isEmpty()) trimmed else "$trimmed.$ext"

    val badChars  = trimmed.any { it in ILLEGAL } || trimmed.endsWith(".")
    val badExt    = customExt && (ext.any { it in ILLEGAL || it == '.' || it == ' ' })
    // Case-insensitively, because FAT and exFAT do not tell "Notes.txt" from "notes.txt":
    // there the second name would open the first file, and creating a document truncates
    // what it opens. Refusing is stricter than ext4 needs and is the safe way round.
    val taken     = fullName.isNotEmpty() && takenNames.any { it.equals(fullName, ignoreCase = true) }
    val error     = when {
        badChars || badExt -> if (badExt) R.string.files_new_error_ext else R.string.files_new_error_chars
        taken              -> R.string.files_new_error_taken
        else               -> null
    }
    val canCreate = trimmed.isNotEmpty() && error == null && (!customExt || ext.isNotEmpty())

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value           = name,
                onValueChange   = onName,
                label           = { Text(stringResource(R.string.files_new_name_label)) },
                // The extension is shown rather than typed, so what is being made is visible
                // without it being something to get wrong.
                suffix          = if (!isFolder && ext.isNotEmpty()) { { Text(".$ext") } } else null,
                singleLine      = true,
                isError         = error != null,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (canCreate) onCreate(fullName) }),
                modifier        = Modifier.weight(1f).focusRequester(focus)
            )
            if (customExt) {
                OutlinedTextField(
                    value         = extension,
                    onValueChange = onExtension,
                    label         = { Text(stringResource(R.string.files_new_ext_label)) },
                    singleLine    = true,
                    isError       = badExt,
                    modifier      = Modifier.width(110.dp)
                )
            }
        }

        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text  = stringResource(error),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(16.dp))
        Button(
            onClick  = { onCreate(fullName) },
            enabled  = canCreate,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.files_new_folder_create)) }
        Spacer(Modifier.height(8.dp))
    }
}

/** What FAT refuses in a name, and the separator that would silently make this a path. */
private const val ILLEGAL = "\\/:*?\"<>|"
