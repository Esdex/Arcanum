package zip.arcanum.arcanum.saf

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.webkit.MimeTypeMap
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import zip.arcanum.R
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.core.database.dao.ContainerDao
import zip.arcanum.core.database.entities.ContainerEntity
import zip.arcanum.crypto.VeraCryptEngine
import kotlinx.coroutines.runBlocking
import java.io.FileNotFoundException

/**
 * Storage Access Framework provider exposing files inside *mounted* containers that the user has
 * explicitly opted in (per-vault "External app access", default off). Other apps reach a file only
 * through a URI the user grants in the system picker; the provider itself is guarded by
 * android.permission.MANAGE_DOCUMENTS so only the system DocumentsUI can enumerate it.
 *
 * Supports read, write, create, delete and rename (write is disabled for read-only mounts). Bytes
 * are served on demand through a proxy file descriptor backed by the JNI crypto engine, so nothing
 * is ever decrypted to disk. Writes go through [VeraCryptEngine.writeAt] (non-truncating), so a
 * backward seek can't wipe a file. When a container is unmounted (or opt-in is turned off) its JNI
 * handle disappears and every read/write fails - see [ContainerRepository.unmountContainer].
 *
 * Document id format: "<containerId>:<path>", where path is the in-container absolute path
 * ("/", "/dir", "/dir/file.ext"). Container ids are UUIDs, so the first ':' is an unambiguous split.
 */
class VaultDocumentsProvider : DocumentsProvider() {

    private val deps by lazy {
        EntryPointAccessors.fromApplication(
            context!!.applicationContext,
            VaultDocumentsProviderEntryPoint::class.java
        )
    }
    private val repo: ContainerRepository get() = deps.containerRepository()
    private val engine: VeraCryptEngine get() = deps.veraCryptEngine()
    private val dao: ContainerDao get() = deps.containerDao()

    private val storageManager by lazy { context!!.getSystemService(StorageManager::class.java) }
    private val callbackThread by lazy {
        HandlerThread("VaultProxyFd").also { it.start() }
    }
    private val callbackHandler by lazy { Handler(callbackThread.looper) }

    override fun onCreate(): Boolean = true

    // ── Roots ──────────────────────────────────────────────────────────────────
    override fun queryRoots(projection: Array<String>?): Cursor {
        val cursor = MatrixCursor(projection ?: DEFAULT_ROOT_PROJECTION)
        val mounted = repo.mountedContainerIds.value
        exposedContainers().forEach { c ->
            if (c.id !in mounted) return@forEach
            var flags = Root.FLAG_LOCAL_ONLY or Root.FLAG_SUPPORTS_IS_CHILD
            if (!repo.isContainerReadOnly(c.id)) flags = flags or Root.FLAG_SUPPORTS_CREATE
            cursor.newRow().apply {
                add(Root.COLUMN_ROOT_ID, c.id)
                add(Root.COLUMN_DOCUMENT_ID, rootDocumentId(c.id))
                // Two-line entry in the picker: app name on top, vault name below.
                add(Root.COLUMN_TITLE, context!!.getString(R.string.app_name))
                add(Root.COLUMN_SUMMARY, c.name)
                add(Root.COLUMN_FLAGS, flags)
                add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
            }
        }
        return cursor
    }

    // ── Documents ────────────────────────────────────────────────────────────────
    override fun queryDocument(documentId: String, projection: Array<String>?): Cursor {
        val (cid, path) = parse(documentId)
        requireExposedAndMounted(cid)
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        if (path == "/") {
            val name = runBlocking { dao.getContainerById(cid) }?.name ?: "Vault"
            addDirRow(cursor, cid, documentId, name)
        } else {
            val info = stat(cid, path) ?: throw FileNotFoundException(documentId)
            addFileRow(cursor, cid, info.path, info.name, info.size, info.isDirectory, info.lastModified)
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<String>?,
        sortOrder: String?
    ): Cursor {
        val (cid, dirPath) = parse(parentDocumentId)
        val handle = requireExposedAndMounted(cid)
        val cursor = MatrixCursor(projection ?: DEFAULT_DOCUMENT_PROJECTION)
        engine.listFiles(handle, dirPath).forEach { info ->
            addFileRow(cursor, cid, info.path, info.name, info.size, info.isDirectory, info.lastModified)
        }
        return cursor
    }

    override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
        val (pCid, pPath) = parse(parentDocumentId)
        val (cCid, cPath) = parse(documentId)
        if (pCid != cCid) return false
        val prefix = if (pPath == "/") "/" else "$pPath/"
        return cPath.startsWith(prefix)
    }

    // ── Open (read + write) ────────────────────────────────────────────────────────
    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?
    ): ParcelFileDescriptor {
        val (cid, path) = parse(documentId)
        val handle = requireExposedAndMounted(cid)

        val wantsWrite = mode.any { it == 'w' || it == 'a' || it == 't' }
        if (!wantsWrite) {
            val size = stat(cid, path)?.takeIf { !it.isDirectory }?.size
                ?: throw FileNotFoundException(documentId)
            val callback = object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = size
                override fun onRead(offset: Long, requested: Int, data: ByteArray): Int =
                    readChunk(cid, path, offset, requested, size, data)
                override fun onRelease() {}
            }
            return storageManager.openProxyFileDescriptor(
                ParcelFileDescriptor.MODE_READ_ONLY, callback, callbackHandler
            )
        }

        if (repo.isContainerReadOnly(cid)) throw FileNotFoundException("Read-only vault: $cid")

        // "w"/"wt" mean truncate-on-open; "wa" (append) and "rw" do NOT. We have no in-place
        // truncate, so for a truncating open we drop the file first and rebuild it from the incoming
        // writes - all non-truncating (writeAt), so a later backward seek to offset 0 can't wipe it.
        val truncate = mode.contains('t') || (mode.contains('w') && !mode.contains('a'))
        if (truncate) runCatching { engine.deleteFile(handle, path) }
        // Make sure the document exists so onGetSize/onRead are well-defined even before any write.
        engine.writeAt(handle, path, EMPTY, 0L)

        val callback = object : ProxyFileDescriptorCallback() {
            @Volatile private var size: Long =
                if (truncate) 0L else (stat(cid, path)?.size ?: 0L)

            override fun onGetSize(): Long = size

            override fun onRead(offset: Long, requested: Int, data: ByteArray): Int =
                readChunk(cid, path, offset, requested, size, data)

            override fun onWrite(offset: Long, count: Int, data: ByteArray): Int {
                val h = repo.getContainerHandle(cid)
                    ?: throw ErrnoException("onWrite", OsConstants.EBADF)
                val bytes = if (count == data.size) data else data.copyOf(count)
                when (engine.writeAt(h, path, bytes, offset)) {
                    VeraCryptEngine.ERR_OK -> {}
                    VeraCryptEngine.ERR_READ_ONLY -> throw ErrnoException("onWrite", OsConstants.EROFS)
                    VeraCryptEngine.ERR_NO_SPACE  -> throw ErrnoException("onWrite", OsConstants.ENOSPC)
                    else -> throw ErrnoException("onWrite", OsConstants.EIO)
                }
                val end = offset + count
                if (end > size) size = end
                return count
            }

            override fun onFsync() {} // every writeAt opens/writes/closes, so data is already durable
            override fun onRelease() { notifyChildrenChanged(cid, parentOf(path)) }
        }
        return storageManager.openProxyFileDescriptor(
            ParcelFileDescriptor.parseMode(mode), callback, callbackHandler
        )
    }

    // ── Create / delete / rename ────────────────────────────────────────────────────
    override fun createDocument(
        parentDocumentId: String,
        mimeType: String,
        displayName: String
    ): String {
        val (cid, parent) = parse(parentDocumentId)
        val handle = requireExposedAndMounted(cid)
        if (repo.isContainerReadOnly(cid)) throw FileNotFoundException("Read-only vault: $cid")
        val name = uniqueName(handle, parent, displayName)
        val childPath = joinPath(parent, name)
        val rc = if (mimeType == Document.MIME_TYPE_DIR) {
            engine.createDirectory(handle, childPath)
        } else {
            engine.writeAt(handle, childPath, EMPTY, 0L)
        }
        if (rc != VeraCryptEngine.ERR_OK) throw FileNotFoundException("Create failed: $childPath")
        notifyChildrenChanged(cid, parent)
        return documentId(cid, childPath)
    }

    override fun deleteDocument(documentId: String) {
        val (cid, path) = parse(documentId)
        val handle = requireExposedAndMounted(cid)
        if (repo.isContainerReadOnly(cid)) throw FileNotFoundException("Read-only vault: $cid")
        val info = stat(cid, path) ?: throw FileNotFoundException(documentId)
        val rc = if (info.isDirectory) engine.deleteDirectory(handle, path)
                 else engine.deleteFile(handle, path)
        if (rc != VeraCryptEngine.ERR_OK) throw FileNotFoundException("Delete failed: $path")
        notifyChildrenChanged(cid, parentOf(path))
    }

    override fun renameDocument(documentId: String, displayName: String): String {
        val (cid, path) = parse(documentId)
        val handle = requireExposedAndMounted(cid)
        if (repo.isContainerReadOnly(cid)) throw FileNotFoundException("Read-only vault: $cid")
        val parent = parentOf(path)
        val newName = uniqueName(handle, parent, displayName)
        val newPath = joinPath(parent, newName)
        if (engine.renameFile(handle, path, newPath) != VeraCryptEngine.ERR_OK)
            throw FileNotFoundException("Rename failed: $path")
        notifyChildrenChanged(cid, parent)
        return documentId(cid, newPath)
    }

    // ── Helpers ──────────────────────────────────────────────────────────────────
    private fun exposedContainers(): List<ContainerEntity> =
        runBlocking { dao.getAllContainersOnce() }.filter { it.externalAccessEnabled }

    /** Returns the live JNI handle, or throws if the container isn't opted-in and mounted. */
    private fun requireExposedAndMounted(cid: String): Long {
        val entity = runBlocking { dao.getContainerById(cid) }
        if (entity == null || !entity.externalAccessEnabled) throw FileNotFoundException(cid)
        return repo.getContainerHandle(cid) ?: throw FileNotFoundException("Not mounted: $cid")
    }

    private fun stat(cid: String, path: String): zip.arcanum.crypto.NativeFileInfo? {
        val handle = repo.getContainerHandle(cid) ?: return null
        val parent = parentOf(path)
        val name = path.substringAfterLast('/')
        return engine.listFiles(handle, parent).firstOrNull { it.name == name }
    }

    // Fills [data] with up to [requested] bytes from [offset], looping over short native reads.
    private fun readChunk(
        cid: String, path: String, offset: Long, requested: Int, fileSize: Long, data: ByteArray
    ): Int {
        val handle = repo.getContainerHandle(cid)
            ?: throw ErrnoException("onRead", OsConstants.EBADF)
        if (offset >= fileSize) return 0
        val want = minOf(requested.toLong(), fileSize - offset).toInt()
        var read = 0
        while (read < want) {
            val chunk = engine.readFile(handle, path, offset + read, want - read)
                ?: throw ErrnoException("onRead", OsConstants.EIO)
            if (chunk.isEmpty()) break
            chunk.copyInto(data, read)
            read += chunk.size
        }
        return read
    }

    private fun parentOf(path: String): String = path.substringBeforeLast('/').ifEmpty { "/" }

    private fun joinPath(parent: String, name: String): String =
        if (parent == "/") "/$name" else "$parent/$name"

    // Picks a name that doesn't collide with an existing entry in [dir], SAF-style: "file (1).ext".
    private fun uniqueName(handle: Long, dir: String, desired: String): String {
        val existing = engine.listFiles(handle, dir).map { it.name }.toHashSet()
        if (desired !in existing) return desired
        val dot = desired.lastIndexOf('.')
        val base = if (dot > 0) desired.substring(0, dot) else desired
        val ext  = if (dot > 0) desired.substring(dot) else ""
        var i = 1
        while ("$base ($i)$ext" in existing) i++
        return "$base ($i)$ext"
    }

    private fun notifyChildrenChanged(cid: String, dirPath: String) {
        runCatching {
            val uri = DocumentsContract.buildChildDocumentsUri(
                authority(context!!), documentId(cid, dirPath)
            )
            context!!.contentResolver.notifyChange(uri, null)
        }
    }

    private fun addDirRow(cursor: MatrixCursor, cid: String, documentId: String, name: String) {
        val flags = if (repo.isContainerReadOnly(cid)) 0 else Document.FLAG_DIR_SUPPORTS_CREATE
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, null)
        }
    }

    private fun addFileRow(
        cursor: MatrixCursor,
        cid: String,
        path: String,
        name: String,
        size: Long,
        isDir: Boolean,
        lastModified: Long
    ) {
        val docId = documentId(cid, path)
        val mime = if (isDir) Document.MIME_TYPE_DIR else mimeOf(name)
        var flags = 0
        if (!repo.isContainerReadOnly(cid)) {
            flags = flags or Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
            flags = flags or if (isDir) Document.FLAG_DIR_SUPPORTS_CREATE else Document.FLAG_SUPPORTS_WRITE
        }
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_MIME_TYPE, mime)
            add(Document.COLUMN_FLAGS, flags)
            add(Document.COLUMN_SIZE, if (isDir) null else size)
            add(Document.COLUMN_LAST_MODIFIED, lastModified.takeIf { it > 0L })
        }
    }

    private fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
            ?: "application/octet-stream"
    }

    private fun parse(documentId: String): Pair<String, String> {
        val idx = documentId.indexOf(':')
        if (idx < 0) throw FileNotFoundException("Bad document id: $documentId")
        val cid = documentId.substring(0, idx)
        val path = documentId.substring(idx + 1).ifEmpty { "/" }
        return cid to path
    }

    companion object {
        private val EMPTY = ByteArray(0)

        fun authority(context: Context): String = context.packageName + ".documents"

        fun documentId(containerId: String, path: String): String =
            "$containerId:" + (if (path.startsWith("/")) path else "/$path")

        fun rootDocumentId(containerId: String): String = "$containerId:/"

        private val DEFAULT_ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_DOCUMENT_ID,
            Root.COLUMN_TITLE,
            Root.COLUMN_SUMMARY,
            Root.COLUMN_FLAGS,
            Root.COLUMN_ICON
        )

        private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE,
            Document.COLUMN_LAST_MODIFIED
        )
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface VaultDocumentsProviderEntryPoint {
    fun containerRepository(): ContainerRepository
    fun veraCryptEngine(): VeraCryptEngine
    fun containerDao(): ContainerDao
}
