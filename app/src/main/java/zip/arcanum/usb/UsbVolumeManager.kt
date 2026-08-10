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

    /**
     * Mounts [device] as a whole-device VeraCrypt volume. Takes ownership of the
     * transport: on success it is held until unmount or detach, and on failure it is
     * closed here, so a caller never has to work out which case it is in.
     */
    suspend fun mount(
        device: UsbBlockDevice,
        password: String,
        readOnly: Boolean,
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
