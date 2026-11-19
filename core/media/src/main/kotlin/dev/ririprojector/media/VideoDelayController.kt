package dev.ririprojector.media

/**
 * Lip-sync controller (ADR-007): how long the receiver holds video so it meets its audio. Pure
 * logic, called once per second with the current A/V offset from [AvSync], which already includes
 * the applied delay, so this is a closed loop that settles where the offset is ~0.
 *
 * Each step moves the delay by [gain] × offset, at most [maxStepMs] (so video never visibly jumps),
 * clamped to 0..[maxDelayMs]; offsets within ±[deadbandMs] are left alone to avoid hunting. Without
 * a measurement (no audio, or audio stalled) the delay winds down to 0 at the same slew rate.
 */
class VideoDelayController(
    private val maxDelayMs: Int = 300,
    private val gain: Double = 0.5,
    private val maxStepMs: Int = 20,
    private val deadbandMs: Int = 5,
) {
    /** Current video delay in microseconds. */
    var delayUs: Long = 0
        private set

    /** [offsetUs]: audio minus video (positive = audio late), or null when not measurable. */
    fun update(offsetUs: Long?): Long {
        val step =
            when {
                offsetUs == null -> -delayUs
                kotlin.math.abs(offsetUs) <= deadbandMs * 1000L -> 0L
                else -> (offsetUs * gain).toLong()
            }
        val limited = step.coerceIn(-maxStepMs * 1000L, maxStepMs * 1000L)
        delayUs = (delayUs + limited).coerceIn(0L, maxDelayMs * 1000L)
        return delayUs
    }

    fun reset() {
        delayUs = 0
    }
}
