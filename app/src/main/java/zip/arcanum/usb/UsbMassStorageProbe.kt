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
import android.hardware.usb.UsbDevice
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
 * Read-only feasibility probe for issue #95, and now the test harness for
 * [UsbBlockDevice]: every SCSI command below is issued through the real transport, so
 * running this exercises the code the native backend will use rather than a parallel
 * copy of it. The checks are the ones that already passed on hardware, which is what
 * makes them worth keeping - they are a regression test for the transport.
 *
 * [run] is read-only. [runWriteTest] modifies one sector and restores it, and lives
 * behind its own confirmation in the debug screen.
 */
class UsbMassStorageProbe(
    private val context: Context,
    private val engine: zip.arcanum.crypto.VeraCryptEngine? = null
) {

    companion object {
        private const val ACTION_USB_PERMISSION = "zip.arcanum.USB_PERMISSION"

        /** Bytes read at each transfer size in the throughput sweep. */
        private const val THROUGHPUT_BYTES = 4 * 1024 * 1024
    }

    private val out = StringBuilder()

    private fun line(s: String = "") {
        out.append(s).append('\n')
    }

    /** Read-only. Issues INQUIRY, READ CAPACITY and READ only. */
    suspend fun run(): String = withTarget("read-only probe", readOnly = true) { dev ->
        probe(dev)
    }

    /**
     * DESTRUCTIVE. Writes a pattern to one sector, reads it back, then restores the
     * original bytes. A separate entry point from [run] so the read-only button cannot
     * reach a write command; the transport is also opened read-only for [run], so even
     * a coding mistake there would be refused inside [UsbBlockDevice].
     */
    suspend fun runWriteTest(): String = withTarget("WRITE TEST - modifies the drive", readOnly = false) { dev ->
        writeTest(dev)
    }

    /**
     * Mounts the connected device as a whole-device VeraCrypt volume and lists its root.
     *
     * Read-only on both halves - the transport is opened read-only and the mount is
     * requested read-only - so a wrong guess about the layout cannot write anything. This
     * is the first end-to-end use of the USB backend: header read, key derivation,
     * alloc_drive, filesystem probe and a directory listing all run over SCSI.
     */
    suspend fun runMountTest(password: String): String =
        withTarget("MOUNT TEST - read-only", readOnly = true) { dev ->
            mountTest(dev, password)
        }

    private suspend fun mountTest(dev: UsbBlockDevice, password: String) {
        val eng = engine
        if (eng == null) {
            line("STOP: no VeraCryptEngine was supplied to the probe.")
            return
        }
        if (password.isEmpty()) {
            line("STOP: no password given.")
            return
        }

        val sector0 = ByteArray(dev.blockSize)
        if (dev.read(0, dev.blockSize, sector0)) {
            // ~7.64 is the ceiling here, not 8.00: with 512 samples over 256 symbols the
            // counts cannot spread evenly, so even a perfect random source measures about
            // log2(256) - 255/(2*512*ln2). Expecting 8.00 would make correct data look
            // suspicious. Below ~7 means this is not ciphertext.
            line("[sector 0] entropy ${"%.2f".format(shannonBits(sector0))} bits/byte " +
                "(a VeraCrypt volume opens with its salt; ~7.6 is what random data scores " +
                "over 512 bytes, well under 7 means it is not encrypted)")
        }

        line("[mount] trying ${dev.sizeBytes / (1024 * 1024)} MB as a whole-device volume...")
        val res = eng.mountContainerUsb(
            transport = dev,
            deviceSize = dev.sizeBytes,
            password = password,
            readOnly = true
        )
        val handle = when (res) {
            is zip.arcanum.crypto.CryptoResult.Success -> res.value
            is zip.arcanum.crypto.CryptoResult.Failure -> {
                line("[mount] FAILED: ${res.error}")
                line("A wrong password and a volume that is not VeraCrypt look the same here:")
                line("both mean no header decrypted. Check the entropy line above.")
                return
            }
        }
        line("[mount] OK, handle=$handle")

        try {
            val entries = eng.listFiles(handle, "/")
            line("[listing] ${entries.size} entries at the root")
            entries.take(20).forEach {
                line("  %-40s %s".format(it.name, if (it.isDirectory) "<dir>" else "${it.size} bytes"))
            }
            if (entries.size > 20) line("  ... and ${entries.size - 20} more")
            line()
            line("VERDICT: a whole-device VeraCrypt volume on USB mounts and reads.")
        } finally {
            val rc = eng.closeContainer(handle)
            line("[unmount] closeContainer returned $rc")
        }
    }

    private suspend fun withTarget(
        what: String,
        readOnly: Boolean,
        body: suspend (UsbBlockDevice) -> Unit
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

        val device = devices.firstOrNull { UsbBlockDevice.massStorageInterface(it) != null }
        if (device == null) {
            line()
            line("STOP: no mass-storage / SCSI / Bulk-Only interface among the attached devices.")
            return@withContext out.toString()
        }
        line()
        line("[target] ${device.deviceName}")

        if (!requestPermission(manager, device)) {
            line("STOP: permission for the device was not granted.")
            return@withContext out.toString()
        }
        line("[permission] granted")

        val dev = try {
            UsbBlockDevice.open(manager, device, readOnly)
        } catch (e: Exception) {
            line("STOP: ${e.message}")
            return@withContext out.toString()
        }
        line("[open] claimed, blockSize=${dev.blockSize} blocks=${dev.blockCount} " +
            "(${dev.sizeBytes / (1024 * 1024)} MB)${if (readOnly) ", read-only" else ""}")

        try {
            body(dev)
        } catch (t: Throwable) {
            line("EXCEPTION: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            dev.close()
            line()
            line("[cleanup] interface released, connection closed")
            line("The drive is gone from Android's file manager until you unplug and replug it:")
            line("claiming the interface detached the kernel driver holding its mount.")
        }

        out.toString()
    }

    private fun probe(dev: UsbBlockDevice) {
        line("[inquiry] ${dev.inquiry() ?: "FAILED"}")

        val sector0 = ByteArray(dev.blockSize)
        if (!dev.read(0, dev.blockSize, sector0)) {
            line("STOP: reading sector 0 failed.")
            return
        }
        line("[sector 0] first 64 bytes:")
        line(hexdump(sector0, 64))
        val bootSig = (sector0[510].toInt() and 0xFF) == 0x55 && (sector0[511].toInt() and 0xFF) == 0xAA
        line("  0x55AA boot signature: $bootSig")
        line("  entropy of the sector (${sector0.size} bytes): ${"%.2f".format(shannonBits(sector0))} bits/byte " +
            "(near 8.00 means encrypted or random, low means a partition table or filesystem)")
        if (bootSig) describePartitionTable(sector0)

        // The last sector is the only read whose LBA uses the full 32-bit width, which
        // is where an addressing mistake would surface.
        val lastOffset = dev.sizeBytes - dev.blockSize
        val lastSector = ByteArray(dev.blockSize)
        val lastOk = dev.read(lastOffset, dev.blockSize, lastSector)
        line("[last sector] LBA ${dev.blockCount - 1} read: $lastOk" +
            if (lastOk) " (entropy ${"%.2f".format(shannonBits(lastSector))} bits/byte)" else "")

        throughputSweep(dev)
        nativeBackendCheck(dev)

        line()
        line("VERDICT: raw block access over BOT/SCSI works through UsbBlockDevice.")
        line("No write command was issued; the transport was opened read-only.")
    }

    /**
     * Reads at several transfer sizes to separate our per-command overhead from the
     * drive's own ceiling. Each size reads a fresh region so the drive's read-ahead
     * cache cannot flatter a later run with data an earlier one already pulled.
     */
    private fun throughputSweep(dev: UsbBlockDevice) {
        line("[throughput sweep] sequential, no crypto, fresh region per size")
        var region = 0L
        for (kb in listOf(8, 32, 128, 512)) {
            val chunk = kb * 1024
            val rounds = (THROUGHPUT_BYTES / chunk).coerceAtLeast(1)
            val span = rounds.toLong() * chunk
            if (region + span > dev.sizeBytes) {
                line("  %4d KB: not enough room left on the device, skipped".format(kb))
                continue
            }
            val buf = ByteArray(chunk)
            var read = 0L
            var failed = false
            val ms = measureTimeMillis {
                for (c in 0 until rounds) {
                    if (!dev.read(region + c.toLong() * chunk, chunk, buf)) {
                        failed = true
                        break
                    }
                    read += chunk
                }
            }
            region += span
            when {
                failed -> line("  %4d KB: FAILED after %d KB".format(kb, read / 1024))
                ms <= 0 -> line("  %4d KB: %d KB too fast to time".format(kb, read / 1024))
                else -> line("  %4d KB: %5.1f MB/s (%d KB in %d ms)".format(
                    kb, (read / 1024.0 / 1024.0) / (ms / 1000.0), read / 1024, ms
                ))
            }
        }
    }

    /**
     * Reads spans through the native BlockBackend and compares them byte for byte with
     * the same spans read directly through the transport.
     *
     * This is the only check that the JNI path carries data faithfully. "The native read
     * returned successfully" proves nothing on its own: a wrong offset, a mis-sized
     * scratch array or a chunking loop that drops a piece all return perfectly valid
     * bytes, just not the requested ones. Only the comparison catches that.
     *
     * The spans are chosen to exercise the parts that differ: one sector (single chunk),
     * one span larger than the 512 KB scratch array (forces the loop to go round more
     * than once), and one at a high offset (full-width LBA).
     */
    private fun nativeBackendCheck(dev: UsbBlockDevice) {
        line("[native backend] reading through the JNI BlockBackend and comparing")
        val cases = listOf(
            "one sector" to (0L to dev.blockSize),
            "64 KB" to (1024L * dev.blockSize to 64 * 1024),
            "768 KB (crosses the 512 KB scratch)" to (2048L * dev.blockSize to 768 * 1024),
            "high offset" to ((dev.blockCount - 64) * dev.blockSize to 32 * 1024)
        )
        for ((name, span) in cases) {
            val (offset, length) = span
            if (offset < 0 || offset + length > dev.sizeBytes) {
                line("  %-38s skipped, past the end".format(name))
                continue
            }
            val direct = ByteArray(length)
            if (!dev.read(offset, length, direct)) {
                line("  %-38s FAILED reading directly".format(name))
                continue
            }
            val viaNative = dev.readThroughNativeBackend(offset, length)
            when {
                viaNative == null ->
                    line("  %-38s native backend unavailable (release build?)".format(name))
                viaNative.size != length ->
                    line("  %-38s MISMATCH: got ${viaNative.size} bytes, wanted $length".format(name))
                !viaNative.contentEquals(direct) -> {
                    val at = viaNative.indices.first { viaNative[it] != direct[it] }
                    line("  %-38s MISMATCH at byte $at".format(name))
                }
                else -> line("  %-38s identical ($length bytes)".format(name))
            }
        }
    }

    /**
     * Target is a sector in the alignment gap between the MBR and the first partition -
     * a megabyte no filesystem uses. The test is read, write pattern, read back, restore,
     * and it checks the two neighbouring sectors are untouched. That neighbour check is
     * the point: a write landing one sector off still passes a naive read-back, because
     * the read is aimed the same wrong way. Only an independent witness catches it.
     */
    private fun writeTest(dev: UsbBlockDevice) {
        val bs = dev.blockSize
        val sector0 = ByteArray(bs)
        if (!dev.read(0, bs, sector0)) {
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
            line("Refusing to write: without the gap there is no sector safe by construction.")
            return
        }
        val target = firstPartLba / 2
        val at = target * bs
        line("[target] LBA $target, inside the unused gap between the MBR and LBA $firstPartLba")

        val before = ByteArray(bs)
        val neighLow = ByteArray(bs)
        val neighHigh = ByteArray(bs)
        if (!dev.read(at, bs, before) ||
            !dev.read(at - bs, bs, neighLow) ||
            !dev.read(at + bs, bs, neighHigh)
        ) {
            line("STOP: could not read the target and its neighbours first.")
            return
        }
        line("[before] target entropy ${"%.2f".format(shannonBits(before))} bits/byte")

        // The pattern carries the LBA it was meant for, so a misdirected write is legible
        // in a hex dump rather than just "the bytes differ".
        val pattern = ByteArray(bs)
        val stamp = "ARCANUM-PROBE-LBA-$target-".toByteArray()
        for (i in pattern.indices) pattern[i] = stamp[i % stamp.size]

        if (!dev.write(at, bs, pattern)) {
            line("STOP: WRITE(10) failed. The drive is unchanged.")
            return
        }
        line("[write] WRITE(10) reported success")

        val readback = ByteArray(bs)
        if (!dev.read(at, bs, readback)) {
            line("DANGER: wrote but could not read back. The sector may hold the pattern.")
            return
        }
        val matches = readback.contentEquals(pattern)
        line("[verify] read-back matches the pattern: $matches")

        val now = ByteArray(bs)
        val lowOk = dev.read(at - bs, bs, now) && now.contentEquals(neighLow)
        val highOk = dev.read(at + bs, bs, now) && now.contentEquals(neighHigh)
        line("[neighbours] LBA ${target - 1} unchanged: $lowOk | LBA ${target + 1} unchanged: $highOk")

        if (!dev.write(at, bs, before)) {
            line("DANGER: could not restore LBA $target. It still holds the probe pattern.")
            return
        }
        val restored = ByteArray(bs)
        val restoreOk = dev.read(at, bs, restored) && restored.contentEquals(before)
        line("[restore] original bytes back in place: $restoreOk")
        line("[sync] SYNCHRONIZE CACHE: ${dev.sync()}${dev.lastError?.let { " - $it" } ?: ""}")

        line()
        if (matches && lowOk && highOk && restoreOk) {
            line("VERDICT: WRITE(10) works and lands exactly where addressed.")
            line("The drive is byte-for-byte as it was before this test.")
        } else {
            line("VERDICT: something is off above - do not build on this until it is explained.")
        }
    }

    /**
     * Whether Android has the drive mounted itself. If it does, we are about to take the
     * interface away from a stack that believes it owns the device.
     */
    private fun reportSystemVolumes() {
        line()
        line("[android storage stack]")
        val sm = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
        if (sm == null) {
            line("  StorageManager unavailable")
            return
        }
        val removable = runCatching { sm.storageVolumes }.getOrNull().orEmpty().filter { it.isRemovable }
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
            val mass = f == UsbBlockDevice.massStorageInterface(d)
            line("    iface #${f.id} class=${f.interfaceClass} sub=${f.interfaceSubclass} " +
                "proto=${f.interfaceProtocol} endpoints=${f.endpointCount}${if (mass) "  <- mass storage BOT" else ""}")
        }
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
