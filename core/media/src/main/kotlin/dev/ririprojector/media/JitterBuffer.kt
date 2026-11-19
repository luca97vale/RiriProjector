package dev.ririprojector.media

/**
 * Adaptive PCM jitter buffer (ADR-007). Pure logic: one fixed ring, no allocation after
 * construction. [push] runs on the network thread, [pull] on the audio thread; both hold the same
 * short lock and never block otherwise.
 *
 * Playback starts (and restarts after an underrun) only once [targetMs] of audio is buffered.
 * An underrun outputs silence and raises the target by [stepUpMs]; a build-up above
 * target + [hysteresisMs] drops the oldest audio back to the target. After [stableMs] of played
 * audio without underruns the target shrinks by [stepDownMs]. Time is counted in played samples,
 * so behavior is deterministic and testable.
 *
 * Clock drift between the sender's capture clock and the receiver's output clock is corrected
 * gently: when the smoothed level leaves target ± [driftBandMs], each pull drops or repeats a
 * single frame (about 0.2 % speed change for 10 ms pulls, inaudible), instead of jumping.
 */
class JitterBuffer(
    sampleRateHz: Int,
    channels: Int,
    capacityMs: Int = 500,
    private val minTargetMs: Int = 20,
    private val maxTargetMs: Int = 60,
    initialTargetMs: Int = 40,
    private val hysteresisMs: Int = 20,
    private val stepUpMs: Int = 10,
    private val stepDownMs: Int = 5,
    private val stableMs: Int = 10_000,
    private val driftBandMs: Int = 10,
) {
    private val frameBytes = channels * 2
    private val bytesPerMs = sampleRateHz * frameBytes / 1000.0
    private val ring = ByteArray(align(msToBytes(capacityMs)))
    private val lock = Any()

    private var readPos = 0
    private var size = 0
    private var priming = true
    private var tailPtsUs = NO_PTS // pts just after the last buffered byte
    private var playedSinceUnderrunMs = 0.0
    private var levelMs = Double.NaN // smoothed buffer level, sampled mid-pull
    private var droppedBytes = 0L

    var targetMs: Int = initialTargetMs.coerceIn(minTargetMs, maxTargetMs)
        private set

    @Volatile var underruns: Int = 0
        private set

    /** Audio discarded by build-up drops, overflow and drift correction. */
    val droppedMs: Long get() = synchronized(lock) { (droppedBytes / bytesPerMs).toLong() }

    /** Frames dropped (positive) or repeated (negative) by drift correction. */
    @Volatile var driftFrames: Long = 0
        private set

    /** Buffered audio in milliseconds. */
    val bufferedMs: Int get() = synchronized(lock) { bytesToMs(size).toInt() }

    /** Appends [src]`[offset, offset + length)`. [ptsUs] is the pts of the first sample. */
    fun push(src: ByteArray, offset: Int, length: Int, ptsUs: Long) {
        synchronized(lock) {
            var n = length - length % frameBytes
            val skip = length - n
            var readFrom = offset
            if (n > ring.size) { // packet larger than the whole ring: keep its newest part
                readFrom += n - ring.size
                n = ring.size
            }
            val overflow = size + n - ring.size
            if (overflow > 0) discardLocked(overflow)
            var writePos = (readPos + size) % ring.size
            var left = n
            while (left > 0) {
                val chunk = minOf(left, ring.size - writePos)
                System.arraycopy(src, readFrom, ring, writePos, chunk)
                readFrom += chunk
                writePos = (writePos + chunk) % ring.size
                left -= chunk
            }
            size += n
            tailPtsUs = ptsUs + (bytesToMs(length - skip) * 1000).toLong()
        }
    }

    /**
     * Fills [dst] with [length] bytes (frame-aligned) of audio or silence.
     * Returns the pts of the first audio sample written, or [NO_PTS] when the output is silence.
     */
    fun pull(dst: ByteArray, length: Int): Long {
        synchronized(lock) {
            val chunkMs = bytesToMs(length)
            if (priming) {
                if (bytesToMs(size) < targetMs || size < length) {
                    dst.fill(0, 0, length)
                    return NO_PTS
                }
                priming = false
                levelMs = Double.NaN
            }
            if (size < length) {
                underruns++
                targetMs = (targetMs + stepUpMs).coerceAtMost(maxTargetMs)
                playedSinceUnderrunMs = 0.0
                priming = true
                dst.fill(0, 0, length)
                return NO_PTS
            }
            // Large build-up (e.g. after a network stall): jump back to target, keeping a full pull.
            if (size > msToBytes(targetMs + hysteresisMs)) discardLocked(size - maxOf(msToBytes(targetMs), length))
            val level = bytesToMs(size) - chunkMs / 2
            levelMs = if (levelMs.isNaN()) level else levelMs + LEVEL_ALPHA * (level - levelMs)
            var stretch = false
            if (levelMs > targetMs + driftBandMs && size >= length + frameBytes) {
                discardLocked(frameBytes)
                driftFrames++
            } else if (levelMs < targetMs - driftBandMs && length > frameBytes) {
                stretch = true
                driftFrames--
            }
            val pts = headPtsLocked()
            val take = if (stretch) length - frameBytes else length
            var left = take
            var off = 0
            while (left > 0) {
                val chunk = minOf(left, ring.size - readPos)
                System.arraycopy(ring, readPos, dst, off, chunk)
                readPos = (readPos + chunk) % ring.size
                off += chunk
                left -= chunk
            }
            size -= take
            if (stretch) System.arraycopy(dst, take - frameBytes, dst, take, frameBytes) // repeat the last frame
            playedSinceUnderrunMs += chunkMs
            if (playedSinceUnderrunMs >= stableMs) {
                targetMs = (targetMs - stepDownMs).coerceAtLeast(minTargetMs)
                playedSinceUnderrunMs = 0.0
            }
            return pts
        }
    }

    fun clear() {
        synchronized(lock) {
            readPos = 0
            size = 0
            priming = true
            tailPtsUs = NO_PTS
            levelMs = Double.NaN
        }
    }

    private fun headPtsLocked(): Long =
        if (tailPtsUs == NO_PTS) NO_PTS else tailPtsUs - (bytesToMs(size) * 1000).toLong()

    private fun discardLocked(bytes: Int) {
        val n = align(bytes).coerceAtMost(size)
        readPos = (readPos + n) % ring.size
        size -= n
        droppedBytes += n
    }

    private fun align(bytes: Int) = (bytes + frameBytes - 1) / frameBytes * frameBytes

    private fun msToBytes(ms: Int) = align((ms * bytesPerMs).toInt())

    private fun bytesToMs(bytes: Int) = bytes / bytesPerMs

    companion object {
        const val NO_PTS = Long.MIN_VALUE
        private const val LEVEL_ALPHA = 0.02
    }
}

/**
 * A/V offset estimator (ADR-007). Each side reports when a sample with a given sender pts reached
 * the screen / the speaker, in receiver monotonic µs. The unknown clock offset between devices
 * cancels out: offset = (audio delay) − (video delay). Positive = audio plays late.
 */
class AvSync(private val alpha: Double = 0.1) {
    private var videoDelayUs = Double.NaN
    private var audioDelayUs = Double.NaN
    private var videoArrivalUs = Double.NaN
    private var audioArrivalUs = Double.NaN

    /** Diagnostics: a packet with [ptsUs] reached the receiver at [arrivedAtUs] (monotonic µs). */
    @Synchronized
    fun onVideoArrived(ptsUs: Long, arrivedAtUs: Long) {
        videoArrivalUs = smooth(videoArrivalUs, (arrivedAtUs - ptsUs).toDouble())
    }

    @Synchronized
    fun onAudioArrived(ptsUs: Long, arrivedAtUs: Long) {
        audioArrivalUs = smooth(audioArrivalUs, (arrivedAtUs - ptsUs).toDouble())
    }

    /** Diagnostics, relative to the video arrival delay (the unknown clock offset cancels). */
    @Synchronized
    fun describe(): String {
        fun ms(v: Double) = if (v.isNaN() ||
            videoArrivalUs.isNaN()
        ) {
            "n/a"
        } else {
            "%.1f".format((v - videoArrivalUs) / 1000)
        }
        return "audio arrives ${ms(
            audioArrivalUs,
        )} ms, video shown ${ms(videoDelayUs)} ms, audio played ${ms(audioDelayUs)} ms after video arrival"
    }

    @Synchronized
    fun onVideoShown(ptsUs: Long, shownAtUs: Long) {
        videoDelayUs = smooth(videoDelayUs, (shownAtUs - ptsUs).toDouble())
    }

    @Synchronized
    fun onAudioPlayed(ptsUs: Long, playedAtUs: Long) {
        audioDelayUs = smooth(audioDelayUs, (playedAtUs - ptsUs).toDouble())
    }

    /** Smoothed audio-minus-video offset in µs, or null until both sides reported. */
    @Synchronized
    fun offsetUs(): Long? =
        if (videoDelayUs.isNaN() || audioDelayUs.isNaN()) null else (audioDelayUs - videoDelayUs).toLong()

    @Synchronized
    fun reset() {
        videoDelayUs = Double.NaN
        audioDelayUs = Double.NaN
    }

    private fun smooth(old: Double, sample: Double) = if (old.isNaN()) sample else old + alpha * (sample - old)
}
