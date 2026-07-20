package zip.arcanum.settings

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs
import kotlin.random.Random

private val CONFETTI_COLORS = listOf(
    Color(0xFF4F8DFD), Color(0xFFAF52DE), Color(0xFF34C759),
    Color(0xFFFF9F0A), Color(0xFFFF375F), Color(0xFF64D2FF),
)

private const val PER_CANNON = 50         // fired from each bottom corner
private const val GRAVITY    = 1500f      // px/s^2
private const val DRAG       = 0.55f      // per second, applied to horizontal drift only

/** Aim, measured up from the horizontal, and how wide each cannon fans out. */
private const val LAUNCH_ANGLE_DEG = 60f
private const val SPREAD_DEG       = 45f
/** Enough to put the fastest pieces near the top of a tall screen, the slowest halfway. */
private const val MIN_SPEED = 1700f
private const val MAX_SPEED = 2600f

private class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    var rotation: Float,
    var spin: Float,
    val w: Float,
    val h: Float,
    val color: Color,
) {
    /** Squash factor, so a piece reads as tumbling rather than sliding. */
    val flutter: Float get() = abs(kotlin.math.cos(Math.toRadians(rotation.toDouble())).toFloat())
}

/**
 * A burst of confetti fired up from the two bottom corners, drawn over whatever is
 * behind it. Pieces arc under gravity and are dropped once they fall back off screen.
 *
 * Increment [burst] to fire; the value itself is not used beyond being a new key. The
 * frame loop only runs while pieces are still on screen, so an idle screen costs nothing.
 * Draws no pointer input of its own, so taps pass straight through to the content below.
 */
@Composable
internal fun ConfettiOverlay(burst: Int, modifier: Modifier = Modifier) {
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val particles   = remember { mutableListOf<Particle>() }
    // Bumped every frame and read inside the draw lambda, so a frame costs a redraw
    // rather than a recomposition.
    val tick = remember { mutableIntStateOf(0) }

    LaunchedEffect(burst, canvasSize) {
        if (burst == 0 || canvasSize == IntSize.Zero) return@LaunchedEffect

        val w = canvasSize.width.toFloat()
        val h = canvasSize.height.toFloat()

        // Two cannons in the bottom corners, each aimed up and inwards. Screen y grows
        // downward, so an upward shot is a negative vy - hence the negated angle.
        listOf(0f to LAUNCH_ANGLE_DEG, w to 180f - LAUNCH_ANGLE_DEG).forEach { (originX, aim) ->
            repeat(PER_CANNON) {
                val deg   = aim + (Random.nextFloat() - 0.5f) * SPREAD_DEG
                val rad   = Math.toRadians(deg.toDouble())
                val speed = MIN_SPEED + Random.nextFloat() * (MAX_SPEED - MIN_SPEED)
                particles += Particle(
                    x        = originX,
                    y        = h,
                    vx       = (kotlin.math.cos(rad) * speed).toFloat(),
                    vy       = (-kotlin.math.sin(rad) * speed).toFloat(),
                    rotation = Random.nextFloat() * 360f,
                    spin     = (Random.nextFloat() - 0.5f) * 720f,
                    w        = 7f + Random.nextFloat() * 7f,
                    h        = 11f + Random.nextFloat() * 9f,
                    color    = CONFETTI_COLORS[Random.nextInt(CONFETTI_COLORS.size)],
                )
            }
        }

        var last = withFrameNanos { it }
        while (particles.isNotEmpty()) {
            withFrameNanos { now ->
                // Clamped so a dropped frame or a backgrounded screen cannot teleport
                // every piece past the bottom in one step.
                val dt = ((now - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = now
                val floor = canvasSize.height + 60f
                val it = particles.iterator()
                while (it.hasNext()) {
                    val p = it.next()
                    p.vy += GRAVITY * dt
                    p.vx -= p.vx * DRAG * dt
                    p.x  += p.vx * dt
                    p.y  += p.vy * dt
                    p.rotation += p.spin * dt
                    if (p.y > floor) it.remove()
                }
                tick.intValue++
            }
        }
    }

    Canvas(modifier = modifier.onSizeChanged { canvasSize = it }) {
        tick.intValue   // read for draw-phase invalidation
        particles.forEach { p ->
            rotate(degrees = p.rotation, pivot = Offset(p.x + p.w / 2f, p.y + p.h / 2f)) {
                drawRect(
                    color   = p.color,
                    topLeft = Offset(p.x, p.y),
                    size    = Size(p.w, p.h * p.flutter.coerceAtLeast(0.25f))
                )
            }
        }
    }
}
