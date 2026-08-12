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

    /** A run of sectors no partition covers, already aligned and ready to be used. */
    data class FreeExtent(val startLba: Long, val sectorCount: Long, val sectorSize: Int) {
        val startByte: Long get() = startLba * sectorSize
        val sizeBytes: Long get() = sectorCount * sectorSize
    }

    /**
     * The gaps between [partitions], in order, with the first 1 MiB reserved for the
     * table and alignment.
     *
     * Extents shorter than [MIN_PLAIN_BYTES] are dropped: a partition that small is not
     * worth offering, and the alignment gap in front of the first partition would
     * otherwise show up as a usable one.
     */
    fun freeExtents(
        deviceBytes: Long,
        sectorSize: Int,
        partitions: List<UsbPartition>,
    ): List<FreeExtent> {
        if (sectorSize <= 0) return emptyList()
        val total = deviceBytes / sectorSize
        val used = partitions.sortedBy { it.startLba }
        val out = ArrayList<FreeExtent>()
        var cursor = ALIGN_SECTORS
        for (p in used) {
            if (p.startLba > cursor) addExtent(out, cursor, p.startLba, sectorSize)
            cursor = maxOf(cursor, p.startLba + p.sectorCount)
        }
        if (total > cursor) addExtent(out, cursor, total, sectorSize)
        return out
    }

    private fun addExtent(out: MutableList<FreeExtent>, fromLba: Long, toLba: Long, sectorSize: Int) {
        val start = roundUp(fromLba, ALIGN_SECTORS)
        if (toLba <= start) return
        val sectors = toLba - start
        if (sectors * sectorSize < MIN_PLAIN_BYTES) return
        out += FreeExtent(start, sectors, sectorSize)
    }

    /** The first unused entry in the table, or -1 when all four are taken. */
    fun freeSlot(partitions: List<UsbPartition>): Int {
        val taken = partitions.map { it.slot }.toSet()
        return (0..3).firstOrNull { it !in taken } ?: -1
    }

    /**
     * Writes one entry into an existing table, leaving the other three alone.
     *
     * [sector0] must already hold a table, or be all zeros for a drive that has none.
     */
    fun addEntry(
        sector0: ByteArray,
        slot: Int,
        startLba: Long,
        sectorCount: Long,
        typeByte: Int,
        sectorSize: Int,
    ): Boolean {
        if (slot !in 0..3 || sector0.size < 512) return false
        writeEntry(
            sector0, 446 + slot * 16,
            UsbPartition(slot, typeByte, startLba, sectorCount, sectorSize, active = false)
        )
        sector0[510] = 0x55.toByte()
        sector0[511] = 0xAA.toByte()
        return true
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

    /**
     * Blanks one entry of an existing table, leaving the other three alone.
     *
     * Only the sixteen bytes of that slot change. The data the partition held is not
     * touched - nothing here overwrites a byte outside the table - so this removes the
     * way to find it rather than the thing itself.
     */
    fun clearEntry(sector0: ByteArray, slot: Int): Boolean {
        if (slot !in 0..3 || sector0.size < 512) return false
        val off = 446 + slot * 16
        java.util.Arrays.fill(sector0, off, off + 16, 0)
        sector0[510] = 0x55.toByte()
        sector0[511] = 0xAA.toByte()
        return true
    }

    private fun roundUp(value: Long, to: Long): Long = ((value + to - 1) / to) * to
}
