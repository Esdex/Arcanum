package zip.arcanum.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import zip.arcanum.core.security.AppPasswordPolicy
import zip.arcanum.core.security.PinManager
import zip.arcanum.core.security.PinResult
import javax.inject.Inject

@HiltViewModel
class ChangePinViewModel @Inject constructor(
    private val pinManager: PinManager
) : ViewModel() {

    enum class Step { VERIFY_CURRENT, ENTER_NEW, CONFIRM_NEW }

    data class State(
        val step: Step      = Step.VERIFY_CURRENT,
        val pin: String     = "",
        val isError: Boolean = false,
        val errorShake: Int = 0,      // incremented to trigger shake
        val isSuccess: Boolean = false,
        val isSaving: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private var newPin = ""

    fun onDigit(d: Char) {
        _state.update { s ->
            val max = if (s.step == Step.VERIFY_CURRENT) 12 else 12
            if (s.pin.length >= max) s else s.copy(pin = s.pin + d, isError = false)
        }
    }

    fun setPin(value: String) {
        _state.update { it.copy(pin = AppPasswordPolicy.sanitize(value), isError = false) }
    }

    fun onBackspace() {
        _state.update { it.copy(pin = it.pin.dropLast(1), isError = false) }
    }

    fun advance() {
        val s = _state.value
        if (!AppPasswordPolicy.isValid(s.pin)) return
        when (s.step) {
            Step.VERIFY_CURRENT -> {
                viewModelScope.launch {
                    _state.update { it.copy(isSaving = true) }
                    val result = pinManager.verifyPin(s.pin)
                    if (result == PinResult.NORMAL) {
                        _state.update { it.copy(isSaving = false, step = Step.ENTER_NEW, pin = "", isError = false) }
                    } else {
                        _state.update { it.copy(isSaving = false, pin = "", isError = true, errorShake = it.errorShake + 1) }
                    }
                }
            }
            Step.ENTER_NEW -> {
                newPin = s.pin
                _state.update { it.copy(step = Step.CONFIRM_NEW, pin = "", isError = false) }
            }
            Step.CONFIRM_NEW -> {
                if (s.pin == newPin) {
                    viewModelScope.launch {
                        _state.update { it.copy(isSaving = true) }
                        if (pinManager.matchesPanicPin(s.pin)) {
                            newPin = ""
                            _state.update {
                                it.copy(
                                    isSaving   = false,
                                    step       = Step.ENTER_NEW,
                                    pin        = "",
                                    isError    = true,
                                    errorShake = it.errorShake + 1
                                )
                            }
                        } else {
                            pinManager.savePin(s.pin)
                            _state.update { it.copy(isSaving = false, isSuccess = true) }
                        }
                    }
                } else {
                    newPin = ""
                    _state.update {
                        it.copy(
                            step       = Step.ENTER_NEW,
                            pin        = "",
                            isError    = true,
                            errorShake = it.errorShake + 1
                        )
                    }
                }
            }
        }
    }
}
