package zip.arcanum.core.lifecycle

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExternalActivityGuard @Inject constructor() {
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()

    val isActive: Boolean
        get() = _activeCount.value > 0

    fun begin() {
        _activeCount.value = _activeCount.value + 1
    }

    fun end() {
        _activeCount.value = (_activeCount.value - 1).coerceAtLeast(0)
    }
}
