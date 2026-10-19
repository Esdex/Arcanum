package zip.arcanum.arcanum.gallery

import kotlin.math.sqrt

/**
 * Turns a track's decoded audio into the bars of its waveform.
 *
 * Every frame of the whole track goes in, in order, and a bar is the RMS of all the frames in
 * its stretch of the track, scaled so the loudest bar is full height. That is what makes the
 * shape of a song readable: a quiet intro, a drop, a breakdown. Peaks would not do - a mastered
 * track touches the ceiling everywhere and its peaks draw a flat fence.
 *
 * Frames are first summed into short blocks, and the blocks are shared out among the bars only
 * at the end, so nothing here needs to know how long the track is. A duration from the file's
 * header can be wrong, and a decoder can deliver a little more or less than it promised.
 */
class WaveformBuilder(private val framesPerBlock: Int) {

    init { require(framesPerBlock > 0) }

    private var sums = DoubleArray(1024)
    private var blockCount = 0
    private var blockSum = 0.0
    private var blockFrames = 0

    /** One frame: the mean of the squares of its channels' samples, each in -1..1. */
    fun addFrame(energy: Double) {
        blockSum += energy
        if (++blockFrames == framesPerBlock) closeBlock()
    }

    private fun closeBlock() {
        if (blockCount == sums.size) sums = sums.copyOf(sums.size * 2)
        // A block shorter than the rest (the last one) is scaled up, so it weighs as a full one.
        sums[blockCount++] = blockSum * framesPerBlock / blockFrames
        blockSum = 0.0
        blockFrames = 0
    }

    /** The bars, each 0..1, or null if no audio came in at all. */
    fun bars(count: Int): List<Float>? {
        require(count > 0)
        if (blockFrames > 0) closeBlock()
        if (blockCount == 0) return null
        val rms = FloatArray(count) { bar ->
            // Every bar gets at least one block, so a track shorter than [count] blocks repeats
            // its blocks rather than leaving bars empty.
            val from = (bar.toLong() * blockCount / count).toInt()
            val to   = maxOf(from + 1, ((bar + 1).toLong() * blockCount / count).toInt())
            var sum = 0.0
            for (i in from until to) sum += sums[i]
            sqrt(sum / ((to - from).toDouble() * framesPerBlock)).toFloat()
        }
        val max = rms.max()
        if (max <= 0f) return List(count) { 0f }
        return rms.map { it / max }
    }
}
