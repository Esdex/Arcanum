package zip.arcanum.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import zip.arcanum.core.database.dao.ContainerDao
import zip.arcanum.core.database.entities.ContainerEntity
import zip.arcanum.core.security.PanicManager
import zip.arcanum.core.security.PanicSettings
import zip.arcanum.core.security.PinManager
import zip.arcanum.core.security.VaultPanicAction
import javax.inject.Inject

@HiltViewModel
class PanicModeViewModel @Inject constructor(
    private val panicManager: PanicManager,
    private val pinManager: PinManager,
    private val containerDao: ContainerDao
) : ViewModel() {

    val settings: StateFlow<PanicSettings> = panicManager.panicSettingsFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, PanicSettings())

    val containers: StateFlow<List<ContainerEntity>> = containerDao.getAllContainers()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isPanicPinSet: StateFlow<Boolean> = pinManager.isPanicPinSetFlow
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun setEnabled(enabled: Boolean) {
        viewModelScope.launch {
            panicManager.savePanicSettings(settings.value.copy(enabled = enabled))
        }
    }

    fun disablePanicMode() {
        viewModelScope.launch { panicManager.resetPanicMode() }
    }

    fun setFullWipe(v: Boolean) = save { copy(fullWipe = v) }
    fun setClearSettings(v: Boolean) = save { copy(clearSettings = v) }
    fun setClearHistory(v: Boolean) = save { copy(clearCalculatorHistory = v) }
    fun setDisableBiometric(v: Boolean) = save { copy(disableBiometric = v) }

    fun setVaultAction(containerId: String, action: VaultPanicAction) {
        save { copy(vaultActions = vaultActions + (containerId to action)) }
    }

    private fun save(transform: PanicSettings.() -> PanicSettings) {
        viewModelScope.launch { panicManager.savePanicSettings(settings.value.transform()) }
    }
}
