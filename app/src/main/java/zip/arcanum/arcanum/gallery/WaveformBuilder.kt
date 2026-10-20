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
        // Every bar gets at least one block, so a track shorter than [count] blocks repeats
        // its blocks rather than leaving bars empty.
        return normalised(FloatArray(count) { bar ->
            val from = (bar.toLong() * blockCount / count).toInt()
            rms(from, maxOf(from + 1, ((bar + 1).toLong() * blockCount / count).toInt()))
        })
    }

    /**
     * The first of [count] bars while the track is still being decoded, [done] (0..1) being
     * how much of it has been: only the bars whose stretch has been heard in full, scaled to
     * the loudest of them. The track's length comes from [done], since the blocks so far are
     * that share of all there will be. Empty until the first bar is complete.
     *
     * The heights move as the track goes on - a quiet intro is full height until the drop
     * arrives - and [bars] has the last word once the decoding ends.
     */
    fun partialBars(count: Int, done: Double): List<Float> {
        require(count > 0)
        if (done <= 0.0 || blockCount == 0) return emptyList()
        val total = blockCount / minOf(done, 1.0)
        val rms = ArrayList<Float>(count)
        for (bar in 0 until count) {
            val from = (bar * total / count).toInt()
            val to = maxOf(from + 1, ((bar + 1) * total / count).toInt())
            if (to > blockCount) break
            rms += rms(from, to)
        }
        return normalised(rms.toFloatArray())
    }

    private fun rms(from: Int, to: Int): Float {
        var sum = 0.0
        for (i in from until to) sum += sums[i]
        return sqrt(sum / ((to - from).toDouble() * framesPerBlock)).toFloat()
    }

    private fun normalised(rms: FloatArray): List<Float> {
        if (rms.isEmpty()) return emptyList()
        val max = rms.max()
        if (max <= 0f) return List(rms.size) { 0f }
        return rms.map { it / max }
    }
}
