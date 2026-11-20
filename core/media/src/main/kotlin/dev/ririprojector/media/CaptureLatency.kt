package dev.ririprojector.media

/**
 * Capture→shown latency on the receiver (protocol v3). Video pts are sender `CLOCK_MONOTONIC` µs
 * at composition; [onShown] gets the receiver `CLOCK_MONOTONIC` µs when the frame reached the
 * display, and the sender's clock estimate from PING ([setClock]) places the pts on this clock:
 * `latency = shownAt − (pts + offset)`, uncertain by ±rtt/2.
 *
 * Thread-safe: frames arrive on the codec thread, PINGs on the network thread, snapshots on the
 * control thread. Allocation-free.
 */
class CaptureLatency(capacity: Int = 120) {
    private val stats = LatencyStats(capacity)
    private var offsetUs: Long? = null
    private var rttUs = 0

    data class Snapshot(val meanUs: Long, val p95Us: Long, val uncertaintyUs: Long)

    /** [offsetUs] = receiver minus sender clock, null while the sender has no estimate. */
    @Synchronized
    fun setClock(offsetUs: Long?, rttUs: Int) {
        if (offsetUs != this.offsetUs) stats.clear() // samples on the old estimate would mix in
        this.offsetUs = offsetUs
        this.rttUs = rttUs.coerceAtLeast(0)
    }

    @Synchronized
    fun onShown(ptsUs: Long, shownAtUs: Long) {
        val offset = offsetUs ?: return
        val latency = shownAtUs - (ptsUs + offset)
        // A negative or absurd value means a stale offset or a pts from another clock: skip it.
        if (latency in 0..MAX_PLAUSIBLE_US) stats.add(latency)
    }

    /** Null until a clock estimate and at least one frame are known. */
    @Synchronized
    fun snapshot(): Snapshot? {
        if (offsetUs == null || stats.size == 0) return null
        return Snapshot(stats.meanUs(), stats.p95Us(), rttUs / 2L)
    }

    @Synchronized
    fun reset() {
        stats.clear()
        offsetUs = null
        rttUs = 0
    }

    private companion object {
        const val MAX_PLAUSIBLE_US = 10_000_000L
    }
}
