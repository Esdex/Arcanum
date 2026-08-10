/*
 * Copyright (c) 2026 Esdex
 * SPDX-License-Identifier: Apache-2.0
 */
package zip.arcanum.usb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import zip.arcanum.crypto.CryptoResult
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the one USB-hosted volume that can be mounted at a time, and takes it down
 * safely when the drive is pulled (issue #95).
 *
 * A yanked flash drive is the normal way this feature ends, not an edge case, so the
 * teardown is the design rather than an afterthought. Two things have to be true when it
 * happens: nothing may hang, and nothing may keep believing the volume is mounted.
 *
 * Hanging is prevented in [UsbBlockDevice], which marks itself dead the moment a command
 * cannot be handed to the endpoint - otherwise every remaining transfer would wait out
 * its full timeout, and a filesystem being torn down issues plenty. By the time this
 * class reacts to the broadcast, the transport already refuses instantly.
 *
 * What cannot be salvaged is anything not yet written. The drive is gone; there is no
 * flush to attempt and no error worth reporting to the filesystem. Saying so plainly to
 * the user is the only honest response, which is what [Event.Detached] is for.
 */
@Singleton
class UsbVolumeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: VeraCryptEngine
) {

    data class MountedVolume(
        val handle: Long,
        val deviceName: String,
        val label: String,
        val sizeBytes: Long,
        val readOnly: Boolean
    )

    sealed interface Event {
        /** The drive was unplugged while mounted; the volume is gone, writes may be lost. */
        data class Detached(val label: String) : Event
        /** Ordinary unmount finished. */
        object Unmounted : Event
    }

    private companion object {
        const val ACTION_USB_PERMISSION = "zip.arcanum.USB_PERMISSION"
    }

    private val _mounted = MutableStateFlow<MountedVolume?>(null)
    val mounted = _mounted.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events = _events.asSharedFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Guards the mount/unmount/detach sequence; all three touch the same two handles. */
    private val lock = Mutex()

    private var transport: UsbBlockDevice? = null

    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            if (intent?.action != UsbManager.ACTION_USB_DEVICE_DETACHED) return
            @Suppress("DEPRECATION")
            val gone: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val held = transport ?: return
            if (gone == null || gone.deviceName != held.device.deviceName) return

            // Do this here, not in the coroutine: it makes every in-flight transfer fail
            // immediately, so whatever is mid-operation stops waiting on hardware that
            // has left rather than blocking until its timeout expires.
            held.markDetached()
            scope.launch { teardown(detached = true) }
        }
    }

    init {
        ContextCompat.registerReceiver(
            context, detachReceiver,
            IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** What a drive says about itself once claimed, enough to remember it by. */
    data class DriveIdentity(
        val saltHash: String,
        val label: String,
        val sizeBytes: Long
    )

    /**
     * The three states the UI has to tell apart when reaching for a remembered vault.
     * Collapsing the last two would leave a user pressing "try again" at a drive that is
     * plugged in and will never match.
     */
    sealed interface OpenResult {
        data class Ok(val device: UsbBlockDevice) : OpenResult
        /** Nothing is plugged in, or nothing that is a mass-storage device. */
        object NoDrive : OpenResult
        /** A drive is here, but it does not hold the volume this vault was saved from. */
        data class WrongVolume(val found: DriveIdentity) : OpenResult
        data class Failed(val reason: String) : OpenResult
    }

    /**
     * Asks the system for permission to talk to the attached drive, if it is not already
     * granted, and waits for the answer.
     *
     * The result arrives as a broadcast from the system server on behalf of our
     * PendingIntent - from outside this process - so the receiver must be registered
     * EXPORTED or it never lands. hasPermission is the authority and is polled as well,
     * which also makes the exported receiver harmless: a forged broadcast proves nothing
     * because the answer is taken from the platform.
     */
    suspend fun ensurePermission(): Boolean = withContext(Dispatchers.IO) {
        val device = attachedDrive() ?: return@withContext false
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (manager.hasPermission(device)) return@withContext true

        val answered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == ACTION_USB_PERMISSION) answered.complete(Unit)
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_USB_PERMISSION),
            ContextCompat.RECEIVER_EXPORTED
        )
        try {
            val pi = android.app.PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_PERMISSION).setPackage(context.packageName),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
            )
            manager.requestPermission(device, pi)
            kotlinx.coroutines.withTimeoutOrNull(60_000) {
                while (!manager.hasPermission(device) && !answered.isCompleted) {
                    kotlinx.coroutines.delay(200)
                }
            }
            manager.hasPermission(device)
        } finally {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /** Passive: no claim, no permission prompt, nothing disturbed. */
    fun attachedDrive(): UsbDevice? {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList.values.firstOrNull {
            UsbBlockDevice.massStorageInterface(it) != null
        }
    }

    /**
     * Claims the attached drive long enough to learn what volume is on it, then lets it
     * go. Used when adding a vault, where there is nothing yet to compare against.
     *
     * Not passive: claiming takes the drive away from Android's own mount, so it leaves
     * the system file manager and only returns after a replug. That is unavoidable -
     * reading offset 0 requires the interface - but it happens at a moment the user may
     * not expect, so the UI should say so.
     */
    suspend fun identifyAttachedDrive(): Result<DriveIdentity> = withContext(Dispatchers.IO) {
        val device = attachedDrive() ?: return@withContext Result.failure(NoDriveException())
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!manager.hasPermission(device)) {
            return@withContext Result.failure(IllegalStateException("no USB permission"))
        }
        runCatching {
            UsbBlockDevice.open(manager, device, readOnly = true).use { dev ->
                val hash = dev.volumeFingerprint()
                    ?: throw java.io.IOException("could not read the volume header")
                DriveIdentity(
                    saltHash = hash,
                    label = dev.inquiry() ?: device.deviceName,
                    sizeBytes = dev.sizeBytes
                )
            }
        }
    }

    /**
     * Opens the attached drive only if it carries the volume identified by [saltHash].
     * On any outcome but [OpenResult.Ok] nothing is left claimed.
     */
    suspend fun openMatching(saltHash: String, readOnly: Boolean): OpenResult =
        withContext(Dispatchers.IO) {
            val device = attachedDrive() ?: return@withContext OpenResult.NoDrive
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            if (!manager.hasPermission(device)) {
                return@withContext OpenResult.Failed("no USB permission")
            }
            val dev = try {
                UsbBlockDevice.open(manager, device, readOnly)
            } catch (e: Exception) {
                return@withContext OpenResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            val hash = dev.volumeFingerprint()
            if (hash == null) {
                dev.close()
                return@withContext OpenResult.Failed("could not read the volume header")
            }
            if (hash != saltHash) {
                val found = DriveIdentity(hash, dev.inquiry() ?: device.deviceName, dev.sizeBytes)
                dev.close()
                return@withContext OpenResult.WrongVolume(found)
            }
            OpenResult.Ok(dev)
        }

    class NoDriveException : Exception("no USB mass-storage device attached")

    /** The outcome of an operation run against a remembered volume. */
    sealed interface VolumeOp<out T> {
        /**
         * [newSaltHash] is the volume's fingerprint re-read after the operation, present
         * only when it was requested. Any operation that rewrites a header changes the
         * salt, so the caller must store this or the vault will no longer recognise its
         * own drive.
         */
        data class Done<T>(val value: T, val newSaltHash: String? = null) : VolumeOp<T>
        object NoDrive : VolumeOp<Nothing>
        data class WrongVolume(val found: DriveIdentity) : VolumeOp<Nothing>
        data class Failed(val reason: String) : VolumeOp<Nothing>
    }

    /**
     * Runs [block] against the drive holding [saltHash], and closes the transport
     * afterwards whatever happens.
     *
     * For the operations that work on an UNMOUNTED volume - changing a password or
     * keyfile, backing up or restoring a header. Mounting deliberately does not use this:
     * it needs the transport to outlive the call, so it hands ownership to this class
     * instead.
     *
     * Refuses while a volume is mounted. Claiming the same interface twice would have two
     * owners issuing commands to one device, and rewriting the header of a volume that is
     * currently mounted is not something to allow by accident.
     */
    suspend fun <T> withMatchingVolume(
        saltHash: String,
        readOnly: Boolean,
        refingerprint: Boolean = false,
        block: suspend (UsbBlockDevice) -> T
    ): VolumeOp<T> {
        if (_mounted.value != null) {
            return VolumeOp.Failed("unmount the vault before changing it")
        }
        return when (val opened = openMatching(saltHash, readOnly)) {
            is OpenResult.Ok -> {
                try {
                    val value = block(opened.device)
                    // Read while the drive is still open - afterwards there is nothing
                    // left to ask, and the old fingerprint no longer matches anything.
                    val fresh = if (refingerprint) opened.device.volumeFingerprint() else null
                    VolumeOp.Done(value, fresh)
                } finally {
                    opened.device.close()
                }
            }
            OpenResult.NoDrive -> VolumeOp.NoDrive
            is OpenResult.WrongVolume -> VolumeOp.WrongVolume(opened.found)
            is OpenResult.Failed -> VolumeOp.Failed(opened.reason)
        }
    }

    /**
     * Mounts [device] as a whole-device VeraCrypt volume. Takes ownership of the
     * transport: on success it is held until unmount or detach, and on failure it is
     * closed here, so a caller never has to work out which case it is in.
     */
    suspend fun mount(
        device: UsbBlockDevice,
        password: String,
        readOnly: Boolean,
        keyfileData: List<ByteArray> = emptyList(),
        pim: Int = 0,
        algorithm: Int = VeraCryptEngine.ALGO_AUTO,
        hashAlgorithm: Int = VeraCryptEngine.HASH_AUTO,
        protectHiddenPassword: String? = null,
        protectHiddenKeyfileData: List<ByteArray> = emptyList(),
        protectHiddenPim: Int = 0,
        mountProgressListener: VeraCryptEngine.MountProgressListener? = null,
        label: String = device.inquiry() ?: device.device.deviceName
    ): CryptoResult<Long> = lock.withLock {
        if (transport != null) {
            // One USB volume at a time: a second claim would fight the first for the
            // same interface. Closing the caller's transport here keeps ownership simple.
            device.close()
            return CryptoResult.Failure(zip.arcanum.crypto.CryptoError.TOO_MANY_MOUNTED)
        }

        val result = engine.mountContainerUsb(
            transport = device,
            deviceSize = device.sizeBytes,
            password = password,
            keyfileData = keyfileData,
            pim = pim,
            algorithm = algorithm,
            hashAlgorithm = hashAlgorithm,
            protectHiddenPassword = protectHiddenPassword,
            protectHiddenKeyfileData = protectHiddenKeyfileData,
            protectHiddenPim = protectHiddenPim,
            mountProgressListener = mountProgressListener,
            readOnly = readOnly
        )
        when (result) {
            is CryptoResult.Success -> {
                transport = device
                _mounted.value = MountedVolume(
                    handle = result.value,
                    deviceName = device.device.deviceName,
                    label = label,
                    sizeBytes = device.sizeBytes,
                    readOnly = readOnly
                )
            }
            is CryptoResult.Failure -> device.close()
        }
        result
    }

    /** Ordinary unmount: flushes, closes the container, releases the interface. */
    suspend fun unmount(): Int = lock.withLock { teardown(detached = false) }

    /**
     * The single teardown path, so an unplug and a deliberate unmount cannot drift apart.
     *
     * The container is closed even when the drive is already gone. That close still has
     * work to do on our side - it frees the drive slot, drops the backend's references
     * and releases the cipher context - and none of it depends on the device answering.
     * Its return code is meaningless in the detached case, which is why it is logged as
     * part of the event rather than returned as a failure the user could act on.
     */
    private suspend fun teardown(detached: Boolean): Int = withContext(Dispatchers.IO) {
        val volume = _mounted.value
        val dev = transport
        transport = null
        _mounted.value = null
        if (volume == null) return@withContext 0

        val rc = runCatching { engine.closeContainer(volume.handle) }.getOrDefault(-1)
        runCatching { dev?.close() }

        _events.tryEmit(if (detached) Event.Detached(volume.label) else Event.Unmounted)
        rc
    }
}
