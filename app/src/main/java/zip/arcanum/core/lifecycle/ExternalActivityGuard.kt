package zip.arcanum.core.lifecycle

import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExternalActivityGuard @Inject constructor() {
    private val _activeCount = MutableStateFlow(0)
    val activeCount: StateFlow<Int> = _activeCount.asStateFlow()
    private var activeSinceMs: Long = 0L

    val isActive: Boolean
        get() {
            if (_activeCount.value <= 0) return false
            if (SystemClock.elapsedRealtime() - activeSinceMs > MAX_EXTERNAL_ACTIVITY_MS) {
                _activeCount.value = 0
                activeSinceMs = 0L
                return false
            }
            return true
        }

    fun begin() {
        if (_activeCount.value == 0) activeSinceMs = SystemClock.elapsedRealtime()
        _activeCount.value = _activeCount.value + 1
    }

    fun end() {
        val next = (_activeCount.value - 1).coerceAtLeast(0)
        _activeCount.value = next
        if (next == 0) activeSinceMs = 0L
    }

    companion object {
        private const val MAX_EXTERNAL_ACTIVITY_MS = 5L * 60L * 1000L
    }
}
