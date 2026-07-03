package zip.arcanum.arcanum.containers.service

import android.os.ParcelFileDescriptor
import zip.arcanum.core.utils.FileUtils
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExpandVolumeParams @Inject constructor() {

    data class Params(
        val containerId: String,
        val path: String,
        val safFd: Int = -1,
        val safPfd: ParcelFileDescriptor? = null,
        val password: String,
        val keyfilePaths: List<String>,
        val pim: Int,
        val newSizeBytes: Long
    )

    private val pending = AtomicReference<Params?>()

    // M5: release previous pending params (fd + keyfile copies) before overwriting
    fun set(params: Params) {
        val old = pending.getAndSet(params)
        old?.safPfd?.close()
        old?.keyfilePaths?.forEach { FileUtils.secureZeroAndDelete(File(it)) }
    }

    fun take(): Params? = pending.getAndSet(null)

    // Called from ViewModel.onCleared() to release params if the service was killed before take()
    fun clear() {
        val old = pending.getAndSet(null)
        old?.safPfd?.close()
        old?.keyfilePaths?.forEach { FileUtils.secureZeroAndDelete(File(it)) }
    }
}
