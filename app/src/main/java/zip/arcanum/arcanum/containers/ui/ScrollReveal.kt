package zip.arcanum.arcanum.containers.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.withFrameNanos

/**
 * Follows the bottom of a scrolling form while something below the fold unfolds, and
 * settles there.
 *
 * Scrolling to `maxValue` once does not work: on the frame the switch was tapped nothing
 * has moved yet, so it lands on the bottom as it was before the expansion. Waiting for two
 * equal readings has the same fault, since the first two frames are equal for that same
 * reason. Growth therefore has to be seen before stillness is allowed to count.
 *
 * Riding the bottom frame by frame rather than jumping at the end also means the eye
 * follows the content down instead of being teleported after it. The frame budget is the
 * guard against a layout that never settles, and beats waiting out the animation's declared
 * duration, which goes stale the moment that animation is retuned.
 */
internal suspend fun ScrollState.rideToBottom(frameBudget: Int = 120) {
    var seenGrowth = false
    var stillFrames = 0
    var previous = maxValue
    var frames = 0
    while (frames < frameBudget && stillFrames < STILL_FRAMES) {
        withFrameNanos { }
        frames++
        val now = maxValue
        when {
            now != previous -> { seenGrowth = true; stillFrames = 0 }
            seenGrowth      -> stillFrames++
        }
        previous = now
        scrollTo(now)
    }
    animateScrollTo(maxValue)
}

/** Frames of no movement that count as the unfolding being over. */
private const val STILL_FRAMES = 3
