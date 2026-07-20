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

    /*
     * There is deliberately no copyUriToCache() here any more.
     *
     * It existed solely to stage keyfiles for the flows that took paths rather
     * than bytes, and it put the user's keyfile in cacheDir in plaintext to do
     * it (issue #116). Every flow now reads keyfiles with readKeyfileBytes()
     * above and passes the bytes to the native layer, which no longer has a
     * function that opens a keyfile by name. Do not reintroduce this.
     */

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

    /**
     * Wipes decrypted copies left in cacheDir/arcanum_temp by the pre-#103 Open with, which
     * exported a file before handing it to another app. Nothing writes there any more - files
     * are served straight from the mounted vault over SAF - but upgrading does not clean up
     * after the old build, and a process killed mid-flight never got the chance to either.
     *
     * Called at app start rather than only from the file browser, so a leftover is not left
     * sitting in the cache until the user happens to open that screen. Do the call off the
     * main thread: a leftover can be a whole video, and secureZeroAndDelete overwrites it.
     */
    fun purgeLegacyTempFiles(context: Context) {
        runCatching {
            val dir = File(context.cacheDir, LEGACY_TEMP_DIR)
            dir.listFiles()?.forEach { secureZeroAndDelete(it) }
            dir.delete()
        }
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

    private const val LEGACY_TEMP_DIR = "arcanum_temp"
}
