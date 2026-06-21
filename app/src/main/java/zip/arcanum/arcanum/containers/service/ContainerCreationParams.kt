package zip.arcanum.arcanum.containers.service

import android.os.ParcelFileDescriptor
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ContainerCreationParams @Inject constructor() {

    data class Params(
        val path: String,
        val sizeBytes: Long,
        val password: String,
        val algorithm: Int,
        val hashAlgorithm: Int,
        val filesystem: Int,
        val quickFormat: Boolean,
        val entropyBytes: ByteArray,
        val keyfilePaths: List<String>,
        val pim: Int,
        val safFd: Int = -1,
        val safPfd: ParcelFileDescriptor? = null
    )

    private val pending = AtomicReference<Params?>()

    fun set(params: Params) { pending.set(params) }

    /** Atomically retrieves and clears the pending params. Returns null if none set. */
    fun take(): Params? = pending.getAndSet(null)
}
