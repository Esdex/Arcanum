package zip.arcanum.usb

import android.util.Log

/**
 * A window onto one partition, handed to the native layer in place of the whole drive.
 *
 * The native backend finds `read`, `write` and `sync` on whatever object it is given, so
 * a volume inside a partition needs nothing more than this: every offset is shifted by
 * [baseByte], and the layers above - XTS, the header code, FatFs, ext4 - go on believing
 * they own a device that starts at zero.
 *
 * The offset deliberately lives here rather than in C++. The native side keeps a
 * write-combining buffer whose bookkeeping is in absolute offsets, and adding a second
 * origin to that arithmetic is how writes end up in the wrong place - which, on a drive
 * where the neighbouring partition holds the user's ordinary files, means destroying data
 * that has nothing to do with the vault.
 *
 * [sizeBytes] is the partition's size, and requests past it are refused rather than
 * clamped. A volume that believes it is larger than its partition is already wrong, and
 * letting it spill into the next partition would corrupt data outside the vault while
 * looking like success.
 */
class UsbPartitionView(
    private val dev: UsbBlockDevice,
    private val baseByte: Long,
    val sizeBytes: Long,
) {
    fun read(offset: Long, length: Int, dest: ByteArray, destOffset: Int): Boolean {
        if (!inRange(offset, length, "read")) return false
        return dev.read(baseByte + offset, length, dest, destOffset)
    }

    fun write(offset: Long, length: Int, src: ByteArray, srcOffset: Int): Boolean {
        if (!inRange(offset, length, "write")) return false
        return dev.write(baseByte + offset, length, src, srcOffset)
    }

    /** The drive's cache is not per partition, so this is the drive's own flush. */
    fun sync(): Boolean = dev.sync()

    private fun inRange(offset: Long, length: Int, what: String): Boolean {
        if (offset < 0 || length < 0 || offset + length > sizeBytes) {
            Log.e(
                "ArcanumUsb",
                "$what of $length bytes at $offset refused: outside a partition of $sizeBytes bytes"
            )
            return false
        }
        return true
    }
}
