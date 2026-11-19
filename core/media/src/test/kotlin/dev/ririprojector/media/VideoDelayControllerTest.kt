package dev.ririprojector.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VideoDelayControllerTest {
    /** Plant: the measured offset is the pipeline's audio lag minus the delay already applied. */
    private fun settle(c: VideoDelayController, audioLagUs: Long, seconds: Int): Long {
        repeat(seconds) { c.update(audioLagUs - c.delayUs) }
        return audioLagUs - c.delayUs
    }

    @Test
    fun `converges so audio and video meet`() {
        val c = VideoDelayController()
        val residual = settle(c, audioLagUs = 180_000, seconds = 30)
        assertThat(c.delayUs).isWithin(5_000).of(180_000)
        assertThat(kotlin.math.abs(residual)).isAtMost(5_000)
    }

    @Test
    fun `never moves more than one step per update`() {
        val c = VideoDelayController(maxStepMs = 20)
        var last = 0L
        repeat(15) {
            c.update(500_000 - c.delayUs)
            assertThat(c.delayUs - last).isAtMost(20_000)
            last = c.delayUs
        }
    }

    @Test
    fun `is clamped to the maximum delay`() {
        val c = VideoDelayController(maxDelayMs = 300)
        settle(c, audioLagUs = 900_000, seconds = 60)
        assertThat(c.delayUs).isEqualTo(300_000)
    }

    @Test
    fun `never goes negative when video is late`() {
        val c = VideoDelayController()
        settle(c, audioLagUs = -100_000, seconds = 10)
        assertThat(c.delayUs).isEqualTo(0)
    }

    @Test
    fun `ignores offsets inside the deadband`() {
        val c = VideoDelayController(deadbandMs = 5)
        c.update(4_000)
        c.update(-4_000)
        assertThat(c.delayUs).isEqualTo(0)
    }

    @Test
    fun `winds down without a measurement and resets`() {
        val c = VideoDelayController(maxStepMs = 20)
        settle(c, audioLagUs = 100_000, seconds = 30)
        c.update(null)
        assertThat(c.delayUs).isWithin(5_000).of(80_000)
        repeat(10) { c.update(null) }
        assertThat(c.delayUs).isEqualTo(0)
        settle(c, audioLagUs = 100_000, seconds = 30)
        c.reset()
        assertThat(c.delayUs).isEqualTo(0)
    }
}
