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
}
