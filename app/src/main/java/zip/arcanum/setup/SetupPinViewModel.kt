package zip.arcanum.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import zip.arcanum.core.security.PinManager
import javax.inject.Inject

@HiltViewModel
class SetupPinViewModel @Inject constructor(
    private val pinManager: PinManager
) : ViewModel() {

    enum class Step { ENTER, CONFIRM }

    data class State(
        val step: Step = Step.ENTER,
        val pin: String = "",
        val isError: Boolean = false,
        val isSuccess: Boolean = false,
        val isSaving: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private var firstPin = ""

    fun onDigit(d: Char) {
        _state.update { s ->
            if (s.pin.length >= 6) s else s.copy(pin = s.pin + d, isError = false)
        }
    }

    fun onBackspace() {
        _state.update { it.copy(pin = it.pin.dropLast(1), isError = false) }
    }

    fun advance() {
        val s = _state.value
        if (s.pin.length < 4) return
        when (s.step) {
            Step.ENTER -> {
                firstPin = s.pin
                _state.update { it.copy(step = Step.CONFIRM, pin = "", isError = false) }
            }
            Step.CONFIRM -> {
                if (s.pin == firstPin) {
                    viewModelScope.launch {
                        _state.update { it.copy(isSaving = true) }
                        pinManager.savePin(s.pin)
                        _state.update { it.copy(isSaving = false, isSuccess = true) }
                    }
                } else {
                    _state.update { it.copy(pin = "", isError = true) }
                }
            }
        }
    }
}
