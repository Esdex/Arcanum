package zip.arcanum.arcanum.share

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.core.database.dao.ContainerDao
import zip.arcanum.crypto.VeraCryptEngine
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ShareTargetViewModel @Inject constructor(
    private val shareIntake: ShareIntake,
    private val repo: ContainerRepository,
    private val dao: ContainerDao,
    private val engine: VeraCryptEngine,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    data class VaultOption(val id: String, val name: String)

    data class State(
        val vaults: List<VaultOption> = emptyList(),
        val selectedVaultId: String? = null,
        val currentPath: String = "/",
        val directories: List<String> = emptyList(),
        val fileCount: Int = 0,
        val isSaving: Boolean = false,
        val savedCount: Int? = null,   // non-null once the import finishes
        val error: String? = null
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val pendingUris: List<Uri> get() = shareIntake.pending.value

    init {
        _state.update { it.copy(fileCount = pendingUris.size) }
        loadVaults()
    }

    private fun loadVaults() {
        viewModelScope.launch {
            val mounted = repo.mountedContainerIds.value
            val options = dao.getAllContainersOnce()
                .filter { it.externalAccessEnabled && it.id in mounted && !repo.isContainerReadOnly(it.id) }
                .map { VaultOption(it.id, it.name) }
            _state.update { it.copy(vaults = options) }
            if (options.size == 1) selectVault(options.first().id)
        }
    }

    fun selectVault(id: String) {
        _state.update { it.copy(selectedVaultId = id) }
        loadDirectory("/")
    }

    fun clearVault() {
        _state.update { it.copy(selectedVaultId = null, currentPath = "/", directories = emptyList()) }
    }

    fun loadDirectory(path: String) {
        val cid = _state.value.selectedVaultId ?: return
        val handle = repo.getContainerHandle(cid) ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val dirs = (engine.listFilesOrNull(handle, path) ?: emptyArray())
                .filter { it.isDirectory }
                .map { it.name }
                .sortedBy { it.lowercase() }
            _state.update { it.copy(currentPath = path, directories = dirs) }
        }
    }

    fun enterDirectory(name: String) {
        val base = _state.value.currentPath
        loadDirectory(if (base == "/") "/$name" else "$base/$name")
    }

    fun navigateUp() {
        val current = _state.value.currentPath
        if (current == "/") return
        loadDirectory(current.substringBeforeLast('/').ifEmpty { "/" })
    }

    fun saveHere() {
        val s = _state.value
        val cid = s.selectedVaultId ?: return
        val handle = repo.getContainerHandle(cid) ?: return
        if (repo.isContainerReadOnly(cid)) {
            _state.update { it.copy(error = "Vault is read-only") }
            return
        }
        val uris = pendingUris
        if (uris.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isSaving = true, error = null) }
            var saved = 0
            val existing = (engine.listFilesOrNull(handle, s.currentPath) ?: emptyArray()).map { it.name }.toMutableSet()
            for (uri in uris) {
                val rawName = fileNameOf(uri) ?: continue
                val name = uniqueName(File(rawName).name.ifEmpty { "file" }, existing)
                existing += name
                val destPath = if (s.currentPath == "/") "/$name" else "${s.currentPath}/$name"
                val ok = writeUriToVault(handle, uri, destPath)
                if (ok) saved++ else runCatching { engine.deleteFile(handle, destPath) }
            }
            shareIntake.clear()
            _state.update { it.copy(isSaving = false, savedCount = saved) }
        }
    }

    private fun writeUriToVault(handle: Long, uri: Uri, destPath: String): Boolean {
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                val buffer = ByteArray(1 * 1024 * 1024)
                var offset = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                    if (engine.writeFile(handle, destPath, chunk, offset) != VeraCryptEngine.ERR_OK)
                        return false
                    offset += read
                }
                true
            } ?: false
        }.getOrDefault(false)
    }

    private fun fileNameOf(uri: Uri): String? {
        appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { return it } }
        return uri.lastPathSegment
    }

    private fun uniqueName(desired: String, existing: Set<String>): String {
        if (desired !in existing) return desired
        val dot = desired.lastIndexOf('.')
        val base = if (dot > 0) desired.substring(0, dot) else desired
        val ext  = if (dot > 0) desired.substring(dot) else ""
        var i = 1
        while ("$base ($i)$ext" in existing) i++
        return "$base ($i)$ext"
    }
}
