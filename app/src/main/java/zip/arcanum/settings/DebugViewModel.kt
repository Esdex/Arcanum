package zip.arcanum.settings

import android.app.AppOpsManager
import android.content.pm.PackageManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.provider.Settings as AndroidSettings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zip.arcanum.ArcanumApp
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.gallery.ThumbnailManager
import zip.arcanum.core.database.AppDatabase
import zip.arcanum.core.database.dao.ContainerDao
import zip.arcanum.core.security.PanicManager
import zip.arcanum.core.security.VaultPanicAction
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.inject.Inject

@HiltViewModel
class DebugViewModel @Inject constructor(
    private val repo: ContainerRepository,
    private val dao: ContainerDao,
    private val panicManager: PanicManager,
    private val thumbnailManager: ThumbnailManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    data class RuntimeInfo(
        val pid: Int,
        val uid: Int,
        val heapUsed: String,
        val heapMax: String,
        val nativeLib: Boolean
    )

    data class SecurityInfo(
        val keystore: String,
        val biometricStrong: String,
        val biometricWeak: String,
        val selinux: String,
        val rooted: Boolean,
        val bootloaderUnlocked: Boolean,
        val adbEnabled: Boolean,
        val devOptionsEnabled: Boolean,
        val overlayCapableApps: Int
    )

    data class DatabaseInfo(
        val version: Int,
        val total: Int,
        val mounted: Int
    )

    data class MountedContainer(
        val name: String,
        val handle: Long,
        val pim: Int
    )

    data class CrashLog(
        val name: String,
        val content: String
    )

    data class DebugState(
        val isLoading: Boolean = false,
        val runtime: RuntimeInfo? = null,
        val security: SecurityInfo? = null,
        val db: DatabaseInfo? = null,
        val mounted: List<MountedContainer> = emptyList(),
        val crashLogs: List<CrashLog> = emptyList(),
        val dryRunActions: List<String>? = null
    )

    private val _state = MutableStateFlow(DebugState())
    val state = _state.asStateFlow()

    private val _events = MutableSharedFlow<DebugEvent>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    sealed interface DebugEvent {
        object CacheCleared : DebugEvent
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            val runtime   = withContext(Dispatchers.Default) { buildRuntime() }
            val security  = withContext(Dispatchers.IO) { buildSecurity() }
            val crashLogs = withContext(Dispatchers.IO) { readCrashLogs() }

            val allContainers = repo.getAllContainersRaw().first()
            val mountedList = allContainers.filter { it.isMounted }.map { entity ->
                MountedContainer(
                    name   = entity.name,
                    handle = repo.getContainerHandle(entity.id) ?: -1L,
                    pim    = repo.getPimForContainer(entity.id)
                )
            }
            val dbInfo = DatabaseInfo(
                version = AppDatabase.VERSION,
                total   = allContainers.size,
                mounted = mountedList.size
            )

            _state.update { it.copy(
                isLoading = false,
                runtime   = runtime,
                security  = security,
                db        = dbInfo,
                mounted   = mountedList,
                crashLogs = crashLogs
            ) }
        }
    }

    private fun readCrashLogs(): List<CrashLog> =
        java.io.File(context.filesDir, ArcanumApp.CRASH_DIR_NAME)
            .listFiles()
            ?.filter { it.isFile && it.length() > 0L }
            ?.sortedByDescending { it.lastModified() }
            ?.map { CrashLog(it.name, it.readText()) }
            ?: emptyList()

    fun copyCrashLogsToClipboard() {
        val logs = state.value.crashLogs
        if (logs.isEmpty()) return
        val text = logs.joinToString("\n\n") { "----- ${it.name} -----\n${it.content}" }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Arcanum Crash Log", text))
    }

    fun clearCrashLogs() {
        viewModelScope.launch(Dispatchers.IO) {
            java.io.File(context.filesDir, ArcanumApp.CRASH_DIR_NAME)
                .listFiles()?.forEach { it.delete() }
            _state.update { it.copy(crashLogs = emptyList()) }
        }
    }

    fun dryRunPanic() {
        viewModelScope.launch {
            val settings   = panicManager.getPanicSettings()
            val containers = dao.getAllContainersOnce()
            val actions    = mutableListOf<String>()

            if (!settings.enabled) {
                actions += "Panic Mode is disabled — no actions would occur."
                _state.update { it.copy(dryRunActions = actions) }
                return@launch
            }

            if (settings.fullWipe) {
                actions += "FULL WIPE:"
                containers.forEach { actions += "  • Secure-delete + remove from DB: ${it.name}" }
                actions += "  • Clear calculator history"
                actions += "  • Reset main PIN"
            } else {
                if (settings.clearCalculatorHistory) actions += "• Clear calculator history"
                if (settings.disableBiometric)        actions += "• Remove biometric from all vaults"
                if (settings.clearSettings)           actions += "• Clear app settings"
                if (containers.isEmpty()) {
                    actions += "(no containers)"
                } else {
                    containers.forEach { entity ->
                        val action = settings.vaultActions[entity.id] ?: VaultPanicAction.KEEP
                        actions += when (action) {
                            VaultPanicAction.DELETE -> "• ${entity.name}: secure-delete file + remove from DB"
                            VaultPanicAction.FORGET -> "• ${entity.name}: remove from DB (file kept on disk)"
                            VaultPanicAction.KEEP   -> "• ${entity.name}: keep — no action"
                        }
                    }
                }
            }

            _state.update { it.copy(dryRunActions = actions) }
        }
    }

    fun clearDryRun() = _state.update { it.copy(dryRunActions = null) }

    fun clearAllThumbnailCache() {
        viewModelScope.launch(Dispatchers.IO) {
            thumbnailManager.clearAllCache()
            _events.emit(DebugEvent.CacheCleared)
        }
    }

    fun copyToClipboard() {
        val s  = state.value
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val text = buildString {
            appendLine("=== Arcanum Debug Info ===")
            appendLine("Time: $ts")
            appendLine()
            s.runtime?.let {
                appendLine("[Runtime]")
                appendLine("PID: ${it.pid}  |  UID: ${it.uid}")
                appendLine("Heap: ${it.heapUsed} / ${it.heapMax}")
                appendLine("libarcanum-native.so: ${if (it.nativeLib) "Loaded" else "Not loaded"}")
                appendLine()
            }
            s.security?.let {
                appendLine("[Device Security]")
                appendLine("Keystore: ${it.keystore}")
                appendLine("Biometric STRONG: ${it.biometricStrong}")
                appendLine("Biometric WEAK: ${it.biometricWeak}")
                appendLine("SELinux: ${it.selinux}")
                appendLine("Root: ${if (it.rooted) "Detected" else "Not detected"}")
                appendLine("Bootloader: ${if (it.bootloaderUnlocked) "Unlocked" else "Locked"}")
                appendLine("USB Debugging: ${if (it.adbEnabled) "Enabled" else "Disabled"}")
                appendLine("Developer Options: ${if (it.devOptionsEnabled) "Enabled" else "Disabled"}")
                val overlayStr = if (it.overlayCapableApps < 0) "Unknown" else "${it.overlayCapableApps} app(s)"
                appendLine("Overlay-capable apps: $overlayStr")
                appendLine("Window FLAG_SECURE: Active")
                appendLine()
            }
            s.db?.let {
                appendLine("[Database]")
                appendLine("Schema version: v${it.version}")
                appendLine("Containers: ${it.total} total, ${it.mounted} mounted")
                appendLine()
            }
            if (s.mounted.isNotEmpty()) {
                appendLine("[Mounted Containers]")
                s.mounted.forEach { c ->
                    appendLine("${c.name}")
                    appendLine("  Handle: 0x${c.handle.toString(16).uppercase().padStart(16, '0')}")
                    appendLine("  PIM: ${if (c.pim > 0) c.pim.toString() else "default"}")
                }
            }
        }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Arcanum Debug", text))
    }

    // ── Collectors ────────────────────────────────────────────────────────────

    private fun buildRuntime(): RuntimeInfo {
        val rt = Runtime.getRuntime()
        return RuntimeInfo(
            pid       = Process.myPid(),
            uid       = Process.myUid(),
            heapUsed  = "%.1f MB".format((rt.totalMemory() - rt.freeMemory()) / 1_048_576f),
            heapMax   = "%.0f MB".format(rt.maxMemory() / 1_048_576f),
            nativeLib = try { System.loadLibrary("arcanum-native"); true } catch (_: UnsatisfiedLinkError) { false }
        )
    }

    private fun buildSecurity(): SecurityInfo = SecurityInfo(
        keystore           = getKeystoreType(),
        biometricStrong    = biometricStatus(BiometricManager.Authenticators.BIOMETRIC_STRONG),
        biometricWeak      = biometricStatus(BiometricManager.Authenticators.BIOMETRIC_WEAK),
        selinux            = getSELinux(),
        rooted             = isRooted(),
        bootloaderUnlocked  = isBootloaderUnlocked(),
        adbEnabled          = isAdbEnabled(),
        devOptionsEnabled   = isDevOptionsEnabled(),
        overlayCapableApps  = countOverlayCapableApps()
    )

    private fun biometricStatus(authenticator: Int): String = when (
        BiometricManager.from(context).canAuthenticate(authenticator)
    ) {
        BiometricManager.BIOMETRIC_SUCCESS               -> "Available"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE     -> "No hardware"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE  -> "Unavailable"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED   -> "Not enrolled"
        else                                              -> "Unknown"
    }

    private fun getKeystoreType(): String = try {
        val spec = KeyGenParameterSpec.Builder(
            "_arc_dbg",
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            .also { it.init(spec) }.generateKey()
        val ks  = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
        val key = ks.getKey("_arc_dbg", null) as SecretKey
        val ki  = SecretKeyFactory.getInstance(key.algorithm, "AndroidKeyStore")
                      .getKeySpec(key, KeyInfo::class.java) as KeyInfo
        ks.deleteEntry("_arc_dbg")
        val isHardware = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ki.securityLevel == KeyProperties.SECURITY_LEVEL_TRUSTED_ENVIRONMENT ||
            ki.securityLevel == KeyProperties.SECURITY_LEVEL_STRONGBOX
        } else {
            @Suppress("DEPRECATION") ki.isInsideSecureHardware
        }
        if (isHardware) "Hardware-backed (TEE)" else "Software (no TEE)"
    } catch (e: Exception) {
        "Unknown (${e.javaClass.simpleName})"
    }

    private fun getSELinux(): String {
        // 1. Direct file read
        try {
            val v = java.io.File("/sys/fs/selinux/enforce").readText().trim()
            if (v == "1") return "Enforcing"
            if (v == "0") return "Permissive"
        } catch (_: Exception) {}

        // 2. android.os.SELinux reflection
        try {
            val cls = Class.forName("android.os.SELinux")
            val enforced = cls.getMethod("isSELinuxEnforced").invoke(null) as Boolean
            return if (enforced) "Enforcing" else "Permissive"
        } catch (_: Exception) {}

        // 3. ro.boot.selinux system property
        try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java, String::class.java)
            val v   = get.invoke(null, "ro.boot.selinux", "") as String
            if (v.isNotEmpty()) return v.replaceFirstChar { it.uppercaseChar() }
        } catch (_: Exception) {}

        // Android 5+ is always enforcing per CDD requirement
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) "Enforcing" else "Unknown"
    }

    private fun isBootloaderUnlocked(): Boolean {
        // ro.boot.verifiedbootstate: green/yellow = locked, orange = unlocked
        try {
            val cls   = Class.forName("android.os.SystemProperties")
            val get   = cls.getMethod("get", String::class.java, String::class.java)
            val state = get.invoke(null, "ro.boot.verifiedbootstate", "") as String
            if (state.isNotEmpty()) return state == "orange"
        } catch (_: Exception) {}
        // Fallback: ro.boot.flash.locked (0 = unlocked)
        try {
            val cls = Class.forName("android.os.SystemProperties")
            val get = cls.getMethod("get", String::class.java, String::class.java)
            val v   = get.invoke(null, "ro.boot.flash.locked", "1") as String
            if (v.isNotEmpty()) return v == "0"
        } catch (_: Exception) {}
        return false
    }

    private fun isAdbEnabled(): Boolean =
        AndroidSettings.Global.getInt(
            context.contentResolver,
            AndroidSettings.Global.ADB_ENABLED, 0
        ) == 1

    private fun isDevOptionsEnabled(): Boolean =
        AndroidSettings.Global.getInt(
            context.contentResolver,
            AndroidSettings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0
        ) == 1

    private fun countOverlayCapableApps(): Int = try {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        context.packageManager
            .getInstalledPackages(PackageManager.GET_PERMISSIONS)
            .count { pkgInfo ->
                pkgInfo.requestedPermissions
                    ?.contains(android.Manifest.permission.SYSTEM_ALERT_WINDOW) == true &&
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW,
                    pkgInfo.applicationInfo!!.uid,
                    pkgInfo.packageName
                ) == AppOpsManager.MODE_ALLOWED
            }
    } catch (_: Exception) { -1 }

    private fun isRooted(): Boolean {
        val suPaths = listOf(
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/data/local/xbin/su", "/data/local/bin/su", "/data/local/su",
            "/system/sd/xbin/su", "/system/bin/failsafe/su",
            "/system/app/Superuser.apk", "/system/app/SuperSU.apk"
        )
        if (suPaths.any { java.io.File(it).exists() }) return true
        if (Build.TAGS?.contains("test-keys") == true) return true
        return try {
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out  = proc.inputStream.bufferedReader().readLine()
            proc.destroy()
            out?.contains("uid=0") == true
        } catch (_: Exception) { false }
    }
}
