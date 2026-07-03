package zip.arcanum.arcanum.files.domain

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import zip.arcanum.crypto.NativeFileInfo
import zip.arcanum.crypto.VeraCryptEngine
import java.io.File
import java.util.UUID

data class VaultTransferItem(
    val path: String,
    val name: String,
    val size: Long,
    val isDirectory: Boolean,
    val lastModified: Long = 0L
)

data class PreparedShareFiles(
    val files: List<File>,
    val mimeType: String
)

object VaultFileTransfer {
    const val DEFAULT_UNLOCKED_FOLDER_LABEL = "Documents/Unlocked"

    private const val CHUNK_SIZE = 1024 * 1024
    private const val SHARE_CACHE_DIR = "arcanum_share"
    private const val SHARE_CACHE_TTL_MS = 6L * 60L * 60L * 1000L

    fun fromNative(file: NativeFileInfo) = VaultTransferItem(
        path = file.path,
        name = file.name,
        size = file.size,
        isDirectory = file.isDirectory,
        lastModified = file.lastModified
    )

    fun documentTreeRoot(context: Context, treeUri: android.net.Uri): DocumentFile? =
        DocumentFile.fromTreeUri(context, treeUri)?.takeIf { it.exists() && it.canWrite() }

    fun prepareShareFiles(
        context: Context,
        engine: VeraCryptEngine,
        handle: Long,
        items: List<VaultTransferItem>
    ): PreparedShareFiles? {
        if (items.isEmpty() || items.any { it.isDirectory }) return null
        clearOldShareCache(context)
        val sessionDir = File(context.cacheDir, "$SHARE_CACHE_DIR/${System.currentTimeMillis()}_${UUID.randomUUID()}")
            .also { it.mkdirs() }
        val outFiles = items.mapIndexedNotNull { index, item ->
            val target = uniqueFile(sessionDir, item.name.ifBlank { "file_$index" })
            if (copyVaultFileToFile(engine, handle, item.path, item.size, target)) {
                if (item.lastModified > 0L) target.setLastModified(item.lastModified)
                target
            } else {
                target.delete()
                null
            }
        }
        if (outFiles.isEmpty()) {
            sessionDir.deleteRecursively()
            return null
        }
        return PreparedShareFiles(outFiles, commonMimeType(outFiles.map { it.name }))
    }

    fun exportItemToDirectory(
        context: Context,
        engine: VeraCryptEngine,
        handle: Long,
        item: VaultTransferItem,
        parent: DocumentFile
    ): Boolean {
        return if (item.isDirectory) {
            exportDirectoryRecursive(context, engine, handle, item.path, parent, item.name, item.lastModified)
        } else {
            exportFileToDirectory(context, engine, handle, item.path, item.size, parent, item.name, item.lastModified)
        }
    }

    fun exportItemToDefaultFolder(
        context: Context,
        engine: VeraCryptEngine,
        handle: Long,
        item: VaultTransferItem
    ): Boolean {
        return if (item.isDirectory) {
            exportDirectoryToPublicRelativePath(
                context = context,
                engine = engine,
                handle = handle,
                srcPath = item.path,
                parentRelativePath = defaultUnlockedRelativePath(),
                dirName = item.name
            )
        } else {
            exportFileToPublicRelativePath(
                context = context,
                engine = engine,
                handle = handle,
                item = item,
                relativePath = defaultUnlockedRelativePath()
            )
        }
    }

    fun copyVaultFileToFile(
        engine: VeraCryptEngine,
        handle: Long,
        srcPath: String,
        fileSize: Long,
        target: File
    ): Boolean {
        return try {
            target.parentFile?.mkdirs()
            target.outputStream().use { out ->
                var offset = 0L
                if (fileSize == 0L) return@use
                while (offset < fileSize) {
                    val toRead = minOf(CHUNK_SIZE.toLong(), fileSize - offset).toInt()
                    val chunk = engine.nativeReadFile(handle, srcPath, offset, toRead) ?: return false
                    if (chunk.isEmpty()) return false
                    out.write(chunk)
                    offset += chunk.size
                }
                if (offset != fileSize) return false
            }
            true
        } catch (_: Exception) {
            target.delete()
            false
        }
    }

    fun clearOldShareCache(context: Context) {
        val root = File(context.cacheDir, SHARE_CACHE_DIR)
        val cutoff = System.currentTimeMillis() - SHARE_CACHE_TTL_MS
        root.listFiles()?.forEach { file ->
            if (file.lastModified() < cutoff) file.deleteRecursively()
        }
    }

    fun mimeType(fileName: String): String {
        return when (fileName.substringAfterLast('.', "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            "webp" -> "image/webp"
            "bmp" -> "image/bmp"
            "heic", "heif" -> "image/heif"
            "mp4", "m4v" -> "video/mp4"
            "mkv" -> "video/x-matroska"
            "avi" -> "video/avi"
            "mov" -> "video/quicktime"
            "webm" -> "video/webm"
            "mp3" -> "audio/mpeg"
            "m4a", "aac" -> "audio/aac"
            "ogg" -> "audio/ogg"
            "flac" -> "audio/flac"
            "wav" -> "audio/wav"
            "pdf" -> "application/pdf"
            "doc", "docx" -> "application/msword"
            "xls", "xlsx" -> "application/vnd.ms-excel"
            "ppt", "pptx" -> "application/vnd.ms-powerpoint"
            "txt", "md" -> "text/plain"
            "zip" -> "application/zip"
            "rar" -> "application/x-rar-compressed"
            "7z" -> "application/x-7z-compressed"
            "json" -> "application/json"
            "xml" -> "application/xml"
            "html", "htm" -> "text/html"
            "apk" -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    private fun commonMimeType(names: List<String>): String {
        val types = names.map { mimeType(it) }.distinct()
        if (types.size == 1) return types.first()
        val topLevels = types.map { it.substringBefore('/') }.distinct()
        return if (topLevels.size == 1) "${topLevels.first()}/*" else "*/*"
    }

    private fun exportDirectoryRecursive(
        context: Context,
        engine: VeraCryptEngine,
        handle: Long,
        srcPath: String,
        parent: DocumentFile,
        dirName: String,
        modifiedAt: Long
    ): Boolean {
        val dir = parent.findFile(dirName)?.takeIf { it.isDirectory } ?: parent.createDirectory(dirName) ?: return false
        val entries = runCatching { engine.nativeListFiles(handle, srcPath).toList() }.getOrDefault(emptyList())
        var allOk = true
        for (entry in entries) {
            val childPath = if (srcPath == "/") "/${entry.name}" else "$srcPath/${entry.name}"
            val item = VaultTransferItem(childPath, entry.name, entry.size, entry.isDirectory, entry.lastModified)
            if (!exportItemToDirectory(context, engine, handle, item, dir)) allOk = false
        }
        if (modifiedAt > 0L) runCatching { dir.uri.path?.let { File(it).setLastModified(modifiedAt) } }
        return allOk
    }

    private fun exportFileToDirectory(
        context: Context,
        engine: VeraCryptEngine,
        handle: Long,
        srcPath: String,
        size: Long,
        parent: DocumentFile,
        name: String,
        modifiedAt: Long
    ): Boolean {
        val existing = parent.findFile(name)
        if (existing != null && existing.isFile) runCatching { existing.delete() }
        val doc = parent.createFile(mimeType(name), name) ?: return false
        val ok = exportFileToUri(context, engine, handle, srcPath, size, doc.uri)
        if (!ok) runCatching { doc.delete() }
        if (ok && modifiedAt > 0L) runCatching { doc.uri.path?.let { File(it).setLastModified(modifiedAt) } }
        return ok
    }

    private fun exportDirectoryToPublicRelativePath(
        context: Context,
        engine: VeraCryptEngine,
        handle: Long,
        srcPath: String,
        parentRelativePath: String,
        dirName: String
    ): Boolean {
        val dirRelativePath = appendRelativePath(parentRelativePath, dirName)
        val entries = runCatching { engine.nativeListFiles(handle, srcPath).toList() }.getOrDefault(emptyList())
        var allOk = true
        for (entry in entries) {
            val childPath = if (srcPath == "/") "/${entry.name}" else "$srcPath/${entry.name}"
            val item = VaultTransferItem(childPath, entry.name, entry.size, entry.isDirectory, entry.lastModified)
            val exported = if (entry.isDirectory) {
                exportDirectoryToPublicRelativePath(context, engine, handle, childPath, dirRelativePath, entry.name)
            } else {
                exportFileToPublicRelativePath(context, engine, handle, item, dirRelativePath)
            }
            if (!exported) allOk = false
        }
        return allOk
    }

    fun exportFileToUri(
        context: Context,
        engine: VeraCryptEngine,
        handle: Long,
        srcPath: String,
        fileSize: Long,
        docUri: android.net.Uri
    ): Boolean {
        return try {
            context.contentResolver.openOutputStream(docUri)?.use { out ->
                var offset = 0L
                while (offset < fileSize) {
                    val toRead = minOf(CHUNK_SIZE.toLong(), fileSize - offset).toInt()
                    val chunk = engine.nativeReadFile(handle, srcPath, offset, toRead) ?: return false
                    if (chunk.isEmpty()) return false
                    out.write(chunk)
                    offset += chunk.size
                }
                offset == fileSize
            } == true || fileSize == 0L
        } catch (_: Exception) {
            false
        }
    }

    private fun exportFileToPublicRelativePath(
        context: Context,
        engine: VeraCryptEngine,
        handle: Long,
        item: VaultTransferItem,
        relativePath: String
    ): Boolean {
        val resolver = context.contentResolver
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val safeName = File(item.name).name.ifBlank { "file" }
        val safeRelativePath = ensureTrailingSlash(relativePath)

        runCatching { deleteExistingPublicFile(context, collection, safeRelativePath, safeName) }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(safeName))
            put(MediaStore.MediaColumns.RELATIVE_PATH, safeRelativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            if (item.lastModified > 0L) {
                put(MediaStore.MediaColumns.DATE_MODIFIED, item.lastModified / 1000L)
            }
        }
        val uri = resolver.insert(collection, values) ?: return false
        val ok = exportFileToUri(context, engine, handle, item.path, item.size, uri)
        if (ok) {
            runCatching {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
            }
        } else {
            runCatching { resolver.delete(uri, null, null) }
        }
        return ok
    }

    private fun deleteExistingPublicFile(
        context: Context,
        collection: android.net.Uri,
        relativePath: String,
        name: String
    ) {
        val projection = arrayOf(MediaStore.MediaColumns._ID)
        val selection = "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=?"
        val args = arrayOf(name, relativePath)
        context.contentResolver.query(collection, projection, selection, args, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            while (cursor.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }
    }

    private fun uniqueFile(dir: File, requestedName: String): File {
        val safeName = File(requestedName).name.ifBlank { "file" }
        val dot = safeName.lastIndexOf('.').takeIf { it > 0 && it < safeName.lastIndex }
        val base = if (dot != null) safeName.substring(0, dot) else safeName
        val ext = if (dot != null) safeName.substring(dot) else ""
        var candidate = File(dir, safeName)
        var index = 2
        while (candidate.exists()) {
            candidate = File(dir, "$base ($index)$ext")
            index++
        }
        return candidate
    }

    private fun defaultUnlockedRelativePath(): String =
        "${Environment.DIRECTORY_DOCUMENTS}/Unlocked"

    private fun appendRelativePath(parent: String, child: String): String {
        val safeChild = File(child).name.ifBlank { "Folder" }
        return "${parent.trimEnd('/')}/$safeChild"
    }

    private fun ensureTrailingSlash(path: String): String =
        path.trim('/').let { if (it.isBlank()) "" else "$it/" }
}
