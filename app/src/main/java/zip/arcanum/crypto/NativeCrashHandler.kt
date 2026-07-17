package zip.arcanum.crypto

/**
 * Installs native signal handlers (SIGSEGV/SIGABRT/…) that write a crash report to a file so a
 * native crash can be captured and shared from inside the app - the only option on Android 10,
 * where [android.app.ApplicationExitInfo] (API 30+) isn't available and users may have no PC or
 * root. See issue #92.
 *
 * The handler restores the previous disposition and re-raises, so the system's own crash path
 * (tombstone, process death) still runs afterwards - nothing is swallowed.
 */
object NativeCrashHandler {

    @Volatile private var installed = false

    init {
        // Loading may fail on a device/ABI where the library is absent; crash capture is then
        // simply unavailable and the app still runs.
        runCatching { System.loadLibrary("arcanum-native") }
    }

    /**
     * Installs the handlers once. [crashFilePath] is the absolute file the native report is
     * written to (overwritten on each crash); [meta] is a short version/abi/device line embedded
     * at the top of the report. No-op if already installed or the native library is missing.
     */
    fun install(crashFilePath: String, meta: String) {
        if (installed) return
        runCatching {
            nativeInstall(crashFilePath, meta)
            installed = true
        }
    }

    private external fun nativeInstall(crashFilePath: String, meta: String)
}
