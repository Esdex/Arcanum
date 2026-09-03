package zip.arcanum.arcanum.gallery.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asAndroidPath
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.awxkee.aire.Aire
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import zip.arcanum.arcanum.containers.data.ContainerRepository
import zip.arcanum.arcanum.gallery.ExifReader
import zip.arcanum.arcanum.gallery.readWholeFile
import zip.arcanum.arcanum.gallery.ExifTransfer
import zip.arcanum.arcanum.gallery.ThumbnailManager
import zip.arcanum.arcanum.gallery.editor.adjustments.applyBorders
import zip.arcanum.arcanum.gallery.editor.adjustments.applyColorMatrix
import zip.arcanum.arcanum.gallery.editor.adjustments.applyEdges
import zip.arcanum.arcanum.gallery.editor.adjustments.applyPosterize
import zip.arcanum.arcanum.gallery.editor.adjustments.applyVignette
import zip.arcanum.arcanum.gallery.editor.adjustments.buildPreviewMatrix
import zip.arcanum.arcanum.gallery.editor.adjustments.defaultSliderValues
import zip.arcanum.arcanum.gallery.editor.adjustments.flipHorizontal
import zip.arcanum.arcanum.gallery.editor.adjustments.flipVertical
import zip.arcanum.arcanum.gallery.editor.adjustments.lerpColorMatrix
import zip.arcanum.arcanum.gallery.editor.adjustments.namedFilters
import zip.arcanum.arcanum.gallery.editor.adjustments.rotate
import zip.arcanum.arcanum.gallery.editor.adjustments.sharpnessKernelSize
import zip.arcanum.arcanum.gallery.editor.model.CropAspectRatio
import zip.arcanum.arcanum.gallery.editor.model.DrawMode
import zip.arcanum.arcanum.gallery.editor.model.EditorTab
import zip.arcanum.arcanum.gallery.editor.model.PathProperties
import zip.arcanum.core.database.dao.MediaFileDao
import zip.arcanum.core.database.entities.MediaFileEntity
import zip.arcanum.core.navigation.Screen
import zip.arcanum.core.notifications.InAppNotification
import zip.arcanum.core.notifications.NotificationCenter
import zip.arcanum.crypto.VeraCryptEngine
import java.io.ByteArrayOutputStream
import java.io.IOException
import javax.inject.Inject

@HiltViewModel
class PhotoEditorViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val mediaFileDao: MediaFileDao,
    private val repo: ContainerRepository,
    private val engine: VeraCryptEngine,
    private val thumbnailManager: ThumbnailManager,
    private val exifReader: ExifReader,
    private val notifications: NotificationCenter
) : ViewModel() {

    private val mediaId: String = savedStateHandle[Screen.PhotoEditor.ARG] ?: ""

    /** The largest original the editor will hold in memory to work on. */
    private val MAX_EDITABLE_BYTES = 50L * 1024 * 1024

    data class UiState(
        // Loading
        val isLoading: Boolean = true,
        val isSaving: Boolean = false,
        val saveSuccess: Boolean = false,
        val savedFileId: String? = null,
        val error: String? = null,
        // Bitmap state
        val originalBitmap: Bitmap? = null,   // never mutated
        val workBitmap: Bitmap? = null,        // after crop/rotate/flip commits
        val undoStack: List<Bitmap> = emptyList(),
        // Tabs
        val selectedTab: EditorTab = EditorTab.CROP,
        val activeFilterKey: String? = null,   // which slider is expanded
        // Crop
        val cropRect: Rect? = null,    // null = full image; in screen px (overlay coords)
        val imageRect: Rect = Rect.Zero, // rendered image bounds in screen px
        val rotation90: Float = 0f,
        val flipH: Boolean = false,
        val flipV: Boolean = false,
        val aspectRatio: CropAspectRatio = CropAspectRatio.FREE,
        val isCropActive: Boolean = true,
        // Sliders
        val sliders: Map<String, Float> = defaultSliderValues(),
        val sliderUndoStack: List<Map<String, Float>> = emptyList(),
        val hasActiveEdit: Boolean = false,
        val effectsPreviewBitmap: Bitmap? = null,
        // Named filter
        val selectedFilterIndex: Int = 0,
        val filterIntensity: Float = 1f,
        // Markup
        val markupPaths: List<Pair<Path, PathProperties>> = emptyList(),
        val markupUndone: List<Pair<Path, PathProperties>> = emptyList(),
        val drawMode: DrawMode = DrawMode.PEN,
        val markupColor: androidx.compose.ui.graphics.Color = androidx.compose.ui.graphics.Color.Red,
        val markupStrokeWidth: Float = 8f,
        val isMarkupDrawing: Boolean = false,
    ) {
        val previewMatrix: ColorMatrix get() {
            val filterMatrix = if (selectedFilterIndex == 0) null
                else namedFilters.getOrNull(selectedFilterIndex)?.matrix
            val base = buildPreviewMatrix(sliders, filterMatrix)
            return if (selectedFilterIndex != 0 && filterIntensity < 1f)
                lerpColorMatrix(buildPreviewMatrix(sliders, null), base, filterIntensity)
            else base
        }
    }

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    private var slidersAtEditStart: Map<String, Float>? = null
    private var effectsJob: Job? = null

    companion object {
        private val bitmapEffectKeys = setOf("sharpness", "posterize", "edges")
        private const val UNDO_BYTE_BUDGET = 150L * 1024 * 1024
    }

    init {
        loadBitmap()
    }

    private fun loadBitmap() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = mediaFileDao.getMediaById(mediaId) ?: run {
                    _state.update { it.copy(isLoading = false, error = "File not found") }
                    return@launch
                }
                val handle = repo.getContainerHandle(file.containerId) ?: run {
                    _state.update { it.copy(isLoading = false, error = "Vault not mounted") }
                    return@launch
                }
                /* In chunks, because one JNI read is refused above 16 MB and comes back
                   empty - which is why a large picture used to reach the decoder as nothing
                   at all and open as "Cannot decode image". */
                val bytes = engine.readWholeFile(handle, file.relativePath, file.size, MAX_EDITABLE_BYTES)
                    ?: run {
                        _state.update { it.copy(
                            isLoading = false,
                            error = if (file.size > MAX_EDITABLE_BYTES) "This picture is too large to edit"
                                    else "Failed to read file"
                        ) }
                        return@launch
                    }
                // Pre-pass: check dimensions and choose inSampleSize to stay within 4096×4096
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    _state.update { it.copy(isLoading = false, error = "Cannot decode image") }
                    return@launch
                }
                var sample = 1
                while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize      = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: run {
                    _state.update { it.copy(isLoading = false, error = "Cannot decode image") }
                    return@launch
                }
                _state.update { it.copy(isLoading = false, originalBitmap = bitmap, workBitmap = bitmap) }
            } catch (_: Throwable) {
                _state.update { it.copy(isLoading = false, error = "Cannot decode image") }
            }
        }
    }

    // ── Tab navigation ────────────────────────────────────────────────────────

    fun selectTab(tab: EditorTab) {
        commitSliderUndoIfNeeded()
        _state.update { it.copy(selectedTab = tab, activeFilterKey = null, isMarkupDrawing = tab == EditorTab.MARKUP) }
    }

    fun selectFilterKey(key: String?) {
        val currentKey = _state.value.activeFilterKey
        val nextKey = if (currentKey == key) null else key
        if (nextKey != currentKey) commitSliderUndoIfNeeded()
        if (nextKey != null) slidersAtEditStart = _state.value.sliders
        _state.update { it.copy(activeFilterKey = nextKey) }
    }

    private fun commitSliderUndoIfNeeded() {
        val snapshot = slidersAtEditStart ?: return
        val current  = _state.value.sliders
        if (snapshot != current) {
            _state.update { s ->
                s.copy(sliderUndoStack = (s.sliderUndoStack + snapshot).takeLast(10), hasActiveEdit = false)
            }
        } else {
            _state.update { it.copy(hasActiveEdit = false) }
        }
        slidersAtEditStart = null
    }

    // ── Slider ────────────────────────────────────────────────────────────────

    fun setSlider(key: String, value: Float) {
        val updated = _state.value.sliders + (key to value)
        val hasEdit = slidersAtEditStart != null && slidersAtEditStart != updated
        _state.update { it.copy(sliders = updated, hasActiveEdit = hasEdit) }
        if (key in bitmapEffectKeys) scheduleEffectsPreview(updated)
    }

    private fun scheduleEffectsPreview(sliders: Map<String, Float>) {
        val sharpness = sliders["sharpness"] ?: 0f
        val posterize = sliders["posterize"] ?: 0f
        val edges     = sliders["edges"]     ?: 0f
        effectsJob?.cancel()
        if (sharpness <= 0f && posterize <= 0f && edges <= 0f) {
            _state.update { it.copy(effectsPreviewBitmap = null) }
            return
        }
        effectsJob = viewModelScope.launch(Dispatchers.Default) {
            delay(120)
            if (!isActive) return@launch
            val src = _state.value.workBitmap ?: return@launch
            // Scale down to preview resolution — full-size ops (edges, posterize) are O(w*h) and OOM on large photos
            val maxDim = 900
            var bmp = if (src.width > maxDim || src.height > maxDim) {
                val scale = maxDim.toFloat() / maxOf(src.width, src.height)
                Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
            } else src
            if (sharpness > 0f) bmp = Aire.sharpness(bmp, sharpnessKernelSize(sharpness))
            if (posterize > 0f) bmp = applyPosterize(bmp, posterize)
            if (edges     > 0f) bmp = applyEdges(bmp, edges)
            _state.update { it.copy(effectsPreviewBitmap = bmp) }
        }
    }

    // ── Named filters ─────────────────────────────────────────────────────────

    fun selectFilter(index: Int) {
        _state.update { it.copy(selectedFilterIndex = index, filterIntensity = 1f) }
    }

    fun setFilterIntensity(intensity: Float) {
        _state.update { it.copy(filterIntensity = intensity) }
    }

    // ── Crop & transform ──────────────────────────────────────────────────────

    fun setCropRect(rect: Rect?) {
        _state.update { it.copy(cropRect = rect) }
    }

    fun setImageRect(rect: Rect) {
        if (_state.value.imageRect != rect) _state.update { it.copy(imageRect = rect) }
    }

    fun setAspectRatio(ratio: CropAspectRatio) {
        _state.update { it.copy(aspectRatio = ratio) }
    }

    fun rotate90CW() {
        val cur = _state.value.workBitmap ?: return
        val rotated = cur.rotate(90f)
        pushUndo(cur)
        _state.update { it.copy(workBitmap = rotated, cropRect = null, effectsPreviewBitmap = null) }
    }

    fun flipH() {
        val cur = _state.value.workBitmap ?: return
        val flipped = cur.flipHorizontal()
        pushUndo(cur)
        _state.update { it.copy(workBitmap = flipped, effectsPreviewBitmap = null) }
    }

    fun flipV() {
        val cur = _state.value.workBitmap ?: return
        val flipped = cur.flipVertical()
        pushUndo(cur)
        _state.update { it.copy(workBitmap = flipped, effectsPreviewBitmap = null) }
    }

    fun applyCrop() {
        val s = _state.value
        val bmp = s.workBitmap ?: return
        val screenRect = s.cropRect ?: return
        val imgRect = s.imageRect
        if (imgRect == Rect.Zero || imgRect.width == 0f || imgRect.height == 0f) return

        // Convert from screen-px (overlay coords) to bitmap-px
        val scaleX = bmp.width  / imgRect.width
        val scaleY = bmp.height / imgRect.height
        val left   = ((screenRect.left   - imgRect.left) * scaleX).toInt().coerceAtLeast(0)
        val top    = ((screenRect.top    - imgRect.top)  * scaleY).toInt().coerceAtLeast(0)
        val width  = (screenRect.width  * scaleX).toInt().coerceAtLeast(1).coerceAtMost(bmp.width  - left)
        val height = (screenRect.height * scaleY).toInt().coerceAtLeast(1).coerceAtMost(bmp.height - top)
        if (width <= 0 || height <= 0) return

        val cropped = Bitmap.createBitmap(bmp, left, top, width, height)
        pushUndo(bmp)
        _state.update { it.copy(workBitmap = cropped, cropRect = null, effectsPreviewBitmap = null) }
    }

    fun resetCrop() {
        _state.update { it.copy(cropRect = null) }
    }

    // ── Undo ─────────────────────────────────────────────────────────────────

    private fun pushUndo(bmp: Bitmap) {
        _state.update { s ->
            var stack = s.undoStack + bmp
            // Evict oldest bitmaps until total retained bytes stay under budget.
            var bytes = stack.sumOf { it.byteCount.toLong() }
            while (bytes > UNDO_BYTE_BUDGET && stack.size > 1) {
                bytes -= stack.first().byteCount.toLong()
                stack = stack.drop(1)
            }
            s.copy(undoStack = stack)
        }
    }

    fun undo() {
        // Commit any in-progress slider edit first so it becomes a discrete undo step
        commitSliderUndoIfNeeded()
        val s = _state.value
        if (s.sliderUndoStack.isNotEmpty()) {
            val prev = s.sliderUndoStack.last()
            _state.update { it.copy(sliders = prev, sliderUndoStack = it.sliderUndoStack.dropLast(1)) }
            slidersAtEditStart = prev  // new baseline after undo
            scheduleEffectsPreview(prev)
            return
        }
        if (s.undoStack.isEmpty()) return
        val prev = s.undoStack.last()
        _state.update { it.copy(workBitmap = prev, undoStack = it.undoStack.dropLast(1), cropRect = null, effectsPreviewBitmap = null) }
    }

    // ── Markup ────────────────────────────────────────────────────────────────

    fun addPath(path: Path, props: PathProperties) {
        _state.update { it.copy(
            markupPaths  = it.markupPaths + (path to props),
            markupUndone = emptyList()
        )}
    }

    fun undoMarkup() {
        val s = _state.value
        if (s.markupPaths.isEmpty()) return
        val last = s.markupPaths.last()
        _state.update { it.copy(
            markupPaths  = it.markupPaths.dropLast(1),
            markupUndone = it.markupUndone + last
        )}
    }

    fun redoMarkup() {
        val s = _state.value
        if (s.markupUndone.isEmpty()) return
        val next = s.markupUndone.last()
        _state.update { it.copy(
            markupPaths  = it.markupPaths + next,
            markupUndone = it.markupUndone.dropLast(1)
        )}
    }

    fun setDrawMode(mode: DrawMode) { _state.update { it.copy(drawMode = mode) } }
    fun setMarkupColor(color: androidx.compose.ui.graphics.Color) { _state.update { it.copy(markupColor = color) } }
    fun setMarkupStrokeWidth(w: Float) { _state.update { it.copy(markupStrokeWidth = w) } }

    fun applyMarkupToBitmap(onDone: () -> Unit) {
        val s = _state.value
        val bmp = s.workBitmap ?: return
        if (s.markupPaths.isEmpty()) { onDone(); return }
        val imgRect = s.imageRect
        viewModelScope.launch(Dispatchers.Default) {
            val result = bmp.copy(bmp.config ?: Bitmap.Config.ARGB_8888, true)
            val canvas = android.graphics.Canvas(result)
            // Map from overlay screen-px coords to bitmap-px coords
            if (imgRect != Rect.Zero && imgRect.width > 0f && imgRect.height > 0f) {
                val m = android.graphics.Matrix()
                m.setTranslate(-imgRect.left, -imgRect.top)
                m.postScale(bmp.width.toFloat() / imgRect.width, bmp.height.toFloat() / imgRect.height)
                canvas.concat(m)
            }
            for ((path, props) in s.markupPaths) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = props.color.hashCode()
                    // re-pack ARGB correctly
                    val c = props.color
                    this.color = android.graphics.Color.argb(
                        (c.alpha * 255).toInt(), (c.red * 255).toInt(),
                        (c.green * 255).toInt(), (c.blue * 255).toInt()
                    )
                    strokeWidth = props.strokeWidth
                    alpha = (props.alpha * 255).toInt().coerceIn(0, 255)
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    if (props.isEraser) {
                        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
                    }
                }
                canvas.drawPath(path.asAndroidPath(), paint)
            }
            pushUndo(bmp)
            _state.update { it.copy(workBitmap = result, markupPaths = emptyList(), markupUndone = emptyList(), effectsPreviewBitmap = null) }
            withContext(Dispatchers.Main) { onDone() }
        }
    }

    // ── Save ─────────────────────────────────────────────────────────────────

    private suspend fun bakeEdits(): Bitmap? {
        val s = _state.value
        var bmp = s.workBitmap ?: return null

        bmp = applyColorMatrix(bmp, s.previewMatrix.values)

        val sliders = s.sliders
        val vigStrength = sliders["vignette"] ?: 0f
        if (vigStrength > 0f) bmp = applyVignette(bmp, vigStrength)

        val sharpVal = sliders["sharpness"] ?: 0f
        if (sharpVal > 0f) bmp = Aire.sharpness(bmp, sharpnessKernelSize(sharpVal))

        val denoiseVal = sliders["denoise"] ?: 0f
        if (denoiseVal > 0f) {
            val r = (denoiseVal * 10f).toInt().coerceIn(1, 10)
            bmp = Aire.stackBlur(bmp, r, r)
        }

        val posterize = sliders["posterize"] ?: 0f
        if (posterize > 0f) bmp = applyPosterize(bmp, posterize)

        val edges = sliders["edges"] ?: 0f
        if (edges > 0f) bmp = applyEdges(bmp, edges)

        val borders = sliders["borders"] ?: 0f
        if (borders > 0f) bmp = applyBorders(bmp, borders)

        return bmp
    }

    /** How an edit can be written back, and the name those bytes have a right to. */
    private data class Encoding(
        val format: Bitmap.CompressFormat,
        val quality: Int,
        val fileName: String
    )

    /**
     * A Bitmap can be encoded as JPEG, PNG or WebP, and as nothing else. Everything the
     * editor opens used to be written as JPEG regardless and kept its old name, so an
     * edited .webp, .heic or .bmp was a JPEG wearing another format's extension - a file
     * that lies about its content to anything trusting the name, and to every program it
     * is exported to.
     *
     * Now the format follows what can actually be written, and where that cannot be the
     * original format, the name follows the format instead of the other way round: a
     * lossless source becomes a PNG, a camera format becomes the JPEG it would have been.
     */
    private fun encodingFor(fileName: String): Encoding {
        val ext  = fileName.substringAfterLast('.', "").lowercase()
        val base = fileName.substringBeforeLast('.', fileName)
        return when (ext) {
            "png"          -> Encoding(Bitmap.CompressFormat.PNG,  100, fileName)
            "jpg", "jpeg"  -> Encoding(Bitmap.CompressFormat.JPEG,  92, fileName)
            "webp"         -> Encoding(webpFormat(),                92, fileName)
            "bmp", "gif"   -> Encoding(Bitmap.CompressFormat.PNG,  100, "$base.png")
            "heic", "heif" -> Encoding(Bitmap.CompressFormat.JPEG,  92, "$base.jpg")
            // A name with no extension at all lands here too, and gets one.
            else           -> Encoding(Bitmap.CompressFormat.JPEG,  92, "$base.jpg")
        }
    }

    // WEBP_LOSSY arrived in API 30; below that the deprecated WEBP is the same encoder.
    @Suppress("DEPRECATION")
    private fun webpFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
        else Bitmap.CompressFormat.WEBP

    private fun Bitmap.compress(encoding: Encoding): ByteArray =
        ByteArrayOutputStream().also { compress(encoding.format, encoding.quality, it) }.toByteArray()

    /**
     * The edit encoded, with the original's EXIF carried into it.
     *
     * `Bitmap.compress` writes pixels only, so without this an edited photo comes out of the
     * editor with no date taken and no location - invisible inside the app, where the index
     * keeps both, and gone the moment the file is exported or the index is built again.
     *
     * Anything that cannot be carried leaves the picture itself untouched: a failure here
     * costs metadata, never the edit.
     */
    private suspend fun encodeWithExif(handle: Long, file: MediaFileEntity, bmp: Bitmap, enc: Encoding): ByteArray {
        val bytes = bmp.compress(enc)
        val tiff  = sourceTiff(handle, file) ?: return bytes
        val ready = ExifTransfer.retarget(tiff, bmp.width, bmp.height)
        return when (enc.format) {
            Bitmap.CompressFormat.JPEG -> ExifTransfer.intoJpeg(bytes, ready)
            Bitmap.CompressFormat.PNG  -> ExifTransfer.intoPng(bytes, ready)
            else                       -> ExifTransfer.intoWebp(bytes, ready, bmp.width, bmp.height)
        }
    }

    /**
     * The original's EXIF as a TIFF block: lifted whole out of a JPEG, so that camera,
     * exposure and everything else comes along; rebuilt from the date and the place for a
     * HEIC, whose block sits inside the ISO container where lifting it is another parser.
     */
    private suspend fun sourceTiff(handle: Long, file: MediaFileEntity): ByteArray? {
        val headSize = minOf(file.size, 512L * 1024).toInt()
        val head = try {
            engine.readFile(handle, file.relativePath, 0L, headSize)
        } catch (_: Throwable) { null } ?: return null
        ExifTransfer.tiffFromJpeg(head)?.let { return it }
        val exif = exifReader.readExif(head)
        return ExifTransfer.buildTiff(exif.dateTimeOriginal, exif.gpsLatitude, exif.gpsLongitude)
    }

    /**
     * The first free "name_N.ext" in [dir], as name to full path, or null when a thousand
     * of them are taken.
     */
    private fun nextFreeName(handle: Long, dir: String, base: String, ext: String): Pair<String, String>? {
        val cleanBase = base.replace(Regex("_\\d+$"), "")
        for (suffix in 1..999) {
            val name = if (ext.isEmpty()) "${cleanBase}_$suffix" else "${cleanBase}_$suffix.$ext"
            val path = if (dir.isEmpty()) name else "$dir/$name"
            if (isFree(handle, path)) return name to path
        }
        return null
    }

    private fun isFree(handle: Long, path: String): Boolean {
        val probe = engine.readFile(handle, path, 0L, 1)
        return probe == null || probe.isEmpty()
    }

    private suspend fun writeChunked(handle: Long, path: String, bytes: ByteArray) {
        val chunkSize = 512 * 1024
        var offset = 0L
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size.toLong()).toInt()
            val result = engine.writeFile(handle, path, bytes.copyOfRange(offset.toInt(), end), offset)
            if (result != 0) throw IOException("Write failed at offset $offset (error $result)")
            offset = end.toLong()
        }
    }

    fun overwrite() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isSaving = true) }
            try {
                val file = mediaFileDao.getMediaById(mediaId) ?: return@launch
                val handle = repo.getContainerHandle(file.containerId) ?: return@launch

                val bmp = bakeEdits() ?: return@launch
                val enc = encodingFor(file.fileName)
                val bytes = encodeWithExif(handle, file, bmp, enc)

                /* Where the bytes land. The name only moves when the original format cannot
                   be written back, and even then it must not land on a file that is already
                   there - replacing the user's other photo is not what Overwrite promised. */
                val dir = file.relativePath.substringBeforeLast('/', "")
                val wanted = if (dir.isEmpty()) enc.fileName else "$dir/${enc.fileName}"
                val (targetName, targetPath) = when {
                    enc.fileName == file.fileName -> file.fileName to file.relativePath
                    isFree(handle, wanted)        -> enc.fileName to wanted
                    else -> nextFreeName(
                        handle, dir,
                        enc.fileName.substringBeforeLast('.', enc.fileName),
                        enc.fileName.substringAfterLast('.', "")
                    ) ?: run {
                        _state.update { it.copy(isSaving = false, error = "Too many copies of this file") }
                        return@launch
                    }
                }

                // Backup-swap: write tmp → rename original to .bak → swap tmp → delete .bak
                // Crash at any step leaves either original or .bak intact; never leaves both gone.
                val tmpPath = file.relativePath + ".tmp"
                val bakPath = file.relativePath + ".bak"
                engine.deleteFile(handle, tmpPath)
                writeChunked(handle, tmpPath, bytes)
                engine.deleteFile(handle, bakPath)
                val bakResult = engine.renameFile(handle, file.relativePath, bakPath)
                if (bakResult != 0) throw IOException("Backup rename failed (error $bakResult)")
                val swapResult = engine.renameFile(handle, tmpPath, targetPath)
                if (swapResult != 0) {
                    engine.renameFile(handle, bakPath, file.relativePath)
                    throw IOException("Swap rename failed (error $swapResult)")
                }
                engine.deleteFile(handle, bakPath)

                mediaFileDao.updateMediaFile(file.copy(
                    fileName     = targetName,
                    relativePath = targetPath,
                    size         = bytes.size.toLong(),
                    width        = bmp.width,
                    height       = bmp.height
                ))
                // Keyed on the path the thumbnail was made from, so it is the old one.
                thumbnailManager.clearFileCache(file.containerId, file.relativePath, file.id)
                if (targetName != file.fileName) {
                    notifications.notify(InAppNotification.SavedAs(targetName))
                }
                _state.update { it.copy(isSaving = false, saveSuccess = true, savedFileId = mediaId) }
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = "Save failed: ${e.message}") }
            }
        }
    }

    fun saveCopy() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(isSaving = true) }
            try {
                val file = mediaFileDao.getMediaById(mediaId) ?: return@launch
                val handle = repo.getContainerHandle(file.containerId) ?: return@launch

                val bmp = bakeEdits() ?: return@launch

                // The copy is named after the format it is actually saved in, not after
                // the format of the file it was made from.
                val enc = encodingFor(file.fileName)
                val dir = file.relativePath.substringBeforeLast('/', "")
                val (newFileName, newPath) = nextFreeName(
                    handle, dir,
                    enc.fileName.substringBeforeLast('.', enc.fileName),
                    enc.fileName.substringAfterLast('.', "")
                ) ?: run {
                    _state.update { it.copy(isSaving = false, error = "Too many copies of this file") }
                    return@launch
                }

                val bytes = encodeWithExif(handle, file, bmp, enc)
                writeChunked(handle, newPath, bytes)

                val copyEntity = file.copy(
                    id           = java.util.UUID.randomUUID().toString(),
                    fileName     = newFileName,
                    relativePath = newPath,
                    size         = bytes.size.toLong(),
                    dateModified = System.currentTimeMillis(),
                    width        = bmp.width,
                    height       = bmp.height,
                    thumbnailPath = null
                )
                mediaFileDao.insertMediaFile(copyEntity)

                _state.update { it.copy(isSaving = false, saveSuccess = true, savedFileId = copyEntity.id) }
            } catch (e: Exception) {
                _state.update { it.copy(isSaving = false, error = "Save failed: ${e.message}") }
            }
        }
    }

    fun clearSaveSuccess() { _state.update { it.copy(saveSuccess = false) } }
    fun clearError() { _state.update { it.copy(error = null) } }
}
