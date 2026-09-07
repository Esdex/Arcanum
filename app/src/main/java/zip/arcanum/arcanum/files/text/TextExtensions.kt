package zip.arcanum.arcanum.files.text

/**
 * Which files a tap opens in the editor.
 *
 * The list is deliberately of names people expect to be text, not of everything that could be
 * read as text. Anything outside it can still be opened through "Edit as text" in the file's
 * own menu, and that path is safe because the editor refuses bytes that are not text - so the
 * answer to an unknown extension is to let it be asked for rather than to guess at it.
 */
object TextExtensions {

    val EDITABLE = setOf(
        "txt", "text", "md", "markdown", "log", "csv", "tsv",
        "json", "xml", "html", "htm", "svg", "xhtml", "plist",
        "ini", "conf", "cfg", "properties", "toml", "yml", "yaml", "env", "gitignore",
        "sh", "bash", "zsh", "fish", "profile", "bashrc",
        "kt", "kts", "java", "c", "h", "cpp", "hpp", "cc",
        "js", "ts", "py", "rs", "go", "swift", "php", "rb",
        "css", "scss", "sql", "gradle", "srt", "vtt", "diff", "patch"
    )

    fun isText(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in EDITABLE
}
