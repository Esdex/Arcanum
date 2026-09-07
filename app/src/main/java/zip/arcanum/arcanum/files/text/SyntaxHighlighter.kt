package zip.arcanum.arcanum.files.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * The editor's syntax colouring, written here rather than taken from a library.
 *
 * A dependency would bring more languages than these, and it would also be a third party's
 * code running over the contents of a vault on every keystroke. These grammars are small
 * enough to read in one sitting: each syntax is one regular expression built from ordered
 * alternatives, so the first alternative that matches at a position wins - which is how a
 * keyword inside a string stays part of the string, and why comments and strings come first
 * in every list.
 *
 * Nothing here changes a single character of the file. It decides colours and nothing else.
 */
enum class Syntax { NONE, MARKDOWN, JSON, XML, KEYVALUE, SHELL, CODE }

enum class TokenKind { KEYWORD, STRING, NUMBER, COMMENT, TAG, ATTR, HEADING, EMPHASIS, LINK, PUNCT }

/** The palette, so the editor and the settings preview colour the same way. */
data class SyntaxColors(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val tag: Color,
    val attr: Color,
    val heading: Color,
    val emphasis: Color,
    val link: Color,
    val punct: Color
) {
    companion object {
        /** Readable on a light background; the dark one is the same hues lifted. */
        val Light = SyntaxColors(
            keyword   = Color(0xFF7B1FA2),
            string    = Color(0xFF1B7F3B),
            number    = Color(0xFF1565C0),
            comment   = Color(0xFF757575),
            tag       = Color(0xFF00695C),
            attr      = Color(0xFFB35C00),
            heading   = Color(0xFF1565C0),
            emphasis  = Color(0xFF5D4037),
            link      = Color(0xFF0277BD),
            punct     = Color(0xFF616161)
        )
        val Dark = SyntaxColors(
            keyword   = Color(0xFFCE93D8),
            string    = Color(0xFF9CCC65),
            number    = Color(0xFF90CAF9),
            comment   = Color(0xFF9E9E9E),
            tag       = Color(0xFF4DB6AC),
            attr      = Color(0xFFFFB74D),
            heading   = Color(0xFF90CAF9),
            emphasis  = Color(0xFFD7CCC8),
            link      = Color(0xFF81D4FA),
            punct     = Color(0xFFBDBDBD)
        )
    }
}

object SyntaxHighlighter {

    /**
     * Above this the text is left plain.
     *
     * Colouring runs over the whole buffer on every change, and a file of this size is
     * already past the point where a phone keeps up with that while somebody types. The
     * editor says the colouring is off rather than letting the keyboard lag.
     */
    const val LIMIT_CHARS = 200_000

    fun of(fileName: String): Syntax = when (fileName.substringAfterLast('.', "").lowercase()) {
        "md", "markdown"                                    -> Syntax.MARKDOWN
        "json"                                              -> Syntax.JSON
        "xml", "html", "htm", "svg", "xhtml", "plist"       -> Syntax.XML
        "ini", "conf", "cfg", "properties", "toml",
        "yml", "yaml", "env", "gitconfig"                   -> Syntax.KEYVALUE
        "sh", "bash", "zsh", "fish", "profile", "bashrc"    -> Syntax.SHELL
        "kt", "kts", "java", "c", "h", "cpp", "hpp", "cc",
        "js", "ts", "py", "rs", "go", "swift", "php", "rb",
        "css", "scss", "sql", "gradle"                      -> Syntax.CODE
        else                                                -> Syntax.NONE
    }

    /** One coloured piece of the text: where it starts, where it ends, and what it is. */
    data class Token(val start: Int, val end: Int, val kind: TokenKind)

    /**
     * The tokens between [from] and [to], with offsets into the whole text.
     *
     * A window rather than the whole file, because the editor colours what is on screen and a
     * screenful either side. The window is cut on line boundaries by the caller; a construct
     * that spans more than that - a fenced block whose opening fence is far above - is
     * coloured only from where the window starts, which is the price of not walking a quarter
     * of a megabyte on every keystroke.
     */
    fun tokens(text: CharSequence, from: Int, to: Int, syntax: Syntax): List<Token> {
        val rules = rulesFor(syntax) ?: return emptyList()
        if (to <= from) return emptyList()
        val window = text.subSequence(from, to)
        val out = ArrayList<Token>()
        for (m in rules.regex.findAll(window)) {
            val kind = rules.kindOf(m) ?: continue
            out += Token(from + m.range.first, from + m.range.last + 1, kind)
        }
        return out
    }

    /** The whole text at once, for the settings preview - never for a file being edited. */
    fun highlight(text: String, syntax: Syntax, colors: SyntaxColors): AnnotatedString {
        if (syntax == Syntax.NONE || text.length > LIMIT_CHARS) return AnnotatedString(text)
        return buildAnnotatedStringFast(text) { add ->
            for (t in tokens(text, 0, text.length, syntax)) add(t.start, t.end, style(t.kind, colors))
        }
    }

    /** The palette as plain colour ints, indexed by [TokenKind] ordinal, for the editor view. */
    fun paletteOf(colors: SyntaxColors): IntArray = IntArray(TokenKind.entries.size) { i ->
        style(TokenKind.entries[i], colors).color.let {
            android.graphics.Color.argb(
                (it.alpha * 255).toInt(), (it.red * 255).toInt(),
                (it.green * 255).toInt(), (it.blue * 255).toInt()
            )
        }
    }

    private fun style(kind: TokenKind, c: SyntaxColors): SpanStyle = when (kind) {
        TokenKind.KEYWORD  -> SpanStyle(color = c.keyword, fontWeight = FontWeight.SemiBold)
        TokenKind.STRING   -> SpanStyle(color = c.string)
        TokenKind.NUMBER   -> SpanStyle(color = c.number)
        TokenKind.COMMENT  -> SpanStyle(color = c.comment, fontStyle = FontStyle.Italic)
        TokenKind.TAG      -> SpanStyle(color = c.tag)
        TokenKind.ATTR     -> SpanStyle(color = c.attr)
        TokenKind.HEADING  -> SpanStyle(color = c.heading, fontWeight = FontWeight.Bold)
        TokenKind.EMPHASIS -> SpanStyle(color = c.emphasis, fontWeight = FontWeight.SemiBold)
        TokenKind.LINK     -> SpanStyle(color = c.link)
        TokenKind.PUNCT    -> SpanStyle(color = c.punct)
    }

    /**
     * One expression per syntax, and a group number per kind.
     *
     * Every alternative below is written with non-capturing groups inside it, because the
     * group INDEX is what says which kind matched - a stray capturing group would shift
     * every index after it and colour the wrong things.
     */
    private class Rules(patterns: List<Pair<String, TokenKind>>) {
        private val kinds = patterns.map { it.second }
        val regex = Regex(patterns.joinToString("|") { "(${it.first})" })

        fun kindOf(m: MatchResult): TokenKind? {
            for (i in kinds.indices) {
                if (m.groups[i + 1] != null) return kinds[i]
            }
            return null
        }
    }

    private val markdown by lazy {
        Rules(listOf(
            "(?s)```.*?```"                       to TokenKind.STRING,
            "`[^`\\n]+`"                          to TokenKind.STRING,
            "(?m)^#{1,6}[^\\n]*"                  to TokenKind.HEADING,
            "(?m)^\\s*>[^\\n]*"                   to TokenKind.COMMENT,
            "!?\\[[^\\]\\n]*\\]\\([^)\\n]*\\)"    to TokenKind.LINK,
            "\\*\\*[^\\n*]+\\*\\*"                to TokenKind.EMPHASIS,
            "(?m)^\\s*(?:[-*+]|\\d+\\.)\\s"       to TokenKind.PUNCT
        ))
    }

    private val json by lazy {
        Rules(listOf(
            "\"(?:\\\\.|[^\"\\\\])*\"(?=\\s*:)"   to TokenKind.ATTR,
            "\"(?:\\\\.|[^\"\\\\])*\""            to TokenKind.STRING,
            "\\b(?:true|false|null)\\b"           to TokenKind.KEYWORD,
            "-?\\b\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?\\b" to TokenKind.NUMBER,
            "[\\{\\}\\[\\],:]"                    to TokenKind.PUNCT
        ))
    }

    private val xml by lazy {
        Rules(listOf(
            "(?s)<!--.*?-->"                      to TokenKind.COMMENT,
            "\"[^\"\\n]*\"|'[^'\\n]*'"            to TokenKind.STRING,
            "</?[A-Za-z_][\\w:.-]*|/?>"           to TokenKind.TAG,
            "[A-Za-z_][\\w:.-]*(?=\\s*=)"         to TokenKind.ATTR
        ))
    }

    private val keyValue by lazy {
        Rules(listOf(
            "(?m)[#;][^\\n]*"                     to TokenKind.COMMENT,
            "(?m)^\\s*\\[[^\\]\\n]*\\]"           to TokenKind.TAG,
            "\"[^\"\\n]*\"|'[^'\\n]*'"            to TokenKind.STRING,
            "(?m)^\\s*[\\w.\\-]+(?=\\s*[:=])"     to TokenKind.ATTR,
            "\\b(?:true|false|yes|no|on|off|null)\\b" to TokenKind.KEYWORD,
            "\\b\\d+(?:\\.\\d+)?\\b"              to TokenKind.NUMBER
        ))
    }

    private val shell by lazy {
        Rules(listOf(
            "(?m)#[^\\n]*"                        to TokenKind.COMMENT,
            "\"(?:\\\\.|[^\"\\\\])*\"|'[^']*'"    to TokenKind.STRING,
            "\\$(?:\\{[^}\\n]*\\}|[A-Za-z_]\\w*)" to TokenKind.ATTR,
            ("\\b(?:if|then|else|elif|fi|for|while|until|do|done|case|esac|function|" +
             "return|local|export|source|echo|read|shift|exit|set|unset|trap)\\b") to TokenKind.KEYWORD,
            "\\b\\d+\\b"                          to TokenKind.NUMBER
        ))
    }

    private val code by lazy {
        Rules(listOf(
            "(?s)/\\*.*?\\*/"                     to TokenKind.COMMENT,
            "(?m)(?://|#)[^\\n]*"                 to TokenKind.COMMENT,
            "(?s)\"\"\".*?\"\"\"|'''(?s).*?'''"   to TokenKind.STRING,
            "\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*'" to TokenKind.STRING,
            ("\\b(?:abstract|as|assert|await|break|case|catch|class|const|continue|def|" +
             "default|del|do|elif|else|enum|except|extends|final|finally|fn|for|from|fun|" +
             "func|function|if|impl|implements|import|in|include|inline|interface|is|lambda|" +
             "let|match|module|mut|new|not|object|open|operator|override|package|pass|" +
             "private|protected|public|raise|return|self|static|struct|super|switch|this|" +
             "throw|throws|trait|try|type|typedef|union|use|val|var|void|when|where|while|" +
             "with|yield|true|false|null|none|nil|None|True|False)\\b") to TokenKind.KEYWORD,
            "\\b(?:0[xX][0-9a-fA-F]+|\\d+(?:\\.\\d+)?[fFlLuU]?)\\b" to TokenKind.NUMBER
        ))
    }

    private fun rulesFor(syntax: Syntax): Rules? = when (syntax) {
        Syntax.NONE     -> null
        Syntax.MARKDOWN -> markdown
        Syntax.JSON     -> json
        Syntax.XML      -> xml
        Syntax.KEYVALUE -> keyValue
        Syntax.SHELL    -> shell
        Syntax.CODE     -> code
    }

    /**
     * Builds the coloured string with one pass and no intermediate copies of the text -
     * `buildAnnotatedString { append(text); addStyle(...) }` copies the whole buffer into
     * the builder, which on a large file happens on every keystroke.
     */
    private inline fun buildAnnotatedStringFast(
        text: String,
        spans: ((Int, Int, SpanStyle) -> Unit) -> Unit
    ): AnnotatedString {
        val ranges = ArrayList<AnnotatedString.Range<SpanStyle>>()
        spans { start, end, style -> ranges.add(AnnotatedString.Range(style, start, end)) }
        return AnnotatedString(text, ranges)
    }
}
