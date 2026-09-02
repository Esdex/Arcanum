package zip.arcanum.core.notifications

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** How long the arrival takes, and how long it waits for the one before it to leave. */
private const val ENTER_MS       = 400L
private const val ENTER_DELAY_MS = 220L

/**
 * Draws whatever the [NotificationCenter] currently holds. One host, in AppNavigation.
 *
 * Everything that varies between notifications - colour, icon, wording, how long it stays -
 * comes from [spec]. This composable decides nothing about them.
 */
@Composable
fun InAppNotificationBanner(
    notification: InAppNotification?,
    onDismiss: () -> Unit,
    onAction: (InAppNotification) -> Unit,
    modifier: Modifier = Modifier
) {
    val context        = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val spec           = notification?.spec(context)

    var dragging by remember(notification) { mutableStateOf(false) }

    /*
     * Time on screen is time the user could have been reading it. The dwell therefore only
     * runs while the app is actually in front of them - a five second banner raised as the
     * app went to the background used to be spent and gone by the time they came back - and
     * it starts again when a finger that was dragging it lets go.
     */
    LaunchedEffect(notification, dragging) {
        val dwell = spec?.dwellMillis ?: 0L
        if (notification == null || dwell <= 0L || dragging) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // Plus the arrival, so a three second notification is three seconds of reading
            // rather than three seconds counted from the top of its own slide.
            delay(dwell + ENTER_MS + ENTER_DELAY_MS)
            onDismiss()
        }
    }

    /*
     * AnimatedContent rather than AnimatedVisibility, because one notification following
     * another is a change of content, not a change of visibility: with visibility alone the
     * queue moved on by swapping the words in place, and a second notification looked like
     * the first one rewriting itself. Now each leaves the way it came in and the next
     * arrives from the top after it, whether it was dismissed by hand or timed out.
     *
     * The caller's modifier belongs here and ONLY here. It used to be applied a second time
     * to the card inside, so the status bar inset and the padding were counted twice.
     */
    AnimatedContent(
        targetState    = notification.takeIf { spec != null },
        transitionSpec = {
            (slideInVertically(tween(ENTER_MS.toInt(), delayMillis = ENTER_DELAY_MS.toInt())) { -it } +
             fadeIn(tween(ENTER_MS.toInt(), delayMillis = ENTER_DELAY_MS.toInt()))) togetherWith
            (slideOutHorizontally(tween(350)) { it } + fadeOut(tween(250)))
        },
        label    = "notification_swap",
        modifier = modifier
    ) { target ->
        val notif  = target ?: return@AnimatedContent
        val config = notif.spec(context)

        var offsetX by remember { mutableFloatStateOf(0f) }
        val animatedOffset by animateFloatAsState(
            targetValue   = offsetX,
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label         = "banner_swipe"
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .offset { IntOffset(animatedOffset.roundToInt(), 0) }
                .pointerInput(notif) {
                    detectHorizontalDragGestures(
                        onDragStart      = { dragging = true },
                        onDragCancel     = { dragging = false; offsetX = 0f },
                        onDragEnd        = {
                            dragging = false
                            if (abs(offsetX) > 150f) onDismiss() else offsetX = 0f
                        },
                        onHorizontalDrag = { _, dragAmount -> offsetX += dragAmount }
                    )
                }
                // A screen reader should say an error over whatever it was reading; a
                // confirmation waits its turn.
                .semantics {
                    liveRegion = if (config.severity == NotificationSeverity.ERROR)
                        LiveRegionMode.Assertive else LiveRegionMode.Polite
                }
        ) {
            Card(
                onClick   = { onAction(notif) },
                shape     = RoundedCornerShape(16.dp),
                colors    = CardDefaults.cardColors(containerColor = config.color),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier  = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier              = Modifier.padding(12.dp),
                    verticalAlignment     = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier         = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color.White.copy(alpha = 0.15f))
                    ) {
                        Icon(
                            imageVector        = config.icon,
                            contentDescription = null,
                            tint               = Color.White,
                            modifier           = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text       = config.title,
                            style      = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color      = Color.White
                        )
                        Text(
                            text      = config.subtitle,
                            style     = MaterialTheme.typography.bodySmall,
                            color     = Color.White.copy(alpha = 0.85f),
                            maxLines  = 1,
                            overflow  = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
