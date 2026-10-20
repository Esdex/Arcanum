package zip.arcanum.arcanum.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WaveformBuilderTest {

    private fun builderOf(vararg amplitudes: Pair<Double, Int>, framesPerBlock: Int = 10) =
        WaveformBuilder(framesPerBlock).apply {
            for ((amp, frames) in amplitudes) repeat(frames) { addFrame(amp * amp) }
        }

    @Test
    fun aSteadyToneIsFullHeightEverywhere() {
        val bars = builderOf(0.3 to 1000).bars(4)!!
        bars.forEach { assertEquals(1f, it, 1e-6f) }
    }

    @Test
    fun aQuietHalfStaysInProportionToTheLoudHalf() {
        // RMS of a quarter of the amplitude is a quarter of the height - not stretched to zero.
        val bars = builderOf(0.2 to 500, 0.8 to 500).bars(2)!!
        assertEquals(0.25f, bars[0], 1e-6f)
        assertEquals(1f, bars[1], 1e-6f)
    }

    @Test
    fun aBarIsTheRmsOfItsWholeStretchNotOneMoment() {
        // A bar made of one loud block and three silent ones is half height (sqrt of 1/4),
        // wherever in the bar the loud block falls.
        val first = builderOf(1.0 to 10, 0.0 to 30, 1.0 to 40).bars(2)!!
        val last  = builderOf(0.0 to 30, 1.0 to 10, 1.0 to 40).bars(2)!!
        assertEquals(0.5f, first[0], 1e-6f)
        assertEquals(0.5f, last[0], 1e-6f)
    }

    @Test
    fun aShortLastBlockWeighsAsAFullOne() {
        // 25 frames at 10 per block: the last block has 5 frames of the same tone.
        val bars = builderOf(0.5 to 25).bars(3)!!
        bars.forEach { assertEquals(1f, it, 1e-6f) }
    }

    @Test
    fun fewerBlocksThanBarsStillFillsEveryBar() {
        val bars = builderOf(0.5 to 10, 0.25 to 10).bars(4)!!
        assertEquals(listOf(1f, 1f, 0.5f, 0.5f), bars)
    }

    @Test
    fun silenceIsFlatAndNoAudioIsNothing() {
        assertEquals(List(3) { 0f }, builderOf(0.0 to 100).bars(3))
        assertNull(WaveformBuilder(10).bars(3))
    }

    // ── While the track is still being decoded ────────────────────────────

    @Test
    fun halfwayThroughTheFirstHalfOfTheBarsIsDrawnAsTheWholeTrackWillDrawIt() {
        // A rising track: 8 steps of 100 frames, 10 frames a block.
        val steps = (1..8).map { it / 10.0 to 100 }.toTypedArray()
        val whole = builderOf(*steps).bars(8)!!
        val half = builderOf(*steps.copyOf(4).requireNoNulls()).partialBars(8, 0.5)
        assertEquals(4, half.size)
        // The same shape, scaled to the loudest bar heard so far rather than to the drop to come.
        val max = whole.take(4).max()
        half.forEachIndexed { i, h -> assertEquals(whole[i] / max, h, 1e-6f) }
    }

    @Test
    fun onlyBarsHeardInFullAreGiven() {
        // 35 % of a track of 4 bars: the first bar is done, the second is not.
        val b = builderOf(0.5 to 350)
        assertEquals(1, b.partialBars(4, 0.35).size)
        assertEquals(0, builderOf(0.5 to 350).partialBars(4, 0.0).size)
        assertEquals(0, WaveformBuilder(10).partialBars(4, 0.5).size)
        assertEquals(0, builderOf(0.5 to 350).partialBars(4, -0.1).size)
    }

    @Test
    fun atTheEndThePartialBarsAreTheBars() {
        val steps = arrayOf(0.2 to 300, 0.9 to 200, 0.4 to 500)
        val whole = builderOf(*steps).bars(10)!!
        val end = builderOf(*steps).partialBars(10, 1.0)
        assertEquals(10, end.size)
        end.forEachIndexed { i, h -> assertEquals(whole[i], h, 1e-6f) }
        // Fewer blocks than bars: each bar still has one, as in the finished waveform.
        val short = builderOf(0.5 to 30).partialBars(10, 1.0)
        assertEquals(10, short.size)
        short.forEach { assertEquals(1f, it, 1e-6f) }
    }
}
