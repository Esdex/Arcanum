package zip.arcanum.arcanum.gallery

import android.media.MediaDataSource

class ByteArrayMediaDataSource(private val bytes: ByteArray) : MediaDataSource() {
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0 || position >= bytes.size || offset < 0 || size <= 0) return -1
        if (offset > buffer.size) return -1
        val capacity = buffer.size - offset
        if (capacity <= 0) return -1
        val count = minOf(size, capacity, (bytes.size - position).toInt(), MAX_READ_BYTES)
        bytes.copyInto(buffer, offset, position.toInt(), position.toInt() + count)
        return count
    }

    override fun getSize(): Long = bytes.size.toLong()

    override fun close() {}

    private companion object {
        const val MAX_READ_BYTES = 1024 * 1024
    }
}
