package dev.ririprojector.media

/**
 * Capture→shown latency on the receiver (protocol v3). Video pts are sender `CLOCK_MONOTONIC` µs
 * at composition; [onShown] gets the receiver `CLOCK_MONOTONIC` µs when the frame reached the
 * display, and the sender's clock estimate from PING ([setClock]) places the pts on this clock:
 * `latency = shownAt − (pts + offset)`, uncertain by ±rtt/2.
 *
 * Samples are stored without the offset (`shownAt − pts`) and the current offset is subtracted in
 * [snapshot] (mean and p95 are shift-invariant), so the small offset updates `ClockSync` makes
 * whenever it finds a lower-RTT sample keep the window; only a jump over [MAX_OFFSET_JUMP_US]
 * (e.g. the clocks really moved) clears it.
 *
 * Thread-safe: frames arrive on the codec thread, PINGs on the network thread, snapshots on the
 * control thread. Allocation-free.
 */
class CaptureLatency(capacity: Int = 120) {
    private val raw = LatencyStats(capacity)
    private var offsetUs: Long? = null
    private var rttUs = 0

    data class Snapshot(val meanUs: Long, val p95Us: Long, val uncertaintyUs: Long)

    /** [offsetUs] = receiver minus sender clock, null while the sender has no estimate. */
    @Synchronized
    fun setClock(offsetUs: Long?, rttUs: Int) {
        val old = this.offsetUs
        if (offsetUs == null || old == null || kotlin.math.abs(offsetUs - old) > MAX_OFFSET_JUMP_US) raw.clear()
        this.offsetUs = offsetUs
        this.rttUs = rttUs.coerceAtLeast(0)
    }

    @Synchronized
    fun onShown(ptsUs: Long, shownAtUs: Long) {
        val offset = offsetUs ?: return
        val delta = shownAtUs - ptsUs
        // A negative or absurd latency means a stale offset or a pts from another clock: skip it.
        if (delta - offset in 0..MAX_PLAUSIBLE_US) raw.add(delta)
    }

    /** Null until a clock estimate and at least one frame are known. */
    @Synchronized
    fun snapshot(): Snapshot? {
        val offset = offsetUs ?: return null
        if (raw.size == 0) return null
        return Snapshot(
            (raw.meanUs() - offset).coerceAtLeast(0),
            (raw.p95Us() - offset).coerceAtLeast(0),
            rttUs / 2L,
        )
    }

    @Synchronized
    fun reset() {
        raw.clear()
        offsetUs = null
        rttUs = 0
    }

    private companion object {
        const val MAX_PLAUSIBLE_US = 10_000_000L
        const val MAX_OFFSET_JUMP_US = 2_000L
    }
}
