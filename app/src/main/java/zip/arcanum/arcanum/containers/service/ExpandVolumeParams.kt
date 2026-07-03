package zip.arcanum.arcanum.containers.service

import android.os.ParcelFileDescriptor
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

enum class ExpandVolumeStrategy {
    SAFE_REBUILD,
    IN_PLACE
}

@Singleton
class ExpandVolumeParams @Inject constructor() {

    data class Params(
        val containerId: String,
        val path: String,
        val safUri: String,
        val safFd: Int = -1,
        val safPfd: ParcelFileDescriptor? = null,
        val targetDataSizeBytes: Long,
        val strategy: ExpandVolumeStrategy,
        val password: String,
        val keyfilePaths: List<String>,
        val pim: Int,
        val entropyBytes: ByteArray,
        val includeHidden: Boolean,
        val hiddenPassword: String,
        val hiddenKeyfilePaths: List<String>,
        val hiddenPim: Int,
        val hiddenTargetDataSizeBytes: Long
    )

    private val pending = AtomicReference<Params?>(null)

    fun set(params: Params) { pending.set(params) }

    fun take(): Params? = pending.getAndSet(null)
}
