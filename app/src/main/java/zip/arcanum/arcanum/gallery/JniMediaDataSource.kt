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
        if (position >= fileSize) return -1
        val toRead = minOf(size, (fileSize - position).toInt())
        val data = runCatching { engine.nativeReadFile(handle, path, position, toRead) }.getOrNull()
            ?: return -1
        if (data.isEmpty()) return -1
        data.copyInto(buffer, offset, 0, data.size)
        return data.size
    }

    override fun getSize(): Long = fileSize
    override fun close() {}
}
