package zip.arcanum.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes an MBR that splits a drive into an ordinary partition and one for a vault (#131).
 *
 * The point of the split is that the drive goes on behaving like a flash drive: Android
 * mounts the first partition and says nothing about the second. That silence was measured
 * rather than hoped for, and it depends on the type byte - see [VAULT_TYPE].
 */
object UsbPartitioner {

    /** 1 MiB in, which is where every partitioning tool starts and what flash erase blocks like. */
    const val ALIGN_SECTORS = 2048L

    /** FAT32 with LBA addressing: what Android expects to find and mount. */
    const val PLAIN_TYPE = 0x0C

    /**
     * Linux data, for the partition holding the volume.
     *
     * Measured on a Nothing phone: with this type Android reads the entry, ignores it and
     * offers nothing - no notification, no "corrupted media", no format prompt. A type it
     * believes it owns (0x07, 0x0C) would very likely make it try to mount, fail, and put
     * the format prompt back on the screen, which is the whole thing this avoids.
     */
    const val VAULT_TYPE = 0x83

    /** The smallest ordinary partition worth making: below this FAT32 is not worth having. */
    const val MIN_PLAIN_BYTES = 64L * 1024 * 1024

    data class Plan(
        val plain: UsbPartition,
        val vault: UsbPartition,
    )

    /**
     * Works out where the two partitions go, or null when they will not fit.
     *
     * [plainBytes] is what the user asked to leave as ordinary storage; it is rounded up
     * to the alignment, so the vault gets whatever is left rather than the two overlapping.
     */
    fun plan(deviceBytes: Long, sectorSize: Int, plainBytes: Long): Plan? {
        if (sectorSize <= 0) return null
        val total = deviceBytes / sectorSize
        val plainSectors = roundUp(plainBytes / sectorSize, ALIGN_SECTORS)
        val vaultStart = ALIGN_SECTORS + plainSectors
        if (plainBytes < MIN_PLAIN_BYTES) return null
        // The vault needs room for a header, a backup header and something in between.
        if (vaultStart + ALIGN_SECTORS >= total) return null

        val vaultSectors = total - vaultStart
        return Plan(
            plain = UsbPartition(0, PLAIN_TYPE, ALIGN_SECTORS, plainSectors, sectorSize, active = true),
            vault = UsbPartition(1, VAULT_TYPE, vaultStart, vaultSectors, sectorSize, active = false),
        )
    }

    /** The 512 bytes of sector 0 describing [plan]. Bytes 0..445 stay zero: no boot code. */
    fun buildMbr(plan: Plan): ByteArray {
        val mbr = ByteArray(512)
        writeEntry(mbr, 446, plan.plain)
        writeEntry(mbr, 462, plan.vault)
        mbr[510] = 0x55.toByte()
        mbr[511] = 0xAA.toByte()
        return mbr
    }

    private fun writeEntry(mbr: ByteArray, off: Int, p: UsbPartition) {
        mbr[off] = if (p.active) 0x80.toByte() else 0x00
        // CHS is meaningless on anything this size and every reader uses the LBA fields
        // below; 0xFE FF FF is the conventional "beyond what CHS can express" filler.
        mbr[off + 1] = 0xFE.toByte(); mbr[off + 2] = 0xFF.toByte(); mbr[off + 3] = 0xFF.toByte()
        mbr[off + 4] = p.typeByte.toByte()
        mbr[off + 5] = 0xFE.toByte(); mbr[off + 6] = 0xFF.toByte(); mbr[off + 7] = 0xFF.toByte()
        ByteBuffer.wrap(mbr, off + 8, 8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(p.startLba.toInt())
            .putInt(p.sectorCount.toInt())
    }

    private fun roundUp(value: Long, to: Long): Long = ((value + to - 1) / to) * to
}
