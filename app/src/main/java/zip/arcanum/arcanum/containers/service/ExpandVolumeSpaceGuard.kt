package zip.arcanum.arcanum.containers.service

import android.os.ParcelFileDescriptor
import android.system.Os
import java.io.File

object ExpandVolumeSpaceGuard {
    private const val BYTES_IN_MB = 1024L * 1024L
    private const val VC_VOLUME_OVERHEAD_BYTES = 256L * 1024L
    private const val MIN_FREE_RESERVE_BYTES = 256L * BYTES_IN_MB
    private const val MAX_FREE_RESERVE_BYTES = 2L * 1024L * BYTES_IN_MB

    fun targetFileSizeBytes(targetDataSizeBytes: Long): Long =
        addSaturating(targetDataSizeBytes, VC_VOLUME_OVERHEAD_BYTES)

    fun requiredScratchBytes(targetDataSizeBytes: Long): Long {
        val targetFileSize = targetFileSizeBytes(targetDataSizeBytes)
        return addSaturating(targetFileSize, reserveBytes(targetFileSize))
    }

    fun requiredFinalBytes(targetDataSizeBytes: Long): Long {
        val targetFileSize = targetFileSizeBytes(targetDataSizeBytes)
        return addSaturating(targetFileSize, reserveBytes(targetFileSize))
    }

    fun reserveBytes(targetFileSizeBytes: Long): Long =
        (targetFileSizeBytes / 20L).coerceIn(MIN_FREE_RESERVE_BYTES, MAX_FREE_RESERVE_BYTES)

    fun usableSpaceForFileTarget(path: String, fallback: File): Long {
        val parent = File(path).parentFile ?: fallback
        return parent.usableSpace
    }

    fun usableSpaceForFd(pfd: ParcelFileDescriptor): Long? = try {
        val stat = Os.fstatvfs(pfd.fileDescriptor)
        val blockSize = if (stat.f_frsize > 0L) stat.f_frsize else stat.f_bsize
        multiplySaturating(stat.f_bavail, blockSize)
    } catch (_: Throwable) {
        null
    }

    fun addSaturating(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun multiplySaturating(left: Long, right: Long): Long {
        if (left <= 0L || right <= 0L) return 0L
        return if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
    }
}
