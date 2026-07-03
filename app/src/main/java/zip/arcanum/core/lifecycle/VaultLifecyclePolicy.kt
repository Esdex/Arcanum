package zip.arcanum.core.lifecycle

object VaultLifecyclePolicy {
    fun shouldIgnoreRouteStop(): Boolean = true

    fun shouldKeepMountedForTrustedOperation(
        isLocked: Boolean,
        trustedOperationActive: Boolean
    ): Boolean = !isLocked && trustedOperationActive

    fun shouldUnmountOnProcessStop(
        isLocked: Boolean,
        trustedOperationActive: Boolean,
        unmountOnBackground: Boolean,
        unmountOnLock: Boolean
    ): Boolean = when {
        isLocked -> unmountOnLock
        trustedOperationActive -> false
        else -> unmountOnBackground
    }
}
