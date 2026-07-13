package zip.arcanum.arcanum.gallery

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapMerge
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import zip.arcanum.core.database.entities.MediaFileEntity
import zip.arcanum.core.database.entities.MediaFileType
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ThumbnailPreloader @Inject constructor(
    private val thumbnailManager: ThumbnailManager,
    private val engine: VeraCryptEngine
) {
    data class State(
        val containerId: String? = null,
        val done: Int = 0,
        val total: Int = 0,
        val isRunning: Boolean = false
    ) {
        val progress: Float get() = if (total > 0) done.toFloat() / total else 0f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var currentJob: Job? = null
    @Volatile private var activeGeneration = 0

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun start(handle: Long, containerId: String, files: List<MediaFileEntity>) {
        currentJob?.cancel()
        val gen = ++activeGeneration

        // Only images and videos without an existing disk cache entry; sort newest-first
        val preloadFiles = files
            .filter { it.fileType == MediaFileType.IMAGE || it.fileType == MediaFileType.VIDEO }
            .let { thumbnailManager.filterUncached(containerId, it) }
            .sortedByDescending { it.dateModified }

        if (preloadFiles.isEmpty()) {
            _state.value = State()
            return
        }

        _state.value = State(containerId = containerId, done = 0, total = preloadFiles.size, isRunning = true)

        currentJob = scope.launch {
            try {
                preloadFiles.asFlow()
                    .flatMapMerge(concurrency = 2) { file ->
                        flow {
                            try { thumbnailManager.getThumbnail(engine, handle, file) } catch (_: Exception) {}
                            emit(Unit)
                        }
                    }
                    .onEach { if (activeGeneration == gen) _state.update { it.copy(done = it.done + 1) } }
                    .collect {}
            } finally {
                if (activeGeneration == gen) _state.update { it.copy(isRunning = false) }
            }
        }
    }

    fun cancel() {
        currentJob?.cancel()
        currentJob = null
        ++activeGeneration
        _state.value = State()
    }
}
