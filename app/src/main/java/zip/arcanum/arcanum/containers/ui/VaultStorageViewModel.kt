package zip.arcanum.arcanum.containers.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.containers.domain.StorageBreakdown
import zip.arcanum.arcanum.containers.domain.StorageCategory
import zip.arcanum.arcanum.containers.domain.StorageCategorizer
import zip.arcanum.core.navigation.Screen
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject

/**
 * Walks the mounted vault's filesystem once and sums file sizes per
 * [StorageCategory], for the Info → Storage donut. The container is guaranteed
 * mounted whenever the Info screen is visible, so [ContainerRepository.getContainerHandle]
 * returns a live native handle.
 */
@HiltViewModel
class VaultStorageViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: ContainerRepository,
    private val engine: VeraCryptEngine,
) : ViewModel() {

    val containerId: String = savedStateHandle[Screen.ContainerScreen.ARG] ?: ""

    private val _breakdown = MutableStateFlow(StorageBreakdown.Loading)
    val breakdown = _breakdown.asStateFlow()

    init {
        refresh()
    }

    /** Recompute the breakdown off the main thread. Safe to call again after edits. */
    fun refresh() {
        _breakdown.value = _breakdown.value.copy(isLoading = true)
        viewModelScope.launch {
            _breakdown.value = withContext(Dispatchers.IO) { compute() }
        }
    }

    private suspend fun compute(): StorageBreakdown {
        val fallbackCapacity = repo.getContainerById(containerId)?.size ?: 0L
        val handle = repo.getContainerHandle(containerId)
            ?: return StorageBreakdown(fallbackCapacity, emptyMap(), isLoading = false)

        val capacity = engine.getDataSize(handle).takeIf { it > 0L } ?: fallbackCapacity

        val sums = HashMap<StorageCategory, Long>()
        // Iterative walk to avoid deep recursion on large trees. Native paths are
        // absolute, so a child's `path` is enqueued directly (mirrors MediaScanner).
        val stack = ArrayDeque<String>()
        stack.addLast("/")
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val entries = try {
                engine.listFiles(handle, dir)
            } catch (_: Exception) {
                continue
            }
            for (entry in entries.filterNotNull()) {
                if (entry.isDirectory) {
                    stack.addLast(entry.path)
                } else {
                    val cat = StorageCategorizer.categorizeFile(entry.name)
                    sums[cat] = (sums[cat] ?: 0L) + entry.size
                }
            }
        }

        return StorageBreakdown(capacity = capacity, used = sums, isLoading = false)
    }
}
