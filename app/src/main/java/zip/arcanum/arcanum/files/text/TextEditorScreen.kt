package zip.arcanum.arcanum.files.text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import android.graphics.Typeface
import android.util.TypedValue
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.Surface
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import zip.arcanum.BuildConfig
import zip.arcanum.R
import zip.arcanum.core.components.BackButton
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.components.EmptyStateView
import zip.arcanum.core.security.TextEditorPrefs
import zip.arcanum.core.theme.LocalDarkMode

/**
 * The text editor (#109).
 *
 * Two things about it are worth knowing before reading further. The gutter is drawn from the
 * text field's own layout rather than as a column of its own, because with wrapping on, one
 * line of a file occupies several rows on screen and only the layout knows where each line
 * begins. And the colouring is a [VisualTransformation], which means the buffer the file is
 * written from never holds a single character the user did not type - the colours and the
 * dots standing in for spaces exist only on the way to the screen.
 */
@Composable
fun TextEditorScreen(
    onBack: () -> Unit,
    viewModel: TextEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val prefs by viewModel.prefs.collectAsState()

    // The editing view keeps the history; the bar only mirrors what it says about it.
    var editor    by remember { mutableStateOf<CodeEditText?>(null) }
    var canUndo   by remember { mutableStateOf(false) }
    var canRedo   by remember { mutableStateOf(false) }

    // The view owns the text and the cursor from the moment the file is handed to it; the
    // view model is told what changed and nothing is ever pushed back, which is what keeps a
    // save from moving the cursor out from under a finger.

    fun leave() {
        viewModel.save()
        onBack()
    }
    BackHandler { leave() }

    // Backgrounding the app is the last moment before an auto-lock can take the vault away,
    // so it is where the buffer has to be on disk. onDispose covers the ordinary way out.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) viewModel.save()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            viewModel.save()
        }
    }

    // Surface, not a bare Column: AppTheme calls MaterialTheme without one, so a screen
    // that provides no surface of its own leaves LocalContentColor at its default black -
    // which is invisible on a dark background, and is what made this bar unreadable.
    // The one thing a save can refuse to do on its own: a file whose encoding has no room
    // for what was typed. Silence here would mean question marks in someone's config file.
    if (state.encodingRefused) {
        AppDialog(
            onDismissRequest = { viewModel.resolveEncoding(convertToUtf8 = false) },
            title = { Text(stringResource(R.string.editor_encoding_title)) },
            text  = { Text(stringResource(R.string.editor_encoding_message)) },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveEncoding(convertToUtf8 = true) }) {
                    Text(stringResource(R.string.editor_encoding_convert))
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.resolveEncoding(convertToUtf8 = false) }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color    = MaterialTheme.colorScheme.background
    ) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier          = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            BackButton(onClick = { leave() })
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text     = state.name,
                    style    = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val note = when {
                    state.isSaving -> stringResource(R.string.editor_saving)
                    state.isDirty  -> stringResource(R.string.editor_unsaved)
                    else           -> null
                }
                if (note != null) {
                    Text(
                        text  = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (state.failure == null && !state.isLoading) {
                IconButton(onClick = { editor?.undo() }, enabled = canUndo) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.editor_undo))
                }
                IconButton(onClick = { editor?.redo() }, enabled = canRedo) {
                    Icon(Icons.AutoMirrored.Outlined.Redo, stringResource(R.string.editor_redo))
                }
            }
            IconButton(onClick = { viewModel.save() }, enabled = state.isDirty && !state.isSaving) {
                Icon(Icons.Outlined.Save, stringResource(R.string.editor_save))
            }
        }

        // What the file is, where that is not what it seems. Both stay on screen rather than
        // appearing once, because both are about what saving will do.
        if (state.failure == null && !state.isLoading) {
            if (state.mixedNewlines) EditorNote(stringResource(R.string.editor_note_mixed_newlines))
            if (state.unknownEncoding) EditorNote(stringResource(R.string.editor_note_encoding))
        }

        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when {
                state.isLoading -> CircularProgressIndicator()

                state.failure != null -> EmptyStateView(
                    lottieRes = when (state.failure) {
                        TextEditorViewModel.Failure.VAULT_CLOSED -> R.raw.info
                        else                                     -> R.raw.error
                    },
                    title = stringResource(
                        when (state.failure) {
                            TextEditorViewModel.Failure.TOO_LARGE    -> R.string.editor_error_too_large
                            TextEditorViewModel.Failure.NOT_TEXT     -> R.string.editor_error_not_text
                            TextEditorViewModel.Failure.VAULT_CLOSED -> R.string.editor_error_vault_closed
                            else                                     -> R.string.editor_error_unreadable
                        }
                    ),
                    subtitle = when (state.failure) {
                        TextEditorViewModel.Failure.TOO_LARGE -> stringResource(R.string.editor_error_too_large_desc,
                            android.text.format.Formatter.formatShortFileSize(
                                LocalContext.current, TextDocument.MAX_EDITABLE_BYTES))
                        TextEditorViewModel.Failure.NOT_TEXT  -> stringResource(R.string.editor_error_not_text_desc)
                        else                                  -> null
                    },
                    loop     = false,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )

                else -> EditorBody(
                    initialText = state.savedText,
                    onChange    = viewModel::onTextChange,
                    prefs       = prefs,
                    syntax      = state.syntax,
                    onCreated   = { editor = it },
                    onHistory   = { undo, redo -> canUndo = undo; canRedo = redo }
                )
            }
        }
    }
}
}

/** One line of quiet explanation under the bar. */
@Composable
private fun EditorNote(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.labelSmall,
        color    = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 6.dp)
    )
}

@Composable
private fun EditorBody(
    initialText: String,
    onChange: (String) -> Unit,
    prefs: TextEditorPrefs,
    syntax: Syntax,
    onCreated: (CodeEditText) -> Unit,
    onHistory: (canUndo: Boolean, canRedo: Boolean) -> Unit
) {
    val dark    = LocalDarkMode.current
    val colors  = if (dark) SyntaxColors.Dark else SyntaxColors.Light
    val palette = remember(colors) { SyntaxHighlighter.paletteOf(colors) }

    val textColor   = MaterialTheme.colorScheme.onBackground.toArgb()
    val cursorColor = MaterialTheme.colorScheme.primary.toArgb()
    val gutterText  = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val gutterBack  = MaterialTheme.colorScheme.surfaceContainerLow.toArgb()

    AndroidView(
        modifier = Modifier.fillMaxSize().imePadding(),
        factory  = { ctx ->
            CodeEditText(ctx).apply {
                background       = null
                onTextEdited     = onChange
                onHistoryChanged = onHistory
                // Wrapping decided before the text arrives: setting it afterwards lays the
                // whole file out a second time, which on a large one is the whole opening
                // cost paid twice.
                setWordWrap(prefs.wordWrap)
                // The text is handed over ONCE, here. Writing it again on a later pass would
                // take the cursor and the selection with it on every keystroke.
                setDocument(initialText)
                onCreated(this)
            }
        },
        update = { view ->
            view.onTextEdited = onChange
            view.onHistoryChanged = onHistory
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.fontSizeSp.toFloat())
            view.typeface = if (prefs.monospace) Typeface.MONOSPACE else Typeface.DEFAULT
            view.setTextColor(textColor)
            view.accentColor = cursorColor
            view.gutterTextColor = gutterText
            view.gutterBackground = gutterBack
            view.showLineNumbers = prefs.lineNumbers
            view.showWhitespace  = prefs.showWhitespace
            view.hideImeOnScroll = prefs.hideImeOnScroll
            view.palette         = palette
            view.syntax          = if (prefs.highlight) syntax else Syntax.NONE
            view.setWordWrap(prefs.wordWrap)
        }
    )
}
