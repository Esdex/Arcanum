package zip.arcanum.arcanum.files.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.util.LruCache
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.core.navigation.Screen
import zip.arcanum.crypto.VaultFileDescriptor
import zip.arcanum.crypto.VeraCryptEngine
import javax.inject.Inject

/**
 * A PDF read straight out of the vault (#137).
 *
 * Nothing is written out and nothing is held whole: the system renderer reads the document
 * through a [VaultFileDescriptor], so opening it costs the cross-reference table and each
 * page costs only itself. That is what makes a large document possible here at all - see
 * `PdfFromVaultTest` for the measurements this rests on.
 */
@HiltViewModel
class PdfViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val engine: VeraCryptEngine,
    private val repo: ContainerRepository,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    enum class Failure { PASSWORD_PROTECTED, UNREADABLE, VAULT_CLOSED }

    data class State(
        val name: String = "",
        val isLoading: Boolean = true,
        val pageCount: Int = 0,
        val failure: Failure? = null
    )

    /** A page's size in points, so the placeholder has the right shape before it is drawn. */
    data class PageShape(val width: Int, val height: Int)

    private val containerId: String = savedStateHandle[Screen.PdfViewer.ARG_CONTAINER] ?: ""
    private val path: String =
        "/" + (savedStateHandle.get<String>(Screen.PdfViewer.ARG_PATH) ?: "").trimStart('/')
    private val name: String = savedStateHandle[Screen.PdfViewer.ARG_NAME] ?: ""
    private val size: Long = savedStateHandle.get<String>(Screen.PdfViewer.ARG_SIZE)?.toLongOrNull() ?: 0L

    private val _state = MutableStateFlow(State(name = name))
    val state = _state.asStateFlow()

    /** One page open at a time, one thread in the renderer: pdfium is not re-entrant. */
    private val lock = Mutex()
    private val closing = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var descriptor: VaultFileDescriptor? = null
    private var renderer: PdfRenderer? = null
    private val shapes = HashMap<Int, PageShape>()

    /**
     * Rendered pages, bounded by memory rather than by count: a page of a big document at
     * full width is several megabytes, and a phone that would hold eight of them would not
     * hold eight of something larger.
     */
    private val pages = object : LruCache<String, Bitmap>(
        ((Runtime.getRuntime().maxMemory() / 8).coerceAtMost(48L * 1024 * 1024) / 1024).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    init {
        open()
        // The vault closing under the viewer - an auto-lock, or the user unmounting from
        // another screen - has to end the session rather than leave a dead descriptor behind.
        viewModelScope.launch {
            repo.mountedContainerIds.collect { mounted ->
                if (containerId !in mounted && _state.value.failure == null && !_state.value.isLoading) {
                    withContext(NonCancellable) { lock.withLock { closeRenderer() } }
                    _state.update { it.copy(failure = Failure.VAULT_CLOSED) }
                }
            }
        }
    }

    private fun open() = viewModelScope.launch {
        val opened: Pair<Failure?, Int> = withContext(Dispatchers.IO) {
            lock.withLock {
                if (repo.getContainerHandle(containerId) == null) {
                    return@withLock Failure.VAULT_CLOSED to 0
                }
                try {
                    val fd = VaultFileDescriptor(
                        appContext, engine, path, size
                    ) { repo.getContainerHandle(containerId) }
                    descriptor = fd
                    val open = PdfRenderer(fd.descriptor)
                    renderer = open
                    null to open.pageCount
                } catch (_: SecurityException) {
                    // pdfium's answer to an encrypted document: it will not ask for a
                    // password, and there is no API below Android 15 that could supply one.
                    closeRenderer()
                    Failure.PASSWORD_PROTECTED to 0
                } catch (_: Exception) {
                    closeRenderer()
                    Failure.UNREADABLE to 0
                }
            }
        }
        val (failure, pageCount) = opened
        _state.update { it.copy(isLoading = false, pageCount = pageCount, failure = failure) }
    }

    /** The page's shape in points, read once and remembered. */
    suspend fun shapeOf(index: Int): PageShape? = withContext(Dispatchers.IO) {
        shapes[index] ?: lock.withLock {
            shapes[index] ?: runCatching {
                renderer?.openPage(index)?.use { PageShape(it.width, it.height) }
            }.getOrNull()?.also { shapes[index] = it }
        }
    }

    /**
     * The page drawn [widthPx] wide, from the cache when it is already there. A page is
     * rendered onto white rather than onto nothing: a PDF page has no background of its own,
     * and left transparent it would show whatever is behind it.
     */
    suspend fun render(index: Int, widthPx: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (widthPx <= 0) return@withContext null
        val key = "$index@$widthPx"
        pages[key]?.let { return@withContext it }
        val bitmap = lock.withLock {
            pages[key]?.let { return@withLock it }
            val open = renderer ?: return@withLock null
            runCatching {
                open.openPage(index).use { page ->
                    val height = (widthPx.toLong() * page.height / page.width)
                        .coerceAtLeast(1L).toInt()
                    shapes[index] = PageShape(page.width, page.height)
                    Bitmap.createBitmap(widthPx, height, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }.getOrNull()?.also { pages.put(key, it) }
        }
        bitmap
    }

    private fun closeRenderer() {
        runCatching { renderer?.close() }
        renderer = null
        runCatching { descriptor?.close() }
        descriptor = null
        pages.evictAll()
    }

    /**
     * Closing has to wait for whatever is inside the renderer to come out: pdfium is native,
     * and closing it under a render in flight is a crash rather than an exception. onCleared
     * cannot suspend and viewModelScope is already cancelled by then, so the wait happens on
     * a scope of its own that ends with it.
     */
    override fun onCleared() {
        super.onCleared()
        closing.launch {
            try {
                lock.withLock { closeRenderer() }
            } finally {
                closing.cancel()
            }
        }
    }
}
