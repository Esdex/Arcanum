package zip.arcanum.arcanum.gallery.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.core.database.dao.MediaFileDao
import zip.arcanum.core.database.entities.MediaFileEntity
import zip.arcanum.core.navigation.Screen
import zip.arcanum.core.security.IdleMonitor
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject

@HiltViewModel
class MediaPlayerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val mediaFileDao: MediaFileDao,
    private val repo: ContainerRepository,
    val engine: VeraCryptEngine,
    private val idleMonitor: IdleMonitor
) : ViewModel() {

    // Backs the gallery AudioPlayer route.
    private val fileId: String = savedStateHandle[Screen.AudioPlayer.ARG] ?: ""

    private val _file = MutableStateFlow<MediaFileEntity?>(null)
    val file = _file.asStateFlow()

    init {
        viewModelScope.launch {
            _file.value = mediaFileDao.getMediaById(fileId)
        }
    }

    fun getHandle(): Long? {
        val containerId = _file.value?.containerId ?: return null
        return repo.getContainerHandle(containerId)
    }

    // Refresh the idle auto-lock baseline. Called periodically by the screen while audio is
    // actively playing so listening (which produces no touch events) doesn't trip the idle timer.
    fun recordInteraction() = idleMonitor.recordInteraction()
}
