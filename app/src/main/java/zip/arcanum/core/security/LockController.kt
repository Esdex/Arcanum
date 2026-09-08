package zip.arcanum.core.security

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.containers.data.VaultCloser
import zip.arcanum.arcanum.containers.service.KeepAliveService
import javax.inject.Inject
import javax.inject.Singleton

// 0=Immediately(1.5s grace) 1=30s 2=1m 3=2m 4=5m 5=10m 6=30m 7=1h
// Index 0 is a background-only "lock the moment you leave" grace period; indices >= 1 are
// inactivity windows enforced by the idle clock in LockController (foreground and background).
fun autoLockDelayMillis(index: Int): Long = when (index) {
    0    -> 1_500L
    1    -> 30_000L
    2    -> 60_000L
    3    -> 120_000L
    4    -> 300_000L
    5    -> 600_000L
    6    -> 1_800_000L
    7    -> 3_600_000L
    else -> 1_500L
}

/**
 * Everything that closes a vault or puts the lock screen up, in one process-wide owner (#102).
 *
 * It used to be spread across the UI: the idle clock was a `LaunchedEffect` inside the
 * navigation composable, the "lock the moment you leave" grace was a lifecycle observer next
 * to it, and the per-vault background and screen-off rules lived in `VaultViewModel`, a
 * ViewModel. All three die with the Activity - which was safe only by accident, because
 * until now the process died with it too and took the mount along.
 *
 * [KeepAliveService] removes that accident on purpose: the process outlives the Activity, so
 * a vault could stay mounted with nothing left to close it. So the rules move down here,
 * where they answer to the process rather than to a composition, and the UI is left with the
 * one thing that is genuinely its own - navigating to the lock screen, which it does by
 * watching [SessionState.lockedFlow] rather than by being told.
 *
 * What did NOT change is the arithmetic of the rules themselves: the same windows, the same
 * three-second settle after a mount, the same flush-before-anything, and the same refusal to
 * cut an operation short ([IdleMonitor.isBusy]).
 */
@Singleton
class LockController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: AppPreferences,
    private val idle: IdleMonitor,
    private val session: SessionState,
    private val repo: ContainerRepository,
    /* Lazy: this singleton is built in Application.onCreate, and VaultCloser reaches the
     * engine, whose class load pulls in libarcanum-native. Nothing about starting the app
     * needs the native library, and a service start or a WorkManager job should not load it
     * either. It materialises the first time a vault is actually closed. */
    private val closer: Lazy<VaultCloser>
) {

    private companion object {
        /** ProcessLifecycleOwner reports ON_STOP during the mount animation; see [ContainerRepository.lastMountAtMs]. */
        const val MOUNT_SETTLE_MS = 3_000L
        /** How often the idle clock re-checks, so a late interaction is picked up promptly. */
        const val IDLE_POLL_MAX_MS = 20_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var started = false
    private var idleJob: Job? = null
    private var graceJob: Job? = null
    private var pendingUnmountJob: Job? = null

    /** Called once, from [zip.arcanum.ArcanumApp.onCreate]. */
    fun start() {
        if (started) return
        started = true
        context.registerReceiver(
            screenStateReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF).apply { addAction(Intent.ACTION_SCREEN_ON) }
        )
        ProcessLifecycleOwner.get().lifecycle.addObserver(appLifecycleObserver)
        scope.launch { runIdleClock() }
        scope.launch { runKeepAlive() }
    }

    // ── Locking ────────────────────────────────────────────────────────────

    /**
     * Puts the session behind the PIN again, and unmounts if the user asked for that.
     *
     * The UI is not called: it watches [SessionState.lockedFlow] and leaves the authenticated
     * area on its own, which is what makes this safe to call from a broadcast receiver, a
     * lifecycle callback or a coroutine with no Activity in sight. Marking the session locked
     * comes first so a second caller finds the work already done, and the screen is gone
     * before a volume takes its time closing.
     *
     * The unmount is deliberately launched on this class's own scope rather than run inline.
     * Marking the session locked is what restarts the idle clock's collector, and that
     * collector cancels the job the lock may have been called from - so an inline unmount
     * could be cancelled by the very state change that ordered it.
     */
    fun lockNow() {
        if (session.isLocked) return
        session.markLocked()
        scope.launch {
            if (prefs.unmountOnAutoLock.first()) closer.get().closeAll()
        }
    }

    /**
     * The inactivity window (delay index >= 1).
     *
     * Restarted whenever the settings or the lock state change, and it runs while the session
     * is unlocked - in the foreground and in the background alike, because a vault left
     * mounted on a phone in a pocket should age out exactly as one left on a table does.
     */
    private suspend fun runIdleClock() {
        combine(
            prefs.autoLockEnabled,
            prefs.autoLockDelayIndex,
            session.lockedFlow
        ) { enabled, index, locked -> Triple(enabled, index, locked) }
            .distinctUntilChanged()
            .collect { (enabled, index, locked) ->
                idleJob?.cancel()
                if (!enabled || index == 0 || locked) return@collect
                // Fresh baseline for the unlock we just entered, or for a window the user
                // has only now changed.
                idle.recordInteraction()
                val windowMs = autoLockDelayMillis(index)
                idleJob = scope.launch {
                    while (currentCoroutineContext().isActive) {
                        // idleMillis() is zero while the app is working for the user, so a
                        // mount, a create, a header restore or a long import holds the window
                        // open instead of being mistaken for a phone on a table.
                        val remaining = windowMs - idle.idleMillis()
                        if (remaining <= 0L) {
                            lockNow()
                            break
                        }
                        delay(remaining.coerceIn(500L, IDLE_POLL_MAX_MS))
                    }
                }
            }
    }

    /**
     * "Immediately" (index 0): lock shortly after the app leaves the foreground.
     *
     * The grace exists because leaving the app is not always leaving: the system file picker
     * is a trip to the background, and so is the split second between screens. Leaving in the
     * middle of a long operation must not tear it down either, so the lock waits the work out
     * - the screen behind it is already gone in any case.
     */
    private fun armImmediateLock() {
        graceJob?.cancel()
        graceJob = scope.launch {
            if (session.isLocked) return@launch
            if (!prefs.autoLockEnabled.first() || prefs.autoLockDelayIndex.first() != 0) return@launch
            delay(autoLockDelayMillis(0))
            while (idle.isBusy) delay(1_000L)
            lockNow()
        }
    }

    /**
     * Coming back: if the window already elapsed while the app was away, lock before the
     * resume touch can reset the clock. Doze defers the idle clock's wake-ups in the
     * background, so this is what makes a long background actually lock.
     */
    private fun catchUpOnReturn() {
        scope.launch {
            if (session.isLocked) return@launch
            if (!prefs.autoLockEnabled.first()) return@launch
            val index = prefs.autoLockDelayIndex.first()
            if (index < 1) return@launch
            if (idle.idleMillis() >= autoLockDelayMillis(index)) lockNow()
        }
    }

    // ── Unmounting on the way out ──────────────────────────────────────────

    /**
     * The per-vault rules: "unmount when I leave the app" and "unmount when the screen goes
     * off". Only the vaults that asked are closed; everything else stays open, which is the
     * whole point of the keep-alive.
     */
    private fun scheduleAutoUnmount(screenLocked: Boolean) {
        // ON_STOP fires during the mount animation and the navigation after it, and a vault
        // unmounted a moment after it opened reads as a mount that failed. Screen-off is not
        // affected: that one is deliberate.
        if (!screenLocked && SystemClock.elapsedRealtime() - repo.lastMountAtMs < MOUNT_SETTLE_MS) return
        if (repo.mountedContainerIds.value.isEmpty()) return
        pendingUnmountJob?.cancel()
        pendingUnmountJob = scope.launch {
            // Flush first, before any waiting and whatever the settings say below. The USB
            // backend holds writes back to merge them, and Android kills backgrounded apps
            // without warning - those bytes exist nowhere else.
            closer.get().flushAll()
            // Only an explicit Unmount or a panic PIN may cut an operation short. Leaving the
            // app must not: an import, a paste, a delete or a move would be torn off
            // mid-write, and opening the system file picker is itself a trip to the
            // background. If the user comes back, or the screen does, this job is cancelled.
            while (idle.isBusy) delay(1_000L)
            closer.get().closeAsPolicy(screenLocked)
        }
    }

    private val appLifecycleObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) {
            scheduleAutoUnmount(screenLocked = false)
            armImmediateLock()
        }

        /* Back in the foreground: whatever the background unmount was waiting to do, it is no
         * longer what the user asked for. */
        override fun onStart(owner: LifecycleOwner) {
            pendingUnmountJob?.cancel()
            graceJob?.cancel()
            catchUpOnReturn()
        }
    }

    /* SCREEN_ON matters as much as SCREEN_OFF: a screen-off unmount waits for any operation
     * to finish, and if the screen comes back before it does, the reason for unmounting is
     * gone. */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> scheduleAutoUnmount(screenLocked = true)
                Intent.ACTION_SCREEN_ON  -> pendingUnmountJob?.cancel()
            }
        }
    }

    // ── Keeping the process alive ──────────────────────────────────────────

    /**
     * The service exists exactly while the user asked for it AND a vault is open, and it is
     * restarted when the disguise changes so its notification always matches what the app is
     * pretending to be.
     *
     * Nothing here decides to keep a vault open: the rules above have already had their say
     * by the time this sees an empty set of mounted vaults.
     */
    private suspend fun runKeepAlive() {
        combine(
            prefs.keepVaultsMounted,
            repo.mountedContainerIds,
            prefs.calculatorEnabled
        ) { wanted, mounted, disguised ->
            if (wanted && mounted.isNotEmpty()) disguised == true else null
        }
            .distinctUntilChanged()
            .collect { disguised ->
                if (disguised == null) stopKeepAlive() else startKeepAlive(disguised)
            }
    }

    private fun startKeepAlive(disguised: Boolean) {
        val intent = Intent(context, KeepAliveService::class.java)
            .putExtra(KeepAliveService.EXTRA_DISGUISED, disguised)
        // A foreground service may only be started from the foreground, and a vault is
        // always mounted from the foreground - but a refusal here must never take the mount
        // down with it, so it is logged and the vault simply keeps the old behaviour.
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { Timber.w(it, "keep-alive service refused to start") }
    }

    private fun stopKeepAlive() {
        runCatching { context.stopService(Intent(context, KeepAliveService::class.java)) }
    }
}
