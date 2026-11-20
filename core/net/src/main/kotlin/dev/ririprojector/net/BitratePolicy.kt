package dev.ririprojector.net

/**
 * Sender-side adaptive bitrate (AIMD, ADR-004 rev. M6). Pure and clock-injected: callers pass
 * monotonic milliseconds.
 *
 * - Decrease ×[DECREASE_FACTOR], at most once per [HOLD_OFF_MS], on: a send-queue video drop
 *   ([onVideoDrop], event-driven so the recovery keyframe is already smaller), oldest queued video
 *   older than [QUEUE_AGE_LIMIT_US], receiver dropped-frame count rising, or the last RTT above
 *   the 30 s RTT floor + [RTT_MARGIN_US] on 2 consecutive ticks.
 * - Increase +[INCREASE_STEP] per tick after [CLEAN_BEFORE_INCREASE_MS] without congestion, up to
 *   the ceiling.
 * - Ceiling = negotiated (Auto) or user's fixed bitrate; floor = max(1 Mbps, 15 % of ceiling).
 *
 * Thread-safe: [onVideoDrop] comes from the encoder thread, [tick] from the socket writer thread
 * (once per second); the resulting bitrate is applied to the encoder on the HostCtl thread.
 */
class BitratePolicy(ceilingBps: Int, startBps: Int = ceilingBps, nowMs: Long = 0) {
    /** Inputs sampled once per second. */
    data class Signals(
        val oldestQueuedVideoAgeUs: Long = 0,
        /** Cumulative dropped frames reported by the receiver (STATS). */
        val receiverDropped: Int = 0,
        /** RTT of the latest PING/PONG, null if none yet. */
        val lastRttUs: Long? = null,
    )

    var ceilingBps: Int = ceilingBps
        private set

    @Volatile
    var currentBps: Int = startBps.coerceIn(floorFor(ceilingBps), ceilingBps)
        private set

    val floorBps: Int get() = floorFor(ceilingBps)

    private var lastDecreaseAtMs = Long.MIN_VALUE / 2
    private var lastCongestionAtMs = nowMs
    private var lastReceiverDropped = -1
    private var rttHighTicks = 0
    private val rttSamples = LongArray(RTT_WINDOW_TICKS)
    private val rttSampleAtMs = LongArray(RTT_WINDOW_TICKS)
    private var rttCount = 0
    private var rttNext = 0

    /** A video frame was dropped in the send queue. Returns the new bitrate, or null if unchanged. */
    @Synchronized
    fun onVideoDrop(nowMs: Long): Int? = decrease(nowMs)

    /** Once per second. Returns the new bitrate, or null if unchanged. */
    @Synchronized
    fun tick(nowMs: Long, signals: Signals): Int? {
        var congested = signals.oldestQueuedVideoAgeUs > QUEUE_AGE_LIMIT_US

        val dropped = signals.receiverDropped
        if (lastReceiverDropped >= 0 && dropped > lastReceiverDropped) congested = true
        lastReceiverDropped = dropped

        val rtt = signals.lastRttUs
        if (rtt != null) {
            val floor = rttFloor(nowMs)
            addRtt(nowMs, rtt)
            rttHighTicks = if (floor != null && rtt > floor + RTT_MARGIN_US) rttHighTicks + 1 else 0
            if (rttHighTicks >= RTT_HIGH_TICKS) congested = true
        }

        if (congested) return decrease(nowMs)
        if (nowMs - lastCongestionAtMs < CLEAN_BEFORE_INCREASE_MS || currentBps >= ceilingBps) return null
        val next = minOf(ceilingBps.toLong(), currentBps + maxOf(1L, (currentBps * INCREASE_STEP).toLong())).toInt()
        currentBps = next
        return next
    }

    /** New ceiling after a resolution change; the current value is clamped to it. */
    @Synchronized
    fun refit(newCeilingBps: Int): Int {
        ceilingBps = newCeilingBps
        currentBps = currentBps.coerceIn(floorFor(newCeilingBps), newCeilingBps)
        return currentBps
    }

    /** A new link: receiver counters restart and old RTTs describe another path. */
    @Synchronized
    fun onNewLink() {
        lastReceiverDropped = -1
        rttHighTicks = 0
        rttCount = 0
        rttNext = 0
    }

    private fun decrease(nowMs: Long): Int? {
        lastCongestionAtMs = nowMs
        if (nowMs - lastDecreaseAtMs < HOLD_OFF_MS) return null
        val next = maxOf(floorBps, (currentBps * DECREASE_FACTOR).toInt())
        lastDecreaseAtMs = nowMs
        rttHighTicks = 0
        if (next == currentBps) return null
        currentBps = next
        return next
    }

    private fun addRtt(nowMs: Long, rttUs: Long) {
        rttSamples[rttNext] = rttUs
        rttSampleAtMs[rttNext] = nowMs
        rttNext = (rttNext + 1) % RTT_WINDOW_TICKS
        if (rttCount < RTT_WINDOW_TICKS) rttCount++
    }

    /** Minimum RTT over the last [RTT_FLOOR_WINDOW_MS], excluding the sample being judged. */
    private fun rttFloor(nowMs: Long): Long? {
        var min = Long.MAX_VALUE
        for (i in 0 until rttCount) {
            if (nowMs - rttSampleAtMs[i] <= RTT_FLOOR_WINDOW_MS && rttSamples[i] < min) min = rttSamples[i]
        }
        return min.takeIf { it != Long.MAX_VALUE }
    }

    companion object {
        const val DECREASE_FACTOR = 0.75
        const val INCREASE_STEP = 0.05
        const val HOLD_OFF_MS = 2_000L
        const val CLEAN_BEFORE_INCREASE_MS = 5_000L
        const val QUEUE_AGE_LIMIT_US = 100_000L
        const val RTT_MARGIN_US = 40_000L
        const val RTT_HIGH_TICKS = 2
        const val RTT_FLOOR_WINDOW_MS = 30_000L
        const val ABSOLUTE_FLOOR_BPS = 1_000_000
        private const val RTT_WINDOW_TICKS = 32

        fun floorFor(ceilingBps: Int): Int = minOf(ceilingBps, maxOf(ABSOLUTE_FLOOR_BPS, (ceilingBps * 0.15).toInt()))
    }
}
