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
import zip.arcanum.core.security.AppPreferences
import zip.arcanum.core.security.BiometricAuth
import zip.arcanum.core.security.DisguiseManager
import zip.arcanum.core.security.PinManager
import zip.arcanum.core.security.PinResult
import zip.arcanum.core.theme.ThemeMode
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val biometricAuth: BiometricAuth,
    private val pinManager: PinManager,
    private val disguiseManager: DisguiseManager,
    private val billingManager: BillingManagerInterface
) : ViewModel() {

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

    // null = loading (key absent from DataStore); treat as true (calculator on by default)
    val calculatorEnabled = prefs.calculatorEnabled.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = null
    )

    private val _manualShowDisguise = MutableStateFlow(false)
    private val _disguiseApplied    = MutableStateFlow(disguiseManager.isDisguiseApplied())
    val disguiseApplied = _disguiseApplied.asStateFlow()

    val showDisguiseOverlay = combine(
        pinManager.isPinSetFlow.map { it ?: false },
        prefs.disguisePromptShown,
        prefs.firstLoginDone,
        _manualShowDisguise
    ) { pinSet, promptShown, firstLoginDone, manual ->
        (pinSet && !promptShown && !disguiseManager.isDisguiseApplied() && firstLoginDone) || manual
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setFirstLoginDone() {
        viewModelScope.launch { prefs.setFirstLoginDone() }
    }

    fun setAutoLock(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoLock(enabled) }
    }

    fun setAutoLockDelayIndex(index: Int) {
        viewModelScope.launch { prefs.setAutoLockDelayIndex(index) }
    }

    val showMountLog = prefs.showMountLog.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = false
    )

    fun setShowMountLog(enabled: Boolean) {
        viewModelScope.launch { prefs.setShowMountLog(enabled) }
    }

    val galleryResyncButton = prefs.galleryResyncButton.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = false
    )

    fun setGalleryResyncButton(enabled: Boolean) {
        viewModelScope.launch { prefs.setGalleryResyncButton(enabled) }
    }

    fun setDebugMode(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setDebugMode(enabled)
            if (!enabled) {
                prefs.setShowMountLog(false)
                prefs.setGalleryResyncButton(false)
            }
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

    fun setCalculatorEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setCalculatorEnabled(enabled) }
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
            // Write calculatorEnabled and apply the alias atomically in one coroutine so a
            // crash between them cannot leave the alias active with calculatorEnabled=false
            // (which would show PinEntry under the calculator launcher icon).
            prefs.setCalculatorEnabled(true)
            disguiseManager.apply()
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

    val unmountOnAutoLock = prefs.unmountOnAutoLock.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = false
    )

    fun setUnmountOnAutoLock(enabled: Boolean) {
        viewModelScope.launch { prefs.setUnmountOnAutoLock(enabled) }
    }
}
