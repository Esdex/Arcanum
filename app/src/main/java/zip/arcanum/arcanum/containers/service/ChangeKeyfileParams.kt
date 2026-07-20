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
        val extraEntropy: ByteArray
    )

    private val ref = AtomicReference<Params?>(null)

    fun set(p: Params) { ref.set(p) }
    fun take(): Params? = ref.getAndSet(null)
}
