package zip.arcanum.arcanum.gallery

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import zip.arcanum.crypto.VeraCryptEngine

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EncryptedDataSource(
    private val engine: VeraCryptEngine,
    private val handle: Long,
    private val filePath: String,
    private val fileSize: Long
) : DataSource {

    private var position: Long = 0
    private var transferListener: TransferListener? = null

    override fun addTransferListener(transferListener: TransferListener) {
        this.transferListener = transferListener
    }

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.position < 0L || (fileSize > 0L && dataSpec.position > fileSize)) {
            position = fileSize
            return 0L
        }
        position = dataSpec.position
        return if (fileSize > 0L) fileSize - position else C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= fileSize) return C.RESULT_END_OF_INPUT
        if (offset < 0 || length <= 0 || offset > buffer.size) return C.RESULT_END_OF_INPUT
        val capacity = buffer.size - offset
        if (capacity <= 0) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), capacity.toLong(), fileSize - position, CHUNK_SIZE.toLong()).toInt()
        val chunk = engine.nativeReadFile(handle, filePath, position, toRead)
            ?.takeIf { it.isNotEmpty() }
            ?: return C.RESULT_END_OF_INPUT
        if (chunk.size > toRead) return C.RESULT_END_OF_INPUT
        chunk.copyInto(buffer, offset)
        position += chunk.size
        return chunk.size
    }

    override fun getUri(): Uri = Uri.parse("$URI_SCHEME://$filePath")

    override fun close() {
        position = 0
    }

    companion object {
        const val CHUNK_SIZE = 1024 * 1024
        const val URI_SCHEME = "arcanum"
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class EncryptedDataSourceFactory(
    private val engine: VeraCryptEngine,
    private val handle: Long,
    private val filePath: String,
    private val fileSize: Long
) : DataSource.Factory {
    override fun createDataSource(): DataSource =
        EncryptedDataSource(engine, handle, filePath, fileSize)

    companion object {
        const val URI_SCHEME = EncryptedDataSource.URI_SCHEME
    }
}

// Mutable factory — call configure() before each setMediaItem()+prepare() on the shared ExoPlayer.
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MutableEncryptedDataSourceFactory(
    private val engine: VeraCryptEngine,
    private val handle: Long
) : DataSource.Factory {
    @Volatile private var path: String = ""
    @Volatile private var size: Long   = 0L

    fun configure(path: String, size: Long) { this.path = path; this.size = size }

    override fun createDataSource(): DataSource = EncryptedDataSource(engine, handle, path, size)
}
