package zip.arcanum.arcanum.containers.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MountLogger @Inject constructor() {

    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    private var startMs = 0L

    fun start() {
        startMs = System.currentTimeMillis()
        _lines.value = emptyList()
    }

    fun log(message: String) {
        val elapsed = (System.currentTimeMillis() - startMs) / 1000.0
        val ts = "[%06.3fs]".format(elapsed)
        _lines.update { it + "$ts $message" }
    }

    fun clear() {
        _lines.value = emptyList()
    }
}
