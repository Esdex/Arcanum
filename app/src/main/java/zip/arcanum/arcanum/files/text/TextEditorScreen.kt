package zip.arcanum.arcanum.files.text

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.CheckBox
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatItalic
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatQuote
import androidx.compose.material.icons.outlined.FormatStrikethrough
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalDensity
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

    /*
     * The editing view belongs to the screen, not to the branch that shows it.
     *
     * The rendered view and the editor are two ways of looking at one text, and the text -
     * with the cursor and the undo history - lives in this view. Letting AndroidView build it
     * would mean building it again on every switch of mode, which would take the history and
     * any unsaved edit with it.
     */
    var editor    by remember { mutableStateOf<CodeEditText?>(null) }
    var canUndo   by remember { mutableStateOf(false) }
    var canRedo   by remember { mutableStateOf(false) }

    val context0 = LocalContext.current
    LaunchedEffect(state.isLoading, state.failure) {
        if (!state.isLoading && state.failure == null && editor == null) {
            editor = CodeEditText(context0).apply {
                background       = null
                onTextEdited     = viewModel::onTextChange
                onHistoryChanged = { undo, redo -> canUndo = undo; canRedo = redo }
                // Wrapping decided before the text arrives: setting it afterwards lays the
                // whole file out a second time, which on a large one is the opening cost
                // paid twice.
                setWordWrap(viewModel.prefs.value.wordWrap)
                setDocument(state.savedText)
                if (state.readOnly) {
                    // Read, select, copy - but no caret to type with, and no keyboard.
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setTextIsSelectable(true)
                    isCursorVisible = false
                }
            }
        }
    }

    val isMarkdown = state.syntax == Syntax.MARKDOWN
    // Markdown has two ways to look at it. The mode survives a rotation but not the screen:
    // opening a file is opening it to read or to write, and that is decided each time.
    var viewMode  by rememberSaveable { mutableStateOf(false) }
    var pendingLink by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current

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

    // Leaving the app from a file kept in a vault is not something to do on a stray touch,
    // and the address is shown in full because that is the part worth reading.
    pendingLink?.let { url ->
        AppDialog(
            onDismissRequest = { pendingLink = null },
            title = { Text(stringResource(R.string.editor_link_title)) },
            text  = { Text(url) },
            confirmButton = {
                TextButton(onClick = {
                    pendingLink = null
                    runCatching {
                        context.startActivity(
                            android.content.Intent(
                                android.content.Intent.ACTION_VIEW,
                                android.net.Uri.parse(url)
                            )
                        )
                    }
                }) { Text(stringResource(R.string.editor_link_open)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingLink = null }) {
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
            .imePadding()
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
            if (state.failure == null && !state.isLoading && !state.readOnly && !viewMode) {
                IconButton(onClick = { editor?.undo() }, enabled = canUndo) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.editor_undo))
                }
                IconButton(onClick = { editor?.redo() }, enabled = canRedo) {
                    Icon(Icons.AutoMirrored.Outlined.Redo, stringResource(R.string.editor_redo))
                }
            }
            if (!state.readOnly && !viewMode) {
                IconButton(onClick = { viewModel.save() }, enabled = state.isDirty && !state.isSaving) {
                    Icon(Icons.Outlined.Save, stringResource(R.string.editor_save))
                }
            }
        }

        // What the file is, where that is not what it seems. Both stay on screen rather than
        // appearing once, because both are about what saving will do.
        if (state.failure == null && !state.isLoading) {
            if (state.readOnly) EditorNote(stringResource(R.string.editor_note_read_only))
            if (state.mixedNewlines) EditorNote(stringResource(R.string.editor_note_mixed_newlines))
            if (state.unknownEncoding) EditorNote(stringResource(R.string.editor_note_encoding))
        }

        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
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

                // The editing view is kept even while the rendered side is on screen: it
                // owns the text and the undo history, and letting it go would drop both
                // every time the mode is flipped. A checkbox tapped in the rendered view
                // edits through it, so the two never hold different texts.
                viewMode -> MarkdownView(
                    source       = state.text,
                    fontSizeSp   = prefs.fontSizeSp,
                    onToggleTask = { offset -> editor?.toggleTaskAt(offset) },
                    onLink       = { url -> pendingLink = url },
                    modifier     = Modifier.fillMaxSize()
                )

                else -> editor?.let { view ->
                    EditorBody(view = view, prefs = prefs, syntax = state.syntax)
                }
            }

            // Markdown is the only thing there are two ways to look at.
            if (isMarkdown && state.failure == null && !state.isLoading) {
                SmallFloatingActionButton(
                    onClick  = {
                        // Going to read: put the keyboard away first, or it stays up over a
                        // page that has nothing to type into.
                        if (!viewMode) editor?.let { view ->
                            androidx.core.view.ViewCompat.getWindowInsetsController(view)
                                ?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                        }
                        viewMode = !viewMode
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .navigationBarsPadding()
                        .padding(16.dp)
                ) {
                    Icon(
                        imageVector = if (viewMode) Icons.Outlined.Edit else Icons.Outlined.Visibility,
                        contentDescription = stringResource(
                            if (viewMode) R.string.editor_mode_edit else R.string.editor_mode_view
                        )
                    )
                }
            }
        }

        // The row of marks, over the keyboard and only while it is up: it is a keyboard
        // extension, and on a screen without one it would be a toolbar in the way.
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        if (isMarkdown && imeVisible && !viewMode && !state.readOnly &&
            state.failure == null && !state.isLoading) {
            MarkupBar(onAction = { prefix, suffix, line ->
                val view = editor ?: return@MarkupBar
                if (line) view.toggleLinePrefix(prefix) else view.wrapSelection(prefix, suffix)
            })
        }
    }
}
}

/**
 * The marks a Markdown file is written with, in reach of a thumb.
 *
 * Each button is either a pair wrapped round the selection or a prefix on the line the caret
 * is on, and each is a toggle: pressing it on text that already carries the mark takes it off.
 * The row scrolls, because ten buttons do not fit a narrow phone and hiding half of them
 * behind an overflow would cost more taps than it saves.
 */
@Composable
private fun MarkupBar(onAction: (prefix: String, suffix: String, lineWide: Boolean) -> Unit) {
    val actions = listOf(
        Triple(Icons.Outlined.FormatBold, R.string.editor_mark_bold, Triple("**", "**", false)),
        Triple(Icons.Outlined.FormatItalic, R.string.editor_mark_italic, Triple("*", "*", false)),
        Triple(Icons.Outlined.FormatStrikethrough, R.string.editor_mark_strike, Triple("~~", "~~", false)),
        Triple(Icons.Outlined.Code, R.string.editor_mark_code, Triple("`", "`", false)),
        Triple(Icons.Outlined.Link, R.string.editor_mark_link, Triple("[", "](url)", false)),
        Triple(Icons.Outlined.Title, R.string.editor_mark_heading, Triple("## ", "", true)),
        Triple(Icons.Outlined.FormatQuote, R.string.editor_mark_quote, Triple("> ", "", true)),
        Triple(Icons.AutoMirrored.Outlined.FormatListBulleted, R.string.editor_mark_bullet, Triple("- ", "", true)),
        Triple(Icons.Outlined.FormatListNumbered, R.string.editor_mark_numbered, Triple("1. ", "", true)),
        Triple(Icons.Outlined.CheckBox, R.string.editor_mark_task, Triple("- [ ] ", "", true))
    )
    HorizontalDivider()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        actions.forEach { (icon, label, marks) ->
            val (prefix, suffix, lineWide) = marks
            IconButton(onClick = { onAction(prefix, suffix, lineWide) }) {
                Icon(icon, stringResource(label))
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
    view: CodeEditText,
    prefs: TextEditorPrefs,
    syntax: Syntax
) {
    val dark    = LocalDarkMode.current
    val colors  = if (dark) SyntaxColors.Dark else SyntaxColors.Light
    val palette = remember(colors) { SyntaxHighlighter.paletteOf(colors) }

    val textColor   = MaterialTheme.colorScheme.onBackground.toArgb()
    val cursorColor = MaterialTheme.colorScheme.primary.toArgb()
    val gutterText  = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val gutterBack  = MaterialTheme.colorScheme.surfaceContainerLow.toArgb()
    val frontBack   = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb()

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        // The instance is the screen's; this only puts it on screen again after a switch of
        // mode, which is why it must be handed back with no parent of its own.
        factory  = { view },
        update   = { v ->
            v.setTextSize(TypedValue.COMPLEX_UNIT_SP, prefs.fontSizeSp.toFloat())
            v.typeface = if (prefs.monospace) Typeface.MONOSPACE else Typeface.DEFAULT
            v.setTextColor(textColor)
            v.accentColor = cursorColor
            v.gutterTextColor = gutterText
            v.gutterBackground = gutterBack
            v.frontMatterBackground = frontBack
            v.showLineNumbers = prefs.lineNumbers
            v.showWhitespace  = prefs.showWhitespace
            v.hideImeOnScroll = prefs.hideImeOnScroll
            v.palette         = palette
            v.syntax          = if (prefs.highlight) syntax else Syntax.NONE
            v.setWordWrap(prefs.wordWrap)
        }
    )
}
