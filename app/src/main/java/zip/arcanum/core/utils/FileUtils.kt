package zip.arcanum.core.utils

import android.content.Context
import android.net.Uri
import android.os.MemoryFile
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.RandomAccessFile

object FileUtils {

    /** Holds in-memory keyfile handles alive until [secureZeroAndDelete] is called. */
    private val memKeyHandles =
        java.util.concurrent.ConcurrentHashMap<String, Pair<ParcelFileDescriptor, MemoryFile>>()

    /**
     * Reads a SAF URI keyfile into anonymous shared memory — no plaintext disk write.
     * Returns (/proc/self/fd/N, displayName) or null on failure.
     * The caller must call [secureZeroAndDelete] when done to release the memory handle.
     */
    fun copyUriToCache(context: Context, uri: Uri): Pair<String, String>? = try {
        val displayName = context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "keyfile"

        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        val mf = MemoryFile("kf", bytes.size.coerceAtLeast(1))
        if (bytes.isNotEmpty()) mf.writeBytes(bytes, 0, 0, bytes.size)
        @Suppress("DiscouragedPrivateApi")
        val rawFd = mf.javaClass.getMethod("getFileDescriptor").invoke(mf) as java.io.FileDescriptor
        val pfd = ParcelFileDescriptor.dup(rawFd)
        val path = "/proc/self/fd/${pfd.fd}"
        memKeyHandles[path] = pfd to mf
        path to displayName
    } catch (_: Exception) { null }


    fun secureZeroAndDelete(file: File) {
        // For in-memory keyfiles backed by MemoryFile, close the handles — no disk data to wipe
        val handle = memKeyHandles.remove(file.absolutePath)
        if (handle != null) {
            try { handle.first.close() } catch (_: Exception) {}
            try { handle.second.close() } catch (_: Exception) {}
            return
        }
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
