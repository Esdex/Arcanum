package zip.arcanum.arcanum.containers.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.VectorPainter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import zip.arcanum.arcanum.containers.domain.StorageCategory
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One category's contribution to the donut. [included] toggles it in/out. */
data class StorageSegment(
    val category: StorageCategory,
    val bytes: Long,
    val included: Boolean,
)

private data class FloatingIcon(
    val fraction: Float, // position within its segment (0..1)
    val sizeDp: Float,
    val alpha: Float,
    val phase: Float,
)

/** A slice resolved for the current animation frame. */
private data class FrameSlice(
    val category: StorageCategory,
    val startAngle: Float,
    val sweepAngle: Float,
    val reveal: Float,   // 0..1 how far this segment has grown toward its full size
    val offset: Float,   // 0..1 peek pop-out amount
    val segAlpha: Float, // dimming: 1 = full, lower = dimmed while another is peeked
)

private const val DIM_FLOOR = 0.22f
private val PEEK_OFFSET = 12.dp

// How much of the ring's width and of the sector's arc a floating icon may take up.
// Both leave room for the icon to travel and to stay off the clip edge.
private const val BAND_ICON_FRACTION = 0.55f
private const val ARC_ICON_FRACTION  = 0.85f
private const val MIN_ICON_SIZE      = 8f

// Icons are rasterised at roughly twice their largest on-screen size, so every icon is
// scaled down rather than up and stays crisp.
private const val ICON_RASTER_DP = 56f

/**
 * Animated donut for vault storage usage. Toggling a category (via the legend)
 * smoothly shrinks/grows its slice. Press-and-hold on a slice "peeks" it: the
 * slice pops out, the centre shows that slice's size, and everything else dims;
 * releasing animates back. [onPeekChange] reports the peeked category so the
 * caller can dim the matching legend rows.
 *
 * @param active gates the icon-flight loop so it only animates while on screen.
 */
@Composable
fun StorageDonutChart(
    segments: List<StorageSegment>,
    centerText: String,
    active: Boolean = true,
    onPeekChange: (StorageCategory?) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    // Fills the donut hole; must match the page background behind the chart.
    val backgroundColor = MaterialTheme.colorScheme.background
    val onSurface = MaterialTheme.colorScheme.onSurface

    val painters: Map<StorageCategory, VectorPainter> = mapOf(
        StorageCategory.PHOTOS to rememberVectorPainter(Icons.Outlined.Image),
        StorageCategory.VIDEOS to rememberVectorPainter(Icons.Outlined.Movie),
        StorageCategory.MUSIC  to rememberVectorPainter(Icons.Outlined.MusicNote),
        StorageCategory.FILES  to rememberVectorPainter(Icons.Outlined.InsertDriveFile),
    )

    // Each icon is rasterised once and then blitted, rather than asking the same
    // VectorPainter to draw itself at a different size for every icon on every frame.
    // A VectorPainter is backed by a single graphics layer that it re-records whenever
    // the requested size changes, and all of a frame's draws reference that one layer -
    // so with icons of differing sizes they all ended up showing whichever size was
    // recorded last, stretched into each icon's rect. That was the artifact.
    val density = LocalDensity.current
    val iconRasters: Map<StorageCategory, ImageBitmap> = remember(density) {
        val px = with(density) { ICON_RASTER_DP.dp.toPx() }.roundToInt().coerceAtLeast(1)
        painters.mapValues { (_, painter) ->
            val bitmap = ImageBitmap(px, px)
            CanvasDrawScope().draw(
                density        = density,
                layoutDirection = LayoutDirection.Ltr,
                canvas         = Canvas(bitmap),
                size           = Size(px.toFloat(), px.toFloat())
            ) {
                with(painter) { draw(size = Size(px.toFloat(), px.toFloat())) }
            }
            bitmap
        }
    }

    // Per-category animated weight: bytes while included, 0 while excluded.
    val weights = remember { mutableStateMapOf<StorageCategory, Animatable<Float, AnimationVector1D>>() }
    LaunchedEffect(segments) {
        val present = segments.map { it.category }.toSet()
        segments.forEach { seg ->
            val anim = weights.getOrPut(seg.category) { Animatable(0f) }
            val target = if (seg.included) seg.bytes.toFloat() else 0f
            launch { anim.animateTo(target, tween(500, easing = FastOutSlowInEasing)) }
        }
        weights.keys.filter { it !in present }.forEach { cat ->
            launch { weights[cat]?.animateTo(0f, tween(500, easing = FastOutSlowInEasing)) }
        }
    }

    // Press-and-hold peek state.
    var pressedCategory by remember { mutableStateOf<StorageCategory?>(null) }
    val sliceOffsets = remember { mutableStateMapOf<StorageCategory, Animatable<Float, AnimationVector1D>>() }
    val dimProgress = remember { Animatable(0f) }
    LaunchedEffect(pressedCategory, segments) {
        segments.forEach { seg ->
            val anim = sliceOffsets.getOrPut(seg.category) { Animatable(0f) }
            launch { anim.animateTo(if (seg.category == pressedCategory) 1f else 0f, tween(220, easing = FastOutSlowInEasing)) }
        }
    }
    LaunchedEffect(pressedCategory) {
        dimProgress.animateTo(if (pressedCategory != null) 1f else 0f, tween(220, easing = FastOutSlowInEasing))
    }

    // Icon flight cycle — only runs while visible.
    val iconCycleAnim = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (active) {
            while (true) {
                iconCycleAnim.snapTo(0f)
                iconCycleAnim.animateTo(1f, animationSpec = tween(2400, easing = LinearEasing))
            }
        } else {
            iconCycleAnim.stop()
        }
    }
    val iconCycle = iconCycleAnim.value

    val floatingIcons: Map<StorageCategory, List<FloatingIcon>> = remember(segments) {
        val rng = kotlin.random.Random(42)
        val targetTotal = segments.filter { it.included }.sumOf { it.bytes }.coerceAtLeast(1L)
        segments.associate { seg ->
            val targetSweep = if (seg.included) seg.bytes.toFloat() / targetTotal * 360f else 0f
            val count = max(2, (targetSweep / 360f * 10f).toInt())
            seg.category to List(count) { i ->
                FloatingIcon(
                    fraction = (i + 0.5f) / count,
                    sizeDp   = rng.nextFloat() * 10f + 16f,
                    alpha    = rng.nextFloat() * 0.3f + 0.3f,
                    phase    = i.toFloat() / count,
                )
            }
        }
    }

    // Resolve slices for this frame from the animated weights + peek state.
    val total = segments.sumOf { (weights[it.category]?.value ?: 0f).toDouble() }
        .toFloat().coerceAtLeast(0.0001f)
    val dim = dimProgress.value
    var startAcc = -90f
    val frameSlices: List<FrameSlice> = segments.mapNotNull { seg ->
        val w = weights[seg.category]?.value ?: 0f
        if (w <= 0.001f) return@mapNotNull null
        val sweep = w / total * 360f
        val reveal = min(1f, w / seg.bytes.toFloat().coerceAtLeast(1f))
        val off = sliceOffsets[seg.category]?.value ?: 0f
        val segAlpha = 1f - dim * (1f - off) * (1f - DIM_FLOOR)
        FrameSlice(seg.category, startAcc, sweep, reveal, off, segAlpha).also { startAcc += sweep }
    }

    Box(
        modifier = modifier.pointerInput(segments) {
            detectTapGestures(onPress = { pos ->
                val included = segments.filter { it.included && it.bytes > 0L }
                val sum = included.sumOf { it.bytes }.coerceAtLeast(1L)
                val minDim = min(size.width, size.height).toFloat()
                val outerR = minDim / 2f * 0.93f
                val innerR = outerR * 0.54f
                val dx = pos.x - size.width / 2f
                val dy = pos.y - size.height / 2f
                val r = sqrt(dx * dx + dy * dy)
                val cat = if (r in innerR..outerR) {
                    val a = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat() + 90f) + 360f) % 360f
                    var acc = 0f
                    var found: StorageCategory? = null
                    for (seg in included) {
                        val sweep = seg.bytes.toFloat() / sum * 360f
                        if (a < acc + sweep) { found = seg.category; break }
                        acc += sweep
                    }
                    found
                } else null

                if (cat != null) {
                    pressedCategory = cat
                    onPeekChange(cat)
                    tryAwaitRelease()
                    pressedCategory = null
                    onPeekChange(null)
                }
            })
        }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (frameSlices.isEmpty()) return@Canvas

            val outerRadius = size.minDimension / 2f * 0.93f
            val innerRadius = outerRadius * 0.54f
            val cx = center.x
            val cy = center.y
            val peekPx = PEEK_OFFSET.toPx()

            val outerRect = Rect(cx - outerRadius, cy - outerRadius, cx + outerRadius, cy + outerRadius)
            val innerRect = Rect(cx - innerRadius, cy - innerRadius, cx + innerRadius, cy + innerRadius)

            val gapDeg = if (frameSlices.size > 1) {
                Math.toDegrees((2.dp.toPx() / outerRadius).toDouble()).toFloat()
            } else 0f

            // Per-slice pop-out offset vector along its mid-angle.
            fun offsetOf(slice: FrameSlice): Offset {
                if (slice.offset <= 0f) return Offset.Zero
                val midRad = Math.toRadians((slice.startAngle + slice.sweepAngle / 2f).toDouble()).toFloat()
                val d = slice.offset * peekPx
                return Offset(cos(midRad) * d, sin(midRad) * d)
            }

            // ── 1. Filled arc sectors ────────────────────────────────────────
            frameSlices.forEach { slice ->
                val adjStart = slice.startAngle + gapDeg / 2f
                val adjSweep = (slice.sweepAngle - gapDeg).coerceAtLeast(0f)
                if (adjSweep <= 0f) return@forEach
                val o = offsetOf(slice)

                drawArc(
                    color = Color(slice.category.colorValue).copy(alpha = slice.segAlpha),
                    startAngle = adjStart,
                    sweepAngle = adjSweep,
                    useCenter = true,
                    topLeft = Offset(cx - outerRadius + o.x, cy - outerRadius + o.y),
                    size = Size(outerRadius * 2f, outerRadius * 2f)
                )

                // Ring glow clipped to this segment's revealed arc, WITHOUT the pop-out
                // offset — it stays in place on the donut so its highlight band keeps
                // hugging the hole edge (matching Regularity's fix for this artifact).
                val innerFrac = innerRadius / outerRadius
                drawContext.canvas.save()
                if (adjSweep >= 360f) {
                    drawContext.canvas.clipPath(Path().apply { addOval(outerRect) })
                    drawContext.canvas.clipPath(Path().apply { addOval(innerRect) }, ClipOp.Difference)
                } else {
                    drawContext.canvas.clipPath(Path().apply {
                        arcTo(outerRect, adjStart, adjSweep, forceMoveTo = true)
                        arcTo(innerRect, adjStart + adjSweep, -adjSweep, forceMoveTo = false)
                        close()
                    })
                }
                drawCircle(
                    brush = Brush.radialGradient(
                        colorStops = arrayOf(
                            0f to Color.Transparent,
                            innerFrac to Color.White.copy(alpha = 0.12f * slice.segAlpha),
                            1f to Color.Transparent
                        ),
                        center = center,
                        radius = outerRadius
                    ),
                    radius = outerRadius,
                    center = center
                )
                drawContext.canvas.restore()
            }

            // ── 2. Donut hole ────────────────────────────────────────────────
            drawCircle(backgroundColor, radius = innerRadius, center = center)

            // ── 3. Flying icons (clipped to each offset segment) ─────────────
            frameSlices.forEach { slice ->
                val raster = iconRasters[slice.category] ?: return@forEach // FREE_SPACE has none
                val adjStart = slice.startAngle + gapDeg / 2f
                val adjSweep = (slice.sweepAngle - gapDeg).coerceAtLeast(0f)
                if (adjSweep <= 0f || slice.reveal < 0.05f) return@forEach
                val o = offsetOf(slice)

                val iconColor = lerp(Color(slice.category.colorValue), Color.White, 0.5f)
                val outerRectOff = outerRect.translate(o.x, o.y)
                val innerRectOff = innerRect.translate(o.x, o.y)

                drawContext.canvas.save()
                if (adjSweep >= 360f) {
                    drawContext.canvas.clipPath(Path().apply { addOval(outerRectOff) })
                    drawContext.canvas.clipPath(Path().apply { addOval(innerRectOff) }, ClipOp.Difference)
                } else {
                    drawContext.canvas.clipPath(Path().apply {
                        arcTo(outerRectOff, adjStart, adjSweep, forceMoveTo = true)
                        arcTo(innerRectOff, adjStart + adjSweep, -adjSweep, forceMoveTo = false)
                        close()
                    })
                }

                floatingIcons[slice.category]?.forEach { icon ->
                    val t = ((iconCycle + icon.phase) % 1.0f)
                    val fadeIn = (t / 0.15f).coerceIn(0f, 1f)
                    val fadeOut = ((1f - t) / 0.15f).coerceIn(0f, 1f)
                    val iconAlpha = (icon.alpha * min(fadeIn, fadeOut) * slice.reveal * slice.segAlpha).coerceIn(0f, 1f)
                    if (iconAlpha < 0.01f) return@forEach

                    // The clip below is a hard, unantialiased edge, so an icon that reaches it
                    // is not faded out - it is sliced. Fit the icon inside its slice instead:
                    // bounded by the ring's width, and by the sector's arc width at its
                    // narrowest point (the inner edge). Without the arc bound a thin segment
                    // cut every icon down to a vertical sliver.
                    val bandWidth = outerRadius - innerRadius
                    val arcWidthAtInner = Math.toRadians(adjSweep.toDouble()).toFloat() * innerRadius
                    val sizePx = min(
                        icon.sizeDp.dp.toPx(),
                        min(bandWidth * BAND_ICON_FRACTION, arcWidthAtInner * ARC_ICON_FRACTION)
                    )
                    // Below this it reads as a speck rather than an icon - drop it instead.
                    if (sizePx < MIN_ICON_SIZE.dp.toPx()) return@forEach
                    val half = sizePx / 2f

                    // Position within the drawn arc, not the raw slice: the raw one includes
                    // the gap between segments, so an icon could sit centred in the gap and
                    // be clipped in half lengthwise.
                    val angle = adjStart + icon.fraction * adjSweep
                    val rad = Math.toRadians(angle.toDouble()).toFloat()
                    // Inset by half an icon at both ends so the flight stays clear of the clip.
                    val curRadius = (innerRadius + half) + (bandWidth - sizePx) * t
                    val ix = cx + cos(rad) * curRadius + o.x
                    val iy = cy + sin(rad) * curRadius + o.y

                    val dst = sizePx.roundToInt().coerceAtLeast(1)
                    drawImage(
                        image         = raster,
                        srcOffset     = IntOffset.Zero,
                        srcSize       = IntSize(raster.width, raster.height),
                        dstOffset     = IntOffset((ix - half).roundToInt(), (iy - half).roundToInt()),
                        dstSize       = IntSize(dst, dst),
                        alpha         = iconAlpha,
                        colorFilter   = ColorFilter.tint(iconColor),
                        filterQuality = FilterQuality.High
                    )
                }

                drawContext.canvas.restore()
            }

            // ── 4. Re-cover inner circle ─────────────────────────────────────
            drawCircle(backgroundColor, radius = innerRadius, center = center)

            // ── 5. Percentage labels ─────────────────────────────────────────
            frameSlices.forEach { slice ->
                val adjSweep = (slice.sweepAngle - gapDeg).coerceAtLeast(0f)
                if (adjSweep < 5f) return@forEach
                val pct = (slice.sweepAngle / 360f * 100f).roundToInt()
                if (pct < 10) return@forEach

                val measured = textMeasurer.measure(
                    "$pct%",
                    style = TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                )

                val o = offsetOf(slice)
                val midRad = Math.toRadians((slice.startAngle + slice.sweepAngle / 2f).toDouble()).toFloat()
                val labelR = (innerRadius + outerRadius) / 2f
                val lx = cx + cos(midRad) * labelR + o.x
                val ly = cy + sin(midRad) * labelR + o.y

                val padH = 5.dp.toPx()
                val padV = 2.5.dp.toPx()
                val pillW = measured.size.width + 2 * padH
                val pillH = measured.size.height + 2 * padV

                drawRoundRect(
                    color = Color.Black.copy(alpha = 0.4f * slice.reveal * slice.segAlpha),
                    topLeft = Offset(lx - pillW / 2f, ly - pillH / 2f),
                    size = Size(pillW, pillH),
                    cornerRadius = CornerRadius(8.dp.toPx())
                )
                drawText(
                    measured,
                    topLeft = Offset(lx - measured.size.width / 2f, ly - measured.size.height / 2f),
                    alpha = slice.reveal * slice.segAlpha
                )
            }
        }

        // ── 6. Center label — peek size while pressed, total otherwise ────────
        val pressed = pressedCategory
        val centerDisplay = if (pressed != null) {
            (segments.firstOrNull { it.category == pressed }?.bytes ?: 0L).formatStorageSize()
        } else centerText
        AnimatedContent(
            targetState = centerDisplay,
            transitionSpec = {
                // New value grows out from the centre; old value shrinks inward.
                (fadeIn(tween(220)) + scaleIn(tween(220), initialScale = 0.5f)) togetherWith
                    (fadeOut(tween(180)) + scaleOut(tween(180), targetScale = 0.5f))
            },
            label = "storage_center",
            contentAlignment = Alignment.Center,
            modifier = Modifier.align(Alignment.Center)
        ) { txt ->
            Text(
                text = txt,
                style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
                color = onSurface
            )
        }
    }
}
