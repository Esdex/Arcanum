package zip.arcanum.core.utils

/**
 * The single list of file extensions the app treats as media.
 *
 * These used to be spelled out separately in MediaScanner, FileUtils, FileManagerViewModel
 * and FileManagerScreen, and had drifted apart: only some knew about `opus`, `bmp` or `m4v`,
 * so the same file could be music in one screen and a generic file in another. Anything
 * that needs to classify a filename reads it from here.
 *
 * Lives in core rather than next to the gallery because the file browser and the storage
 * breakdown need it too, and core must not depend on a feature package.
 */
object MediaExtensions {

    val IMAGE = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp")
    val VIDEO = setOf("mp4", "mkv", "avi", "mov", "3gp", "webm", "m4v")
    val AUDIO = setOf("mp3", "flac", "aac", "ogg", "wav", "m4a", "opus")

    /** Lowercased extension without the dot, or "" when the name has none. */
    fun of(fileName: String): String = fileName.substringAfterLast('.', "").lowercase()

    fun isImage(fileName: String): Boolean = of(fileName) in IMAGE
    fun isVideo(fileName: String): Boolean = of(fileName) in VIDEO
    fun isAudio(fileName: String): Boolean = of(fileName) in AUDIO
}
