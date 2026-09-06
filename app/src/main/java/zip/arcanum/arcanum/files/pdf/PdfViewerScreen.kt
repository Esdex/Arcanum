package zip.arcanum.arcanum.files.pdf

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import zip.arcanum.R
import kotlinx.coroutines.launch
import zip.arcanum.core.components.EmptyStateView
import kotlin.math.roundToInt

/**
 * Reading a PDF without letting it out of the vault (#137).
 *
 * The pages are a plain vertical list, each one drawn to the width of the screen as it comes
 * into view and thrown away again when memory gets tight. Pinching scales what is already
 * drawn, and past a point the page under the finger is drawn again at twice the width so the
 * text stays sharp; dragging while zoomed moves about the page and keeps scrolling the
 * document, so the way out of a zoomed page is not to zoom out first.
 */
@Composable
fun PdfViewerScreen(
    onBack: () -> Unit,
    viewModel: PdfViewerViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()

    var viewportWidth by remember { mutableIntStateOf(0) }
    var viewportHeight by remember { mutableIntStateOf(0) }
    var scale by remember { mutableFloatStateOf(1f) }
    val density = LocalDensity.current

    // Zoom only. Moving about a zoomed page is left to a real horizontal scroll below,
    // rather than to a translation of the layer: a hand-moved layer stops dead where the
    // finger lifts, while a scroll carries on and settles the way the page list does.
    val transformState = rememberTransformableState { zoomChange, _, _ ->
        scale = (scale * zoomChange).coerceIn(1f, MAX_ZOOM)
    }
    val sideways = rememberScrollState()
    val scope = rememberCoroutineScope()
    val fling = ScrollableDefaults.flingBehavior()
    LaunchedEffect(scale) { if (scale <= 1.01f) sideways.scrollTo(0) }

    // Sharper pages while zoomed, but on two conditions, both learnt the hard way on a
    // device: not during the gesture, and not for every page. A page at half again the
    // screen's width is fifteen megabytes; drawing one for each page in view, in the middle
    // of a pinch, is what made panning stutter.
    val zoomedIn by remember { derivedStateOf { scale > ZOOM_FOR_DETAIL } }
    val transforming = transformState.isTransformInProgress
    var detailWidth by remember { mutableIntStateOf(0) }
    LaunchedEffect(transforming, zoomedIn, viewportWidth) {
        if (!transforming) {
            detailWidth = if (zoomedIn && viewportWidth > 0) {
                minOf((viewportWidth * DETAIL_FACTOR).roundToInt(), MAX_RENDER_WIDTH)
            } else 0
        }
    }
    val focusedPage by remember { derivedStateOf { listState.firstVisibleItemIndex } }


    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.common_back),
                    tint = MaterialTheme.colorScheme.onBackground
                )
            }
            Text(
                text     = state.name,
                style    = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (state.pageCount > 0) {
                Text(
                    text  = stringResource(R.string.pdf_page_of, focusedPage + 1, state.pageCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .clipToBounds()
                .onSizeChanged { viewportWidth = it.width; viewportHeight = it.height },
            contentAlignment = Alignment.Center
        ) {
            when {
                state.isLoading -> CircularProgressIndicator()

                state.failure != null -> EmptyStateView(
                    // The error animation for the two that are errors; a vault closed on
                    // purpose is not one, and gets the quieter of the two.
                    lottieRes = when (state.failure) {
                        PdfViewerViewModel.Failure.VAULT_CLOSED -> R.raw.info
                        else                                    -> R.raw.error
                    },
                    title    = stringResource(
                        when (state.failure) {
                            PdfViewerViewModel.Failure.PASSWORD_PROTECTED -> R.string.pdf_error_password
                            PdfViewerViewModel.Failure.VAULT_CLOSED       -> R.string.pdf_error_vault_closed
                            else                                          -> R.string.pdf_error_unreadable
                        }
                    ),
                    subtitle = when (state.failure) {
                        PdfViewerViewModel.Failure.PASSWORD_PROTECTED ->
                            stringResource(R.string.pdf_error_password_desc)
                        else -> null
                    },
                    loop     = false,
                    modifier = Modifier.padding(horizontal = 32.dp)
                )

                else -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // canPan is false: at rest a one-finger drag belongs to the list,
                        // which scrolls and flings the way the rest of the app does.
                        .transformable(state = transformState, canPan = { false })
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = {
                                scale = if (scale > 1.01f) 1f else ZOOM_ON_TAP
                            })
                        }
                        // Zoomed in, one gesture has to be able to go in any direction and
                        // change its mind mid-drag. Two scrolls stacked cannot do that: the
                        // first one to claim an axis keeps the finger until it is lifted. So
                        // while zoomed the drag is read here and pushed into both scrolls at
                        // once, and let go of with the platform's own fling on each axis.
                        .pointerInput(scale > 1.01f) {
                            if (scale <= 1.01f) return@pointerInput
                            val velocity = VelocityTracker()
                            detectDragGestures(
                                onDragStart = { velocity.resetTracking() },
                                onDrag = { change, delta ->
                                    change.consume()
                                    velocity.addPointerInputChange(change)
                                    sideways.dispatchRawDelta(-delta.x)
                                    // The list is drawn scaled, so a finger's worth of screen
                                    // is that much less of the document.
                                    listState.dispatchRawDelta(-delta.y / scale)
                                },
                                onDragEnd = {
                                    val speed = velocity.calculateVelocity()
                                    scope.launch {
                                        sideways.scroll { with(fling) { performFling(-speed.x) } }
                                    }
                                    scope.launch {
                                        listState.scroll { with(fling) { performFling(-speed.y / scale) } }
                                    }
                                }
                            )
                        }
                        // Off, because the drag above is what moves it: left on, it would
                        // take the whole gesture the moment it went sideways.
                        .horizontalScroll(sideways, enabled = false)
                ) {
                    Box(
                        modifier = Modifier
                            .width(with(density) { (viewportWidth * scale).toDp() })
                            .fillMaxHeight()
                    ) {
                        LazyColumn(
                            state          = listState,
                            contentPadding = PaddingValues(vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .width(with(density) { viewportWidth.toDp() })
                                .height(with(density) { viewportHeight.toDp() })
                                // Drawn from the top left corner so that what the sideways
                                // scroll measures and what is on the screen are the same
                                // number of pixels.
                                .graphicsLayer {
                                    scaleX = scale
                                    scaleY = scale
                                    transformOrigin = TransformOrigin(0f, 0f)
                                }
                        ) {
                            items(count = state.pageCount, key = { it }) { index ->
                                PdfPage(
                                    viewModel = viewModel,
                                    index     = index,
                                    widthPx   = if (detailWidth > 0 && index == focusedPage) detailWidth
                                                else viewportWidth
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPage(viewModel: PdfViewerViewModel, index: Int, widthPx: Int) {
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    var shape by remember(index) { mutableStateOf<PdfViewerViewModel.PageShape?>(null) }

    LaunchedEffect(index, widthPx) {
        if (widthPx <= 0) return@LaunchedEffect
        shape = viewModel.shapeOf(index)
        bitmap = viewModel.render(index, widthPx)
    }

    val ratio = shape
        ?.let { it.width.toFloat() / it.height.coerceAtLeast(1) }
        ?: DEFAULT_PAGE_RATIO

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .aspectRatio(ratio)
            .background(Color.White),
        contentAlignment = Alignment.Center
    ) {
        val drawn = bitmap
        if (drawn != null) {
            Image(
                bitmap             = drawn.asImageBitmap(),
                contentDescription = stringResource(R.string.pdf_cd_page, index + 1),
                contentScale       = ContentScale.FillWidth,
                modifier           = Modifier.fillMaxSize()
            )
        }
    }
}

/** A4 upright, the shape to hold a page's place until its real one is known. */
private const val DEFAULT_PAGE_RATIO = 1f / 1.414f
private const val MAX_ZOOM = 4f
private const val ZOOM_ON_TAP = 2.5f
private const val ZOOM_FOR_DETAIL = 1.6f
private const val DETAIL_FACTOR = 1.5f
private const val MAX_RENDER_WIDTH = 2200
