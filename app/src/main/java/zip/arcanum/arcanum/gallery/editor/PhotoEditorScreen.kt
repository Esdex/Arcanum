package zip.arcanum.arcanum.gallery.editor

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Crop
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import zip.arcanum.R
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import zip.arcanum.arcanum.gallery.editor.adjustments.colourFilters
import zip.arcanum.arcanum.gallery.editor.adjustments.effectsFilters
import zip.arcanum.arcanum.gallery.editor.adjustments.lightingFilters
import zip.arcanum.arcanum.gallery.editor.components.AdjustSection
import zip.arcanum.arcanum.gallery.editor.components.CropOverlay
import zip.arcanum.arcanum.gallery.editor.components.EditorTabSelector
import zip.arcanum.arcanum.gallery.editor.components.FiltersSection
import zip.arcanum.arcanum.gallery.editor.components.MarkupBottomBar
import zip.arcanum.arcanum.gallery.editor.components.MarkupCanvas
import zip.arcanum.arcanum.gallery.editor.components.MarkupToolbar
import zip.arcanum.arcanum.gallery.editor.model.CropAspectRatio
import zip.arcanum.arcanum.gallery.editor.model.DrawMode
import zip.arcanum.arcanum.gallery.editor.model.EditorTab
import zip.arcanum.arcanum.gallery.editor.model.PathProperties

@Composable
fun PhotoEditorScreen(
    onBack: (copiedFileId: String?) -> Unit,
    viewModel: PhotoEditorViewModel = hiltViewModel()
) {
    val s by viewModel.state.collectAsState()

    // Hide system bars
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window ?: return@DisposableEffect onDispose {}
        val wic = androidx.core.view.WindowCompat.getInsetsController(window, view)
        wic.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        wic.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose { wic.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
    }

    LaunchedEffect(s.saveSuccess) { if (s.saveSuccess) { viewModel.clearSaveSuccess(); onBack(s.savedFileId) } }

    // Markup path tracking
    var currentPath  by remember { mutableStateOf<Path?>(null) }

    val isMarkup      = s.selectedTab == EditorTab.MARKUP
    val canUndo       = s.undoStack.isNotEmpty() || s.sliderUndoStack.isNotEmpty() || s.hasActiveEdit
    val blurRadius by animateDpAsState(if (s.isSaving) 24.dp else 0.dp, tween(300), label = "blur")
    val cornerRadius by animateDpAsState(if (isMarkup) 0.dp else 12.dp, tween(350), label = "corner")
    val cropInset by animateDpAsState(if (s.selectedTab == EditorTab.CROP) 24.dp else 0.dp, tween(300), label = "cropInset")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .blur(blurRadius)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Top bar ───────────────────────────────────────────────────
            AnimatedVisibility(
                visible = !isMarkup,
                enter   = fadeIn() + slideInVertically { -it },
                exit    = fadeOut() + slideOutVertically { -it }
            ) {
                Row(
                    modifier          = Modifier.fillMaxWidth().statusBarsPadding()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onBack(null) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { viewModel.undo() }, enabled = canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Undo",
                            tint = if (canUndo) Color.White else Color.White.copy(alpha = 0.3f))
                    }
                    var showOverwriteMenu by remember { mutableStateOf(false) }
                    val splitBg = MaterialTheme.colorScheme.primaryContainer
                    val splitFg = MaterialTheme.colorScheme.onPrimaryContainer
                    val splitAlpha = if (canUndo) 1f else 0.38f
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .height(40.dp)
                            .clip(RoundedCornerShape(topStart = 50.dp, bottomStart = 50.dp, topEnd = 4.dp, bottomEnd = 4.dp))
                            .background(splitBg.copy(alpha = splitAlpha))
                            .clickable(enabled = canUndo) { viewModel.saveCopy() }
                            .padding(horizontal = 16.dp)
                    ) {
                        Text(
                            text  = stringResource(R.string.editor_save_copy),
                            style = MaterialTheme.typography.labelLarge,
                            color = splitFg.copy(alpha = splitAlpha)
                        )
                    }
                    Spacer(Modifier.width(3.dp))
                    Box(modifier = Modifier.padding(end = 4.dp)) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .height(40.dp)
                                .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 50.dp, bottomEnd = 50.dp))
                                .background(splitBg.copy(alpha = splitAlpha))
                                .clickable(enabled = canUndo) { showOverwriteMenu = true }
                                .padding(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.MoreVert, contentDescription = null, tint = splitFg.copy(alpha = splitAlpha))
                        }
                        DropdownMenu(
                            expanded         = showOverwriteMenu,
                            onDismissRequest = { showOverwriteMenu = false }
                        ) {
                            DropdownMenuItem(
                                text    = { Text(stringResource(R.string.editor_overwrite)) },
                                onClick = { showOverwriteMenu = false; viewModel.overwrite() }
                            )
                        }
                    }
                }
            }

            // ── Crop toolbar ─────────────────────────────────────────────
            AnimatedVisibility(
                visible = s.selectedTab == EditorTab.CROP,
                enter   = fadeIn(tween(200)),
                exit    = fadeOut(tween(150))
            ) {
                CropToolbar(
                    selectedAspect = s.aspectRatio,
                    hasCropRect    = s.cropRect != null,
                    onAspectSelect = viewModel::setAspectRatio,
                    onFlipH        = viewModel::flipH,
                    onFlipV        = viewModel::flipV,
                    onRotate       = viewModel::rotate90CW,
                    onApplyCrop    = viewModel::applyCrop,
                    onResetCrop    = viewModel::resetCrop
                )
            }

            // ── Image area ────────────────────────────────────────────────
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = cropInset)
                    .clip(RoundedCornerShape(cornerRadius))
            ) {
                val bmp = s.workBitmap
                if (bmp != null) {
                    // Compute vignette overlay alpha for preview
                    val vigAlpha = (s.sliders["vignette"] ?: 0f)
                    val denoiseRadius = ((s.sliders["denoise"] ?: 0f) * 8f).dp
                    // Use effects-previewed bitmap when available (same dimensions as workBitmap)
                    val displayBitmap = s.effectsPreviewBitmap ?: bmp

                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(if (denoiseRadius > 0.dp) Modifier.blur(denoiseRadius) else Modifier)
                    ) {
                        Image(
                            bitmap       = displayBitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            colorFilter  = if (s.selectedTab != EditorTab.MARKUP)
                                ColorFilter.colorMatrix(s.previewMatrix) else null,
                            modifier = Modifier
                                .fillMaxSize()
                                .onGloballyPositioned { coords ->
                                    val bmpAr = bmp.width.toFloat() / bmp.height.toFloat()
                                    val boxSize = coords.size
                                    val boxAr = boxSize.width.toFloat() / boxSize.height.toFloat()
                                    val (iw, ih) = if (bmpAr > boxAr) {
                                        boxSize.width.toFloat() to boxSize.width / bmpAr
                                    } else {
                                        boxSize.height * bmpAr to boxSize.height.toFloat()
                                    }
                                    val ox = (boxSize.width - iw) / 2f
                                    val oy = (boxSize.height - ih) / 2f
                                    viewModel.setImageRect(Rect(ox, oy, ox + iw, oy + ih))
                                }
                        )
                    }

                    // Vignette preview overlay
                    if (vigAlpha > 0f && s.selectedTab != EditorTab.MARKUP) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(
                                    androidx.compose.ui.graphics.Brush.radialGradient(
                                        0.5f to Color.Transparent,
                                        1f to Color.Black.copy(alpha = vigAlpha * 0.8f)
                                    )
                                )
                        )
                    }

                    // Borders preview: white frame drawn as 4 rects (save actually expands bitmap)
                    val bordersVal = s.sliders["borders"] ?: 0f
                    if (bordersVal > 0f && s.selectedTab != EditorTab.MARKUP) {
                        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize()) {
                            val t = (bordersVal * 32).dp.toPx()
                            val w = size.width; val h = size.height
                            drawRect(Color.White, size = androidx.compose.ui.geometry.Size(w, t))
                            drawRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(0f, h - t), size = androidx.compose.ui.geometry.Size(w, t))
                            drawRect(Color.White, size = androidx.compose.ui.geometry.Size(t, h))
                            drawRect(Color.White, topLeft = androidx.compose.ui.geometry.Offset(w - t, 0f), size = androidx.compose.ui.geometry.Size(t, h))
                        }
                    }

                    // Crop overlay
                    if (s.selectedTab == EditorTab.CROP && s.imageRect != Rect.Zero) {
                        CropOverlay(
                            cropRect        = s.cropRect,
                            aspectRatio     = s.aspectRatio,
                            imageRect       = s.imageRect,
                            modifier        = Modifier.fillMaxSize(),
                            onCropRectChange = viewModel::setCropRect
                        )
                    }

                    // Markup canvas
                    if (isMarkup) {
                        MarkupCanvas(
                            paths        = s.markupPaths,
                            currentPath  = currentPath,
                            currentProps = PathProperties(
                                color       = s.markupColor,
                                strokeWidth = s.markupStrokeWidth,
                                alpha       = if (s.drawMode == DrawMode.HIGHLIGHTER) 0.5f else 1f,
                                isEraser    = s.drawMode == DrawMode.ERASER
                            ),
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(s.drawMode, s.markupColor, s.markupStrokeWidth) {
                                    detectDragGestures(
                                        onDragStart = { offset ->
                                            currentPath = Path().apply { moveTo(offset.x, offset.y) }
                                        },
                                        onDrag = { change, _ ->
                                            currentPath?.lineTo(change.position.x, change.position.y)
                                            // Force recompose by creating new reference
                                            currentPath = currentPath?.let { p ->
                                                Path().apply { addPath(p) }
                                            }
                                        },
                                        onDragEnd = {
                                            currentPath?.let { path ->
                                                viewModel.addPath(path, PathProperties(
                                                    color       = s.markupColor,
                                                    strokeWidth = s.markupStrokeWidth,
                                                    alpha       = if (s.drawMode == DrawMode.HIGHLIGHTER) 0.5f else 1f,
                                                    isEraser    = s.drawMode == DrawMode.ERASER
                                                ))
                                            }
                                            currentPath = null
                                        },
                                        onDragCancel = { currentPath = null }
                                    )
                                }
                        )
                    }
                } else if (s.isLoading) {
                    CircularProgressIndicator(color = Color.White)
                } else if (s.error != null) {
                    Text(s.error ?: "", color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(24.dp))
                }
            }

            // ── Bottom area ───────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(spring(stiffness = Spring.StiffnessHigh))
                    .navigationBarsPadding()
            ) {
                AnimatedContent(
                    targetState = isMarkup,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                    label       = "bottomContent"
                ) { inMarkup ->
                    if (inMarkup) {
                        Column {
                            MarkupBottomBar(
                                canUndo  = s.markupPaths.isNotEmpty(),
                                canRedo  = s.markupUndone.isNotEmpty(),
                                onUndo   = viewModel::undoMarkup,
                                onRedo   = viewModel::redoMarkup,
                                onDone   = { viewModel.applyMarkupToBitmap { viewModel.selectTab(EditorTab.CROP) } },
                                onCancel = { viewModel.selectTab(EditorTab.CROP) }
                            )
                            MarkupToolbar(
                                drawMode      = s.drawMode,
                                selectedColor = s.markupColor,
                                strokeWidth   = s.markupStrokeWidth,
                                onModeChange  = viewModel::setDrawMode,
                                onColorChange = viewModel::setMarkupColor,
                                onStrokeChange = viewModel::setMarkupStrokeWidth
                            )
                        }
                    } else {
                        Column {
                            // Tab-specific controls
                            when (s.selectedTab) {
                                EditorTab.LIGHTING -> AdjustSection(
                                    filters         = lightingFilters,
                                    sliders         = s.sliders,
                                    activeKey       = s.activeFilterKey,
                                    onFilterClick   = viewModel::selectFilterKey,
                                    onSliderChange  = viewModel::setSlider
                                )
                                EditorTab.COLOUR -> AdjustSection(
                                    filters         = colourFilters,
                                    sliders         = s.sliders,
                                    activeKey       = s.activeFilterKey,
                                    onFilterClick   = viewModel::selectFilterKey,
                                    onSliderChange  = viewModel::setSlider
                                )
                                EditorTab.EFFECTS -> AdjustSection(
                                    filters         = effectsFilters,
                                    sliders         = s.sliders,
                                    activeKey       = s.activeFilterKey,
                                    onFilterClick   = viewModel::selectFilterKey,
                                    onSliderChange  = viewModel::setSlider
                                )
                                EditorTab.FILTERS -> FiltersSection(
                                    workBitmap        = s.workBitmap,
                                    selectedIndex     = s.selectedFilterIndex,
                                    intensity         = s.filterIntensity,
                                    onSelectFilter    = viewModel::selectFilter,
                                    onIntensityChange = viewModel::setFilterIntensity
                                )
                                EditorTab.CROP, EditorTab.MARKUP -> { /* no extra controls */ }
                            }
                            EditorTabSelector(selected = s.selectedTab, onSelect = viewModel::selectTab)
                        }
                    }
                }
            }
        }

        // Saving overlay
        AnimatedVisibility(
            visible  = s.isSaving,
            enter    = fadeIn(),
            exit     = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White)
            }
        }
    }
}

// ── Crop toolbar ──────────────────────────────────────────────────────────────

@Composable
private fun CropToolbar(
    selectedAspect: CropAspectRatio,
    hasCropRect: Boolean,
    onAspectSelect: (CropAspectRatio) -> Unit,
    onFlipH: () -> Unit,
    onFlipV: () -> Unit,
    onRotate: () -> Unit,
    onApplyCrop: () -> Unit,
    onResetCrop: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().background(Color.Black)) {
        // Aspect ratio row
        Row(
            modifier              = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            CropAspectRatio.entries.forEach { ratio ->
                val selected = ratio == selectedAspect
                Text(
                    text     = ratio.label,
                    style    = MaterialTheme.typography.labelMedium,
                    color    = if (selected) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                        .then(Modifier.pointerInput(ratio) { detectTapGestures { onAspectSelect(ratio) } })
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        // Transform buttons
        Row(
            modifier              = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment     = Alignment.CenterVertically
        ) {
            IconButton(onClick = onFlipH) {
                Icon(Icons.Outlined.Flip, "Flip H", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onFlipV) {
                Icon(Icons.Outlined.Flip, "Flip V", tint = Color.White,
                    modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = 90f })
            }
            IconButton(onClick = onRotate) {
                Icon(Icons.Outlined.RotateRight, "Rotate 90°", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.weight(1f))
            if (hasCropRect) {
                IconButton(onClick = onResetCrop) {
                    Text("✕", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyLarge)
                }
                IconButton(onClick = onApplyCrop) {
                    Icon(Icons.Outlined.Crop, "Apply crop", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                }
            }
        }
    }
}
