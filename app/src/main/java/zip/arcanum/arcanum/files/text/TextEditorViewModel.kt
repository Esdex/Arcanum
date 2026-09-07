package zip.arcanum.arcanum.files.text

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.core.navigation.Screen
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.notifications.NotificationCenter
import zip.arcanum.core.security.AppPreferences
import zip.arcanum.core.security.TextEditorPrefs
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject

/**
 * A text file opened out of a vault (#109).
 *
 * The whole file is held in memory, which is what an editor is - and why it refuses anything
 * over [TextDocument.MAX_EDITABLE_BYTES] instead of trying and stalling. Reading and writing
 * go a megabyte at a time, the size every other file operation in the app uses, because one
 * JNI call cannot carry more than 16 MB and the vault would rather not be asked to.
 *
 * Saving never writes over the file. It writes a sibling, removes the original and renames -
 * so an interrupted save leaves either the old file or the new one, never half of either.
 * The cost is room for two copies at the moment of saving, which for a file this size is
 * nothing, and the gain is that the thing being edited is the one thing the vault holds.
 */
@HiltViewModel
class TextEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val engine: VeraCryptEngine,
    private val repo: ContainerRepository,
    private val notifications: NotificationCenter,
    prefs: AppPreferences
) : ViewModel() {

    enum class Failure { TOO_LARGE, NOT_TEXT, UNREADABLE, VAULT_CLOSED }

    data class State(
        val name: String = "",
        val isLoading: Boolean = true,
        val isSaving: Boolean = false,
        val text: String = "",
        val savedText: String = "",
        val syntax: Syntax = Syntax.NONE,
        val mixedNewlines: Boolean = false,
        val unknownEncoding: Boolean = false,
        /** A vault mounted read-only can be read here, and nothing more. */
        val readOnly: Boolean = false,
        /** Set when a save was refused because the file's encoding cannot hold what was typed. */
        val encodingRefused: Boolean = false,
        val failure: Failure? = null
    ) {
        val isDirty: Boolean get() = text != savedText
    }

    private val containerId: String = savedStateHandle[Screen.TextEditor.ARG_CONTAINER] ?: ""
    private val path: String =
        "/" + (savedStateHandle.get<String>(Screen.TextEditor.ARG_PATH) ?: "").trimStart('/')
    private val name: String = savedStateHandle[Screen.TextEditor.ARG_NAME] ?: ""
    private val size: Long = savedStateHandle.get<String>(Screen.TextEditor.ARG_SIZE)?.toLongOrNull() ?: 0L

    private val _state = MutableStateFlow(
        State(
            name     = name,
            syntax   = SyntaxHighlighter.of(name),
            readOnly = repo.isContainerReadOnly(containerId)
        )
    )
    val state = _state.asStateFlow()

    val prefs: kotlinx.coroutines.flow.StateFlow<TextEditorPrefs> = prefs.textEditor
        .stateIn(viewModelScope, SharingStarted.Eagerly, TextEditorPrefs())

    /** What the file was when it was read: its encoding, its line endings, its final newline. */
    private var document: TextDocument? = null

    /** One save at a time. Two overlapping saves would race over the same temporary name. */
    private val saveLock = Mutex()

    /**
     * Saving runs here rather than in [viewModelScope], because one of the moments a save
     * happens is the editor being left - and by then the view model is on its way out. A
     * write cancelled half way is exactly what the sibling-and-rename dance is there to
     * prevent, so it must not be tied to the screen's life.
     */
    private val saver = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        load()
        viewModelScope.launch {
            repo.mountedContainerIds.collect { mounted ->
                val s = _state.value
                if (containerId !in mounted && s.failure == null && !s.isLoading) {
                    _state.update { it.copy(failure = Failure.VAULT_CLOSED) }
                }
            }
        }
    }

    private fun load() = viewModelScope.launch {
        if (size > TextDocument.MAX_EDITABLE_BYTES) {
            _state.update { it.copy(isLoading = false, failure = Failure.TOO_LARGE) }
            return@launch
        }
        val result = withContext(Dispatchers.IO) {
            val handle = repo.getContainerHandle(containerId)
                ?: return@withContext Failure.VAULT_CLOSED to null
            val bytes = readWhole(handle) ?: return@withContext Failure.UNREADABLE to null
            val doc = TextDocument.decode(bytes) ?: return@withContext Failure.NOT_TEXT to null
            null to doc
        }
        val (failure, doc) = result
        if (doc == null) {
            _state.update { it.copy(isLoading = false, failure = failure) }
            return@launch
        }
        document = doc
        _state.update {
            it.copy(
                isLoading       = false,
                text            = doc.text,
                savedText       = doc.text,
                mixedNewlines   = doc.mixedNewlines,
                unknownEncoding = doc.decodedAsFallback
            )
        }
    }

    private fun readWhole(handle: Long): ByteArray? {
        if (size == 0L) return ByteArray(0)
        val out = ByteArray(size.toInt())
        var read = 0
        while (read < out.size) {
            val want = minOf(CHUNK, out.size - read)
            val part = runCatching { engine.readFile(handle, path, read.toLong(), want) }.getOrNull()
                ?: return null
            if (part.isEmpty()) return null
            System.arraycopy(part, 0, out, read, part.size)
            read += part.size
        }
        return out
    }

    fun onTextChange(newText: String) {
        _state.update {
            it.copy(text = newText)
        }
    }

    /**
     * Writes the buffer back, and calls [onDone] with whether it landed.
     *
     * Called by the Save button and again when the editor is left - including when the app
     * goes to the background, which is what happens on the way to an auto-lock. A save with
     * nothing to save is not a write: it answers success and touches nothing, so leaving a
     * file one has only read does not change its date.
     */
    fun save(onDone: (Boolean) -> Unit = {}) {
        val s = _state.value
        val doc = document
        if (doc == null || !s.isDirty || s.failure != null) { onDone(true); return }
        // Nothing to try: the write would be refused by the filesystem, and finding that out
        // at the moment of leaving the screen is the worst time to be told.
        if (s.readOnly) {
            notifications.notify(InAppNotification.ReadOnlyError)
            onDone(false)
            return
        }

        // A file the editor could not read as UTF-8 is held byte for byte, and its encoding
        // may simply have no room for what was just typed. Writing it anyway would put
        // question marks where the characters were, so the save stops and asks.
        if (!doc.canEncode(s.text)) {
            _state.update { it.copy(encodingRefused = true) }
            onDone(false)
            return
        }

        saver.launch {
            _state.update { it.copy(isSaving = true) }
            val text = _state.value.text
            val ok = saveLock.withLock { withContext(Dispatchers.IO) { write(doc, text) } }
            _state.update { it.copy(isSaving = false, savedText = if (ok) text else it.savedText) }
            notifications.notify(
                if (ok) InAppNotification.DocumentSaved(s.name)
                else InAppNotification.ReadOnlyError
            )
            onDone(ok)
        }
    }

    /** Answers the question the refusal asked: keep the file's encoding, or move it to UTF-8. */
    fun resolveEncoding(convertToUtf8: Boolean) {
        _state.update { it.copy(encodingRefused = false) }
        if (!convertToUtf8) return
        document = document?.asUtf8()
        _state.update { it.copy(unknownEncoding = false) }
        save()
    }

    /**
     * The write itself: sibling, then swap.
     *
     * The temporary name is the file's own with a suffix, so it lands in the same folder and
     * therefore on the same volume - a rename across volumes is a copy, and this rename has
     * to be the cheap kind. If the swap cannot be completed the temporary file is removed and
     * the original is left exactly as it was; the one case that leaves a stray behind is a
     * delete that succeeds and a rename that does not, and there the new text is the file
     * that survives under the temporary name rather than being thrown away.
     */
    private fun write(doc: TextDocument, text: String): Boolean {
        val handle = repo.getContainerHandle(containerId) ?: return false
        val bytes  = doc.encode(text)
        val tmp    = "$path$TMP_SUFFIX"

        runCatching { engine.deleteFile(handle, tmp) }   // a leftover from a killed save

        var written = 0
        while (written < bytes.size) {
            val end = minOf(written + CHUNK, bytes.size)
            val part = bytes.copyOfRange(written, end)
            val rc = runCatching { engine.writeFile(handle, tmp, part, written.toLong()) }
                .getOrDefault(VeraCryptEngine.ERR_FS)
            if (rc != VeraCryptEngine.ERR_OK) {
                runCatching { engine.deleteFile(handle, tmp) }
                return false
            }
            written = end
        }
        if (bytes.isEmpty()) {
            // Nothing was written, so nothing created the file: touch it into being.
            val rc = runCatching { engine.writeAt(handle, tmp, ByteArray(0), 0L) }
                .getOrDefault(VeraCryptEngine.ERR_FS)
            if (rc != VeraCryptEngine.ERR_OK) return false
        }

        val deleted = runCatching { engine.deleteFile(handle, path) }
            .getOrDefault(VeraCryptEngine.ERR_FS) == VeraCryptEngine.ERR_OK
        if (!deleted) {
            runCatching { engine.deleteFile(handle, tmp) }
            return false
        }
        val renamed = runCatching { engine.renameFile(handle, tmp, path) }
            .getOrDefault(VeraCryptEngine.ERR_FS) == VeraCryptEngine.ERR_OK
        return renamed
    }

    private companion object {
        const val CHUNK = 1 * 1024 * 1024
        const val TMP_SUFFIX = ".arcanum-save"
    }
}
