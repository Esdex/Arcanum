package zip.arcanum.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zip.arcanum.core.backup.SettingsBackup
import javax.inject.Inject

/**
 * Saving the settings to a file and reading them back (#63).
 *
 * The work is deliberately off the main thread: deriving a key from a password is meant to be
 * slow, and 600 000 rounds of PBKDF2 is about a second of it.
 */
@HiltViewModel
class BackupViewModel @Inject constructor(
    private val backup: SettingsBackup,
    @ApplicationContext private val context: Context
) : ViewModel() {

    sealed interface Result {
        data class Exported(val settings: Int, val vaults: Int, val encrypted: Boolean) : Result
        data class Imported(val settings: Int, val vaults: Int, val skipped: Int) : Result
        data object WrongPassword : Result
        data class TooNew(val format: Int) : Result
        data object Malformed : Result
        data class Failed(val message: String) : Result
    }

    data class UiState(
        val busy: Boolean = false,
        val result: Result? = null,
        /** The file waiting for a password, if the one picked turned out to be protected. */
        val pendingImport: Uri? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    /** How much there is to save, kept fresh as the switches move. */
    private val _preview = MutableStateFlow<SettingsBackup.Summary?>(null)
    val preview = _preview.asStateFlow()

    fun refreshPreview(includeVaults: Boolean) {
        viewModelScope.launch { _preview.value = backup.preview(includeVaults) }
    }

    fun clearResult() = _state.update { it.copy(result = null) }
    fun cancelPendingImport() = _state.update { it.copy(pendingImport = null) }

    fun export(target: Uri, includeVaults: Boolean, password: CharArray?) {
        _state.update { it.copy(busy = true, result = null) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val (bytes, summary) = backup.export(includeVaults, password)
                    context.contentResolver.openOutputStream(target, "wt")?.use { it.write(bytes) }
                        ?: error("could not write the file")
                    Result.Exported(
                        settings  = summary.settings,
                        vaults    = summary.vaults,
                        encrypted = password != null && password.isNotEmpty()
                    )
                }.getOrElse { Result.Failed(it.message ?: "export failed") }
            }
            password?.fill(BLANK)
            _state.update { it.copy(busy = false, result = result) }
        }
    }

    /**
     * Reads the file far enough to know whether it needs a password, and asks only then: a
     * password box in front of a file that does not want one is a question with no answer.
     */
    fun beginImport(source: Uri) {
        _state.update { it.copy(busy = true, result = null) }
        viewModelScope.launch {
            val bytes = read(source)
            if (bytes == null) {
                _state.update { it.copy(busy = false, result = Result.Malformed) }
                return@launch
            }
            when (backup.needsPassword(bytes)) {
                null  -> _state.update { it.copy(busy = false, result = Result.Malformed) }
                true  -> _state.update { it.copy(busy = false, pendingImport = source) }
                false -> restore(bytes, null)
            }
        }
    }

    fun finishImport(password: CharArray) {
        val source = _state.value.pendingImport ?: return
        _state.update { it.copy(busy = true, pendingImport = null) }
        viewModelScope.launch {
            val bytes = read(source)
            if (bytes == null) _state.update { it.copy(busy = false, result = Result.Malformed) }
            else restore(bytes, password)
        }
    }

    private suspend fun read(source: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(source)?.use { it.readBytes() } }
            .getOrNull()
    }

    private suspend fun restore(bytes: ByteArray, password: CharArray?) {
        val result = withContext(Dispatchers.IO) {
            when (val r = backup.import(bytes, password)) {
                is SettingsBackup.Restored.Success       ->
                    Result.Imported(r.settings, r.vaults, r.vaultsSkipped)
                is SettingsBackup.Restored.WrongPassword -> Result.WrongPassword
                is SettingsBackup.Restored.TooNew        -> Result.TooNew(r.format)
                is SettingsBackup.Restored.Malformed     -> Result.Malformed
            }
        }
        password?.fill(BLANK)
        _state.update { it.copy(busy = false, result = result) }
    }

    private companion object {
        /** What a password is wiped with once it has been used. */
        const val BLANK = ' '
    }
}
