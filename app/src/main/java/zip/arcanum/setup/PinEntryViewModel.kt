package zip.arcanum.setup

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zip.arcanum.R
import zip.arcanum.core.security.AppBiometricUnlockManager
import zip.arcanum.core.security.AppPreferences
import zip.arcanum.core.security.BiometricAuth
import zip.arcanum.core.security.IntruderCaptureManager
import zip.arcanum.core.security.PanicManager
import zip.arcanum.core.security.PinManager
import zip.arcanum.core.security.PinResult
import javax.inject.Inject

sealed interface PinEntryState {
    object Idle      : PinEntryState
    object Verifying : PinEntryState
    object PanicWiping : PinEntryState
    object PanicComplete : PinEntryState
    object WrongPin  : PinEntryState
    data class Locked(val remainingSec: Long) : PinEntryState
}

@HiltViewModel
class PinEntryViewModel @Inject constructor(
    private val pinManager: PinManager,
    private val panicManager: PanicManager,
    private val biometricAuth: BiometricAuth,
    private val appBiometricUnlockManager: AppBiometricUnlockManager,
    private val prefs: AppPreferences,
    private val intruderCaptureManager: IntruderCaptureManager,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _state = MutableStateFlow<PinEntryState>(PinEntryState.Idle)
    val state = _state.asStateFlow()

    val isBiometricAvailable: Boolean = biometricAuth.isAvailable()

    val biometricUnlockEnabled = prefs.biometricUnlockEnabled.stateIn(
        scope        = viewModelScope,
        started      = SharingStarted.Eagerly,
        initialValue = false
    )

    // One-shot read: returns the persisted value from DataStore at screen entry time.
    // Using this instead of observing the StateFlow in LaunchedEffect prevents a second
    // biometric prompt when registration happens in the same session (setBiometricUnlockEnabled
    // fires → StateFlow changes → LaunchedEffect would re-trigger while still in composition).
    suspend fun loadInitialBiometricReady(): Boolean =
        prefs.biometricUnlockEnabled.first() && appBiometricUnlockManager.hasEnrollment()

    fun hasBiometricEnrollment(): Boolean = appBiometricUnlockManager.hasEnrollment()

    fun submitPin(pin: String, onAuthenticated: () -> Unit) {
        if (_state.value is PinEntryState.Verifying) return
        _state.value = PinEntryState.Verifying
        viewModelScope.launch(Dispatchers.IO) {
            when (pinManager.verifyPin(pin)) {
                PinResult.NORMAL -> {
                    panicManager.getPanicSettings()
                    pinManager.dummyPromote()
                    withContext(Dispatchers.Main) {
                        _state.value = PinEntryState.Idle
                        onAuthenticated()
                    }
                }
                PinResult.PANIC -> {
                    if (panicManager.prepareForPanic() == null) {
                        pinManager.clearPanicPin()
                        withContext(Dispatchers.Main) { _state.value = PinEntryState.WrongPin }
                    } else {
                        withContext(Dispatchers.Main) { _state.value = PinEntryState.PanicWiping }
                        panicManager.executeWipe()
                        withContext(Dispatchers.Main) { _state.value = PinEntryState.PanicComplete }
                    }
                }
                PinResult.WRONG -> {
                    withContext(Dispatchers.Main) { _state.value = PinEntryState.WrongPin }
                    viewModelScope.launch { intruderCaptureManager.captureBurstIfEnabled() }
                }
                PinResult.LOCKED -> {
                    val sec = (pinManager.lockoutRemainingMs() / 1000L).coerceAtLeast(1L)
                    withContext(Dispatchers.Main) { _state.value = PinEntryState.Locked(sec) }
                }
            }
        }
    }

    // Verifies PIN, then if correct shows biometric prompt to register biometric unlock.
    // On biometric success: saves pref + calls onAuthenticated.
    fun registerBiometricWithPin(pin: String, activity: FragmentActivity, onAuthenticated: () -> Unit) {
        if (_state.value is PinEntryState.Verifying) return
        _state.value = PinEntryState.Verifying
        viewModelScope.launch(Dispatchers.IO) {
            when (pinManager.verifyPin(pin)) {
                PinResult.NORMAL -> {
                    panicManager.getPanicSettings()
                    pinManager.dummyPromote()
                    withContext(Dispatchers.Main) {
                        _state.value = PinEntryState.Idle
                        val cryptoObject = appBiometricUnlockManager.getCryptoObjectForEnroll()
                        if (cryptoObject == null) {
                            viewModelScope.launch { prefs.setBiometricUnlockEnabled(false) }
                            onAuthenticated()
                            return@withContext
                        }
                        biometricAuth.authenticateWithCrypto(
                            activity = activity,
                            cryptoObject = cryptoObject,
                            title = context.getString(R.string.biometric_enable_title),
                            subtitle = context.getString(R.string.biometric_enable_subtitle),
                            negativeButtonText = context.getString(R.string.biometric_use_pin),
                            onSuccess = { result ->
                                val cipher = result.cryptoObject?.cipher
                                viewModelScope.launch {
                                    val enrolled = cipher != null && appBiometricUnlockManager.completeEnrollment(cipher)
                                    prefs.setBiometricUnlockEnabled(enrolled)
                                    onAuthenticated()
                                }
                            },
                            onError  = { _, _ ->
                                viewModelScope.launch { prefs.setBiometricUnlockEnabled(false) }
                                onAuthenticated()
                            },
                            onFailed = {}
                        )
                    }
                }
                PinResult.PANIC -> {
                    if (panicManager.prepareForPanic() == null) {
                        pinManager.clearPanicPin()
                        withContext(Dispatchers.Main) { _state.value = PinEntryState.WrongPin }
                    } else {
                        withContext(Dispatchers.Main) { _state.value = PinEntryState.PanicWiping }
                        panicManager.executeWipe()
                        withContext(Dispatchers.Main) { _state.value = PinEntryState.PanicComplete }
                    }
                }
                PinResult.WRONG -> {
                    withContext(Dispatchers.Main) { _state.value = PinEntryState.WrongPin }
                    viewModelScope.launch { intruderCaptureManager.captureBurstIfEnabled() }
                }
                PinResult.LOCKED -> {
                    val sec = (pinManager.lockoutRemainingMs() / 1000L).coerceAtLeast(1L)
                    withContext(Dispatchers.Main) { _state.value = PinEntryState.Locked(sec) }
                }
            }
        }
    }

    fun authenticate(activity: FragmentActivity, onAuthenticated: () -> Unit) {
        val cryptoObject = appBiometricUnlockManager.getCryptoObjectForUnlock()
        if (cryptoObject == null) {
            viewModelScope.launch { prefs.setBiometricUnlockEnabled(false) }
            return
        }
        biometricAuth.authenticateWithCrypto(
            activity  = activity,
            cryptoObject = cryptoObject,
            title     = context.getString(R.string.biometric_unlock_title),
            subtitle  = context.getString(R.string.biometric_unlock_subtitle),
            negativeButtonText = context.getString(R.string.biometric_use_pin),
            onSuccess = { result ->
                val cipher = result.cryptoObject?.cipher
                if (cipher != null && appBiometricUnlockManager.verifyUnlock(cipher)) {
                    onAuthenticated()
                } else {
                    appBiometricUnlockManager.clearEnrollment()
                    viewModelScope.launch { prefs.setBiometricUnlockEnabled(false) }
                }
            },
            onError   = { _, _ -> },
            onFailed  = {}
        )
    }

    fun clearError() {
        if (_state.value is PinEntryState.WrongPin || _state.value is PinEntryState.Locked) {
            _state.value = PinEntryState.Idle
        }
    }
}
