package zip.arcanum.arcanum.files.text

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Markdown as it reads rather than as it is written (#109).
 *
 * The blocks come from [Markdown.parse]; this only decides how each one looks. Two of them do
 * something: a checkbox edits the file it came from, and a link asks before leaving the app -
 * both are the caller's to handle, because both reach outside what a renderer should decide.
 */
@Composable
fun MarkdownView(
    source: String,
    fontSizeSp: Int,
    onToggleTask: (markerOffset: Int) -> Unit,
    onLink: (url: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val blocks = remember(source) { Markdown.parse(source) }
    val base   = fontSizeSp.sp

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
    ) {
        items(blocks) { block ->
            when (block) {
                is MdBlock.Heading   -> HeadingBlock(block, base, onLink)
                is MdBlock.Paragraph -> Text(
                    text     = inline(block.text, onLink),
                    style    = TextStyle(fontSize = base, lineHeight = base * 1.5f),
                    color    = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                is MdBlock.Code      -> CodeBlock(block, base)
                is MdBlock.Quote     -> QuoteBlock(block, base, onLink)
                is MdBlock.Item      -> ItemBlock(block, base, onLink)
                is MdBlock.Task      -> TaskBlock(block, base, onToggleTask, onLink)
                is MdBlock.Table     -> TableBlock(block, base, onLink)
                is MdBlock.FrontMatter -> FrontMatterBlock(block, base)
                MdBlock.Rule         -> HorizontalDivider(Modifier.padding(vertical = 12.dp))
            }
        }
    }
}

@Composable
private fun HeadingBlock(block: MdBlock.Heading, base: androidx.compose.ui.unit.TextUnit, onLink: (String) -> Unit) {
    val scale = when (block.level) {
        1 -> 1.9f; 2 -> 1.55f; 3 -> 1.3f; 4 -> 1.15f; 5 -> 1.05f; else -> 1f
    }
    Column(modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)) {
        Text(
            text  = inline(block.text, onLink),
            style = TextStyle(
                fontSize   = base * scale,
                fontWeight = FontWeight.Bold,
                lineHeight = base * scale * 1.3f
            ),
            color = if (block.level >= 6) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onBackground
        )
        // The rule under the first two levels is what makes a long note read as sections,
        // and it is what people recognise from a rendered README.
        if (block.level <= 2) {
            Spacer(Modifier.height(6.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun CodeBlock(block: MdBlock.Code, base: androidx.compose.ui.unit.TextUnit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .horizontalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        Text(
            text  = block.text,
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = base * 0.9f, lineHeight = base * 1.4f),
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false
        )
    }
}

@Composable
private fun QuoteBlock(block: MdBlock.Quote, base: androidx.compose.ui.unit.TextUnit, onLink: (String) -> Unit) {
    Row(modifier = Modifier.padding(vertical = 6.dp)) {
        Box(
            Modifier
                .width(4.dp)
                .height(base.value.dp * 1.6f)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text  = inline(block.text, onLink),
            style = TextStyle(fontSize = base, lineHeight = base * 1.5f),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ItemBlock(block: MdBlock.Item, base: androidx.compose.ui.unit.TextUnit, onLink: (String) -> Unit) {
    Row(
        modifier = Modifier.padding(start = (block.indent * 16).dp, top = 3.dp, bottom = 3.dp)
    ) {
        Text(
            text  = block.number?.let { "$it." } ?: "•",
            style = TextStyle(fontSize = base, lineHeight = base * 1.5f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(if (block.number != null) 28.dp else 20.dp)
        )
        Text(
            text  = inline(block.text, onLink),
            style = TextStyle(fontSize = base, lineHeight = base * 1.5f),
            color = MaterialTheme.colorScheme.onBackground
        )
    }
}

@Composable
private fun TaskBlock(
    block: MdBlock.Task,
    base: androidx.compose.ui.unit.TextUnit,
    onToggle: (Int) -> Unit,
    onLink: (String) -> Unit
) {
    Row(
        modifier          = Modifier.padding(start = (block.indent * 16).dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (block.number != null) {
            Text(
                text  = "${block.number}.",
                style = TextStyle(fontSize = base, lineHeight = base * 1.5f),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(28.dp)
            )
        }
        Checkbox(
            checked         = block.checked,
            onCheckedChange = { onToggle(block.markerOffset) },
            modifier        = Modifier.size(36.dp)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text  = inline(block.text, onLink),
            style = TextStyle(fontSize = base, lineHeight = base * 1.5f),
            color = if (block.checked) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onBackground
        )
    }
}

/** The note's own properties, set apart from the text rather than read as part of it. */
@Composable
private fun FrontMatterBlock(block: MdBlock.FrontMatter, base: androidx.compose.ui.unit.TextUnit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(12.dp)
    ) {
        block.entries.forEach { (key, value) ->
            Row(modifier = Modifier.padding(vertical = 2.dp)) {
                Text(
                    text     = key,
                    style    = TextStyle(fontSize = base * 0.9f, fontWeight = FontWeight.SemiBold),
                    color    = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(110.dp)
                )
                Text(
                    text  = value,
                    style = TextStyle(fontSize = base * 0.9f),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

/**
 * A table, laid out by what is in it.
 *
 * Compose has no table, and a row of cells sharing the width equally makes a column of one
 * word as wide as a column of a sentence. So every cell is measured once with the text style
 * it will be drawn in, each column takes its widest, and the whole thing scrolls sideways
 * when the sum is wider than the screen - a table is the one block that may legitimately be
 * wider than the page.
 */
@Composable
private fun TableBlock(
    block: MdBlock.Table,
    base: androidx.compose.ui.unit.TextUnit,
    onLink: (String) -> Unit
) {
    val measurer = rememberTextMeasurer()
    val style    = TextStyle(fontSize = base, lineHeight = base * 1.4f)
    val density  = LocalDensity.current
    val columns  = maxOf(block.header.size, block.rows.maxOfOrNull { it.size } ?: 0)

    val widths = remember(block, base) {
        (0 until columns).map { column ->
            val cells = listOf(block.header.getOrNull(column).orEmpty()) +
                block.rows.map { it.getOrNull(column).orEmpty() }
            val widest = cells.maxOf { cell ->
                measurer.measure(AnnotatedString(cell.ifEmpty { " " }), style).size.width
            }
            with(density) { widest.toDp() }.coerceIn(48.dp, 260.dp) + 24.dp
        }
    }

    Column(
        modifier = Modifier
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .horizontalScroll(rememberScrollState())
    ) {
        Row(modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
            block.header.forEachIndexed { column, cell ->
                TableCell(cell, widths.getOrElse(column) { 96.dp }, block.aligns.getOrElse(column) { -1 },
                          base, bold = true, onLink = onLink)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        block.rows.forEachIndexed { rowIndex, row ->
            Row(
                modifier = Modifier.background(
                    // The faint stripe is what keeps a wide row readable across the screen.
                    if (rowIndex % 2 == 1) MaterialTheme.colorScheme.surfaceContainer
                    else Color.Transparent
                )
            ) {
                for (column in 0 until columns) {
                    TableCell(row.getOrNull(column).orEmpty(), widths.getOrElse(column) { 96.dp },
                              block.aligns.getOrElse(column) { -1 }, base, bold = false, onLink = onLink)
                }
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    width: androidx.compose.ui.unit.Dp,
    align: Int,
    base: androidx.compose.ui.unit.TextUnit,
    bold: Boolean,
    onLink: (String) -> Unit
) {
    Text(
        text  = inline(text, onLink),
        style = TextStyle(
            fontSize   = base,
            lineHeight = base * 1.4f,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
            textAlign  = when (align) {
                0    -> TextAlign.Center
                1    -> TextAlign.End
                else -> TextAlign.Start
            }
        ),
        color    = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.width(width).padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

/**
 * The marks inside a line: bold, italic, struck through, code, and links.
 *
 * One pass, taking whichever mark begins earliest, so `**bold with `code` in it**` does not
 * come out as two half-open runs. Anything unmatched stays the text it was - an asterisk in
 * the middle of a word is an asterisk.
 */
@Composable
private fun inline(text: String, onLink: (String) -> Unit): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    val codeColor = MaterialTheme.colorScheme.tertiary
    return remember(text, linkColor, codeColor) {
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                val next = INLINE_RULES
                    .mapNotNull { rule -> rule.regex.find(text, i)?.let { rule to it } }
                    .minByOrNull { it.second.range.first }
                if (next == null) { append(text.substring(i)); break }

                val (rule, match) = next
                if (match.range.first > i) append(text.substring(i, match.range.first))
                when (rule.kind) {
                    InlineKind.ESCAPED -> append(match.groupValues[1])
                    InlineKind.BOLD_ITALIC -> withStyle(
                        SpanStyle(fontWeight = FontWeight.Bold, fontStyle = FontStyle.Italic)
                    ) { append(match.groupValues[1]) }
                    InlineKind.BOLD   -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(match.groupValues[1]) }
                    InlineKind.ITALIC -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(match.groupValues[1]) }
                    InlineKind.STRIKE -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(match.groupValues[1]) }
                    InlineKind.CODE   -> withStyle(
                        SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor)
                    ) { append(match.groupValues[1]) }
                    InlineKind.LINK   -> {
                        val label = match.groupValues[1].ifBlank { match.groupValues[2] }
                        val url   = match.groupValues[2]
                        withLink(
                            LinkAnnotation.Clickable(
                                tag    = url,
                                styles = TextLinkStyles(
                                    SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                                ),
                                linkInteractionListener = { onLink(url) }
                            )
                        ) { append(label) }
                    }
                }
                i = match.range.last + 1
            }
        }
    }
}

private enum class InlineKind { BOLD, ITALIC, BOLD_ITALIC, STRIKE, CODE, LINK, ESCAPED }

private class InlineRule(val regex: Regex, val kind: InlineKind)

/* Code first: what is inside backticks is text, whatever marks it contains. */
private val INLINE_RULES = listOf(
    InlineRule(Regex("`([^`\n]+)`"), InlineKind.CODE),
    // A backslash makes the next mark a character: it must be read before the marks are.
    InlineRule(Regex("\\\\([\\\\`*_{}\\[\\]()#+.!~>|-])"), InlineKind.ESCAPED),
    InlineRule(Regex("\\*\\*\\*([^*\n]+)\\*\\*\\*"), InlineKind.BOLD_ITALIC),
    InlineRule(Regex("!?\\[([^\\]\n]*)]\\(([^)\\s]+)\\)"), InlineKind.LINK),
    InlineRule(Regex("\\*\\*([^*\n]+)\\*\\*"), InlineKind.BOLD),
    InlineRule(Regex("__([^_\n]+)__"), InlineKind.BOLD),
    InlineRule(Regex("~~([^~\n]+)~~"), InlineKind.STRIKE),
    InlineRule(Regex("(?<![\\w*])\\*([^*\n]+)\\*(?![\\w*])"), InlineKind.ITALIC),
    InlineRule(Regex("(?<![\\w_])_([^_\n]+)_(?![\\w_])"), InlineKind.ITALIC)
)
