package dev.ririprojector.core.ui

import com.google.common.truth.Truth.assertThat
import dev.ririprojector.core.ui.DogLoaderMotion.BEAM_PERIOD_MS
import dev.ririprojector.core.ui.DogLoaderMotion.BEAM_PERIOD_RECONNECTING_MS
import dev.ririprojector.core.ui.DogLoaderMotion.PAW_PERIOD_MS
import dev.ririprojector.core.ui.DogLoaderMotion.PAW_STAGGER_MS
import dev.ririprojector.core.ui.DogLoaderMotion.RING_PERIOD_MS
import org.junit.Test

class DogLoaderMotionTest {
    @Test
    fun `ring turns once per period and not at all when idle`() {
        assertThat(DogLoaderMotion.ringDegrees(0, DogLoaderMode.LOADING)).isEqualTo(0f)
        assertThat(DogLoaderMotion.ringDegrees(RING_PERIOD_MS / 2, DogLoaderMode.LOADING)).isWithin(0.01f).of(180f)
        assertThat(DogLoaderMotion.ringDegrees(RING_PERIOD_MS, DogLoaderMode.LOADING)).isEqualTo(0f)
        assertThat(DogLoaderMotion.ringDegrees(700, DogLoaderMode.IDLE)).isEqualTo(0f)
    }

    @Test
    fun `reconnecting ring turns slower than loading`() {
        val t = 600L
        assertThat(DogLoaderMotion.ringDegrees(t, DogLoaderMode.RECONNECTING))
            .isLessThan(DogLoaderMotion.ringDegrees(t, DogLoaderMode.LOADING))
    }

    @Test
    fun `beam pulses between its limits, peaking mid period`() {
        val low = DogLoaderMotion.beamAlpha(0, DogLoaderMode.LOADING)
        val high = DogLoaderMotion.beamAlpha(BEAM_PERIOD_MS / 2, DogLoaderMode.LOADING)
        assertThat(low).isWithin(0.001f).of(0.08f)
        assertThat(high).isWithin(0.001f).of(0.8f)
        for (t in 0L..5_000L step 37) {
            assertThat(
                DogLoaderMotion.beamAlpha(t, DogLoaderMode.IDLE),
            ).isIn(com.google.common.collect.Range.closed(0.08f, 0.5f))
        }
    }

    @Test
    fun `reconnecting beam flickers in three steps`() {
        val values = (0 until BEAM_PERIOD_RECONNECTING_MS.toInt() step 50)
            .map { DogLoaderMotion.beamAlpha(it.toLong(), DogLoaderMode.RECONNECTING) }
            .toSet()
        assertThat(values).hasSize(3)
    }

    @Test
    fun `paws light up in turn and stay within opacity limits`() {
        // Paw 0 is fully lit at 45% of its period; paw 1 is a stagger later.
        val t0 = (PAW_PERIOD_MS * 0.45).toLong()
        assertThat(DogLoaderMotion.pawAlpha(t0, 0)).isEqualTo(1f)
        assertThat(DogLoaderMotion.pawAlpha(t0 + PAW_STAGGER_MS, 1)).isEqualTo(1f)
        assertThat(DogLoaderMotion.pawAlpha(0, 0)).isWithin(0.001f).of(0.12f)
        for (t in 0L..4_000L step 23) {
            for (i in 0 until DogLoaderMotion.PAW_COUNT) {
                assertThat(DogLoaderMotion.pawAlpha(t, i)).isIn(com.google.common.collect.Range.closed(0.12f, 1f))
            }
        }
    }

    @Test
    fun `bob stays within one tilt amplitude`() {
        for (t in 0L..5_000L step 41) {
            assertThat(DogLoaderMotion.bob(t)).isIn(com.google.common.collect.Range.closed(-1f, 1f))
        }
    }
}
