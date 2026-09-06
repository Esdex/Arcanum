package zip.arcanum.crypto

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import java.io.Closeable

/**
 * Fills [data] with up to [requested] bytes of [path] starting at [offset], following the
 * native layer's own limit on how much it will hand over at once - hence the loop.
 *
 * Shared by everything that has to serve a file from inside a vault a range at a time: the
 * documents provider behind Open with, and the PDF viewer's descriptor below.
 */
fun VeraCryptEngine.readInto(
    handle: Long,
    path: String,
    offset: Long,
    requested: Int,
    fileSize: Long,
    data: ByteArray
): Int {
    if (offset >= fileSize) return 0
    val want = minOf(requested.toLong(), fileSize - offset).toInt()
    var read = 0
    while (read < want) {
        val chunk = readFile(handle, path, offset + read, want - read)
            ?: throw ErrnoException("onRead", OsConstants.EIO)
        if (chunk.isEmpty()) break
        chunk.copyInto(data, read)
        read += chunk.size
    }
    return read
}

/**
 * A real, seekable descriptor for a file that exists only inside a mounted vault.
 *
 * Some system components will not read anything else - `PdfRenderer` is the one this was
 * written for: it needs to seek to the cross-reference table at the end of the document and
 * then jump about it. The vault offers ranges through JNI and nothing that resembles a file,
 * and writing a decrypted copy to the cache to make one is the path deleted with #103 and
 * never to be brought back.
 *
 * `StorageManager.openProxyFileDescriptor` is the way round it: the descriptor is genuine
 * and seekable, and every read of it arrives here as a callback that goes to the vault.
 * Nothing is ever written out, and only the ranges actually asked for are decrypted - a
 * 300 MB document costs about 20 KB to open (measured in `PdfFromVaultTest`).
 *
 * [handle] is asked for on every read rather than captured, so a vault that is unmounted
 * mid-read answers EBADF instead of reading through a handle that no longer exists.
 */
class VaultFileDescriptor(
    context: Context,
    private val engine: VeraCryptEngine,
    private val path: String,
    private val size: Long,
    private val handle: () -> Long?
) : Closeable {

    private val thread = HandlerThread("vault-fd").apply { start() }

    val descriptor: ParcelFileDescriptor = runCatching {
        context.getSystemService(StorageManager::class.java).openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = size

                override fun onRead(offset: Long, requested: Int, data: ByteArray): Int {
                    val open = handle() ?: throw ErrnoException("onRead", OsConstants.EBADF)
                    return engine.readInto(open, path, offset, requested, size, data)
                }

                override fun onRelease() {}
            },
            Handler(thread.looper)
        )
    }.getOrElse { failure ->
        thread.quitSafely()
        throw failure
    }

    /**
     * The FUSE loop behind the descriptor sends a last command or two after the close, and
     * a thread already gone answers those with "sending message to a Handler on a dead
     * thread" in the log. Quitting from the thread itself puts the quit behind whatever is
     * still queued on it.
     */
    override fun close() {
        runCatching { descriptor.close() }
        if (!Handler(thread.looper).post { thread.quitSafely() }) thread.quitSafely()
    }
}
