package zip.arcanum.arcanum.backup

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import zip.arcanum.core.database.dao.ContainerDao
import zip.arcanum.core.database.entities.ContainerEntity
import zip.arcanum.core.navigation.Screen
import zip.arcanum.R
import zip.arcanum.core.security.BiometricAuth
import javax.inject.Inject

data class BackupUiState(
    val container: ContainerEntity? = null,
    val settings: BackupSettings = BackupSettings(),
    val lastRecord: BackupRecord? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val hasUnsavedChanges: Boolean = false,
    val secretsUnlocked: Boolean = true,
    val message: String? = null,
    val error: String? = null
) {
    val canStart: Boolean
        get() = container != null && settings.hasUsableDestination() && !hasUnsavedChanges
}

@HiltViewModel
class BackupViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val containerDao: ContainerDao,
    private val settingsRepository: BackupSettingsRepository,
    private val localUploader: LocalFolderBackupUploader,
    private val s3Uploader: S3BackupUploader,
    private val megaUploader: MegaBackupUploader,
    private val biometricAuth: BiometricAuth,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val containerId: String = savedStateHandle[Screen.Backup.ARG] ?: ""
    private val _state = MutableStateFlow(BackupUiState())
    val state: StateFlow<BackupUiState> = _state.asStateFlow()
    val progress: StateFlow<BackupProgressState> = BackupService.progress
        .map { current ->
            if (current.containerId == containerId) current else BackupProgressState()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BackupProgressState())

    init {
        reload()
    }

    fun reload() {
        viewModelScope.launch {
            val container = containerDao.getContainerById(containerId)
            val settings = settingsRepository.loadSettings(containerId)
            val lastRecord = settingsRepository.loadLastRecord(containerId, settings.provider)
            _state.value = BackupUiState(
                container = container,
                settings = settings,
                lastRecord = lastRecord,
                isLoading = false,
                secretsUnlocked = !settings.hasSensitiveCredentials()
            )
        }
    }

    fun updateSettings(transform: (BackupSettings) -> BackupSettings) {
        _state.value = _state.value.copy(
            settings = transform(_state.value.settings),
            hasUnsavedChanges = true,
            message = null,
            error = null
        )
        viewModelScope.launch {
            val provider = _state.value.settings.provider
            val lastRecord = settingsRepository.loadLastRecord(containerId, provider)
            _state.value = _state.value.copy(lastRecord = lastRecord)
        }
    }

    fun onLocalFolderSelected(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        updateSettings { it.copy(provider = BackupProvider.LOCAL, localFolderUri = uri.toString()) }
    }

    fun save() {
        viewModelScope.launch {
            val settings = _state.value.settings
            _state.value = _state.value.copy(isSaving = true, error = null, message = null)
            try {
                uploaderFor(settings.provider).validate(settings)
                settingsRepository.saveSettings(containerId, settings)
                _state.value = _state.value.copy(
                    isSaving = false,
                    hasUnsavedChanges = false,
                    secretsUnlocked = true,
                    message = context.getString(R.string.backup_settings_saved),
                    error = null
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(
                    isSaving = false,
                    error = t.userMessage(context),
                    message = null
                )
            }
        }
    }

    fun startBackup() {
        val current = _state.value
        if (!current.settings.hasUsableDestination()) {
            _state.value = current.copy(error = context.getString(R.string.backup_error_save_valid_settings))
            return
        }
        if (current.hasUnsavedChanges) {
            _state.value = current.copy(error = context.getString(R.string.backup_error_save_new_settings))
            return
        }
        BackupService.start(context, containerId)
    }

    fun cancelBackup() {
        if (BackupService.progress.value.containerId == containerId) {
            BackupService.cancel(context)
        }
    }

    fun unlockCredentialEditing(activity: FragmentActivity?) {
        if (activity == null) {
            _state.value = _state.value.copy(error = context.getString(R.string.backup_error_auth_open))
            return
        }
        if (!biometricAuth.isAvailable() && !biometricAuth.hasDeviceLock()) {
            _state.value = _state.value.copy(error = context.getString(R.string.backup_error_device_lock_required))
            return
        }
        biometricAuth.authenticateWithDeviceLock(
            activity = activity,
            title = context.getString(R.string.backup_auth_title),
            subtitle = context.getString(R.string.backup_auth_subtitle),
            onSuccess = {
                _state.value = _state.value.copy(secretsUnlocked = true, error = null)
            },
            onError = { _, err ->
                _state.value = _state.value.copy(error = err.toString())
            }
        )
    }

    private fun uploaderFor(provider: BackupProvider): BackupUploader = when (provider) {
        BackupProvider.LOCAL -> localUploader
        BackupProvider.S3    -> s3Uploader
        BackupProvider.MEGA  -> megaUploader
    }
}
