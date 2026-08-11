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
        val keyfileData: List<ByteArray>,
        val pim: Int,
        val safFd: Int = -1,
        val safPfd: ParcelFileDescriptor? = null,
        /**
         * True when the volume occupies a whole USB device (#95). No path and no
         * descriptor then: the service opens the drive itself, so a claimed USB
         * interface never has to cross the service boundary.
         */
        val usbWholeDevice: Boolean = false,
        /**
         * Where on the drive the volume goes, and how much room it has. Both 0 means the
         * whole device, which is what USB creation was before partitions (#131).
         */
        val usbStartByte: Long = 0L,
        val usbSpanSize: Long = 0L,
    )

    private val pending = AtomicReference<Params?>()

    fun set(params: Params) { pending.set(params) }

    /** Atomically retrieves and clears the pending params. Returns null if none set. */
    fun take(): Params? = pending.getAndSet(null)
}
