/*
 * Copyright (c) 2026 Esdex
 * SPDX-License-Identifier: Apache-2.0
 */
package zip.arcanum.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A USB mass-storage device as a plain block device: bytes at an offset, in and out.
 *
 * This is the transport half of issue #95. The native `BlockBackend` for a USB volume
 * calls in here; everything above it - XTS, sector numbering, the read-only and hidden
 * volume guards - is unchanged and unaware that the bytes are not in a file.
 *
 * Speaks USB Mass Storage Bulk-Only Transport with the SCSI transparent command set,
 * which is what every flash drive implements. Measured on real hardware before this
 * class existed (see UsbMassStorageProbe): force-claiming the interface takes it from
 * Android's own mount, and reads and writes land exactly where addressed.
 *
 * Two sizing rules, both learned by measurement rather than assumed:
 *
 *  - **One bulkTransfer call cannot carry an unbounded buffer.** A single 512 KB call
 *    returns -1 having moved nothing. Every data phase is therefore chunked at
 *    [MAX_BULK_BYTES] and looped, which leaves the SCSI transfer length free to be
 *    whatever we want.
 *  - **Large SCSI commands are worth issuing.** Throughput climbs with transfer size
 *    (8 KB: 14.9 MB/s, 32 KB: 23.8, 128 KB: 29.6, 512 KB: 32.3) before flattening at
 *    the USB 2.0 ceiling. So a request is split at [MAX_TRANSFER_BYTES], not smaller.
 *    A caller that reads a sector at a time will be an order of magnitude slower; that
 *    is what the readahead layer above this class exists to prevent.
 *
 * Not thread-safe by accident: every operation takes the instance lock. Native callers
 * already arrive serialised behind `g_fatfs_mutex`, but this class must not depend on
 * a guarantee made in another language. Nothing here calls back into native code, so
 * that lock ordering cannot deadlock.
 */
class UsbBlockDevice private constructor(
    /** The device this was opened on, so a detach broadcast can be matched against it. */
    val device: UsbDevice,
    private val connection: UsbDeviceConnection,
    private val iface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint,
    private val readOnly: Boolean
) : Closeable {

    /** Set once by [readCapacity] during [open]; the device is unusable before that. */
    var blockSize: Int = 0
        private set
    var blockCount: Long = 0
        private set

    companion object {
        private const val CLASS_MASS_STORAGE = UsbConstants.USB_CLASS_MASS_STORAGE
        private const val SUBCLASS_SCSI = 6
        private const val PROTOCOL_BULK_ONLY = 80

        private const val CBW_SIGNATURE = 0x43425355
        private const val CSW_SIGNATURE = 0x53425355
        private const val CBW_LENGTH = 31
        private const val CSW_LENGTH = 13

        private const val TIMEOUT_MS = 5_000

        /**
         * The status phase gets its own, much longer budget.
         *
         * A data phase moves at link speed and is done in milliseconds; the status that
         * follows is where the drive actually commits the write to flash, and a cheap
         * stick doing an erase cycle mid-format can sit on that for seconds. Measured
         * here: a 512 KB write during mkfs exceeded five seconds and was reported as
         * "no CSW returned", which looked like a transport fault and was not one.
         */
        private const val STATUS_TIMEOUT_MS = 30_000

        /**
         * Kept, wired to nothing, because the investigation it served answered its
         * question: writes fail by SIZE, not by wear. See MAX_BULK_BYTES.
         */
        const val DIAGNOSTIC_MODE = false

        /**
         * Ceiling on ONE bulkTransfer call - the single most important number here.
         *
         * Measured with a size ladder on a SanDisk 3.2Gen1: writes of 4, 8, 16 and 32 KB
         * complete in 1-11 ms, and a 64 KB write gets no status at all, leaving the drive
         * answering nothing - not even TEST UNIT READY - until it is physically replugged.
         * The SCSI command is innocent: what breaks is asking Android to move that much in
         * one call. 16 KB is the historic usbfs limit and what mass-storage libraries use;
         * 32 KB happened to pass here, which is not a reason to sit on the edge.
         *
         * Everything else today was a symptom of this: the "wedge after ~30 MB", the
         * failed formats, the aborted imports. Do not raise it to chase throughput without
         * running the ladder again on more than one drive.
         */
        private const val MAX_BULK_BYTES = 16 * 1024

        /**
         * Ceiling on one SCSI command.
         *
         * Was 512 KB, chosen from a throughput sweep across an idle drive - where it read
         * and wrote happily at 32 MB/s. It did not survive real work: during mkfs and
         * during a file import, 512 KB WRITE(10) commands stopped returning a status at
         * all, and waiting twenty seconds did not help. Reads at that size never failed;
         * only writes, and only once other traffic was interleaved with them.
         *
         * 128 KB is in the range real drivers use - the kernel's own usb-storage caps a
         * transfer at about 120 KB, which is not caution but experience with what devices
         * actually tolerate. The measured cost is 29.6 MB/s against 32.3, ten percent,
         * for writes that complete.
         */
        const val MAX_TRANSFER_BYTES = 128 * 1024

        /**
         * Floor for the adaptive back-off below. One cluster's worth: small enough that
         * any device implementing the protocol at all should manage it, and large enough
         * that a drive stuck here is merely slow rather than unusable.
         */
        const val MIN_TRANSFER_BYTES = 16 * 1024

        /**
         * VeraCrypt's XTS folds an absolute 512-byte sector number into every sector,
         * so a drive that reports anything else cannot host a compatible volume without
         * a different sector mapping. Refused at open rather than silently mis-encrypted.
         */
        const val REQUIRED_BLOCK_SIZE = 512

        /** VeraCrypt header salt: 64 plaintext bytes at offset 0, before the encrypted body. */
        const val SALT_BYTES = 64

        /** True when this device exposes a mass-storage / SCSI / Bulk-Only interface. */
        fun massStorageInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val f = device.getInterface(i)
                if (f.interfaceClass == CLASS_MASS_STORAGE &&
                    f.interfaceSubclass == SUBCLASS_SCSI &&
                    f.interfaceProtocol == PROTOCOL_BULK_ONLY
                ) return f
            }
            return null
        }

        /**
         * Claims [device] and reads its geometry. The caller must already hold USB
         * permission for it. Throws [IOException] with a specific reason on any failure,
         * leaving nothing claimed or open.
         *
         * `force = true` on the claim detaches whatever driver currently owns the
         * interface, including the kernel driver behind an Android mount. That mount
         * does not survive and does not come back on release - the user has to replug
         * the drive for Android to see it again. This is deliberate: two writers on one
         * device, ours and a filesystem driver, would destroy it.
         */
        @Throws(IOException::class)
        fun open(manager: UsbManager, device: UsbDevice, readOnly: Boolean): UsbBlockDevice {
            val iface = massStorageInterface(device)
                ?: throw IOException("no mass-storage bulk-only interface on ${device.deviceName}")
            if (!manager.hasPermission(device)) {
                throw IOException("no USB permission for ${device.deviceName}")
            }
            val connection = manager.openDevice(device)
                ?: throw IOException("openDevice failed for ${device.deviceName}")

            var ok = false
            try {
                if (!connection.claimInterface(iface, true)) {
                    throw IOException("could not claim interface ${iface.id}")
                }
                var epIn: UsbEndpoint? = null
                var epOut: UsbEndpoint? = null
                for (i in 0 until iface.endpointCount) {
                    val e = iface.getEndpoint(i)
                    if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (e.direction == UsbConstants.USB_DIR_IN) epIn = epIn ?: e else epOut = epOut ?: e
                }
                if (epIn == null || epOut == null) {
                    throw IOException("no bulk endpoint pair on interface ${iface.id}")
                }

                val dev = UsbBlockDevice(device, connection, iface, epIn, epOut, readOnly)

                // NOTE: a Bulk-Only reset here was tried and made things strictly
                // worse - READ CAPACITY, which worked without it, began failing, and the
                // prescribed UNIT ATTENTION handshake afterwards did not repair it. Do
                // not reintroduce it without measuring again.
                dev.readCapacity()
                ok = true
                return dev
            } finally {
                if (!ok) {
                    runCatching { connection.releaseInterface(iface) }
                    runCatching { connection.close() }
                }
            }
        }
    }

    private val lock = Any()
    private var tag = 1


    private var closed = false
    private var inRequestSense = false

    /**
     * Set when the device stops answering at all, as opposed to a command failing.
     *
     * This exists to stop a hang, not to tidy state. Every transfer waits up to
     * [TIMEOUT_MS], and a filesystem asked to continue on a drive that has been pulled
     * will issue dozens of them - minutes of the app appearing frozen. Once one command
     * cannot even be sent, the rest fail immediately.
     */
    @Volatile
    private var dead = false

    /** True once the device stopped answering, or the transport was closed. */
    val isUsable: Boolean get() = !dead && !closed

    /**
     * Marks the device gone without touching it - for a detach broadcast, where the
     * hardware has already left and any further transfer would only wait out its timeout.
     */
    fun markDetached() {
        dead = true
        lastError = "the device was detached"
    }

    /**
     * Why the last operation failed, as the drive itself explained it, or null if the
     * last one succeeded. Filled in from REQUEST SENSE - without it a failure is just
     * "false" and there is nothing to diagnose from.
     */
    var lastError: String? = null
        private set

    val sizeBytes: Long get() = blockCount * blockSize

    /**
     * Reads [length] bytes at byte [offset] into [dest] starting at [destOffset].
     *
     * Offset and length must both be whole multiples of [blockSize]; the layers above
     * only ever work in sectors, so a violation is a bug rather than a case to round.
     * It fails loudly instead, because quietly reading the wrong span of an encrypted
     * volume produces plausible garbage rather than an error.
     */
    fun read(offset: Long, length: Int, dest: ByteArray, destOffset: Int = 0): Boolean =
        synchronized(lock) { transfer(offset, length, dest, destOffset, writing = false) }

    /** Writes [length] bytes from [dest] at byte [offset]. Refused when read-only. */
    fun write(offset: Long, length: Int, src: ByteArray, srcOffset: Int = 0): Boolean {
        if (readOnly) return false
        return synchronized(lock) { transfer(offset, length, src, srcOffset, writing = true) }
    }

    /**
     * SCSI INQUIRY: vendor, product and revision as the drive reports them. Purely
     * descriptive - it is what a "which drive is this?" prompt should show, so the user
     * confirming that a whole device is about to be encrypted can see what they picked.
     */
    fun inquiry(): String? = synchronized(lock) {
        val buf = ByteArray(36)
        if (!scsiIn(ByteArray(6).also { it[0] = 0x12; it[4] = 36 }, buf, 0, buf.size)) return null
        // SCSI pads these fixed-width fields with whatever the vendor felt like - spaces
        // by the spec, NULs in practice. Keeping only printable characters is what makes
        // this fit to show as a device name rather than something with \0 in the middle.
        fun field(off: Int, len: Int) =
            String(buf, off, len, Charsets.US_ASCII).filter { it in ' '..'~' }.trim()
        listOf(field(8, 8), field(16, 16), field(32, 4))
            .filter { it.isNotEmpty() }
            .joinToString(" ")
            .ifEmpty { null }
    }

    /**
     * SHA-256 of the volume header salt - the first 64 bytes of the device.
     *
     * This is how a USB-hosted vault is recognised again later. The salt is plaintext,
     * unique per volume and readable without the password, so it identifies the volume
     * rather than the hardware: the same stick reformatted is honestly a different vault,
     * and the same volume in a different port is the same one. Nothing about the device
     * serves this purpose - its name is a bus address that changes on replug, VID and PID
     * describe a model rather than a unit, and serial numbers are frequently absent.
     *
     * Null when the sector cannot be read. Note that it is NOT evidence a VeraCrypt
     * volume is present: ciphertext and random bytes are indistinguishable by design, so
     * a blank drive yields a perfectly good hash of nothing in particular.
     */
    fun volumeFingerprint(): String? = synchronized(lock) {
        val sector = ByteArray(blockSize.coerceAtLeast(SALT_BYTES))
        if (!read(0, sector.size, sector)) return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(sector.copyOf(SALT_BYTES))
        digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * SCSI SYNCHRONIZE CACHE(10) - the equivalent of fsync for this backend. Without it
     * a drive is free to keep the last writes in its own cache, so an unmount that
     * reported success could still lose them if the drive is pulled straight after.
     */
    fun sync(): Boolean = synchronized(lock) {
        if (readOnly) return true
        if (scsiNoData(ByteArray(10).also { it[0] = 0x35 })) return true

        // Plenty of flash drives simply do not implement this command. That is the drive
        // declining to be asked, not an I/O failure, and reporting it as one would make a
        // perfectly good unmount look broken. Treated as success, with the reason left in
        // lastError rather than swallowed.
        //
        // Note what this does NOT promise: a drive with a write-back cache and no way to
        // flush it can still lose the last writes if it is yanked immediately. That is a
        // durability caveat for USB volumes to surface in the UI, not something this
        // layer can fix.
        if (lastFailureWasUnsupported()) return true
        return false
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            runCatching { connection.releaseInterface(iface) }
            runCatching { connection.close() }
        }
    }

    // ── internals ────────────────────────────────────────────────────────────

    /**
     * READ CAPACITY(10): last LBA and block size, both big-endian, unlike the CBW and
     * CSW around them which are little-endian. Getting that backwards yields a
     * plausible-looking but absurd geometry rather than an error, so the size is
     * range-checked as well as parsed.
     */
    @Throws(IOException::class)
    private fun readCapacity() {
        val cap = ByteArray(8)
        if (!scsiIn(ByteArray(10).also { it[0] = 0x25 }, cap, 0, cap.size)) {
            throw IOException("READ CAPACITY(10) failed")
        }
        val bb = ByteBuffer.wrap(cap).order(ByteOrder.BIG_ENDIAN)
        val lastLba = bb.int.toLong() and 0xFFFFFFFFL
        val bs = (bb.int.toLong() and 0xFFFFFFFFL).toInt()
        if (bs != REQUIRED_BLOCK_SIZE) {
            throw IOException("block size $bs is not $REQUIRED_BLOCK_SIZE, cannot host a VeraCrypt volume")
        }
        if (lastLba <= 0) throw IOException("nonsensical capacity: last LBA $lastLba")
        blockSize = bs
        blockCount = lastLba + 1
    }

    private fun transfer(
        offset: Long,
        length: Int,
        buf: ByteArray,
        bufOffset: Int,
        writing: Boolean
    ): Boolean {
        // Each refusal says which one it was: "the write failed" is not enough to act on
        // when the causes are as different as a dead device and a misaligned request.
        if (closed || dead) {
            lastError = if (dead) "device stopped responding" else "transport closed"
            return log("$lastError")
        }
        if (offset % blockSize != 0L || length % blockSize != 0) {
            return log("unaligned: offset=$offset length=$length blockSize=$blockSize")
        }
        if (length < 0 || bufOffset < 0 || bufOffset + length > buf.size) {
            return log("buffer too small: need ${bufOffset + length}, have ${buf.size}")
        }
        if (offset < 0 || offset + length > sizeBytes) {
            return log("past the end: offset=$offset length=$length size=$sizeBytes")
        }

        var done = 0
        while (done < length) {
            val chunk = minOf(MAX_TRANSFER_BYTES, length - done)
            val lba = (offset + done) / blockSize
            val sectors = chunk / blockSize
            val cdb = cdb10(if (writing) 0x2A else 0x28, lba, sectors)
            val at = bufOffset + done
            // No retry, and no shrinking on failure. Both were here to survive drives
            // that "could not manage large commands", which was never the illness - the
            // per-call transfer size was. Retrying into a device whose state we are no
            // longer sure of is how a single fault became an unrecoverable one.
            val ok = if (writing) scsiOut(cdb, buf, at, chunk) else scsiIn(cdb, buf, at, chunk)
            if (!ok) return log("${if (writing) "write" else "read"} of $chunk B at lba $lba failed: ${lastError ?: "no reason"}")
            done += chunk
        }
        return true
    }

    /** READ(10) / WRITE(10): 32-bit LBA, 16-bit transfer length, both big-endian. */
    private fun cdb10(opcode: Int, lba: Long, sectors: Int): ByteArray =
        ByteArray(10).also {
            it[0] = opcode.toByte()
            it[2] = (lba ushr 24).toByte()
            it[3] = (lba ushr 16).toByte()
            it[4] = (lba ushr 8).toByte()
            it[5] = lba.toByte()
            it[7] = (sectors ushr 8).toByte()
            it[8] = sectors.toByte()
        }

    private fun buildCbw(cdb: ByteArray, dataLength: Int, deviceToHost: Boolean, myTag: Int): ByteArray =
        ByteBuffer.allocate(CBW_LENGTH).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(CBW_SIGNATURE)
            putInt(myTag)
            putInt(dataLength)
            put(if (deviceToHost) 0x80.toByte() else 0x00)
            put(0)                 // LUN 0
            put(cdb.size.toByte())
            put(cdb)
        }.array()

    /**
     * A command block that cannot even be handed to the endpoint means the device is no
     * longer there - a live drive rejecting a command answers with a CSW instead. So this
     * is the one failure treated as fatal to the whole transport rather than to one call.
     */
    /**
     * TEST UNIT READY - six zero bytes, no data phase, the cheapest question there is.
     * Used after a failure to find out whether the device is still speaking at all.
     */
    private fun testUnitReady(): Boolean {
        val myTag = tag++
        val cbw = buildCbw(ByteArray(6), 0, deviceToHost = false, myTag = myTag)
        if (connection.bulkTransfer(epOut, cbw, cbw.size, TIMEOUT_MS) != CBW_LENGTH) return false
        val csw = ByteArray(CSW_LENGTH)
        return connection.bulkTransfer(epIn, csw, csw.size, TIMEOUT_MS) == CSW_LENGTH
    }

    /**
     * Bulk-Only Mass Storage Reset, followed by clearing both endpoint halts.
     *
     * The recovery the specification prescribes when host and device may disagree about
     * what is in flight - after a status that never arrived, for instance. Without it a
     * retry is sent into a device that is still finishing the previous command.
     */
    private fun botReset(): Boolean {
        val rc = connection.controlTransfer(
            0x21,               // host to device, class, interface
            0xFF,               // Bulk-Only Mass Storage Reset
            0, iface.id,
            null, 0, TIMEOUT_MS
        )
        clearHalt(epIn)
        clearHalt(epOut)
        return rc >= 0
    }

    /**
     * Clears a stalled bulk endpoint - CLEAR_FEATURE(ENDPOINT_HALT) on the standard
     * control pipe.
     *
     * Required by Bulk-Only Transport, not optional politeness: a device that stalls a
     * data phase leaves the endpoint halted, and every command after it fails until the
     * halt is cleared. Skipping this is why a single failed write turned into a dead
     * transport rather than a retryable hiccup.
     */
    private fun clearHalt(endpoint: UsbEndpoint): Boolean {
        val rc = connection.controlTransfer(
            0x02,               // host to device, standard, endpoint
            0x01,               // CLEAR_FEATURE
            0x00,               // ENDPOINT_HALT
            endpoint.address,
            null, 0, TIMEOUT_MS
        )
        return rc >= 0
    }

    /** Logs and returns false, so a refusal is one expression at the call site. */
    private fun log(reason: String): Boolean {
        android.util.Log.e("ArcanumUsb", reason)
        return false
    }

    private fun failDead(): Boolean {
        dead = true
        lastError = "the device stopped responding - was it unplugged?"
        return false
    }

    private fun readCsw(myTag: Int): Boolean {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val csw = ByteArray(CSW_LENGTH)
        if (connection.bulkTransfer(epIn, csw, csw.size, STATUS_TIMEOUT_MS) != CSW_LENGTH) {
            lastError = "no CSW returned"
            // Deliberately nothing else. A Bulk-Only reset here is the specified
            // recovery, but it was never shown to help, and the one time a reset was
            // tried elsewhere - right after claiming the interface - it broke READ
            // CAPACITY, which had worked without it. Losing a status now means the
            // caller gets a clean failure rather than a device in a state we invented.
            return false
        }
        // A status that took seconds is the difference between "the drive is slow" and
        // "the drive is broken", and only a measurement can say which.
        val waited = android.os.SystemClock.elapsedRealtime() - startedAt
        if (waited > 1000) android.util.Log.w("ArcanumUsb", "status took ${waited}ms")

        val cb = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        val sig = cb.int
        val rtag = cb.int
        cb.int // residue
        val status = cb.get().toInt()

        if (sig != CSW_SIGNATURE || rtag != myTag) {
            lastError = "malformed CSW (sig=%08x tag=%d expected %d)".format(sig, rtag, myTag)
            return false
        }
        if (status == 0) {
            lastError = null
            return true
        }

        // Status 1 is CHECK CONDITION: the drive is holding sense data and, until it is
        // read, some devices refuse everything that follows. So REQUEST SENSE is not
        // only how we learn the reason, it is what unwedges the device.
        lastError = if (status == 1) requestSense() else "CSW status $status (phase error)"
        return false
    }

    /**
     * REQUEST SENSE(6). Returns a human-readable reason and, as a side effect, clears
     * the pending CHECK CONDITION. Guarded against recursion: a failure inside here must
     * not ask for sense about itself.
     */
    private fun requestSense(): String {
        if (inRequestSense) return "sense unavailable (nested failure)"
        inRequestSense = true
        try {
            val sense = ByteArray(18)
            val myTag = tag++
            val cbw = buildCbw(
                ByteArray(6).also { it[0] = 0x03; it[4] = sense.size.toByte() },
                sense.size, deviceToHost = true, myTag = myTag
            )
            if (connection.bulkTransfer(epOut, cbw, cbw.size, TIMEOUT_MS) != CBW_LENGTH) {
                return "CHECK CONDITION, and REQUEST SENSE could not be sent"
            }
            var got = 0
            while (got < sense.size) {
                val n = connection.bulkTransfer(epIn, sense, got, sense.size - got, TIMEOUT_MS)
                if (n <= 0) break
                got += n
            }
            // Drain its own CSW so the next command starts from a clean phase.
            val csw = ByteArray(CSW_LENGTH)
            connection.bulkTransfer(epIn, csw, csw.size, TIMEOUT_MS)

            if (got < 14) return "CHECK CONDITION, sense data truncated ($got bytes)"
            val key = sense[2].toInt() and 0x0F
            val asc = sense[12].toInt() and 0xFF
            val ascq = sense[13].toInt() and 0xFF
            return "CHECK CONDITION key=0x%02x asc=0x%02x ascq=0x%02x (%s)".format(
                key, asc, ascq, senseText(key, asc)
            )
        } finally {
            inRequestSense = false
        }
    }

    private fun senseText(key: Int, asc: Int): String = when {
        key == 0x05 && asc == 0x20 -> "invalid command - the drive does not implement it"
        key == 0x05 -> "illegal request"
        key == 0x02 -> "not ready"
        key == 0x03 -> "medium error"
        key == 0x04 -> "hardware error"
        key == 0x06 -> "unit attention - the device reset or the medium changed"
        key == 0x07 -> "data protect - the drive is write protected"
        else -> "sense key $key"
    }

    /** True when the last failure was the drive saying it has no such command. */
    private fun lastFailureWasUnsupported(): Boolean =
        lastError?.contains("asc=0x20") == true

    /**
     * Debug-only bridge to the native BlockBackend built on this device (issue #95).
     * Reads [length] bytes at [offset] the way a mounted volume would - through JNI,
     * the scratch array and the chunking loop - so the result can be compared against
     * [read] on the same span. Present only in debug builds; returns null in release,
     * where the native symbol does not exist.
     */
    fun readThroughNativeBackend(offset: Long, length: Int): ByteArray? = synchronized(lock) {
        if (closed) return null
        // The lock is reentrant, and it has to be: the native side calls straight back
        // into read() on this same thread.
        return try {
            runCatching { System.loadLibrary("arcanum-native") }
            nativeReadThroughBackend(this, offset, length)
        } catch (e: UnsatisfiedLinkError) {
            null // release build: the symbol is gated out with the KAT hooks
        }
    }

    /** Debug-only I/O census over the native backend, for sizing a cache. */
    fun ioStats(): String? = try {
        runCatching { System.loadLibrary("arcanum-native") }
        nativeIoStats()
    } catch (e: UnsatisfiedLinkError) { null }

    fun resetIoStats() {
        try {
            runCatching { System.loadLibrary("arcanum-native") }
            nativeResetIoStats()
        } catch (e: UnsatisfiedLinkError) { /* release build */ }
    }

    private external fun nativeIoStats(): String?
    private external fun nativeResetIoStats()

    private external fun nativeReadThroughBackend(
        transport: UsbBlockDevice,
        offset: Long,
        length: Int
    ): ByteArray?

    private fun scsiIn(cdb: ByteArray, dest: ByteArray, destOffset: Int, length: Int): Boolean {
        if (dead) return false
        val myTag = tag++
        val cbw = buildCbw(cdb, length, deviceToHost = true, myTag = myTag)
        if (connection.bulkTransfer(epOut, cbw, cbw.size, TIMEOUT_MS) != CBW_LENGTH) return failDead()

        var got = 0
        while (got < length) {
            val want = minOf(MAX_BULK_BYTES, length - got)
            val n = connection.bulkTransfer(epIn, dest, destOffset + got, want, TIMEOUT_MS)
            if (n <= 0) {
                lastError = "data-in stalled after $got/$length bytes (rc=$n)"
                clearHalt(epIn)
                readCsw(myTag)          // drain it, or the next command reads this one's
                return false
            }
            got += n
        }
        return readCsw(myTag)
    }

    private fun scsiOut(cdb: ByteArray, src: ByteArray, srcOffset: Int, length: Int): Boolean {
        if (dead) return false
        val myTag = tag++
        val cbw = buildCbw(cdb, length, deviceToHost = false, myTag = myTag)
        if (connection.bulkTransfer(epOut, cbw, cbw.size, TIMEOUT_MS) != CBW_LENGTH) return failDead()

        var sent = 0
        while (sent < length) {
            val want = minOf(MAX_BULK_BYTES, length - sent)
            val n = connection.bulkTransfer(epOut, src, srcOffset + sent, want, TIMEOUT_MS)
            if (n <= 0) {
                lastError = "data-out stalled after $sent/$length bytes (rc=$n)"
                clearHalt(epOut)
                readCsw(myTag)
                return false
            }
            sent += n
        }
        return readCsw(myTag)
    }

    /** A command with no data phase: CBW then CSW. */
    private fun scsiNoData(cdb: ByteArray): Boolean {
        if (closed || dead) return false
        val myTag = tag++
        val cbw = buildCbw(cdb, 0, deviceToHost = false, myTag = myTag)
        if (connection.bulkTransfer(epOut, cbw, cbw.size, TIMEOUT_MS) != CBW_LENGTH) return failDead()
        return readCsw(myTag)
    }
}
