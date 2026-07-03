package zip.arcanum.settings

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zip.arcanum.billing.BillingManagerInterface
import zip.arcanum.core.security.AppBiometricUnlockManager
import zip.arcanum.core.security.AppPreferences
import zip.arcanum.core.security.BiometricAuth
import zip.arcanum.core.security.DisguiseProfile
import zip.arcanum.core.security.DisguiseManager
import zip.arcanum.core.security.IntruderCapture
import zip.arcanum.core.security.IntruderCaptureManager
import zip.arcanum.core.security.PinManager
import zip.arcanum.core.security.PinResult
import zip.arcanum.core.theme.ThemeMode
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val biometricAuth: BiometricAuth,
    private val appBiometricUnlockManager: AppBiometricUnlockManager,
    private val pinManager: PinManager,
    private val disguiseManager: DisguiseManager,
    private val intruderCaptureManager: IntruderCaptureManager,
    private val billingManager: BillingManagerInterface
) : ViewModel() {

    init {
        viewModelScope.launch {
            disguiseManager.normalizeLegacyAliases()
        }
    }

    val isPro = billingManager.isPro

    val autoLockEnabled = prefs.autoLockEnabled.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = true
    )

    val autoLockDelayIndex = prefs.autoLockDelayIndex.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = 0
    )

    val autoLockDeadlineMs = prefs.autoLockDeadlineMs.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = 0L
    )

    val debugMode = prefs.debugMode.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = false
    )

    val themeMode = prefs.themeMode.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = ThemeMode.SYSTEM
    )

    val isAmoledGlass = prefs.isAmoledGlass.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = false
    )

    val isDynamicColor = prefs.isDynamicColor.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = true
    )

    val screenCaptureProtection = prefs.screenCaptureProtection.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = true
    )

    val deleteImportedFiles = prefs.deleteImportedFiles.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = false
    )

    val deleteExportedFiles = prefs.deleteExportedFiles.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = false
    )

    val hideFromRecents = prefs.hideFromRecents.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = false
    )

    val biometricUnlockEnabled = prefs.biometricUnlockEnabled.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = false
    )

    val intruderDetectionEnabled = prefs.intruderDetectionEnabled.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = false
    )

    // null = loading (key absent from DataStore); treat as true (calculator on by default)
    val calculatorEnabled = prefs.calculatorEnabled.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = null
    )

    val disguiseEnabled = prefs.disguiseEnabled.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = disguiseManager.isDisguiseApplied()
    )

    val disguiseProfile = prefs.disguiseProfile.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = disguiseManager.appliedProfile() ?: DisguiseProfile.default
    )

    private val _manualShowDisguise = MutableStateFlow(false)
    private val _disguiseApplied    = MutableStateFlow(disguiseManager.isDisguiseApplied())
    private val _intruderCaptures   = MutableStateFlow<List<IntruderCapture>>(emptyList())
    val disguiseApplied = _disguiseApplied.asStateFlow()
    val intruderCaptures = _intruderCaptures.asStateFlow()

    val showDisguiseOverlay = combine(
        pinManager.isPinSetFlow.map { it ?: false },
        prefs.disguisePromptShown,
        prefs.firstLoginDone,
        _manualShowDisguise
    ) { pinSet, promptShown, firstLoginDone, manual ->
        (pinSet && !promptShown && !disguiseManager.isDisguiseApplied() && firstLoginDone) || manual
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        refreshIntruderCaptures()
    }

    fun setFirstLoginDone() {
        viewModelScope.launch { prefs.setFirstLoginDone() }
    }

    fun setAutoLock(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoLock(enabled) }
    }

    fun setAutoLockDelayIndex(index: Int) {
        viewModelScope.launch { prefs.setAutoLockDelayIndex(index) }
    }

    fun setAutoLockDeadlineMs(deadlineMs: Long) {
        viewModelScope.launch { prefs.setAutoLockDeadlineMs(deadlineMs) }
    }

    val showMountLog = prefs.showMountLog.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = false
    )

    fun setShowMountLog(enabled: Boolean) {
        viewModelScope.launch { prefs.setShowMountLog(enabled) }
    }

    fun setDebugMode(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setDebugMode(enabled)
            if (!enabled) prefs.setShowMountLog(false)
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefs.setThemeMode(mode) }
    }

    fun setAmoledGlass(enabled: Boolean) {
        viewModelScope.launch { prefs.setAmoledGlass(enabled) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { prefs.setDynamicColor(enabled) }
    }

    fun setScreenCaptureProtection(enabled: Boolean) {
        viewModelScope.launch { prefs.setScreenCaptureProtection(enabled) }
    }

    fun setDeleteImportedFiles(enabled: Boolean) {
        viewModelScope.launch { prefs.setDeleteImportedFiles(enabled) }
    }

    fun setDeleteExportedFiles(enabled: Boolean) {
        viewModelScope.launch { prefs.setDeleteExportedFiles(enabled) }
    }

    fun setHideFromRecents(enabled: Boolean) {
        viewModelScope.launch { prefs.setHideFromRecents(enabled) }
    }

    fun setBiometricUnlockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled) {
                appBiometricUnlockManager.clearEnrollment()
                prefs.setBiometricUnlockEnabled(false)
            } else {
                prefs.setBiometricUnlockEnabled(appBiometricUnlockManager.hasEnrollment())
            }
        }
    }

    fun setIntruderDetectionEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setIntruderDetectionEnabled(enabled)
            refreshIntruderCaptures()
        }
    }

    fun refreshIntruderCaptures() {
        _intruderCaptures.value = intruderCaptureManager.listCaptures()
    }

    fun deleteIntruderCapture(capture: IntruderCapture) {
        viewModelScope.launch {
            intruderCaptureManager.deleteCapture(capture.file)
            refreshIntruderCaptures()
        }
    }

    fun deleteAllIntruderCaptures() {
        viewModelScope.launch {
            intruderCaptureManager.deleteAllCaptures()
            refreshIntruderCaptures()
        }
    }

    fun setCalculatorEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setCalculatorEnabled(enabled) }
    }

    fun setDisguiseEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                // Enabling is applied only from the explicit profile confirmation button.
                // This keeps a simple switch tap from changing launcher aliases immediately.
            } else {
                disguiseManager.reset()
                _disguiseApplied.value = false
            }
        }
    }

    fun setDisguiseProfile(profile: DisguiseProfile) {
        viewModelScope.launch {
            prefs.setDisguiseProfile(profile)
        }
    }

    fun applyDisguiseProfile(profile: DisguiseProfile) {
        viewModelScope.launch {
            disguiseManager.apply(profile)
            _disguiseApplied.value = true
        }
    }

    fun requestDisguise() { _manualShowDisguise.value = true }

    fun dismissDisguiseOverlay() {
        _manualShowDisguise.value = false
        viewModelScope.launch { prefs.setDisguisePromptShown(true) }
    }

    fun resetDisguise() {
        viewModelScope.launch {
            disguiseManager.reset()
            _disguiseApplied.value = false
        }
    }

    fun applyDisguise(onRestart: () -> Unit) {
        viewModelScope.launch {
            disguiseManager.apply(DisguiseProfile.CALCULATOR)
            _disguiseApplied.value = true
            _manualShowDisguise.value = false
            withContext(Dispatchers.Main) { onRestart() }
        }
    }

    fun hasDeviceLock(): Boolean = biometricAuth.hasDeviceLock()

    fun authenticateForScreenshotDisable(
        activity: FragmentActivity,
        title: String,
        subtitle: String,
        onSuccess: () -> Unit,
        onError: (Int, CharSequence) -> Unit,
        onNoDeviceLock: () -> Unit
    ) {
        if (biometricAuth.hasDeviceLock()) {
            biometricAuth.authenticateWithDeviceLock(activity, title, subtitle, onSuccess, onError)
        } else {
            onNoDeviceLock()
        }
    }

    suspend fun verifyPin(pin: String): Boolean =
        pinManager.verifyPin(pin) == PinResult.NORMAL

    fun authenticateForDebug(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onError: (Int, CharSequence) -> Unit
    ) = biometricAuth.authenticateForDebug(activity, onSuccess, onError)
}
