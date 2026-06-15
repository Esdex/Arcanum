package zip.arcanum.settings

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import zip.arcanum.core.security.AppPreferences
import zip.arcanum.core.security.BiometricAuth
import zip.arcanum.core.security.PinManager
import zip.arcanum.core.security.PinResult
import zip.arcanum.core.theme.ThemeMode
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val prefs: AppPreferences,
    private val biometricAuth: BiometricAuth,
    private val pinManager: PinManager
) : ViewModel() {

    val autoLockEnabled = prefs.autoLockEnabled.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = true
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

    fun setAutoLock(enabled: Boolean) {
        viewModelScope.launch { prefs.setAutoLock(enabled) }
    }

    fun setDebugMode(enabled: Boolean) {
        viewModelScope.launch { prefs.setDebugMode(enabled) }
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
