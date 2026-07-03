package zip.arcanum.arcanum.gallery

import android.media.MediaDataSource
import zip.arcanum.crypto.VeraCryptEngine

class JniMediaDataSource(
    private val engine: VeraCryptEngine,
    private val handle: Long,
    private val path: String,
    private val fileSize: Long
) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= fileSize || offset < 0 || size <= 0) return -1
        if (offset > buffer.size) return -1
        val capacity = buffer.size - offset
        if (capacity <= 0) return -1
        val toRead = minOf(size.toLong(), capacity.toLong(), MAX_READ_BYTES, fileSize - position).toInt()
        val data = runCatching { engine.nativeReadFile(handle, path, position, toRead) }.getOrNull()
            ?: return -1
        if (data.isEmpty() || data.size > toRead) return -1
        data.copyInto(buffer, offset, 0, data.size)
        return data.size
    }

    override fun getSize(): Long = fileSize
    override fun close() {}

    private companion object {
        const val MAX_READ_BYTES = 1024L * 1024L
    }
}
