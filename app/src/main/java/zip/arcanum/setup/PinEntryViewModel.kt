package zip.arcanum.setup

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
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
import zip.arcanum.core.security.AppPreferences
import zip.arcanum.core.security.BiometricAuth
import zip.arcanum.core.security.PanicManager
import zip.arcanum.core.security.PanicWipeWorker
import zip.arcanum.core.security.PinManager
import zip.arcanum.core.security.PinResult
import javax.inject.Inject

sealed interface PinEntryState {
    object Idle      : PinEntryState
    object Verifying : PinEntryState
    object WrongPin  : PinEntryState
    data class Locked(val remainingSec: Long) : PinEntryState
}

@HiltViewModel
class PinEntryViewModel @Inject constructor(
    private val pinManager: PinManager,
    private val panicManager: PanicManager,
    private val biometricAuth: BiometricAuth,
    private val prefs: AppPreferences,
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
    suspend fun loadInitialBiometricEnabled(): Boolean = prefs.biometricUnlockEnabled.first()

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
                    val panicEnabled = panicManager.prepareForPanic() != null
                    // Enqueue before onAuthenticated() so the work is registered before
                    // navigation tears down this ViewModel's scope and cancels the coroutine.
                    if (panicEnabled) {
                        WorkManager.getInstance(context)
                            .enqueueUniqueWork(
                                PanicWipeWorker.WORK_NAME,
                                ExistingWorkPolicy.REPLACE,
                                OneTimeWorkRequestBuilder<PanicWipeWorker>()
                                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                    .build()
                            )
                    }
                    withContext(Dispatchers.Main) {
                        _state.value = PinEntryState.Idle
                        onAuthenticated()
                    }
                }
                PinResult.WRONG -> {
                    withContext(Dispatchers.Main) { _state.value = PinEntryState.WrongPin }
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
                        biometricAuth.authenticate(
                            activity  = activity,
                            title     = "Enable biometric unlock",
                            subtitle  = "Confirm your identity to register",
                            onSuccess = {
                                viewModelScope.launch {
                                    prefs.setBiometricUnlockEnabled(true)
                                    onAuthenticated()
                                }
                            },
                            onError  = { _, _ -> },
                            onFailed = {}
                        )
                    }
                }
                PinResult.PANIC -> {
                    val panicEnabled = panicManager.prepareForPanic() != null
                    if (panicEnabled) {
                        WorkManager.getInstance(context)
                            .enqueueUniqueWork(
                                PanicWipeWorker.WORK_NAME,
                                ExistingWorkPolicy.REPLACE,
                                OneTimeWorkRequestBuilder<PanicWipeWorker>()
                                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                    .build()
                            )
                    }
                    withContext(Dispatchers.Main) {
                        _state.value = PinEntryState.Idle
                        onAuthenticated()
                    }
                }
                PinResult.WRONG -> {
                    withContext(Dispatchers.Main) { _state.value = PinEntryState.WrongPin }
                }
                PinResult.LOCKED -> {
                    val sec = (pinManager.lockoutRemainingMs() / 1000L).coerceAtLeast(1L)
                    withContext(Dispatchers.Main) { _state.value = PinEntryState.Locked(sec) }
                }
            }
        }
    }

    fun authenticate(activity: FragmentActivity, onAuthenticated: () -> Unit) {
        biometricAuth.authenticate(
            activity  = activity,
            title     = "Unlock Arcanum",
            subtitle  = "Confirm your identity",
            onSuccess = onAuthenticated,
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
