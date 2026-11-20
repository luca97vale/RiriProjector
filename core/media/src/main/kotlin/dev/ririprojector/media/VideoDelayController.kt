package dev.ririprojector.media

/**
 * Lip-sync controller (ADR-007): how long the receiver holds video so it meets its audio. Pure
 * logic, ticked about once per second with an [AvSync.Snapshot]. The measured offset already
 * includes the applied delay, so this is a closed loop that settles where offset ≈ [trimUs].
 *
 * - It acts only on fresh data: at least [minVideoSamples] video and [minAudioSamples] audio
 *   reports since the previous tick, both sides' latest report younger than [maxSampleAgeMs].
 *   Otherwise it holds (a static screen renders no frames, and that must not move the delay).
 * - The first fresh tick (acquisition) jumps straight up to the estimated target (never down),
 *   and the tick after a jump is skipped so the smoothed offset can catch up with it.
 * - After that each step is [gain] × error, at most [maxStepMs], ignoring errors within
 *   ±[deadbandMs] (no hunting), and the delay is clamped to 0..[maxDelayMs].
 * - With no audio progress for [audioLostMs] the delay slews back to 0 and the loop re-acquires.
 */
class VideoDelayController(
    private val maxDelayMs: Int = 300,
    private val gain: Double = 0.5,
    private val maxStepMs: Int = 20,
    private val deadbandMs: Int = 15,
    private val trimUs: Long = 0,
    private val minVideoSamples: Int = 10,
    private val minAudioSamples: Int = 20,
    private val maxSampleAgeMs: Int = 500,
    private val audioLostMs: Int = 10_000,
) {
    /** Current video delay in microseconds. */
    var delayUs: Long = 0
        private set

    private var acquired = false
    private var skipNext = false
    private var lastVideoSamples = 0L
    private var lastAudioSamples = 0L
    private var audioProgressAtUs = NEVER

    /** Advances the loop with the latest [sync] measurement at receiver monotonic [nowUs]. */
    fun update(sync: AvSync.Snapshot, nowUs: Long): Long {
        val newVideo = sync.videoSamples - lastVideoSamples
        val newAudio = sync.audioSamples - lastAudioSamples
        lastVideoSamples = sync.videoSamples
        lastAudioSamples = sync.audioSamples
        if (audioProgressAtUs == NEVER || newAudio > 0) audioProgressAtUs = nowUs

        if (nowUs - audioProgressAtUs > audioLostMs * 1000L) {
            acquired = false
            skipNext = false
            delayUs = (delayUs - maxStepMs * 1000L).coerceAtLeast(0)
            return delayUs
        }
        val offset = sync.offsetUs
        val fresh =
            offset != null &&
                newVideo >= minVideoSamples &&
                newAudio >= minAudioSamples &&
                nowUs - sync.lastVideoAtUs < maxSampleAgeMs * 1000L &&
                nowUs - sync.lastAudioAtUs < maxSampleAgeMs * 1000L
        if (!fresh || offset == null) return delayUs // hold
        if (skipNext) {
            skipNext = false
            return delayUs
        }
        val error = offset - trimUs
        // Acquisition jumps up only: jumping down would release the backlog in one burst (a decoder
        // backlog drop); a lower target is reached by the normal slew instead.
        if (!acquired) {
            acquired = true
            if (error > 0) {
                skipNext = true
                delayUs = clamp(delayUs + error)
                return delayUs
            }
        }
        if (kotlin.math.abs(error) <= deadbandMs * 1000L) return delayUs
        val step = (error * gain).toLong().coerceIn(-maxStepMs * 1000L, maxStepMs * 1000L)
        delayUs = clamp(delayUs + step)
        return delayUs
    }

    /** Back to no delay; call on a new audio stream (AUDIO_CONFIG) together with [AvSync.reset]. */
    fun reset() {
        delayUs = 0
        acquired = false
        skipNext = false
        lastVideoSamples = 0
        lastAudioSamples = 0
        audioProgressAtUs = NEVER
    }

    private fun clamp(us: Long) = us.coerceIn(0L, maxDelayMs * 1000L)

    private companion object {
        const val NEVER = Long.MIN_VALUE
    }
}
