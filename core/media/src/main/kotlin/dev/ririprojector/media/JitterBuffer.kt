package dev.ririprojector.media

/**
 * Adaptive PCM jitter buffer (ADR-007, rev. M6). Pure logic: one fixed ring, no allocation after
 * construction. [push] runs on the network thread, [pull] on the audio thread; both hold the same
 * short lock and never block otherwise. Time is counted in pulled (played) audio, so behavior is
 * deterministic and testable.
 *
 * Audio over Wi-Fi arrives in clumps: the buffer level is a sawtooth, high right after a clump and
 * lowest just before the next one. So the buffer controls its **low-water mark** (the lowest level
 * after a pull), never its instantaneous level, which would throw every clump away:
 * - Arrival gaps are measured as the audio pulled between two pushes.
 * - After an underrun (silence is output) the target grows by [stepUpMs] and playback restarts
 *   once target + the largest recent gap is buffered. After [stableMs] without one it shrinks by
 *   [stepDownMs]. [targetMs] is thus the margin kept *below* the deepest dip seen recently.
 * - Every [windowMs] the lowest level over the last [historyWindows] windows is compared with the
 *   target: an excess is removed by dropping, a deficit rebuilt by repeating, at most
 *   [maxCorrectionFrames] single frames per pull (≤ 0.8 % speed change for 10 ms pulls,
 *   inaudible). This also absorbs clock drift between the sender's capture and our output.
 * - A whole window whose low-water is more than [jumpMs] above target (audio piled up after a
 *   network stall) jumps back at once instead of draining slowly.
 */
class JitterBuffer(
    sampleRateHz: Int,
    channels: Int,
    capacityMs: Int = 500,
    private val minTargetMs: Int = 10,
    private val maxTargetMs: Int = 120,
    initialTargetMs: Int = 20,
    /** Gap assumed before any is measured (priming = target + gap). */
    private val initialGapMs: Int = 20,
    private val stepUpMs: Int = 10,
    private val stepDownMs: Int = 5,
    private val stableMs: Int = 30_000,
    private val windowMs: Int = 2_000,
    private val historyWindows: Int = 4,
    private val bandMs: Int = 5,
    private val jumpMs: Int = 100,
    private val maxCorrectionFrames: Int = 4,
) {
    private val frameBytes = channels * 2
    private val bytesPerMs = sampleRateHz * frameBytes / 1000.0
    private val ring = ByteArray(align(msToBytes(capacityMs)))
    private val maxPrimeMs = capacityMs * 4 / 5
    private val lock = Any()

    private var readPos = 0
    private var size = 0
    private var priming = true
    private var primeMs = 0
    private var tailPtsUs = NO_PTS // pts just after the last buffered byte
    private var playedSinceUnderrunMs = 0.0
    private var droppedBytes = 0L

    // Arrival gap in progress: audio pulled since the last push.
    private var pulledSincePushMs = 0.0
    private var sawPush = false

    // The window being measured.
    private var windowPlayedMs = 0.0
    private var windowMinMs = Double.MAX_VALUE
    private var windowMaxGapMs = 0.0
    private var windowClean = true // no silence output during the window

    // Closed windows; a NaN minimum marks a window that output silence.
    private val historyMin = DoubleArray(historyWindows)
    private val historyGap = DoubleArray(historyWindows)
    private var historyCount = 0
    private var historyNext = 0

    /** Pending level correction in frames: > 0 to drop, < 0 to repeat. */
    private var correctionFrames = 0

    var targetMs: Int = initialTargetMs.coerceIn(minTargetMs, maxTargetMs)
        private set

    @Volatile var underruns: Int = 0
        private set

    /** Audio discarded by level correction, stall recovery and overflow. */
    val droppedMs: Long get() = synchronized(lock) { (droppedBytes / bytesPerMs).toLong() }

    /** Net frames dropped (positive) or repeated (negative) by level correction. */
    @Volatile var driftFrames: Long = 0
        private set

    /** Largest arrival gap over the recent windows, in ms (diagnostics). */
    @Volatile var peakGapMs: Int = initialGapMs
        private set

    /** Lowest level after a pull over the recent windows, in ms; -1 until known (diagnostics). */
    @Volatile var lowWaterMs: Int = -1
        private set

    /** Buffered audio in milliseconds. */
    val bufferedMs: Int get() = synchronized(lock) { bytesToMs(size).toInt() }

    init {
        startPriming()
    }

    /** Appends [src]`[offset, offset + length)`. [ptsUs] is the pts of the first sample. */
    fun push(src: ByteArray, offset: Int, length: Int, ptsUs: Long) {
        synchronized(lock) {
            if (sawPush && pulledSincePushMs > windowMaxGapMs) windowMaxGapMs = pulledSincePushMs
            sawPush = true
            pulledSincePushMs = 0.0
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
            pulledSincePushMs += chunkMs
            if (priming) {
                if (bytesToMs(size) < primeMs || size < length) return silenceLocked(dst, length, chunkMs)
                priming = false
            }
            if (size < length) {
                underruns++
                targetMs = (targetMs + stepUpMs).coerceAtMost(maxTargetMs)
                playedSinceUnderrunMs = 0.0
                // Levels measured so far no longer describe the buffer; the gaps still do.
                for (i in 0 until historyCount) historyMin[i] = Double.NaN
                startPriming()
                return silenceLocked(dst, length, chunkMs)
            }

            var take = length
            var repeat = 0
            if (correctionFrames > 0) {
                val n = minOf(correctionFrames, maxCorrectionFrames, (size - length) / frameBytes)
                if (n > 0) {
                    discardLocked(n * frameBytes)
                    correctionFrames -= n
                    driftFrames += n
                    shiftLevels(-n * frameBytes / bytesPerMs)
                }
            } else if (correctionFrames < 0) {
                repeat = minOf(-correctionFrames, maxCorrectionFrames, length / frameBytes - 1)
                take = length - repeat * frameBytes
                correctionFrames += repeat
                driftFrames -= repeat
                shiftLevels(repeat * frameBytes / bytesPerMs)
            }

            val pts = headPtsLocked()
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
            for (k in 0 until repeat) { // repeat the last frame
                System.arraycopy(dst, take - frameBytes, dst, take + k * frameBytes, frameBytes)
            }

            playedSinceUnderrunMs += chunkMs
            if (playedSinceUnderrunMs >= stableMs) {
                targetMs = (targetMs - stepDownMs).coerceAtLeast(minTargetMs)
                playedSinceUnderrunMs = 0.0
            }
            val level = bytesToMs(size)
            if (level < windowMinMs) windowMinMs = level
            advanceWindowLocked(chunkMs)
            return pts
        }
    }

    fun clear() {
        synchronized(lock) {
            readPos = 0
            size = 0
            tailPtsUs = NO_PTS
            sawPush = false
            pulledSincePushMs = 0.0
            historyCount = 0
            historyNext = 0
            peakGapMs = initialGapMs
            lowWaterMs = -1
            startPriming()
        }
    }

    private fun silenceLocked(dst: ByteArray, length: Int, chunkMs: Double): Long {
        dst.fill(0, 0, length)
        windowClean = false
        advanceWindowLocked(chunkMs)
        return NO_PTS
    }

    private fun startPriming() {
        val gap = maxOf(recentGapMs(), pulledSincePushMs).toInt()
        primeMs = (targetMs + maxOf(gap, initialGapMs)).coerceAtMost(maxPrimeMs)
        priming = true
        correctionFrames = 0
        resetWindow()
    }

    private fun advanceWindowLocked(chunkMs: Double) {
        windowPlayedMs += chunkMs
        if (windowPlayedMs < windowMs) return
        historyMin[historyNext] = if (windowClean && windowMinMs != Double.MAX_VALUE) windowMinMs else Double.NaN
        historyGap[historyNext] = windowMaxGapMs
        historyNext = (historyNext + 1) % historyWindows
        if (historyCount < historyWindows) historyCount++
        val latestMin = if (windowClean) windowMinMs else Double.NaN
        resetWindow()
        peakGapMs = recentGapMs().toInt()
        decideLocked(latestMin)
    }

    /** Sets the level correction from the low-water marks (see the class comment). */
    private fun decideLocked(latestMin: Double) {
        if (!latestMin.isNaN() && latestMin - targetMs > jumpMs) {
            val ms = minOf(latestMin, bytesToMs(size)) - targetMs
            if (ms > 0) {
                discardLocked(msToBytes(ms.toInt()))
                shiftLevels(-ms)
            }
            correctionFrames = 0
            return
        }
        var low = Double.MAX_VALUE
        for (i in 0 until historyCount) {
            val m = historyMin[i]
            if (m.isNaN()) {
                lowWaterMs = -1
                correctionFrames = 0
                return
            }
            if (m < low) low = m
        }
        lowWaterMs = low.toInt()
        if (historyCount < historyWindows) return
        val excess = low - targetMs
        correctionFrames =
            when {
                excess > bandMs -> (excess * bytesPerMs).toInt() / frameBytes
                excess < -bandMs -> -((-excess * bytesPerMs).toInt() / frameBytes)
                else -> 0
            }
    }

    /** The recorded window minima move with the level when we drop or repeat audio. */
    private fun shiftLevels(ms: Double) {
        for (i in 0 until historyCount) historyMin[i] += ms // NaN stays NaN
        if (windowMinMs != Double.MAX_VALUE) windowMinMs += ms
    }

    private fun recentGapMs(): Double {
        var max = windowMaxGapMs
        for (i in 0 until historyCount) if (historyGap[i] > max) max = historyGap[i]
        return max
    }

    private fun resetWindow() {
        windowPlayedMs = 0.0
        windowMinMs = Double.MAX_VALUE
        windowMaxGapMs = 0.0
        windowClean = true
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
    private var videoSamples = 0L
    private var audioSamples = 0L
    private var lastVideoAtUs = 0L
    private var lastAudioAtUs = 0L

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
        videoSamples++
        lastVideoAtUs = shownAtUs
    }

    @Synchronized
    fun onAudioPlayed(ptsUs: Long, playedAtUs: Long) {
        audioDelayUs = smooth(audioDelayUs, (playedAtUs - ptsUs).toDouble())
        audioSamples++
        lastAudioAtUs = playedAtUs
    }

    /** Smoothed audio-minus-video offset in µs, or null until both sides reported. */
    @Synchronized
    fun offsetUs(): Long? =
        if (videoDelayUs.isNaN() || audioDelayUs.isNaN()) null else (audioDelayUs - videoDelayUs).toLong()

    /**
     * Smoothed `receiver time − pts` per stage, in µs (null until seen). Adding the sender-minus-
     * receiver clock offset turns each into a capture→stage latency (diagnostics, once per second).
     */
    data class Stages(val videoArrived: Long?, val videoShown: Long?, val audioArrived: Long?, val audioPlayed: Long?)

    @Synchronized
    fun stages(): Stages {
        fun l(v: Double) = if (v.isNaN()) null else v.toLong()
        return Stages(l(videoArrivalUs), l(videoDelayUs), l(audioArrivalUs), l(audioDelayUs))
    }

    /** Offset plus per-side sample counts and the time of each side's latest sample. */
    @Synchronized
    fun snapshot(): Snapshot = Snapshot(offsetUs(), videoSamples, audioSamples, lastVideoAtUs, lastAudioAtUs)

    @Synchronized
    fun reset() {
        videoDelayUs = Double.NaN
        audioDelayUs = Double.NaN
        videoSamples = 0
        audioSamples = 0
        lastVideoAtUs = 0
        lastAudioAtUs = 0
    }

    /** Counters only grow until [reset]; timestamps are receiver monotonic µs (0 = never). */
    data class Snapshot(
        val offsetUs: Long?,
        val videoSamples: Long,
        val audioSamples: Long,
        val lastVideoAtUs: Long,
        val lastAudioAtUs: Long,
    )

    private fun smooth(old: Double, sample: Double) = if (old.isNaN()) sample else old + alpha * (sample - old)
}
