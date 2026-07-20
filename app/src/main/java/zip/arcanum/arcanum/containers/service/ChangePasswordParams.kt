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
        val extraEntropy: ByteArray
    )

    private val pending = AtomicReference<Params?>()

    fun set(params: Params) { pending.set(params) }

    fun take(): Params? = pending.getAndSet(null)
}
