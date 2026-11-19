package dev.ririprojector.media

/**
 * Decides which access units reach the decoder (ADR-005). Keeps at most [maxInFlight] frames
 * between queueing and rendering; beyond that the decoder is falling behind, so non-key frames are
 * dropped until the next keyframe (later P-frames would reference dropped ones) and a keyframe is
 * requested once per episode. After (re)configuration nothing is decoded before a CONFIG followed
 * by a keyframe. Not thread-safe: guard externally if called from several threads.
 */
class DecoderBacklog(private val maxInFlight: Int = 3) {
    enum class Decision { DECODE, DROP, DROP_AND_REQUEST_KEYFRAME }

    var inFlight: Int = 0
        private set
    var dropped: Long = 0
        private set
    private var haveConfig = false
    private var waitingForKeyframe = true
    private var requested = false

    /** Call for every incoming access unit; [isConfig] for CSD packets. */
    fun onAccessUnit(isConfig: Boolean, isKeyframe: Boolean): Decision {
        if (isConfig) {
            haveConfig = true
            return Decision.DECODE
        }
        if (isKeyframe && haveConfig) {
            waitingForKeyframe = false
            requested = false
            inFlight++
            return Decision.DECODE
        }
        if (!waitingForKeyframe && inFlight < maxInFlight) {
            inFlight++
            return Decision.DECODE
        }
        dropped++
        waitingForKeyframe = true
        if (requested) return Decision.DROP
        requested = true
        return Decision.DROP_AND_REQUEST_KEYFRAME
    }

    /** Call when the decoder emits (renders or discards) a frame. */
    fun onFrameOutput() {
        if (inFlight > 0) inFlight--
    }

    /**
     * An access unit that got [Decision.DECODE] could not be queued (decoder stalled, buffer too
     * small). Later frames would reference it, so wait for a keyframe; the caller requests one.
     */
    fun onSubmitFailed(isConfig: Boolean) {
        if (isConfig) {
            haveConfig = false
        } else if (inFlight > 0) {
            inFlight--
        }
        dropped++
        waitingForKeyframe = true
        requested = true
    }

    /** After a codec reset or reconfiguration: wait for CONFIG + keyframe again. */
    fun reset() {
        inFlight = 0
        haveConfig = false
        waitingForKeyframe = true
        requested = false
    }
}

/** Rolling window of latency samples in µs with mean and p95. Not thread-safe. */
class LatencyStats(private val capacity: Int = 120) {
    private val samples = LongArray(capacity)
    private val sorted = LongArray(capacity)
    private var count = 0
    private var next = 0

    fun add(valueUs: Long) {
        samples[next] = valueUs
        next = (next + 1) % capacity
        if (count < capacity) count++
    }

    val size: Int get() = count

    fun meanUs(): Long {
        if (count == 0) return 0
        var sum = 0L
        for (i in 0 until count) sum += samples[i]
        return sum / count
    }

    /** 95th percentile (nearest rank); allocation-free. */
    fun p95Us(): Long {
        if (count == 0) return 0
        System.arraycopy(samples, 0, sorted, 0, count)
        java.util.Arrays.sort(sorted, 0, count)
        val rank = ((count * 95 + 99) / 100).coerceIn(1, count)
        return sorted[rank - 1]
    }

    fun clear() {
        count = 0
        next = 0
    }
}
