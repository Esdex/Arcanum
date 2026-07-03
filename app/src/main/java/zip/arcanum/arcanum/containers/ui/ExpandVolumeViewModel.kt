package zip.arcanum.arcanum.containers.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import zip.arcanum.R
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.containers.service.ExpandVolumeParams
import zip.arcanum.arcanum.containers.service.ExpandVolumeService
import zip.arcanum.arcanum.containers.service.ExpandVolumeSpaceGuard
import zip.arcanum.arcanum.containers.service.ExpandVolumeStrategy
import zip.arcanum.core.database.entities.ContainerEntity
import zip.arcanum.core.navigation.Screen
import zip.arcanum.core.utils.FileUtils
import java.io.File
import javax.inject.Inject

private const val BYTES_IN_MB = 1024L * 1024L
private const val EXPAND_ENTROPY_REQUIRED = 500

data class ExpandVolumeState(
    val container: ContainerEntity? = null,
    val targetSizeInput: String = "",
    val targetUnitGb: Boolean = true,
    val strategy: ExpandVolumeStrategy = ExpandVolumeStrategy.SAFE_REBUILD,
    val password: String = "",
    val pim: String = "",
    val keyfilePaths: List<String> = emptyList(),
    val keyfileDisplayNames: List<String> = emptyList(),
    val includeHidden: Boolean = false,
    val hiddenPassword: String = "",
    val hiddenPim: String = "",
    val hiddenTargetSizeInput: String = "",
    val hiddenTargetUnitGb: Boolean = false,
    val hiddenKeyfilePaths: List<String> = emptyList(),
    val hiddenKeyfileDisplayNames: List<String> = emptyList(),
    val progressFraction: Float = 0f,
    val progressMessage: String = "",
    val entropyPoints: Int = 0,
    val isRunning: Boolean = false,
    val isSuccess: Boolean = false,
    val error: String? = null
) {
    val targetDataSizeBytes: Long
        get() = parseSize(targetSizeInput, targetUnitGb)

    val hiddenTargetDataSizeBytes: Long
        get() = parseSize(hiddenTargetSizeInput, hiddenTargetUnitGb)

    private fun parseSize(value: String, gb: Boolean): Long {
        val raw = value.toLongOrNull() ?: return 0L
        val mb = if (gb) raw * 1024L else raw
        return mb * BYTES_IN_MB
    }
}

@HiltViewModel
class ExpandVolumeViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repo: ContainerRepository,
    private val paramsStore: ExpandVolumeParams,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val containerId: String = savedStateHandle[Screen.ExpandVolume.ARG] ?: ""
    private val _state = MutableStateFlow(ExpandVolumeState())
    val state = _state.asStateFlow()

    private var serviceOwnsKeyfiles = false
    private var progressJob: Job? = null
    private val entropyBuffer = ByteArray(EXPAND_ENTROPY_REQUIRED * 2)
    private var entropyIndex = 0

    init {
        viewModelScope.launch {
            val entity = repo.getAllContainersRaw()
                .first()
                .firstOrNull { it.id == containerId }
            if (entity != null) {
                val defaultTargetGb = ((entity.size + 1024L * BYTES_IN_MB - 1L) / (1024L * BYTES_IN_MB) + 1L)
                    .coerceAtLeast(1L)
                _state.update {
                    it.copy(
                        container = entity,
                        targetSizeInput = defaultTargetGb.toString(),
                        hiddenTargetSizeInput = "100"
                    )
                }
            }
        }
    }

    fun updateTarget(value: String) {
        if (value.all { it.isDigit() } && value.length <= 7) {
            _state.update { it.copy(targetSizeInput = value, error = null) }
        }
    }

    fun setTargetUnitGb(value: Boolean) =
        _state.update { it.copy(targetUnitGb = value) }

    fun updatePassword(value: String) =
        _state.update { it.copy(password = value, error = null) }

    fun updatePim(value: String) {
        if (value.all { it.isDigit() } && value.length <= 4) {
            _state.update { it.copy(pim = value, error = null) }
        }
    }

    fun setIncludeHidden(value: Boolean) =
        _state.update { it.copy(includeHidden = value, error = null) }

    fun updateHiddenPassword(value: String) =
        _state.update { it.copy(hiddenPassword = value, error = null) }

    fun updateHiddenPim(value: String) {
        if (value.all { it.isDigit() } && value.length <= 4) {
            _state.update { it.copy(hiddenPim = value, error = null) }
        }
    }

    fun updateHiddenTarget(value: String) {
        if (value.all { it.isDigit() } && value.length <= 7) {
            _state.update { it.copy(hiddenTargetSizeInput = value, error = null) }
        }
    }

    fun setHiddenTargetUnitGb(value: Boolean) =
        _state.update { it.copy(hiddenTargetUnitGb = value) }

    fun addKeyfile(path: String, displayName: String) =
        _state.update { it.copy(
            keyfilePaths = it.keyfilePaths + path,
            keyfileDisplayNames = it.keyfileDisplayNames + displayName
        ) }

    fun addHiddenKeyfile(path: String, displayName: String) =
        _state.update { it.copy(
            hiddenKeyfilePaths = it.hiddenKeyfilePaths + path,
            hiddenKeyfileDisplayNames = it.hiddenKeyfileDisplayNames + displayName
        ) }

    fun removeKeyfile(index: Int) {
        val s = _state.value
        if (index !in s.keyfilePaths.indices) return
        FileUtils.secureZeroAndDelete(File(s.keyfilePaths[index]))
        _state.update {
            it.copy(
                keyfilePaths = it.keyfilePaths.filterIndexed { i, _ -> i != index },
                keyfileDisplayNames = it.keyfileDisplayNames.filterIndexed { i, _ -> i != index }
            )
        }
    }

    fun removeHiddenKeyfile(index: Int) {
        val s = _state.value
        if (index !in s.hiddenKeyfilePaths.indices) return
        FileUtils.secureZeroAndDelete(File(s.hiddenKeyfilePaths[index]))
        _state.update {
            it.copy(
                hiddenKeyfilePaths = it.hiddenKeyfilePaths.filterIndexed { i, _ -> i != index },
                hiddenKeyfileDisplayNames = it.hiddenKeyfileDisplayNames.filterIndexed { i, _ -> i != index }
            )
        }
    }

    fun addEntropyPoint(x: Int, y: Int) {
        if (entropyIndex >= entropyBuffer.size) return
        entropyBuffer[entropyIndex++] = x.toByte()
        entropyBuffer[entropyIndex++] = y.toByte()
        _state.update { it.copy(entropyPoints = entropyIndex / 2, error = null) }
    }

    fun start() {
        val s = _state.value
        val container = s.container ?: return
        if (s.isRunning) return
        if (container.isMounted || repo.getContainerHandle(container.id) != null) {
            _state.update { it.copy(error = context.getString(R.string.expand_error_unmount_first)) }
            return
        }
        if (s.password.isBlank()) {
            _state.update { it.copy(error = context.getString(R.string.expand_error_password_required)) }
            return
        }
        if (s.targetDataSizeBytes <= 0L) {
            _state.update { it.copy(error = context.getString(R.string.expand_error_target_required)) }
            return
        }
        if (s.includeHidden && s.hiddenPassword.isBlank()) {
            _state.update { it.copy(error = context.getString(R.string.expand_error_hidden_required)) }
            return
        }
        if (s.entropyPoints < EXPAND_ENTROPY_REQUIRED) {
            _state.update { it.copy(error = context.getString(R.string.expand_error_entropy_required)) }
            return
        }
        val pfd = if (container.safUri.isNotBlank()) {
            try {
                context.contentResolver.openFileDescriptor(Uri.parse(container.safUri), "rw")
            } catch (_: Exception) {
                null
            }
        } else null
        if (container.safUri.isNotBlank() && pfd == null) {
            _state.update { it.copy(error = context.getString(R.string.expand_error_io)) }
            return
        }
        val spaceError = validateFreeSpace(container, s.targetDataSizeBytes, pfd)
        if (spaceError != null) {
            pfd?.close()
            _state.update { it.copy(error = spaceError) }
            return
        }

        ExpandVolumeService.resetProgress()
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            ExpandVolumeService.progress.filterNotNull().collect { progress ->
                _state.update {
                    it.copy(
                        progressFraction = progress.fraction,
                        progressMessage = progress.message,
                        isRunning = !progress.isComplete,
                        isSuccess = progress.isComplete && progress.error == null,
                        error = progress.error
                    )
                }
            }
        }

        paramsStore.set(
            ExpandVolumeParams.Params(
                containerId = container.id,
                path = container.path,
                safUri = container.safUri,
                safFd = pfd?.fd ?: -1,
                safPfd = pfd,
                targetDataSizeBytes = s.targetDataSizeBytes,
                strategy = ExpandVolumeStrategy.SAFE_REBUILD,
                password = s.password,
                keyfilePaths = s.keyfilePaths,
                pim = s.pim.toIntOrNull() ?: 0,
                entropyBytes = entropyBuffer.copyOf(entropyIndex),
                includeHidden = s.includeHidden,
                hiddenPassword = s.hiddenPassword,
                hiddenKeyfilePaths = s.hiddenKeyfilePaths,
                hiddenPim = s.hiddenPim.toIntOrNull() ?: 0,
                hiddenTargetDataSizeBytes = s.hiddenTargetDataSizeBytes
            )
        )
        serviceOwnsKeyfiles = true
        _state.update { it.copy(isRunning = true, error = null, progressFraction = 0f, progressMessage = "") }

        try {
            context.startForegroundService(Intent(context, ExpandVolumeService::class.java))
        } catch (e: Exception) {
            paramsStore.take()?.let { residual ->
                residual.entropyBytes.fill(0)
                residual.safPfd?.close()
            }
            cleanupKeyfiles(s)
            serviceOwnsKeyfiles = false
            _state.update { it.copy(isRunning = false, error = e.message ?: context.getString(R.string.expand_error_unknown)) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        entropyBuffer.fill(0)
        if (!serviceOwnsKeyfiles) cleanupKeyfiles(_state.value)
        progressJob?.cancel()
    }

    private fun cleanupKeyfiles(s: ExpandVolumeState) {
        s.keyfilePaths.forEach { FileUtils.secureZeroAndDelete(File(it)) }
        s.hiddenKeyfilePaths.forEach { FileUtils.secureZeroAndDelete(File(it)) }
    }

    private fun validateFreeSpace(
        container: ContainerEntity,
        targetDataSizeBytes: Long,
        pfd: android.os.ParcelFileDescriptor?
    ): String? {
        val requiredScratch = ExpandVolumeSpaceGuard.requiredScratchBytes(targetDataSizeBytes)
        if (container.safUri.isBlank()) {
            val free = ExpandVolumeSpaceGuard.usableSpaceForFileTarget(container.path, context.filesDir)
            return if (free < requiredScratch) formatSpaceError(requiredScratch, free) else null
        }

        val cacheFree = context.cacheDir.usableSpace
        if (cacheFree < requiredScratch) return formatSpaceError(requiredScratch, cacheFree)

        val sourcePfd = pfd ?: return context.getString(R.string.expand_error_free_space_unknown)
        val providerFree = ExpandVolumeSpaceGuard.usableSpaceForFd(sourcePfd)
            ?: return context.getString(R.string.expand_error_free_space_unknown)
        val currentFileSize = sourcePfd.statSize.takeIf { it > 0L } ?: container.size
        val availableAfterReplacing = ExpandVolumeSpaceGuard.addSaturating(providerFree, currentFileSize)
        val requiredFinal = ExpandVolumeSpaceGuard.requiredFinalBytes(targetDataSizeBytes)
        return if (availableAfterReplacing < requiredFinal) {
            formatSpaceError(requiredFinal, availableAfterReplacing)
        } else null
    }

    private fun formatSpaceError(required: Long, available: Long): String =
        context.getString(
            R.string.expand_error_free_space_required,
            FileUtils.getHumanReadableSize(required),
            FileUtils.getHumanReadableSize(available)
        )
}
