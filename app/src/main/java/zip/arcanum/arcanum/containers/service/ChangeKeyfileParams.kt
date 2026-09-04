package zip.arcanum.arcanum.containers.service

import android.os.ParcelFileDescriptor
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ChangeKeyfileParams @Inject constructor() {

    data class Params(
        val path: String,
        val safFd: Int = -1,
        val safPfd: ParcelFileDescriptor? = null,
        val password: String,
        val oldKeyfileData: List<ByteArray>,
        val pim: Int,
        val newKeyfileData: List<ByteArray>,
        val newHashAlgorithm: Int,
        /** PRF of the volume as it is now; -1 scans the five PBKDF2 hashes (never Argon2id). */
        val oldHashAlgorithm: Int,
        val extraEntropy: ByteArray,
        /** Non-empty when the volume is a whole USB device (#95); see ChangePasswordParams. */
        val usbSaltHash: String = "",
        /** Where the volume starts on its drive; 0 for a whole device (#131). */
        val usbStartByte: Long = 0L
    )

    private val ref = AtomicReference<Params?>(null)

    fun set(p: Params) { ref.set(p) }
    fun take(): Params? = ref.getAndSet(null)
}
