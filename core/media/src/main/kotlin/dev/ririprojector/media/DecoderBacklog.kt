package dev.ririprojector.media

/**
 * Decides which access units reach the decoder (ADR-005).
 *
 * The decoder is "falling behind" when the oldest unit still inside it has waited longer than
 * [maxLagUs], or when more than [hardCap] units are in flight. Then non-key frames are dropped
 * until the next keyframe (later P-frames would reference dropped ones) and a keyframe is requested
 * once per episode. A short network burst does not trigger a drop as long as the decoder keeps up.
 *
 * Units the decoder consumes without producing output would otherwise leak in-flight slots
 * forever; the in-flight window is therefore resynchronized whenever a keyframe ends a drop
 * episode, so a leak costs at most one episode.
 *
 * After (re)configuration nothing is decoded before a CONFIG followed by a keyframe.
 * Not thread-safe: guard externally.
 */
class DecoderBacklog(private val maxLagUs: Long = 60_000, private val hardCap: Int = 8) {
    enum class Decision { DECODE, DROP, DROP_AND_REQUEST_KEYFRAME }

    private val queuedAt = LongArray(hardCap + 1)
    private var head = 0

    var inFlight: Int = 0
        private set
    var dropped: Long = 0
        private set
    var haveConfig: Boolean = false
        private set
    private var waitingForKeyframe = true
    private var requested = false

    /** Call for every incoming access unit; [isConfig] for CSD packets. */
    fun onAccessUnit(isConfig: Boolean, isKeyframe: Boolean, nowUs: Long): Decision {
        if (isConfig) {
            haveConfig = true
            return Decision.DECODE
        }
        if (isKeyframe && haveConfig) {
            if (waitingForKeyframe) clearWindow() // resync: anything older is gone or leaked
            waitingForKeyframe = false
            requested = false
            push(nowUs)
            return Decision.DECODE
        }
        if (!waitingForKeyframe && !behind(nowUs)) {
            push(nowUs)
            return Decision.DECODE
        }
        return drop()
    }

    /** Call when the decoder emits (renders) a frame. Outputs come in input order. */
    fun onFrameOutput() {
        if (inFlight > 0) {
            head = (head + 1) % queuedAt.size
            inFlight--
        }
    }

    /**
     * An access unit that got [Decision.DECODE] could not be queued (decoder stalled, buffer too
     * small). Later frames would reference it, so wait for a keyframe; the caller requests one.
     */
    fun onSubmitFailed(isConfig: Boolean) {
        if (isConfig) {
            haveConfig = false
        } else if (inFlight > 0) {
            inFlight-- // the newest entry
        }
        dropped++
        waitingForKeyframe = true
        requested = true
    }

    /** After a codec reset: wait for CONFIG + keyframe again. */
    fun reset() {
        clearWindow()
        haveConfig = false
        waitingForKeyframe = true
        requested = false
    }

    private fun behind(nowUs: Long): Boolean =
        inFlight >= hardCap || (inFlight > 0 && nowUs - queuedAt[head] > maxLagUs)

    private fun drop(): Decision {
        dropped++
        waitingForKeyframe = true
        if (requested) return Decision.DROP
        requested = true
        return Decision.DROP_AND_REQUEST_KEYFRAME
    }

    private fun push(nowUs: Long) {
        if (inFlight == queuedAt.size) onFrameOutput() // keyframes bypass the cap; forget the oldest
        queuedAt[(head + inFlight) % queuedAt.size] = nowUs
        inFlight++
    }

    private fun clearWindow() {
        inFlight = 0
        head = 0
    }
}

/**
 * Limits REQUEST_KEYFRAME messages to one per [minIntervalMs], so resets, stalls and backlog
 * episodes cannot turn into an IDR storm. Thread-safe.
 */
class KeyframeRequestLimiter(private val minIntervalMs: Long = 300) {
    private var lastMs = Long.MIN_VALUE / 2

    @Synchronized
    fun tryAcquire(nowMs: Long): Boolean {
        if (nowMs - lastMs < minIntervalMs) return false
        lastMs = nowMs
        return true
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
