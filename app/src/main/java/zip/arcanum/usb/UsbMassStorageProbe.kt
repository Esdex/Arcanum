/*
 * Copyright (c) 2026 Esdex
 * SPDX-License-Identifier: Apache-2.0
 */
package zip.arcanum.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.measureTimeMillis

/**
 * Read-only feasibility probe for issue #95 (whole-device VeraCrypt volumes on USB).
 *
 * This is a SPIKE, not a transport. It answers the questions that decide whether the
 * feature is possible at all, and it deliberately contains no write path: the only SCSI
 * commands it issues are INQUIRY, READ CAPACITY(10) and READ(10). It cannot modify a
 * connected drive even if it malfunctions.
 *
 * What it is meant to establish, on real hardware:
 *
 *  1. Does the phone enumerate a mass-storage device at all (USB host support present)?
 *  2. Does `claimInterface(force = true)` take the interface away from the kernel driver
 *     while Android's own storage stack has, or wants, the same device?
 *  3. Do Bulk-Only Transport + SCSI actually work through `bulkTransfer`?
 *  4. What does sector 0 look like, and is Android concurrently mounting the drive?
 *  5. What raw read throughput do we get? This decides the transport design: a Kotlin
 *     transport calling down through JNI is far simpler than reimplementing USB bulk
 *     transfers natively over usbfs, but only if it is fast enough to sit under XTS for
 *     a whole device.
 *
 * Everything is reported as text so it can be copied out of the debug screen.
 */
class UsbMassStorageProbe(private val context: Context) {

    companion object {
        private const val ACTION_USB_PERMISSION = "zip.arcanum.USB_PERMISSION"

        /** USB mass storage, SCSI transparent command set, Bulk-Only Transport. */
        private const val CLASS_MASS_STORAGE = UsbConstants.USB_CLASS_MASS_STORAGE // 8
        private const val SUBCLASS_SCSI = 6
        private const val PROTOCOL_BULK_ONLY = 80 // 0x50

        private const val CBW_SIGNATURE = 0x43425355 // "USBC"
        private const val CSW_SIGNATURE = 0x53425355 // "USBS"
        private const val CBW_LENGTH = 31
        private const val CSW_LENGTH = 13

        private const val TIMEOUT_MS = 5_000

        /** Ceiling on a single bulkTransfer call; larger buffers are split and looped. */
        private const val MAX_BULK_BYTES = 64 * 1024

        /** Bytes read at each transfer size in the throughput sweep. */
        private const val THROUGHPUT_BYTES = 4 * 1024 * 1024
    }

    private val out = StringBuilder()
    private var tag = 1

    private fun line(s: String = "") {
        out.append(s).append('\n')
    }

    /** Read-only probe. Issues INQUIRY, READ CAPACITY and READ only. */
    suspend fun run(): String = withTarget("read-only probe") { conn, epIn, epOut ->
        probe(conn, epIn, epOut)
    }

    /**
     * DESTRUCTIVE. Writes a pattern to one sector, reads it back, then restores the
     * original bytes. Deliberately a separate entry point from [run] so it cannot be
     * triggered by the read-only button.
     */
    suspend fun runWriteTest(): String = withTarget("WRITE TEST - modifies the drive") { conn, epIn, epOut ->
        writeTest(conn, epIn, epOut)
    }

    /**
     * Everything both entry points need: find a mass-storage device, get permission, open
     * it, take the interface, locate the bulk endpoint pair. Releases all of it afterwards
     * whatever [body] does.
     */
    private suspend fun withTarget(
        what: String,
        body: (UsbDeviceConnection, UsbEndpoint, UsbEndpoint) -> Unit
    ): String = withContext(Dispatchers.IO) {
        out.clear()
        line("=== Arcanum USB mass-storage probe ($what) ===")
        line("time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
        line("device: ${Build.MANUFACTURER} ${Build.MODEL} | sdk ${Build.VERSION.SDK_INT}")
        line()

        val hasHost = context.packageManager.hasSystemFeature("android.hardware.usb.host")
        line("[host support] android.hardware.usb.host = $hasHost")
        if (!hasHost) {
            line("STOP: this phone reports no USB host support. Nothing else can work.")
            return@withContext out.toString()
        }

        reportSystemVolumes()

        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val devices = manager.deviceList.values.toList()
        line()
        line("[enumeration] ${devices.size} USB device(s) attached")
        if (devices.isEmpty()) {
            line("STOP: nothing attached. Plug a flash drive in through OTG and run again.")
            return@withContext out.toString()
        }

        devices.forEach { describeDevice(it) }

        val target = devices.firstNotNullOfOrNull { dev ->
            findMassStorageInterface(dev)?.let { dev to it }
        }
        if (target == null) {
            line()
            line("STOP: no mass-storage / SCSI / Bulk-Only interface among the attached devices.")
            return@withContext out.toString()
        }

        val (device, iface) = target
        line()
        line("[target] ${device.deviceName} interface #${iface.id}")

        if (!requestPermission(manager, device)) {
            line("STOP: permission for the device was not granted.")
            return@withContext out.toString()
        }
        line("[permission] granted")

        val connection = manager.openDevice(device)
        if (connection == null) {
            line("STOP: openDevice returned null despite permission.")
            return@withContext out.toString()
        }

        try {
            // force = true is the whole question: it asks the platform to detach whatever
            // driver currently owns the interface (the kernel usb-storage driver, if Android
            // has already mounted the drive) and hand it to us.
            val claimed = connection.claimInterface(iface, true)
            line("[claim] claimInterface(force=true) = $claimed")
            if (!claimed) {
                line("STOP: could not claim the interface. Raw block access is not available this way.")
                return@withContext out.toString()
            }

            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) {
                val e = iface.getEndpoint(i)
                if (e.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (e.direction == UsbConstants.USB_DIR_IN) epIn = epIn ?: e else epOut = epOut ?: e
            }
            if (epIn == null || epOut == null) {
                line("STOP: bulk IN/OUT endpoint pair not found (in=$epIn out=$epOut).")
                return@withContext out.toString()
            }
            line("[endpoints] in=${epIn.address} out=${epOut.address} maxPacket=${epIn.maxPacketSize}")

            body(connection, epIn, epOut)
        } catch (t: Throwable) {
            line("EXCEPTION: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { connection.releaseInterface(iface) }
            runCatching { connection.close() }
            line()
            line("[cleanup] interface released, connection closed")
            line("The drive is gone from Android's file manager until you unplug and replug it:")
            line("claiming the interface detached the kernel driver holding its mount.")
        }

        out.toString()
    }

    /**
     * Whether Android has the drive mounted itself. If it does, we are about to take the
     * interface away from a stack that believes it owns the device - which is exactly the
     * conflict this probe exists to observe.
     */
    private fun reportSystemVolumes() {
        line()
        line("[android storage stack]")
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        if (sm == null) {
            line("  StorageManager unavailable")
            return
        }
        val volumes = runCatching { sm.storageVolumes }.getOrNull().orEmpty()
        val removable = volumes.filter { it.isRemovable }
        if (removable.isEmpty()) {
            line("  no removable volume mounted by the system")
        } else {
            removable.forEach { v ->
                val desc = runCatching { v.getDescription(context) }.getOrNull() ?: "?"
                line("  mounted: \"$desc\" state=${v.state} primary=${v.isPrimary}")
            }
            line("  NOTE: the system has this drive mounted. Claiming it below is a conflict.")
        }
    }

    private fun describeDevice(d: UsbDevice) {
        line("  ${d.deviceName}  VID=%04x PID=%04x  \"%s %s\"".format(
            d.vendorId, d.productId, d.manufacturerName ?: "?", d.productName ?: "?"
        ))
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            val mass = f.interfaceClass == CLASS_MASS_STORAGE &&
                f.interfaceSubclass == SUBCLASS_SCSI &&
                f.interfaceProtocol == PROTOCOL_BULK_ONLY
            line("    iface #${f.id} class=${f.interfaceClass} sub=${f.interfaceSubclass} " +
                "proto=${f.interfaceProtocol} endpoints=${f.endpointCount}${if (mass) "  <- mass storage BOT" else ""}")
        }
    }

    private fun findMassStorageInterface(d: UsbDevice): UsbInterface? {
        for (i in 0 until d.interfaceCount) {
            val f = d.getInterface(i)
            if (f.interfaceClass == CLASS_MASS_STORAGE &&
                f.interfaceSubclass == SUBCLASS_SCSI &&
                f.interfaceProtocol == PROTOCOL_BULK_ONLY
            ) return f
        }
        return null
    }

    private suspend fun requestPermission(manager: UsbManager, device: UsbDevice): Boolean {
        if (manager.hasPermission(device)) return true

        val granted = CompletableDeferred<Boolean>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != ACTION_USB_PERMISSION) return
                granted.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            val pi = PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE
            )
            manager.requestPermission(device, pi)
            return withTimeoutOrNull(60_000) { granted.await() } ?: false
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun probe(connection: UsbDeviceConnection, epIn: UsbEndpoint, epOut: UsbEndpoint) {
        // ── INQUIRY ──────────────────────────────────────────────────────────
        val inquiry = ByteArray(36)
        val inqCdb = ByteArray(6).also { it[0] = 0x12; it[4] = 36 }
        if (!scsiIn(connection, epIn, epOut, inqCdb, inquiry)) {
            line("STOP: INQUIRY failed.")
            return
        }
        val vendor = String(inquiry, 8, 8).trim()
        val product = String(inquiry, 16, 16).trim()
        val rev = String(inquiry, 32, 4).trim()
        line("[inquiry] vendor=\"$vendor\" product=\"$product\" rev=\"$rev\"")

        // ── READ CAPACITY(10) ────────────────────────────────────────────────
        val cap = ByteArray(8)
        val capCdb = ByteArray(10).also { it[0] = 0x25 }
        if (!scsiIn(connection, epIn, epOut, capCdb, cap)) {
            line("STOP: READ CAPACITY(10) failed.")
            return
        }
        val bb = ByteBuffer.wrap(cap).order(ByteOrder.BIG_ENDIAN)
        val lastLba = bb.int.toLong() and 0xFFFFFFFFL
        val blockSize = bb.int.toLong() and 0xFFFFFFFFL
        val totalBytes = (lastLba + 1) * blockSize
        line("[capacity] lastLBA=$lastLba blockSize=$blockSize total=${totalBytes / (1024 * 1024)} MB")
        if (blockSize != 512L) {
            line("  NOTE: block size is not 512. VeraCrypt's XTS sector numbering assumes 512-byte")
            line("  sectors, so this drive needs the sector-size question answered before use.")
        }

        // ── READ(10) sector 0 ────────────────────────────────────────────────
        val sector0 = ByteArray(blockSize.toInt())
        if (!scsiIn(connection, epIn, epOut, read10Cdb(0, 1, blockSize), sector0)) {
            line("STOP: READ(10) of sector 0 failed.")
            return
        }
        line("[sector 0] first 64 bytes:")
        line(hexdump(sector0, 64))
        val bootSig = (sector0[510].toInt() and 0xFF) == 0x55 && (sector0[511].toInt() and 0xFF) == 0xAA
        line("  0x55AA boot signature: $bootSig")
        line("  entropy of the sector (${sector0.size} bytes): ${"%.2f".format(shannonBits(sector0))} bits/byte " +
            "(near 8.00 means encrypted or random, low means a partition table or filesystem)")
        if (bootSig) describePartitionTable(sector0)

        // A 32-bit LBA in READ(10) tops out at 2 TB with 512-byte sectors, which no flash
        // drive reaches, but the last sector is still the one addressing bugs surface on:
        // it is the only read whose LBA uses the full width.
        val lastSector = ByteArray(blockSize.toInt())
        val lastOk = scsiIn(connection, epIn, epOut, read10Cdb(lastLba, 1, blockSize), lastSector)
        line("[last sector] LBA $lastLba read: $lastOk" +
            if (lastOk) " (entropy ${"%.2f".format(shannonBits(lastSector))} bits/byte)" else "")

        throughputSweep(connection, epIn, epOut, lastLba, blockSize)

        line()
        line("VERDICT: raw block access over BOT/SCSI works on this device.")
        line("No write command was issued; the drive is untouched.")
    }

    /**
     * The remaining unknown: does WRITE(10) work, and does it land where we aim it?
     *
     * Target is a sector inside the alignment gap between the MBR and the first partition
     * (LBA 1 up to the partition's startLBA, 2048 on this drive - a megabyte no filesystem
     * ever uses). Writing there touches neither the partition table nor any filesystem
     * structure, so even a total failure costs nothing but the sector itself.
     *
     * The test is read - write pattern - read back - restore - read back, and it also checks
     * the two neighbouring sectors are untouched. That neighbour check is the point: a
     * write that lands one sector off still passes a naive read-back, because the read is
     * aimed the same wrong way. Only an independent witness catches it.
     */
    private fun writeTest(conn: UsbDeviceConnection, epIn: UsbEndpoint, epOut: UsbEndpoint) {
        val cap = ByteArray(8)
        if (!scsiIn(conn, epIn, epOut, ByteArray(10).also { it[0] = 0x25 }, cap)) {
            line("STOP: READ CAPACITY(10) failed.")
            return
        }
        val bb = ByteBuffer.wrap(cap).order(ByteOrder.BIG_ENDIAN)
        val lastLba = bb.int.toLong() and 0xFFFFFFFFL
        val bs = (bb.int.toLong() and 0xFFFFFFFFL).toInt()
        line("[capacity] lastLBA=$lastLba blockSize=$bs")

        val sector0 = ByteArray(bs)
        if (!scsiIn(conn, epIn, epOut, read10Cdb(0, 1, bs.toLong()), sector0)) {
            line("STOP: could not read sector 0 to locate the partition gap.")
            return
        }
        val bootSig = (sector0[510].toInt() and 0xFF) == 0x55 && (sector0[511].toInt() and 0xFF) == 0xAA
        var firstPartLba = 0L
        if (bootSig) {
            for (i in 0 until 4) {
                val off = 446 + i * 16
                if ((sector0[off + 4].toInt() and 0xFF) == 0x00) continue
                val s = ByteBuffer.wrap(sector0, off + 8, 4).order(ByteOrder.LITTLE_ENDIAN)
                    .int.toLong() and 0xFFFFFFFFL
                if (firstPartLba == 0L || s < firstPartLba) firstPartLba = s
            }
        }
        if (firstPartLba < 8) {
            line("STOP: no MBR gap on this drive (first partition at LBA $firstPartLba).")
            line("Refusing to write: without the gap there is no sector here that is safe by construction.")
            return
        }
        val target = firstPartLba / 2
        line("[target] LBA $target, inside the unused gap between the MBR and LBA $firstPartLba")

        val before = ByteArray(bs)
        val neighLow = ByteArray(bs)
        val neighHigh = ByteArray(bs)
        if (!scsiIn(conn, epIn, epOut, read10Cdb(target, 1, bs.toLong()), before) ||
            !scsiIn(conn, epIn, epOut, read10Cdb(target - 1, 1, bs.toLong()), neighLow) ||
            !scsiIn(conn, epIn, epOut, read10Cdb(target + 1, 1, bs.toLong()), neighHigh)
        ) {
            line("STOP: could not read the target and its neighbours first.")
            return
        }
        line("[before] target entropy ${"%.2f".format(shannonBits(before))} bits/byte")

        // Pattern carries the LBA it was meant for, so a misdirected write is legible in a
        // hex dump rather than just "the bytes differ".
        val pattern = ByteArray(bs)
        val stamp = "ARCANUM-PROBE-LBA-$target-".toByteArray()
        for (i in pattern.indices) pattern[i] = stamp[i % stamp.size]

        if (!scsiOut(conn, epIn, epOut, write10Cdb(target, 1, bs.toLong()), pattern)) {
            line("STOP: WRITE(10) failed. The drive is unchanged.")
            return
        }
        line("[write] WRITE(10) reported success")

        val readback = ByteArray(bs)
        if (!scsiIn(conn, epIn, epOut, read10Cdb(target, 1, bs.toLong()), readback)) {
            line("DANGER: wrote but could not read back. The sector may hold the pattern.")
            return
        }
        val matches = readback.contentEquals(pattern)
        line("[verify] read-back matches the pattern: $matches")

        val lowOk = run {
            val now = ByteArray(bs)
            scsiIn(conn, epIn, epOut, read10Cdb(target - 1, 1, bs.toLong()), now) &&
                now.contentEquals(neighLow)
        }
        val highOk = run {
            val now = ByteArray(bs)
            scsiIn(conn, epIn, epOut, read10Cdb(target + 1, 1, bs.toLong()), now) &&
                now.contentEquals(neighHigh)
        }
        line("[neighbours] LBA ${target - 1} unchanged: $lowOk | LBA ${target + 1} unchanged: $highOk")

        // ── restore ──────────────────────────────────────────────────────────
        if (!scsiOut(conn, epIn, epOut, write10Cdb(target, 1, bs.toLong()), before)) {
            line("DANGER: could not restore LBA $target. It still holds the probe pattern.")
            return
        }
        val restored = ByteArray(bs)
        val restoreOk = scsiIn(conn, epIn, epOut, read10Cdb(target, 1, bs.toLong()), restored) &&
            restored.contentEquals(before)
        line("[restore] original bytes back in place: $restoreOk")

        line()
        if (matches && lowOk && highOk && restoreOk) {
            line("VERDICT: WRITE(10) works and lands exactly where addressed.")
            line("The drive is byte-for-byte as it was before this test.")
        } else {
            line("VERDICT: something is off above - do not build a transport on this until it is explained.")
        }
    }

    private fun write10Cdb(lba: Long, sectors: Int, blockSize: Long): ByteArray =
        read10Cdb(lba, sectors, blockSize).also { it[0] = 0x2A }

    /**
     * The four MBR entries at offset 446. Worth printing because whole-device encryption
     * overwrites this sector first: what is listed here is exactly what stops existing
     * once the drive becomes a VeraCrypt volume.
     */
    private fun describePartitionTable(sector0: ByteArray) {
        line("  partition table:")
        var any = false
        for (i in 0 until 4) {
            val off = 446 + i * 16
            val type = sector0[off + 4].toInt() and 0xFF
            if (type == 0x00) continue
            any = true
            val bb = ByteBuffer.wrap(sector0, off + 8, 8).order(ByteOrder.LITTLE_ENDIAN)
            val start = bb.int.toLong() and 0xFFFFFFFFL
            val count = bb.int.toLong() and 0xFFFFFFFFL
            val boot = if ((sector0[off].toInt() and 0xFF) == 0x80) " active" else ""
            line("    #$i type=0x%02x (%s)%s startLBA=%d sectors=%d (%d MB)".format(
                type, partitionTypeName(type), boot, start, count, count * 512 / (1024 * 1024)
            ))
        }
        if (!any) line("    (all four entries empty)")
    }

    private fun partitionTypeName(t: Int): String = when (t) {
        0x01, 0x04, 0x06, 0x0E -> "FAT12/16"
        0x0B, 0x0C -> "FAT32"
        0x07 -> "exFAT/NTFS"
        0x83 -> "Linux"
        0xEE -> "GPT protective - real layout is a GPT header at LBA 1"
        else -> "unknown"
    }

    /**
     * Reads at several transfer sizes to separate two explanations of a given speed: our
     * per-command overhead (three bulk transfers per SCSI command) versus the drive's own
     * ceiling. If throughput climbs with the transfer size, overhead dominates and a native
     * usbfs transport is worth considering; if it plateaus, the drive is the limit and the
     * simple Kotlin transport costs nothing.
     *
     * Each size reads a fresh region, so the drive's read-ahead cache cannot flatter a later
     * run with data an earlier one already pulled.
     */
    private fun throughputSweep(
        conn: UsbDeviceConnection,
        epIn: UsbEndpoint,
        epOut: UsbEndpoint,
        lastLba: Long,
        blockSize: Long
    ) {
        line("[throughput sweep] sequential, no crypto, fresh region per size")
        var region = 0L
        for (kb in listOf(8, 32, 128, 512)) {
            val chunkBytes = kb * 1024
            val chunkSectors = (chunkBytes / blockSize).toInt()
            if (chunkSectors <= 0) continue
            val chunks = (THROUGHPUT_BYTES / chunkBytes).coerceAtLeast(1)
            val span = chunks.toLong() * chunkSectors
            if (region + span > lastLba) {
                line("  %4d KB: not enough room left on the device, skipped".format(kb))
                continue
            }
            val buf = ByteArray(chunkBytes)
            var read = 0L
            var failed = false
            val ms = measureTimeMillis {
                for (c in 0 until chunks) {
                    val lba = region + c.toLong() * chunkSectors
                    if (!scsiIn(conn, epIn, epOut, read10Cdb(lba, chunkSectors, blockSize), buf)) {
                        failed = true
                        break
                    }
                    read += buf.size
                }
            }
            region += span
            when {
                failed -> line("  %4d KB: FAILED after %d KB - this transfer size is not usable".format(kb, read / 1024))
                ms <= 0 -> line("  %4d KB: %d KB too fast to time".format(kb, read / 1024))
                else -> line("  %4d KB: %5.1f MB/s (%d KB in %d ms)".format(
                    kb, (read / 1024.0 / 1024.0) / (ms / 1000.0), read / 1024, ms
                ))
            }
        }
    }

    private fun read10Cdb(lba: Long, sectors: Int, blockSize: Long): ByteArray =
        ByteArray(10).also {
            it[0] = 0x28
            it[2] = (lba ushr 24).toByte()
            it[3] = (lba ushr 16).toByte()
            it[4] = (lba ushr 8).toByte()
            it[5] = lba.toByte()
            it[7] = (sectors ushr 8).toByte()
            it[8] = sectors.toByte()
        }

    /**
     * One device-to-host SCSI command: CBW out, data in, CSW in. Returns true when the
     * command block wrapper came back with status 0 and the expected data arrived.
     */
    private fun scsiIn(
        conn: UsbDeviceConnection,
        epIn: UsbEndpoint,
        epOut: UsbEndpoint,
        cdb: ByteArray,
        data: ByteArray
    ): Boolean {
        val myTag = tag++
        val cbw = ByteBuffer.allocate(CBW_LENGTH).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(CBW_SIGNATURE)
            putInt(myTag)
            putInt(data.size)
            put(0x80.toByte())      // bmCBWFlags: device to host
            put(0)                  // LUN 0
            put(cdb.size.toByte())  // command length
            put(cdb)
        }.array()

        if (conn.bulkTransfer(epOut, cbw, cbw.size, TIMEOUT_MS) != CBW_LENGTH) {
            line("  ! CBW transfer failed (cdb 0x%02x)".format(cdb[0]))
            return false
        }

        // One bulkTransfer cannot carry an unbounded buffer - the platform caps a single
        // call, and asking for 512 KB in one go returned -1 with nothing transferred. Cap
        // each call and loop, so the SCSI transfer length is ours to choose.
        var got = 0
        while (got < data.size) {
            val want = minOf(MAX_BULK_BYTES, data.size - got)
            val tmp = ByteArray(want)
            val n = conn.bulkTransfer(epIn, tmp, want, TIMEOUT_MS)
            if (n <= 0) {
                line("  ! data phase returned $n after $got/${data.size} bytes (cdb 0x%02x)".format(cdb[0]))
                return false
            }
            System.arraycopy(tmp, 0, data, got, n)
            got += n
        }

        val csw = ByteArray(CSW_LENGTH)
        if (conn.bulkTransfer(epIn, csw, csw.size, TIMEOUT_MS) != CSW_LENGTH) {
            line("  ! CSW transfer failed (cdb 0x%02x)".format(cdb[0]))
            return false
        }
        val cb = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        val sig = cb.int
        val rtag = cb.int
        cb.int // residue
        val status = cb.get().toInt()
        if (sig != CSW_SIGNATURE || rtag != myTag || status != 0) {
            line("  ! CSW bad: sig=%08x tag=%d/%d status=%d (cdb 0x%02x)".format(sig, rtag, myTag, status, cdb[0]))
            return false
        }
        return true
    }

    /**
     * One host-to-device SCSI command: CBW out, data out, CSW in. The only caller is the
     * write test; nothing on the read path can reach it.
     */
    private fun scsiOut(
        conn: UsbDeviceConnection,
        epIn: UsbEndpoint,
        epOut: UsbEndpoint,
        cdb: ByteArray,
        data: ByteArray
    ): Boolean {
        val myTag = tag++
        val cbw = ByteBuffer.allocate(CBW_LENGTH).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(CBW_SIGNATURE)
            putInt(myTag)
            putInt(data.size)
            put(0x00)               // bmCBWFlags: host to device
            put(0)                  // LUN 0
            put(cdb.size.toByte())
            put(cdb)
        }.array()

        if (conn.bulkTransfer(epOut, cbw, cbw.size, TIMEOUT_MS) != CBW_LENGTH) {
            line("  ! CBW transfer failed (cdb 0x%02x)".format(cdb[0]))
            return false
        }

        var sent = 0
        while (sent < data.size) {
            val want = minOf(MAX_BULK_BYTES, data.size - sent)
            val tmp = data.copyOfRange(sent, sent + want)
            val n = conn.bulkTransfer(epOut, tmp, want, TIMEOUT_MS)
            if (n <= 0) {
                line("  ! data-out returned $n after $sent/${data.size} bytes (cdb 0x%02x)".format(cdb[0]))
                return false
            }
            sent += n
        }

        val csw = ByteArray(CSW_LENGTH)
        if (conn.bulkTransfer(epIn, csw, csw.size, TIMEOUT_MS) != CSW_LENGTH) {
            line("  ! CSW transfer failed (cdb 0x%02x)".format(cdb[0]))
            return false
        }
        val cb = ByteBuffer.wrap(csw).order(ByteOrder.LITTLE_ENDIAN)
        val sig = cb.int
        val rtag = cb.int
        cb.int // residue
        val status = cb.get().toInt()
        if (sig != CSW_SIGNATURE || rtag != myTag || status != 0) {
            line("  ! CSW bad: sig=%08x tag=%d/%d status=%d (cdb 0x%02x)".format(sig, rtag, myTag, status, cdb[0]))
            return false
        }
        return true
    }

    private fun hexdump(b: ByteArray, count: Int): String {
        val sb = StringBuilder()
        var i = 0
        while (i < count && i < b.size) {
            sb.append("  %04x  ".format(i))
            for (j in 0 until 16) {
                sb.append(if (i + j < b.size) "%02x ".format(b[i + j]) else "   ")
            }
            sb.append(' ')
            for (j in 0 until 16) {
                if (i + j >= b.size) break
                val c = b[i + j].toInt() and 0xFF
                sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
            }
            sb.append('\n')
            i += 16
        }
        return sb.toString().trimEnd('\n')
    }

    /** Shannon entropy in bits per byte - tells a filesystem apart from ciphertext. */
    private fun shannonBits(b: ByteArray): Double {
        val counts = IntArray(256)
        for (x in b) counts[x.toInt() and 0xFF]++
        var h = 0.0
        for (c in counts) {
            if (c == 0) continue
            val p = c.toDouble() / b.size
            h -= p * (Math.log(p) / Math.log(2.0))
        }
        return h
    }
}
