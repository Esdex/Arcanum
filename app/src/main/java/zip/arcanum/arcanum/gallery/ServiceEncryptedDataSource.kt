package zip.arcanum.arcanum.gallery

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.crypto.VeraCryptEngine
import java.io.IOException

@androidx.annotation.OptIn(UnstableApi::class)
class ServiceEncryptedDataSource(
    private val engine: VeraCryptEngine,
    private val repo: ContainerRepository
) : DataSource {

    private var handle: Long = -1L
    private var filePath: String = ""
    private var fileSize: Long = 0L
    private var position: Long = 0L
    private var openedUri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        openedUri = dataSpec.uri
        val containerId = dataSpec.uri.getQueryParameter("cid")
            ?: throw IOException("Missing cid in URI")
        filePath = "/" + (dataSpec.uri.getQueryParameter("path")
            ?: throw IOException("Missing path in URI")).trimStart('/')
        fileSize = dataSpec.uri.getQueryParameter("size")?.toLongOrNull()
            ?: throw IOException("Missing size in URI")
        handle = repo.getContainerHandle(containerId)
            ?: throw IOException("Container not mounted: $containerId")
        position = dataSpec.position
        return if (fileSize > 0L) fileSize - position else C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= fileSize) return C.RESULT_END_OF_INPUT
        val toRead = minOf(length.toLong(), fileSize - position, CHUNK_SIZE.toLong()).toInt()
        val chunk = engine.readFile(handle, filePath, position, toRead)
            ?.takeIf { it.isNotEmpty() }
            ?: return C.RESULT_END_OF_INPUT
        chunk.copyInto(buffer, offset)
        position += chunk.size
        return chunk.size
    }

    override fun getUri(): Uri? = openedUri
    override fun addTransferListener(transferListener: TransferListener) {}
    override fun close() { position = 0L }

    companion object {
        const val URI_SCHEME = "arcanum-svc"
        private const val CHUNK_SIZE = 1024 * 1024
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
class ServiceEncryptedDataSourceFactory(
    private val engine: VeraCryptEngine,
    private val repo: ContainerRepository
) : DataSource.Factory {
    override fun createDataSource() = ServiceEncryptedDataSource(engine, repo)
}
