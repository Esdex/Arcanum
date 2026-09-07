package zip.arcanum.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import zip.arcanum.R
import zip.arcanum.arcanum.files.text.Syntax
import zip.arcanum.arcanum.files.text.SyntaxColors
import zip.arcanum.arcanum.files.text.SyntaxHighlighter
import zip.arcanum.core.components.GroupedBox
import zip.arcanum.core.components.GroupedSwitch
import zip.arcanum.core.components.SettingsGroup
import zip.arcanum.core.security.TextEditorPrefs
import zip.arcanum.core.theme.LocalDarkMode

/**
 * The editor's settings, with the editor itself at the top of them (#109).
 *
 * The preview is not a picture: it is the same gutter, the same colouring and the same font
 * the editor uses, so a change to a switch below is answered above by the thing that will
 * change. That is the whole reason this screen exists as its own rather than as five rows in
 * Appearance - "monospace" and "show whitespace" mean nothing described in words and
 * everything shown.
 */
@Composable
internal fun TextEditorSubScreen(
    prefs: TextEditorPrefs,
    onFontSize: (Int) -> Unit,
    onMonospace: (Boolean) -> Unit,
    onLineNumbers: (Boolean) -> Unit,
    onWordWrap: (Boolean) -> Unit,
    onHighlight: (Boolean) -> Unit,
    onWhitespace: (Boolean) -> Unit,
    onOpenNew: (Boolean) -> Unit,
    onHideIme: (Boolean) -> Unit,
    onBack: () -> Unit
) {
    SubScreenScaffold(title = stringResource(R.string.settings_editor_title), onBack = onBack) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = innerPadding.calculateTopPadding(), bottom = innerPadding.calculateBottomPadding())
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            EditorPreview(prefs)

            SettingsGroup(title = stringResource(R.string.settings_editor_group_text)) {
                row { shape ->
                    GroupedBox(shape) {
                        Row(
                            modifier              = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment     = Alignment.CenterVertically
                        ) {
                            Text(
                                text  = stringResource(R.string.settings_editor_font_size),
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Text(
                                text  = "${prefs.fontSizeSp} sp",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        Slider(
                            value         = prefs.fontSizeSp.toFloat(),
                            onValueChange = { onFontSize(it.toInt()) },
                            valueRange    = TextEditorPrefs.FONT_SIZES.first.toFloat()..
                                            TextEditorPrefs.FONT_SIZES.last.toFloat(),
                            steps         = TextEditorPrefs.FONT_SIZES.count() - 2,
                            modifier      = Modifier.fillMaxWidth()
                        )
                    }
                }
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_editor_monospace),
                        checked         = prefs.monospace,
                        onCheckedChange = onMonospace
                    )
                }
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_editor_word_wrap),
                        checked         = prefs.wordWrap,
                        onCheckedChange = onWordWrap
                    )
                }
            }

            SettingsGroup(title = stringResource(R.string.settings_editor_group_gutter)) {
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_editor_line_numbers),
                        checked         = prefs.lineNumbers,
                        onCheckedChange = onLineNumbers
                    )
                }
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_editor_whitespace),
                        checked         = prefs.showWhitespace,
                        onCheckedChange = onWhitespace
                    )
                }
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_editor_highlight),
                        info            = stringResource(R.string.settings_editor_highlight_desc),
                        checked         = prefs.highlight,
                        onCheckedChange = onHighlight
                    )
                }
            }

            SettingsGroup(title = stringResource(R.string.settings_editor_group_behaviour)) {
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_editor_open_new),
                        checked         = prefs.openAfterCreate,
                        onCheckedChange = onOpenNew
                    )
                }
                row { shape ->
                    GroupedSwitch(
                        shape           = shape,
                        title           = stringResource(R.string.settings_editor_hide_ime),
                        checked         = prefs.hideImeOnScroll,
                        onCheckedChange = onHideIme
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * A few lines of the editor, drawn the way the editor draws them.
 *
 * The sample is Markdown because it is the format with something to show for every setting on
 * this screen: a heading and a link for the colouring, indentation for the whitespace dots,
 * and a line long enough to run off the edge for the wrap.
 */
@Composable
private fun EditorPreview(prefs: TextEditorPrefs) {
    val colors = if (LocalDarkMode.current) SyntaxColors.Dark else SyntaxColors.Light
    val style  = TextStyle(
        fontFamily = if (prefs.monospace) FontFamily.Monospace else FontFamily.Default,
        fontSize   = prefs.fontSizeSp.sp,
        lineHeight = (prefs.fontSizeSp * 1.45f).sp,
        color      = MaterialTheme.colorScheme.onSurface
    )

    val source = stringResource(R.string.settings_editor_preview_sample)
    val shown  = if (prefs.showWhitespace) source.replace(' ', '·') else source
    val text   = if (prefs.highlight) SyntaxHighlighter.highlight(shown, Syntax.MARKDOWN, colors)
                 else androidx.compose.ui.text.AnnotatedString(shown)
    val lines  = source.count { it == '\n' } + 1

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Text(
            text     = stringResource(R.string.settings_editor_preview),
            style    = MaterialTheme.typography.labelLarge,
            color    = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp)
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 150.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(vertical = 12.dp)
        ) {
            if (prefs.lineNumbers) {
                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier
                        .width((lines.toString().length * prefs.fontSizeSp * 0.62f + 20f).dp)
                        .padding(end = 8.dp)
                ) {
                    for (n in 1..lines) {
                        Text(
                            text  = n.toString(),
                            style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
                        )
                    }
                }
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp)
                    .then(if (prefs.wordWrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))
            ) {
                Text(text = text, style = style, softWrap = prefs.wordWrap)
            }
        }
    }
}
