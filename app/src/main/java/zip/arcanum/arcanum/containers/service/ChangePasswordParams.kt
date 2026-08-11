package zip.arcanum.arcanum.containers.service

import android.os.ParcelFileDescriptor
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChangePasswordParams @Inject constructor() {

    data class Params(
        val path: String,
        val safFd: Int = -1,
        val safPfd: ParcelFileDescriptor? = null,
        val oldPassword: String,
        val oldKeyfileData: List<ByteArray>,
        val oldPim: Int,
        val newPassword: String,
        val newKeyfileData: List<ByteArray>,
        val newHashAlgorithm: Int,
        val newPim: Int,
        val wipePassCount: Int,
        val extraEntropy: ByteArray,
        /**
         * Non-empty when the volume is a whole USB device (#95). Deliberately the salt
         * hash rather than an open transport: the service opens the drive itself, so
         * opening, using and closing it all happen in one place, and nothing has to
         * survive being handed across the service boundary.
         */
        val usbSaltHash: String = "",
        /** Where the volume starts on its drive; 0 for a whole device (#131). */
        val usbStartByte: Long = 0L
    )

    private val pending = AtomicReference<Params?>()

    fun set(params: Params) { pending.set(params) }

    fun take(): Params? = pending.getAndSet(null)
}
