package zip.arcanum.arcanum.files.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import zip.arcanum.R
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.files.domain.PreparedShareFiles
import zip.arcanum.arcanum.files.domain.VaultFileTransfer
import zip.arcanum.arcanum.files.domain.VaultTransferItem
import zip.arcanum.core.components.AppDialog
import zip.arcanum.core.lifecycle.ExternalActivityGuard
import zip.arcanum.core.navigation.Screen
import zip.arcanum.core.security.AppPreferences
import zip.arcanum.crypto.VeraCryptEngine
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext

private const val MAX_TEXT_FILE_BYTES = 5 * 1024 * 1024

enum class TextEditorError {
    NOT_MOUNTED,
    LOAD_FAILED,
    SAVE_FAILED,
    TOO_LARGE
}

data class TextEditorState(
    val fileName: String = "",
    val text: String = "",
    val originalText: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val error: TextEditorError? = null,
    val sharePayload: PreparedShareFiles? = null,
    val exportDone: Boolean = false,
    val exportDeleted: Boolean = false
) {
    val isDirty: Boolean get() = text != originalText
}

@HiltViewModel
class TextEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val engine: VeraCryptEngine,
    private val repo: ContainerRepository,
    private val appPrefs: AppPreferences,
    private val externalActivityGuard: ExternalActivityGuard
) : ViewModel() {
    private val containerId: String = savedStateHandle[Screen.TextEditor.ARG_CONTAINER] ?: ""
    private val path: String = "/" + (savedStateHandle.get<String>(Screen.TextEditor.ARG_PATH) ?: "").trimStart('/')
    private val routeName: String = savedStateHandle[Screen.TextEditor.ARG_NAME] ?: path.substringAfterLast("/")

    private val _state = MutableStateFlow(TextEditorState(fileName = routeName))
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun updateText(value: String) {
        _state.update { it.copy(text = value, error = null) }
    }

    fun save() {
        val current = _state.value
        val handle = repo.getContainerHandle(containerId) ?: run {
            _state.update { it.copy(error = TextEditorError.NOT_MOUNTED, isSaving = false) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isSaving = true, error = null) }
            val bytes = current.text.toByteArray(Charsets.UTF_8)
            val saved = writeBytes(handle, path, bytes)
            if (saved) {
                runCatching { engine.nativeSetModifiedTime(handle, path, System.currentTimeMillis()) }
            }
            _state.update {
                if (saved) {
                    it.copy(originalText = current.text, isSaving = false, error = null)
                } else {
                    it.copy(isSaving = false, error = TextEditorError.SAVE_FAILED)
                }
            }
        }
    }

    fun beginExternalActivity() {
        externalActivityGuard.begin()
    }

    fun finishExternalActivity() {
        externalActivityGuard.end()
    }

    fun prepareShare() {
        val handle = repo.getContainerHandle(containerId) ?: run {
            _state.update { it.copy(error = TextEditorError.NOT_MOUNTED) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val item = currentTransferItem(handle) ?: run {
                _state.update { it.copy(error = TextEditorError.LOAD_FAILED) }
                return@launch
            }
            val payload = VaultFileTransfer.prepareShareFiles(context, engine, handle, listOf(item))
            _state.update {
                if (payload == null) it.copy(error = TextEditorError.LOAD_FAILED)
                else it.copy(sharePayload = payload, error = null)
            }
        }
    }

    fun decryptToTree(treeUri: android.net.Uri) {
        val handle = repo.getContainerHandle(containerId) ?: run {
            _state.update { it.copy(error = TextEditorError.NOT_MOUNTED) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val item = currentTransferItem(handle) ?: run {
                _state.update { it.copy(error = TextEditorError.LOAD_FAILED) }
                return@launch
            }
            val root = VaultFileTransfer.documentTreeRoot(context, treeUri) ?: run {
                _state.update { it.copy(error = TextEditorError.SAVE_FAILED) }
                return@launch
            }
            val exported = VaultFileTransfer.exportItemToDirectory(context, engine, handle, item, root)
            if (!exported) {
                _state.update { it.copy(error = TextEditorError.SAVE_FAILED) }
                return@launch
            }
            val deleteAfterExport = appPrefs.deleteExportedFiles.first()
            if (deleteAfterExport) {
                val deleted = runCatching { engine.nativeDeleteFile(handle, path) == VeraCryptEngine.ERR_OK }
                    .getOrDefault(false)
                _state.update { it.copy(exportDone = true, exportDeleted = deleted) }
            } else {
                _state.update { it.copy(exportDone = true, exportDeleted = false) }
            }
        }
    }

    fun clearSharePayload() {
        _state.update { it.copy(sharePayload = null) }
    }

    fun clearExportDone() {
        _state.update { it.copy(exportDone = false, exportDeleted = false) }
    }

    private fun load() {
        val handle = repo.getContainerHandle(containerId) ?: run {
            _state.update { it.copy(isLoading = false, error = TextEditorError.NOT_MOUNTED) }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = runCatching {
                val size = findFileSize(handle, path) ?: return@runCatching null
                if (size > MAX_TEXT_FILE_BYTES) {
                    _state.update { it.copy(isLoading = false, error = TextEditorError.TOO_LARGE) }
                    return@launch
                }
                readText(handle, path, size)
            }.getOrNull()

            _state.update {
                if (loaded == null) {
                    it.copy(isLoading = false, error = TextEditorError.LOAD_FAILED)
                } else {
                    it.copy(text = loaded, originalText = loaded, isLoading = false, error = null)
                }
            }
        }
    }

    private fun readText(handle: Long, path: String, size: Long): String? {
        if (size == 0L) return ""
        val out = ByteArrayOutputStream(size.coerceAtMost(MAX_TEXT_FILE_BYTES.toLong()).toInt())
        var offset = 0L
        val chunkSize = 256 * 1024
        while (offset < size) {
            val toRead = minOf(chunkSize.toLong(), size - offset).toInt()
            val chunk = engine.nativeReadFile(handle, path, offset, toRead) ?: return null
            if (chunk.isEmpty()) return null
            out.write(chunk)
            offset += chunk.size
        }
        return out.toByteArray().toString(Charsets.UTF_8)
    }

    private fun writeBytes(handle: Long, path: String, bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) {
            return runCatching {
                engine.nativeWriteFile(handle, path, ByteArray(0), 0L) == VeraCryptEngine.ERR_OK
            }.getOrDefault(false)
        }

        var offset = 0
        val chunkSize = 256 * 1024
        while (offset < bytes.size) {
            val end = minOf(bytes.size, offset + chunkSize)
            val rc = runCatching {
                engine.nativeWriteFile(handle, path, bytes.copyOfRange(offset, end), offset.toLong())
            }.getOrDefault(VeraCryptEngine.ERR_FS)
            if (rc != VeraCryptEngine.ERR_OK) return false
            offset = end
        }
        return true
    }

    private fun findFileSize(handle: Long, filePath: String): Long? {
        val parent = filePath.substringBeforeLast("/", "").ifBlank { "/" }
        val name = filePath.substringAfterLast("/")
        return runCatching {
            engine.nativeListFiles(handle, parent).firstOrNull {
                it.path == filePath || it.name == name
            }?.size
        }.getOrNull()
    }

    private fun currentTransferItem(handle: Long): VaultTransferItem? {
        val size = findFileSize(handle, path) ?: return null
        return VaultTransferItem(
            path = path,
            name = routeName,
            size = size,
            isDirectory = false,
            lastModified = System.currentTimeMillis()
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TextEditorScreen(
    onBack: () -> Unit,
    viewModel: TextEditorViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    var showDiscardConfirm by remember { mutableStateOf(false) }

    val decryptLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        viewModel.finishExternalActivity()
        if (uri != null) viewModel.decryptToTree(uri)
    }

    fun launchDecryptPicker() {
        viewModel.beginExternalActivity()
        runCatching { decryptLauncher.launch(null) }
            .onFailure { viewModel.finishExternalActivity() }
    }

    LaunchedEffect(state.sharePayload) {
        state.sharePayload?.let { payload ->
            launchShareIntent(context, payload.files, payload.mimeType)
            viewModel.clearSharePayload()
        }
    }

    LaunchedEffect(state.exportDone) {
        if (state.exportDone) {
            val close = state.exportDeleted
            viewModel.clearExportDone()
            if (close) onBack()
        }
    }

    fun requestBack() {
        if (state.isDirty && !state.isSaving) showDiscardConfirm = true else onBack()
    }

    BackHandler { requestBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = ::requestBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.common_back))
                    }
                },
                title = {
                    Text(
                        text = state.fileName,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    IconButton(
                        onClick = viewModel::prepareShare,
                        enabled = !state.isLoading && !state.isSaving && state.error != TextEditorError.TOO_LARGE
                    ) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.files_action_share))
                    }
                    IconButton(
                        onClick = ::launchDecryptPicker,
                        enabled = !state.isLoading && !state.isSaving && state.error != TextEditorError.TOO_LARGE
                    ) {
                        Icon(Icons.Outlined.LockOpen, contentDescription = stringResource(R.string.files_action_decrypt))
                    }
                    TextButton(
                        onClick = viewModel::save,
                        enabled = state.isDirty && !state.isLoading && !state.isSaving && state.error != TextEditorError.TOO_LARGE
                    ) {
                        Text(stringResource(R.string.common_save))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                state.isLoading -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }
                state.error in setOf(
                    TextEditorError.NOT_MOUNTED,
                    TextEditorError.LOAD_FAILED,
                    TextEditorError.TOO_LARGE
                ) && state.text.isEmpty() -> {
                    Text(
                        text = textEditorErrorText(state.error),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp)
                    )
                }
                else -> {
                    OutlinedTextField(
                        value = state.text,
                        onValueChange = viewModel::updateText,
                        placeholder = { Text(stringResource(R.string.text_editor_placeholder)) },
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(16.dp)
                    )
                    if (state.error != null) {
                        Text(
                            text = textEditorErrorText(state.error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(horizontal = 24.dp, vertical = 8.dp)
                        )
                    }
                }
            }

            if (state.isSaving) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        }
    }

    if (showDiscardConfirm) {
        AppDialog(
            onDismissRequest = { showDiscardConfirm = false },
            title = { Text(stringResource(R.string.text_editor_unsaved_title)) },
            text = { Text(stringResource(R.string.text_editor_unsaved_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDiscardConfirm = false
                    onBack()
                }) {
                    Text(stringResource(R.string.text_editor_discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun textEditorErrorText(error: TextEditorError?): String =
    when (error) {
        TextEditorError.NOT_MOUNTED -> stringResource(R.string.text_editor_error_not_mounted)
        TextEditorError.LOAD_FAILED -> stringResource(R.string.text_editor_error_load_failed)
        TextEditorError.SAVE_FAILED -> stringResource(R.string.text_editor_error_save_failed)
        TextEditorError.TOO_LARGE -> stringResource(R.string.text_editor_error_too_large)
        null -> ""
    }

private fun launchShareIntent(context: Context, files: List<File>, mimeType: String) {
    if (files.isEmpty()) return
    val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.provider", it) }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uris.first())
            clipData = ClipData.newUri(context.contentResolver, files.first().name, uris.first())
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = mimeType
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = ClipData.newUri(context.contentResolver, files.first().name, uris.first()).apply {
                uris.drop(1).forEach { uri -> addItem(ClipData.Item(uri)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.files_share_chooser_title)))
    }
}
