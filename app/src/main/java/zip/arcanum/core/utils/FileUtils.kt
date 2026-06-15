package zip.arcanum.core.utils

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

object FileUtils {

    /**
     * Copies a SAF URI to a temp file in the app's cache dir.
     * Returns (absolutePath, displayName) or null on failure.
     * The caller is responsible for deleting the file when done.
     */
    fun copyUriToCache(context: Context, uri: Uri): Pair<String, String>? = try {
        val displayName = context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "keyfile"

        val cacheFile = File(context.cacheDir, "arcanum_keyfile_${System.currentTimeMillis()}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            cacheFile.outputStream().use { output -> input.copyTo(output) }
        }
        cacheFile.absolutePath to displayName
    } catch (_: Exception) { null }


    fun getFileSize(file: File): Long = file.length()

    fun getHumanReadableSize(bytes: Long): String {
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> "%.2f GB".format(gb)
            mb >= 1.0 -> "%.2f MB".format(mb)
            kb >= 1.0 -> "%.2f KB".format(kb)
            else      -> "$bytes B"
        }
    }

    fun getExtension(fileName: String): String =
        fileName.substringAfterLast('.', "").lowercase()

    fun isImageFile(fileName: String): Boolean =
        getExtension(fileName) in setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif")

    fun isVideoFile(fileName: String): Boolean =
        getExtension(fileName) in setOf("mp4", "mkv", "avi", "mov", "webm", "3gp")

    fun isAudioFile(fileName: String): Boolean =
        getExtension(fileName) in setOf("mp3", "flac", "ogg", "wav", "aac", "m4a")
}
