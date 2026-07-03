package zip.arcanum.arcanum.containers.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.gallery.MediaScanner
import zip.arcanum.arcanum.gallery.ThumbnailPreloader
import zip.arcanum.core.database.entities.MediaFileEntity
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject

@HiltViewModel
class MountCoordinator @Inject constructor(
    private val mediaScanner: MediaScanner,
    private val repo: ContainerRepository,
    private val thumbnailPreloader: ThumbnailPreloader,
    private val cryptoEngine: VeraCryptEngine
) : ViewModel() {

    sealed interface Phase {
        object Idle : Phase
        data class Unlocking(val containerId: String) : Phase
        data class Indexing(
            val containerId: String,
            val scanned: Int,
            val found: Int,
            val currentPath: String
        ) : Phase
        data class ScanComplete(val containerId: String) : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase = _phase.asStateFlow()

    /** Called immediately when crypto succeeds — starts the unlock animation. */
    fun beginUnlocking(containerId: String) {
        _phase.value = Phase.Unlocking(containerId)
    }

    /** Called by the overlay after the lock-open animation finishes. */
    fun beginScanning() {
        val containerId = (_phase.value as? Phase.Unlocking)?.containerId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val handle = repo.getContainerHandle(containerId)
            if (handle == null) {
                _phase.value = Phase.ScanComplete(containerId)
                return@launch
            }
            var lastProgress = MediaScanner.ScanProgress(0, 0, "", false, emptyList<MediaFileEntity>())
            mediaScanner.scanContainer(handle, containerId).collect { progress ->
                lastProgress = progress
                _phase.value = Phase.Indexing(
                    containerId = containerId,
                    scanned     = progress.scannedFiles,
                    found       = progress.totalFound,
                    currentPath = progress.currentPath
                )
            }
            _phase.value = Phase.ScanComplete(containerId)
            // Kick off background thumbnail pre-generation for the just-scanned files.
            // The preloader runs independently; the gallery uses the disk cache as thumbnails complete.
            thumbnailPreloader.start(handle, containerId, lastProgress.mediaFiles)
        }
    }

    /** Called by AppNavigation after navigation has been committed. */
    fun dismiss() {
        _phase.value = Phase.Idle
    }

    /** Unmounts all currently mounted containers — called before auto-lock navigation. */
    fun unmountAll() {
        viewModelScope.launch(Dispatchers.IO) {
            repo.getAllContainersRaw().first().filter { it.isMounted }.forEach { c ->
                val handle = repo.getContainerHandle(c.id)
                if (handle != null) cryptoEngine.unmountContainer(handle)
                repo.unmountContainer(c.id)
            }
        }
    }
}
