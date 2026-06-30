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
        val oldKeyfilePaths: List<String>,
        val oldPim: Int,
        val newPassword: String,
        val newKeyfilePaths: List<String>,
        val newHashAlgorithm: Int,
        val newPim: Int,
        val wipePassCount: Int
    )

    private val pending = AtomicReference<Params?>()

    fun set(params: Params) { pending.set(params) }

    fun take(): Params? = pending.getAndSet(null)
}
