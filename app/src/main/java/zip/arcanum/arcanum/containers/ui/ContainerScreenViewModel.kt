package zip.arcanum.arcanum.containers.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.containers.domain.Container
import zip.arcanum.core.navigation.Screen
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.security.AppPreferences
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject

@HiltViewModel
class ContainerScreenViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: ContainerRepository,
    private val cryptoEngine: VeraCryptEngine,
    appPreferences: AppPreferences
) : ViewModel() {

    val containerId: String = savedStateHandle[Screen.ContainerScreen.ARG] ?: ""

    private val _container = MutableStateFlow<Container?>(null)
    val container = _container.asStateFlow()

    /** Route of the tab to open on. `null` until the preference is loaded. */
    val defaultTabRoute = appPreferences.defaultContainerTab
        .map { it.route }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Raised when the vault's ext4 superblock says the last session that wrote to it
     * did not finish - the app killed, the battery gone, a drive pulled (#142).
     *
     * Read here rather than at mount because this is the screen the user lands on;
     * the value itself was recorded when the volume was opened and does not change
     * while it stays open, so asking late still answers about the session that was
     * interrupted. Always null for FAT and exFAT, which keep no such flag.
     */
    private val _needsCheckNotice = MutableStateFlow<InAppNotification?>(null)
    val needsCheckNotice = _needsCheckNotice.asStateFlow()

    init {
        viewModelScope.launch {
            _container.value = repo.getContainerById(containerId)

            val handle = repo.getContainerHandle(containerId)
            if (handle != null && withContext(Dispatchers.IO) {
                    cryptoEngine.ext4NeedsCheck(handle)
                }) {
                _needsCheckNotice.value = InAppNotification.VaultNeedsCheck
            }
        }
    }

    fun clearNeedsCheckNotice() { _needsCheckNotice.value = null }

    fun unmount(onDone: () -> Unit) {
        viewModelScope.launch {
            val handle = repo.getContainerHandle(containerId)
            if (handle != null) {
                cryptoEngine.unmountContainer(handle)
            }
            repo.unmountContainer(containerId)
            onDone()
        }
    }
}
