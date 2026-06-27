package zip.arcanum.core.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.RandomAccessFile

object FileUtils {

    /**
     * Reads a SAF URI into a ByteArray without writing anything to disk.
     * Returns (bytes, displayName) or null on failure.
     * Caller should zero the array when done: bytes.fill(0).
     */
    fun readKeyfileBytes(context: Context, uri: Uri): Pair<ByteArray, String>? = try {
        val displayName = context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "keyfile"
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        bytes to displayName
    } catch (_: Exception) { null }

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


    fun secureZeroAndDelete(file: File) {
        try {
            val len = file.length()
            if (len > 0L) {
                val zeros = ByteArray(minOf(len, 65536L).toInt())
                RandomAccessFile(file, "rw").use { raf ->
                    var remaining = len
                    while (remaining > 0L) {
                        val toWrite = minOf(remaining, zeros.size.toLong()).toInt()
                        raf.write(zeros, 0, toWrite)
                        remaining -= toWrite
                    }
                    raf.fd.sync()
                }
            }
        } catch (_: Exception) {}
        file.delete()
    }

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

    fun normalizeSafUri(uri: Uri): Uri {
        return try {
            val paths = uri.pathSegments
            val treeIdx = paths.indexOf("tree")
            val docIdx  = paths.lastIndexOf("document")
            if (treeIdx >= 0 && docIdx > treeIdx) return uri
            val authority = uri.authority ?: return uri
            val docId = DocumentsContract.getDocumentId(uri)
            DocumentsContract.buildDocumentUri(authority, docId)
        } catch (_: Exception) { uri }
    }

    fun safUriDocumentId(uri: Uri): String? = try {
        DocumentsContract.getDocumentId(uri)
    } catch (_: Exception) { null }
}
