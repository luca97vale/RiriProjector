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

    var targetMs: Int = initialTargetMs.coerceIn(minTargetMs, maxTargetMs)
        private set

    @Volatile var underruns: Int = 0
        private set

    @Volatile var droppedMs: Long = 0
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
            }
            if (size < length) {
                underruns++
                targetMs = (targetMs + stepUpMs).coerceAtMost(maxTargetMs)
                playedSinceUnderrunMs = 0.0
                priming = true
                dst.fill(0, 0, length)
                return NO_PTS
            }
            val excess = size - msToBytes(targetMs + hysteresisMs)
            if (excess > 0) discardLocked(size - msToBytes(targetMs))
            val pts = headPtsLocked()
            var left = length
            var off = 0
            while (left > 0) {
                val chunk = minOf(left, ring.size - readPos)
                System.arraycopy(ring, readPos, dst, off, chunk)
                readPos = (readPos + chunk) % ring.size
                off += chunk
                left -= chunk
            }
            size -= length
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
        }
    }

    private fun headPtsLocked(): Long =
        if (tailPtsUs == NO_PTS) NO_PTS else tailPtsUs - (bytesToMs(size) * 1000).toLong()

    private fun discardLocked(bytes: Int) {
        val n = align(bytes).coerceAtMost(size)
        readPos = (readPos + n) % ring.size
        size -= n
        droppedMs += bytesToMs(n).toLong()
    }

    private fun align(bytes: Int) = (bytes + frameBytes - 1) / frameBytes * frameBytes

    private fun msToBytes(ms: Int) = align((ms * bytesPerMs).toInt())

    private fun bytesToMs(bytes: Int) = bytes / bytesPerMs

    companion object {
        const val NO_PTS = Long.MIN_VALUE
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
