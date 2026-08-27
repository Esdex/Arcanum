package zip.arcanum.core.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import java.io.File
import java.io.RandomAccessFile

object FileUtils {

    /**
     * VeraCrypt mixes only the first megabyte of a keyfile into the password
     * (VC_KEYFILE_MAX_READ in vc_header.cpp, and vc_process_keyfile_buf stops at it),
     * so bytes past this point cannot change the outcome for any volume, ours or a
     * desktop one. Reading only this much is therefore not a behaviour change.
     */
    const val KEYFILE_MAX_BYTES = 1 * 1024 * 1024

    /**
     * When the provider says a document was last modified, in epoch milliseconds, or 0
     * when it will not say - a provider may omit the column, return null, or refuse the
     * query outright, and none of that should cost an import.
     *
     * A vault should keep the date a file arrived with rather than the moment it was
     * copied, and writing the content moves the timestamp to now on both filesystems, so
     * this is what gets stamped back afterwards (#154).
     */
    fun uriLastModified(context: Context, uri: Uri): Long =
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null, null, null
            )?.use { c ->
                val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                if (c.moveToFirst() && idx >= 0 && !c.isNull(idx)) c.getLong(idx) else 0L
            } ?: 0L
        }.getOrDefault(0L)

    /**
     * Reads a SAF URI into a ByteArray without writing anything to disk.
     * Returns (bytes, displayName) or null on failure.
     * Caller should zero the array when done: bytes.fill(0).
     *
     * Reads at most [KEYFILE_MAX_BYTES]. It used to read the whole file, which killed
     * the process on any large pick - a 350 MB file asked for a 350 MB allocation
     * against a 256 MB heap (#136). That arrives as OutOfMemoryError, an Error and not
     * an Exception, so the catch below never saw it and the app simply vanished.
     *
     * Both the name query and the read can block on a network-backed provider, so this
     * must not be called on the main thread.
     */
    fun readKeyfileBytes(context: Context, uri: Uri): Pair<ByteArray, String>? = try {
        val displayName = context.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "keyfile"
        val bytes = context.contentResolver.openInputStream(uri)?.use { stream ->
            val buf = ByteArray(KEYFILE_MAX_BYTES)
            var filled = 0
            while (filled < KEYFILE_MAX_BYTES) {
                val n = stream.read(buf, filled, KEYFILE_MAX_BYTES - filled)
                if (n <= 0) break
                filled += n
            }
            // Trim to what the file actually held, and wipe the oversized buffer rather
            // than leaving a megabyte of keyfile lying in the heap.
            if (filled == KEYFILE_MAX_BYTES) buf else buf.copyOf(filled).also { buf.fill(0) }
        } ?: return null
        bytes to displayName
    } catch (_: Throwable) { null }

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

    // Media classification lives in MediaExtensions - see the note there.

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
