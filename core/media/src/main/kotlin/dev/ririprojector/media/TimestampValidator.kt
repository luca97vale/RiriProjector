package dev.ririprojector.media

/**
 * Decides whether an `AudioTrack.getTimestamp` sample can be trusted for lip-sync (ADR-007).
 * Right after `play()`, after an underrun or on some HALs the call succeeds with a stale or frozen
 * position; reporting those would steer the video delay with garbage. A sample is valid when the
 * call succeeded, the frame position moved forward since the last valid sample (or the HAL
 * returned that same sample again), and the sample is younger than [maxAgeNs]. Pure logic,
 * allocation-free, used from the audio thread only.
 */
class TimestampValidator(private val maxAgeNs: Long = 200_000_000L) {
    private var lastFramePosition = -1L
    private var lastNanoTime = Long.MIN_VALUE

    /** Rejected samples since the start, for diagnostics (read from another thread). */
    @Volatile var rejected = 0L
        private set

    fun accept(success: Boolean, framePosition: Long, sampleNanoTime: Long, nowNs: Long): Boolean {
        // The HAL refreshes the timestamp every few ms: the very same sample again is fine (the age
        // bound catches it going stale); the same position at a new time means playback froze.
        val repeated = framePosition == lastFramePosition && sampleNanoTime == lastNanoTime
        val ok =
            success &&
                (framePosition > lastFramePosition || repeated) &&
                nowNs - sampleNanoTime in 0..maxAgeNs
        if (ok) {
            lastFramePosition = framePosition
            lastNanoTime = sampleNanoTime
        } else {
            rejected++
        }
        return ok
    }

    fun reset() {
        lastFramePosition = -1L
        lastNanoTime = Long.MIN_VALUE
        rejected = 0
    }
}
