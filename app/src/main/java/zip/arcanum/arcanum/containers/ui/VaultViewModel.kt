package zip.arcanum.arcanum.containers.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.biometric.BiometricPrompt
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.core.database.entities.ContainerEntity
import zip.arcanum.core.security.BiometricAuth
import zip.arcanum.core.security.BiometricCryptoManager
import zip.arcanum.crypto.CryptoResult
import zip.arcanum.crypto.VeraCryptEngine
import javax.crypto.Cipher
import javax.inject.Inject

private val Context.vaultDisplayDataStore: DataStore<Preferences>
    by preferencesDataStore(name = "vault_display_prefs")

@HiltViewModel
class VaultViewModel @Inject constructor(
    private val repo: ContainerRepository,
    private val cryptoEngine: VeraCryptEngine,
    private val biometricCryptoManager: BiometricCryptoManager,
    private val biometricAuth: BiometricAuth,
    @ApplicationContext private val context: Context
) : ViewModel() {

    enum class SortBy        { NAME, SIZE, LAST_OPENED }
    enum class SortDirection { ASCENDING, DESCENDING }
    enum class GroupBy       { NONE, LOCATION }

    data class SortState(
        val sortBy:         SortBy        = SortBy.NAME,
        val direction:      SortDirection = SortDirection.ASCENDING,
        val groupBy:        GroupBy       = GroupBy.NONE,
        val biometricFirst: Boolean       = false
    )

    private object DisplayKeys {
        val SORT_BY         = stringPreferencesKey("sort_by")
        val SORT_DIRECTION  = stringPreferencesKey("sort_direction")
        val GROUP_BY        = stringPreferencesKey("group_by")
        val BIOMETRIC_FIRST = booleanPreferencesKey("biometric_first")
    }

    private val _sortState = MutableStateFlow(SortState())
    val sortState = _sortState.asStateFlow()

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                unmountContainersOnStop(isLocked = true)
            }
        }
    }

    init {
        context.registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF))
        viewModelScope.launch {
            val prefs = context.vaultDisplayDataStore.data.first()
            _sortState.value = SortState(
                sortBy         = prefs[DisplayKeys.SORT_BY]
                                     ?.let { runCatching { SortBy.valueOf(it) }.getOrNull() }
                                     ?: SortBy.NAME,
                direction      = prefs[DisplayKeys.SORT_DIRECTION]
                                     ?.let { runCatching { SortDirection.valueOf(it) }.getOrNull() }
                                     ?: SortDirection.ASCENDING,
                groupBy        = prefs[DisplayKeys.GROUP_BY]
                                     ?.let { runCatching { GroupBy.valueOf(it) }.getOrNull() }
                                     ?: GroupBy.NONE,
                biometricFirst = prefs[DisplayKeys.BIOMETRIC_FIRST] ?: false
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        context.unregisterReceiver(screenOffReceiver)
    }

    private fun persistSortState(state: SortState) {
        viewModelScope.launch {
            context.vaultDisplayDataStore.edit { prefs ->
                prefs[DisplayKeys.SORT_BY]         = state.sortBy.name
                prefs[DisplayKeys.SORT_DIRECTION]  = state.direction.name
                prefs[DisplayKeys.GROUP_BY]        = state.groupBy.name
                prefs[DisplayKeys.BIOMETRIC_FIRST] = state.biometricFirst
            }
        }
    }

    val containers = combine(repo.getAllContainersRaw(), _sortState) { list, sort ->
        var sorted = when (sort.sortBy) {
            SortBy.NAME        -> list.sortedBy { it.name.lowercase() }
            SortBy.SIZE        -> list.sortedBy { it.size }
            SortBy.LAST_OPENED -> list.sortedBy { it.lastAccessedAt }
        }
        if (sort.direction == SortDirection.DESCENDING) sorted = sorted.reversed()
        if (sort.biometricFirst) sorted = sorted.sortedByDescending { it.hasBiometric }
        sorted
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun updateSort(sortBy: SortBy, direction: SortDirection) {
        _sortState.update { it.copy(sortBy = sortBy, direction = direction) }
        persistSortState(_sortState.value)
    }

    fun updateGroupBy(groupBy: GroupBy) {
        _sortState.update { it.copy(groupBy = groupBy) }
        persistSortState(_sortState.value)
    }

    fun toggleBiometricFirst() {
        _sortState.update { it.copy(biometricFirst = !it.biometricFirst) }
        persistSortState(_sortState.value)
    }

    sealed interface MountState {
        object Idle    : MountState
        object Loading : MountState
        data class Error(val message: String) : MountState
    }

    private val _mountState = MutableStateFlow<MountState>(MountState.Idle)
    val mountState = _mountState.asStateFlow()

    private var mountJob: Job? = null

    fun cancelMount() {
        mountJob?.cancel()
        mountJob = null
        _mountState.value = MountState.Idle
    }

    fun mountContainer(
        container: ContainerEntity,
        password: String,
        keyfilePaths: List<String> = emptyList(),
        pim: Int = 0,
        algorithm: Int = VeraCryptEngine.ALGO_AUTO,
        hashAlgorithm: Int = VeraCryptEngine.HASH_AUTO,
        protectHiddenPassword: String? = null,
        onSuccess: (containerId: String) -> Unit
    ) {
        mountJob = viewModelScope.launch {
            _mountState.value = MountState.Loading
            val result = cryptoEngine.mountContainer(
                path = container.path, password = password,
                keyfilePaths = keyfilePaths, pim = pim,
                algorithm = algorithm, hashAlgorithm = hashAlgorithm
            )
            when (result) {
                is CryptoResult.Success -> {
                    val isHidden  = cryptoEngine.getVolumeType(result.value) == 1
                    val hasHidden = !protectHiddenPassword.isNullOrBlank()
                    repo.mountContainer(container.id, result.value, pim,
                        isHidden = isHidden, hasHidden = hasHidden)
                    val algId = cryptoEngine.nativeGetAlgorithmId(result.value)
                    if (algId >= 0) {
                        repo.updateAlgorithm(
                            container.id,
                            VeraCryptEngine.algorithmIdToString(algId)
                        )
                    }
                    val hashId = cryptoEngine.nativeGetHashId(result.value)
                    if (hashId >= 0) {
                        repo.updatePrf(
                            container.id,
                            VeraCryptEngine.hashIdToString(hashId)
                        )
                    }
                    val fsType = cryptoEngine.nativeGetFilesystem(result.value)
                    if (fsType >= 0) {
                        repo.updateFilesystem(
                            container.id,
                            VeraCryptEngine.filesystemIdToString(fsType)
                        )
                    }
                    _mountState.value = MountState.Idle
                    onSuccess(container.id)
                }
                is CryptoResult.Failure -> {
                    _mountState.value = MountState.Error("Wrong password")
                }
            }
        }
    }

    fun resetMountState() { _mountState.value = MountState.Idle }

    // ── Add-vault result ───────────────────────────────────────────────

    sealed interface AddVaultResult {
        data class Added(val fileName: String)         : AddVaultResult
        data class AlreadyExists(val fileName: String) : AddVaultResult
        data object InvalidFile                        : AddVaultResult
        data class Error(val message: String)          : AddVaultResult
    }

    private val _addVaultResult = MutableStateFlow<AddVaultResult?>(null)
    val addVaultResult = _addVaultResult.asStateFlow()

    fun addContainerFromPath(path: String) {
        viewModelScope.launch {
            val file = java.io.File(path)
            val size = file.length()
            if (!file.exists() || size < 131072L || size % 512 != 0L) {
                _addVaultResult.value = AddVaultResult.InvalidFile
                return@launch
            }
            if (repo.containsPath(path)) {
                _addVaultResult.value = AddVaultResult.AlreadyExists(file.name)
                return@launch
            }
            try {
                repo.addContainerFromPath(path)
                _addVaultResult.value = AddVaultResult.Added(file.name)
            } catch (e: Exception) {
                _addVaultResult.value = AddVaultResult.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun clearAddVaultResult() { _addVaultResult.value = null }

    // ── Bulk delete ────────────────────────────────────────────────────

    fun unmountContainer(id: String, onDone: () -> Unit) {
        viewModelScope.launch {
            val handle = repo.getContainerHandle(id)
            if (handle != null) cryptoEngine.unmountContainer(handle)
            repo.unmountContainer(id)
            onDone()
        }
    }

    fun deleteContainers(ids: Set<String>) {
        viewModelScope.launch {
            ids.forEach { id ->
                val handle = repo.getContainerHandle(id)
                if (handle != null) cryptoEngine.unmountContainer(handle)
            }
            repo.deleteContainersById(ids)
        }
    }

    fun updateUnmountOnLock(id: String, value: Boolean) {
        viewModelScope.launch { repo.updateUnmountOnLock(id, value) }
    }

    fun updateUnmountOnBackground(id: String, value: Boolean) {
        viewModelScope.launch { repo.updateUnmountOnBackground(id, value) }
    }

    fun unmountContainersOnStop(isLocked: Boolean) {
        viewModelScope.launch {
            repo.getAllContainersRaw().first().filter { it.isMounted }.forEach { c ->
                if (c.unmountOnBackground || (isLocked && c.unmountOnLock)) {
                    val handle = repo.getContainerHandle(c.id)
                    if (handle != null) cryptoEngine.unmountContainer(handle)
                    repo.unmountContainer(c.id)
                }
            }
        }
    }

    fun removeFromList(id: String) {
        viewModelScope.launch {
            val handle = repo.getContainerHandle(id)
            if (handle != null) cryptoEngine.unmountContainer(handle)
            repo.deleteContainersById(setOf(id))
        }
    }

    // ── Biometric ──────────────────────────────────────────────────────

    fun isBiometricAvailable(): Boolean = biometricAuth.isAvailable()

    fun hasBiometricCredentials(containerId: String): Boolean =
        biometricCryptoManager.hasSavedCredentials(containerId)

    fun getBiometricCryptoObjectForEncrypt(): BiometricPrompt.CryptoObject? =
        try { biometricCryptoManager.getCryptoObjectForEncrypt() } catch (_: Exception) { null }

    fun getBiometricCryptoObjectForDecrypt(containerId: String): BiometricPrompt.CryptoObject? =
        biometricCryptoManager.getCryptoObjectForDecrypt(containerId)

    fun saveBiometricCredentials(containerId: String, cipher: Cipher, password: String, pim: Int) {
        biometricCryptoManager.saveEncryptedCredentials(containerId, cipher, password, pim)
        viewModelScope.launch { repo.updateBiometric(containerId, true) }
    }

    fun decryptBiometricCredentials(containerId: String, cipher: Cipher): Pair<String, Int>? =
        biometricCryptoManager.loadDecryptedCredentials(containerId, cipher)

    fun deleteBiometricCredentials(containerId: String) {
        biometricCryptoManager.deleteCredentials(containerId)
        viewModelScope.launch { repo.updateBiometric(containerId, false) }
    }

    // ── Delete vault file ──────────────────────────────────────────────

    fun deleteVaultFile(id: String) {
        viewModelScope.launch {
            val handle = repo.getContainerHandle(id)
            if (handle != null) cryptoEngine.unmountContainer(handle)
            val container = repo.getContainerById(id)
            repo.deleteContainersById(setOf(id))
            container?.path?.let { java.io.File(it).delete() }
        }
    }
}
