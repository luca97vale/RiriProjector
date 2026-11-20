package dev.ririprojector.net

/**
 * NTP-style estimate (4 timestamps) of the round-trip time and of the receiver-minus-sender clock
 * offset from PING/PONG exchanges, all on `CLOCK_MONOTONIC` µs. Keeps the last [window] samples
 * and trusts the one with the lowest RTT, whose symmetric-path assumption is the least distorted
 * by queuing. Time the receiver spends between reading the PING and writing the PONG is excluded.
 *
 * [addSample] and [reset] must be called from one thread; the estimates are safe to read from any.
 */
class ClockSync(private val window: Int = 8) {
    private val rtts = LongArray(window)
    private val offsets = LongArray(window)
    private var count = 0
    private var next = 0

    /** Best RTT in the window, or null before the first sample. */
    @Volatile
    var rttUs: Long? = null
        private set

    /** RTT of the most recent sample (for congestion detection), or null before the first one. */
    @Volatile
    var lastRttUs: Long? = null
        private set

    /** Receiver clock minus sender clock, from the best sample. */
    @Volatile
    var offsetUs: Long? = null
        private set

    /**
     * @param t1 sender clock when the PING left
     * @param t2 receiver clock when the PING was read
     * @param t3 receiver clock when the PONG was written
     * @param t4 sender clock when the PONG arrived
     */
    fun addSample(t1: Long, t2: Long, t3: Long, t4: Long) {
        val hold = t3 - t2
        val rtt = (t4 - t1) - hold
        if (t4 < t1 || hold < 0 || rtt < 0) return // clock went backwards or bogus echo; ignore
        rtts[next] = rtt
        offsets[next] = ((t2 - t1) + (t3 - t4)) / 2
        next = (next + 1) % window
        if (count < window) count++
        var best = 0
        for (i in 1 until count) if (rtts[i] < rtts[best]) best = i
        lastRttUs = rtt
        rttUs = rtts[best]
        offsetUs = offsets[best]
    }

    /** Forgets every sample: the peer (and its clock) may have changed. */
    fun reset() {
        count = 0
        next = 0
        rttUs = null
        lastRttUs = null
        offsetUs = null
    }

    /** Converts a receiver timestamp to the sender clock, if an offset is known. */
    fun toSenderTime(receiverUs: Long): Long? = offsetUs?.let { receiverUs - it }
}
