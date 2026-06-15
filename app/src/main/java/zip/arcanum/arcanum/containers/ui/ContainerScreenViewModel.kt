package zip.arcanum.arcanum.containers.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.containers.domain.Container
import zip.arcanum.core.navigation.Screen
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject

@HiltViewModel
class ContainerScreenViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: ContainerRepository,
    private val cryptoEngine: VeraCryptEngine
) : ViewModel() {

    val containerId: String = savedStateHandle[Screen.ContainerScreen.ARG] ?: ""

    private val _container = MutableStateFlow<Container?>(null)
    val container = _container.asStateFlow()

    init {
        viewModelScope.launch {
            _container.value = repo.getContainerById(containerId)
        }
    }

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
