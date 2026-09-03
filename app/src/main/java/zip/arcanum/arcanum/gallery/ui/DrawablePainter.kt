package zip.arcanum.arcanum.gallery.ui

import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import kotlin.math.roundToInt

/**
 * Paints a platform Drawable inside Compose. It exists for AnimatedImageDrawable, which is
 * how a GIF plays (#159) and which Compose has no painter for.
 *
 * A running AnimatedImageDrawable does not drive itself: it asks its Callback to redraw it
 * and to run its next frame later. With no callback attached it decodes one frame and stops
 * looking animated, so the callback here is the whole mechanism - invalidateDrawable bumps a
 * snapshot counter that onDraw reads, and the frame Runnables go on the main looper.
 */
private val mainHandler = Handler(Looper.getMainLooper())

internal class DrawablePainter(private val drawable: Drawable) : Painter(), RememberObserver {

    private var redraws by mutableIntStateOf(0)

    private val callback = object : Drawable.Callback {
        override fun invalidateDrawable(who: Drawable) { redraws++ }
        override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {
            mainHandler.postAtTime(what, `when`)
        }
        override fun unscheduleDrawable(who: Drawable, what: Runnable) {
            mainHandler.removeCallbacks(what)
        }
    }

    override val intrinsicSize: Size
        get() = if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
            Size(drawable.intrinsicWidth.toFloat(), drawable.intrinsicHeight.toFloat())
        else Size.Unspecified

    override fun DrawScope.onDraw() {
        drawIntoCanvas { canvas ->
            redraws                            // read it so the next frame repaints this
            drawable.setBounds(0, 0, size.width.roundToInt(), size.height.roundToInt())
            val native = canvas.nativeCanvas
            val save = native.save()
            drawable.draw(native)
            native.restoreToCount(save)
        }
    }

    override fun onRemembered() { drawable.callback = callback }
    override fun onAbandoned()  { drawable.callback = null }
    override fun onForgotten()  { drawable.callback = null }
}

@Composable
internal fun rememberDrawablePainter(drawable: Drawable): Painter =
    remember(drawable) { DrawablePainter(drawable) }
