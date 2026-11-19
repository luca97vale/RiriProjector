package dev.ririprojector.net

/**
 * NTP-style estimate of the round-trip time and of the receiver-minus-sender clock offset from
 * PING/PONG exchanges. Keeps the last [window] samples and trusts the one with the lowest RTT,
 * whose midpoint assumption is the least distorted by queuing. Not thread-safe.
 */
class ClockSync(private val window: Int = 8) {
    private val rtts = LongArray(window)
    private val offsets = LongArray(window)
    private var count = 0
    private var next = 0

    /** Best RTT in the window, or null before the first sample. */
    var rttUs: Long? = null
        private set

    /** Receiver clock minus sender clock, from the best sample. */
    var offsetUs: Long? = null
        private set

    /**
     * @param sentUs sender clock when the PING left
     * @param receiverUs receiver clock when it answered (from PONG)
     * @param receivedUs sender clock when the PONG arrived
     */
    fun addSample(sentUs: Long, receiverUs: Long, receivedUs: Long) {
        val rtt = receivedUs - sentUs
        if (rtt < 0) return // clock went backwards or bogus echo; ignore
        rtts[next] = rtt
        offsets[next] = receiverUs - (sentUs + rtt / 2)
        next = (next + 1) % window
        if (count < window) count++
        var best = 0
        for (i in 1 until count) if (rtts[i] < rtts[best]) best = i
        rttUs = rtts[best]
        offsetUs = offsets[best]
    }

    /** Converts a receiver timestamp to the sender clock, if an offset is known. */
    fun toSenderTime(receiverUs: Long): Long? = offsetUs?.let { receiverUs - it }
}
