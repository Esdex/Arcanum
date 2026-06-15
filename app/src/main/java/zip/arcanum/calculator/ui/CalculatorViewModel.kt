package zip.arcanum.calculator.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zip.arcanum.calculator.logic.CalculatorEngine
import zip.arcanum.calculator.logic.CalculatorState
import zip.arcanum.core.database.dao.CalculatorHistoryDao
import zip.arcanum.core.database.entities.CalculationEntity
import zip.arcanum.core.security.PanicManager
import zip.arcanum.core.security.PinManager
import zip.arcanum.core.security.PinResult
import javax.inject.Inject

sealed class CalculatorEvent {
    object NavigateToArcanum : CalculatorEvent()
    object TriggerPanic       : CalculatorEvent()
}

data class DisplayUiState(
    val expressionText: String = "0",
    val resultText: String = "",
    val isResult: Boolean = false
)

@HiltViewModel
class CalculatorViewModel @Inject constructor(
    private val engine: CalculatorEngine,
    private val pinManager: PinManager,
    private val historyDao: CalculatorHistoryDao,
    private val panicManager: PanicManager
) : ViewModel() {

    private val _state = MutableStateFlow(CalculatorState())

    private val _events = MutableSharedFlow<CalculatorEvent>()
    val events: SharedFlow<CalculatorEvent> = _events.asSharedFlow()

    private val _isVerifying = MutableStateFlow(false)
    val isVerifying: StateFlow<Boolean> = _isVerifying.asStateFlow()

    private var isResult = false

    private val _displayUiState = MutableStateFlow(DisplayUiState())
    val displayUiState: StateFlow<DisplayUiState> = _displayUiState.asStateFlow()

    fun onInput(input: String) {
        val current = _state.value

        if (isResult) {
            when {
                input == "AC" -> {
                    isResult = false
                }
                input in listOf("+", "-", "×", "÷") -> {
                    // Continue with result as the left operand
                    _state.value = CalculatorState(display = current.display)
                    isResult = false
                }
                input == "=" -> return
                else -> {
                    // Any digit/dot/function → start fresh
                    _state.value = CalculatorState()
                    isResult = false
                }
            }
        }

        val newState = engine.processInput(_state.value, input)
        _state.value = newState

        if (input == "=" && !newState.hasError) {
            isResult = true
            viewModelScope.launch {
                historyDao.insertCalculation(
                    CalculationEntity(
                        expression = current.expression + current.display,
                        result     = newState.display,
                        timestamp  = System.currentTimeMillis()
                    )
                )
            }
        }

        pushDisplayState()
    }

    private fun pushDisplayState() {
        val s = _state.value
        _displayUiState.value = when {
            s.hasError -> DisplayUiState(expressionText = "Error", resultText = "", isResult = false)
            isResult   -> DisplayUiState(expressionText = s.display, resultText = "", isResult = true)
            else -> {
                val exprLine = when {
                    s.expression.isEmpty() -> s.display
                    s.display == "0"       -> s.expression
                    else                   -> s.expression + s.display
                }
                DisplayUiState(
                    expressionText = exprLine,
                    resultText     = engine.preview(s),
                    isResult       = false
                )
            }
        }
    }

    // Called by 2-second long press on = — PIN verification only
    fun onLongPressEquals() {
        if (_isVerifying.value) return
        val current = _state.value
        val pin = (current.expression + current.display).filter { it.isDigit() }

        _isVerifying.value = true
        viewModelScope.launch(Dispatchers.IO) {
            val result = pinManager.verifyPin(pin)
            when (result) {
                PinResult.NORMAL -> {
                    delay(1_000L)
                    withContext(Dispatchers.Main) { _isVerifying.value = false }
                    _events.emit(CalculatorEvent.NavigateToArcanum)
                }
                PinResult.PANIC  -> {
                    // Wipe completes before navigation — spinner stays visible during wipe
                    panicManager.executePanic()
                    withContext(Dispatchers.Main) { _isVerifying.value = false }
                    _events.emit(CalculatorEvent.NavigateToArcanum)
                }
                PinResult.WRONG  -> {
                    withContext(Dispatchers.Main) { _isVerifying.value = false }
                }
            }
        }
    }
}
