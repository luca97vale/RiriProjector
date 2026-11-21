package dev.ririprojector.core.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** What the dog loader is telling the user. */
enum class DogLoaderMode {
    /** Ready and waiting: the dog bobs and the beam glows softly, no spinner. */
    IDLE,

    /** Connecting: spinning cyan ring, pulsing beam, walking paws. */
    LOADING,

    /** Link lost, trying again: slow tan ring and a flickering beam. */
    RECONNECTING,
}

/**
 * The loader's animation as pure functions of elapsed time, so the timing is tested on the JVM and
 * the view only draws. Values are in 0..1 unless stated otherwise.
 */
object DogLoaderMotion {
    const val RING_PERIOD_MS = 1_400L
    const val RING_PERIOD_RECONNECTING_MS = 2_400L
    const val BOB_PERIOD_MS = 2_400L
    const val BEAM_PERIOD_MS = 1_200L
    const val BEAM_PERIOD_IDLE_MS = 3_000L
    const val BEAM_PERIOD_RECONNECTING_MS = 2_600L
    const val PAW_PERIOD_MS = 1_800L
    const val PAW_STAGGER_MS = 250L
    const val PAW_COUNT = 4

    /** Head tilt amplitude, degrees. */
    const val BOB_DEGREES = 3f

    private const val BEAM_MIN = 0.08f
    private const val BEAM_MAX = 0.8f
    private const val BEAM_MAX_IDLE = 0.5f
    private const val PAW_MIN = 0.12f

    private fun phase(timeMs: Long, periodMs: Long): Float = (timeMs.mod(periodMs)).toFloat() / periodMs

    /** Ring rotation in degrees, 0 until 360; none in [DogLoaderMode.IDLE]. */
    fun ringDegrees(timeMs: Long, mode: DogLoaderMode): Float = when (mode) {
        DogLoaderMode.IDLE -> 0f
        DogLoaderMode.LOADING -> phase(timeMs, RING_PERIOD_MS) * 360f
        DogLoaderMode.RECONNECTING -> phase(timeMs, RING_PERIOD_RECONNECTING_MS) * 360f
    }

    /** -1..1: the head tilt (times [BOB_DEGREES]) and the lift (upwards when positive). */
    fun bob(timeMs: Long): Float = sin(2.0 * PI * phase(timeMs, BOB_PERIOD_MS)).toFloat()

    /** Beam opacity: a smooth pulse, softer when idle, a three-step flicker when reconnecting. */
    fun beamAlpha(timeMs: Long, mode: DogLoaderMode): Float = when (mode) {
        DogLoaderMode.LOADING -> lerp(BEAM_MIN, BEAM_MAX, smoothPulse(phase(timeMs, BEAM_PERIOD_MS)))

        DogLoaderMode.IDLE -> lerp(BEAM_MIN, BEAM_MAX_IDLE, smoothPulse(phase(timeMs, BEAM_PERIOD_IDLE_MS)))

        DogLoaderMode.RECONNECTING -> {
            val step = floor(phase(timeMs, BEAM_PERIOD_RECONNECTING_MS) * 3f) // 0, 1, 2
            lerp(BEAM_MIN, BEAM_MAX, step / 2f)
        }
    }

    /** Opacity of paw [index] (0-based): each lights up in turn, like the dog walking over. */
    fun pawAlpha(timeMs: Long, index: Int): Float {
        val p = phase(timeMs - index * PAW_STAGGER_MS, PAW_PERIOD_MS)
        val v =
            when {
                p < 0.10f -> 0f
                p < 0.30f -> (p - 0.10f) / 0.20f
                p < 0.60f -> 1f
                p < 0.90f -> 1f - (p - 0.60f) / 0.30f
                else -> 0f
            }
        return lerp(PAW_MIN, 1f, v)
    }

    /** Values for a still frame when the system has animations turned off. */
    fun staticBeamAlpha(mode: DogLoaderMode): Float = if (mode == DogLoaderMode.IDLE) BEAM_MAX_IDLE else BEAM_MAX

    private fun smoothPulse(p: Float): Float = ((1.0 - cos(2.0 * PI * p)) / 2.0).toFloat()

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
