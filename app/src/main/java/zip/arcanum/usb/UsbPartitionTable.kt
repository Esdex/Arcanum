package zip.arcanum.usb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One entry of an MBR partition table.
 *
 * [sectorSize] is carried along because the LBA fields are counted in the drive's own
 * logical blocks, and every caller that multiplies by 512 out of habit gets a wrong
 * offset on any drive that is not 512. Reading the wrong span of an encrypted volume
 * produces plausible garbage rather than an error, so the conversion lives here once.
 */
data class UsbPartition(
    /** Which of the four MBR slots this came from, 0 based. Slots may be sparse. */
    val slot: Int,
    val typeByte: Int,
    val startLba: Long,
    val sectorCount: Long,
    val sectorSize: Int,
    val active: Boolean,
) {
    val startByte: Long get() = startLba * sectorSize
    val sizeBytes: Long get() = sectorCount * sectorSize

    /**
     * What the type byte says the partition holds. Descriptive only: the byte is a claim
     * by whoever wrote the table, not something anyone verifies. A VeraCrypt volume shows
     * up as whatever type it was given, which is why the picker calls an unrecognised one
     * unrecognised rather than guessing that it is a vault.
     */
    val typeName: String get() = partitionTypeName(typeByte)

    /** True for the types Android itself will try to mount - see [GPT_PROTECTIVE]. */
    val looksLikeFilesystem: Boolean
        get() = typeByte in setOf(0x01, 0x04, 0x06, 0x07, 0x0B, 0x0C, 0x0E)
}

const val GPT_PROTECTIVE = 0xEE

/** Extended partition containers: they hold logical partitions rather than data. */
private val EXTENDED_TYPES = setOf(0x05, 0x0F, 0x85)

fun partitionTypeName(t: Int): String = when (t) {
    0x01, 0x04, 0x06, 0x0E -> "FAT12/16"
    0x0B, 0x0C -> "FAT32"
    0x07 -> "exFAT/NTFS"
    0x83 -> "Linux"
    0x05, 0x0F, 0x85 -> "extended"
    GPT_PROTECTIVE -> "GPT protective"
    else -> "unrecognised"
}

/**
 * Parses the four primary entries of an MBR out of [sector0].
 *
 * Returns an empty list when there is no usable table, which is the normal answer for a
 * drive holding a whole-device VeraCrypt volume: its first sector is a random salt, so
 * the 0x55AA signature matching by chance is a 1 in 65536 event and the entries would
 * then almost certainly fail the range checks below.
 *
 * [deviceSectors] bounds the entries. A table claiming a partition that runs past the end
 * of the drive is either damage or a fake-capacity drive (#132), and either way it must
 * not become an offset we read at.
 */
fun parseMbr(sector0: ByteArray, sectorSize: Int, deviceSectors: Long): List<UsbPartition> {
    if (sector0.size < 512) return emptyList()
    val signed = (sector0[510].toInt() and 0xFF) == 0x55 && (sector0[511].toInt() and 0xFF) == 0xAA
    if (!signed) return emptyList()

    val out = ArrayList<UsbPartition>(4)
    for (slot in 0 until 4) {
        val off = 446 + slot * 16
        val type = sector0[off + 4].toInt() and 0xFF
        if (type == 0x00) continue
        val bb = ByteBuffer.wrap(sector0, off + 8, 8).order(ByteOrder.LITTLE_ENDIAN)
        val start = bb.int.toLong() and 0xFFFFFFFFL
        val count = bb.int.toLong() and 0xFFFFFFFFL
        // A partition starting at sector 0 would overlap the table itself, and a zero
        // length is not a partition. Both appear in tables written by broken tools.
        if (start == 0L || count == 0L) continue
        if (deviceSectors > 0 && start + count > deviceSectors) continue
        val bootFlag = sector0[off].toInt() and 0xFF
        out += UsbPartition(
            slot = slot,
            typeByte = type,
            startLba = start,
            sectorCount = count,
            sectorSize = sectorSize,
            active = bootFlag == 0x80,
        )
    }
    return out
}

/**
 * True when [partitions] describe a GPT drive. Only the protective entry is visible from
 * the MBR; the real layout is a header at LBA 1 that this does not read, so the honest
 * thing is to say so rather than offer the one fake partition as if it were usable.
 */
fun isGptProtective(partitions: List<UsbPartition>): Boolean =
    partitions.size == 1 && partitions[0].typeByte == GPT_PROTECTIVE

/** Containers rather than data, so they are never a place to put a volume. */
fun UsbPartition.isExtendedContainer(): Boolean = typeByte in EXTENDED_TYPES
