package dev.ririprojector.media

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import org.junit.Test

class VideoDelayControllerTest {
    /**
     * Closed-loop plant ticked once per second: the measured offset is the pipeline's audio lag
     * minus the delay applied, with optional measurement noise. Video/audio report rates can be
     * switched off to model a static screen or a silent/stalled audio stream.
     */
    private class Plant(val c: VideoDelayController, var audioLagUs: Long) {
        var nowUs = 1_000_000_000L
        var videoPerSec = 60
        var audioPerSec = 100
        var noiseUs = 0L
        private var video = 0L
        private var audio = 0L
        private var lastVideoAt = 0L
        private var lastAudioAt = 0L
        private var tick = 0

        fun step(): Long {
            nowUs += 1_000_000
            if (videoPerSec > 0) {
                video += videoPerSec
                lastVideoAt = nowUs - 10_000
            }
            if (audioPerSec > 0) {
                audio += audioPerSec
                lastAudioAt = nowUs - 5_000
            }
            val noise = if (tick++ % 2 == 0) noiseUs else -noiseUs
            val offset = if (video == 0L || audio == 0L) null else audioLagUs - c.delayUs + noise
            return c.update(AvSync.Snapshot(offset, video, audio, lastVideoAt, lastAudioAt), nowUs)
        }

        fun run(seconds: Int) = repeat(seconds) { step() }

        fun residualUs() = audioLagUs - c.delayUs
    }

    @Test
    fun `acquires with one jump, then audio and video meet`() {
        val c = VideoDelayController()
        val p = Plant(c, audioLagUs = 180_000)
        p.step()
        assertThat(c.delayUs).isEqualTo(180_000) // acquisition jump
        p.run(30)
        assertThat(abs(p.residualUs())).isAtMost(15_000)
    }

    @Test
    fun `tracks a slow change with bounded steps after acquisition`() {
        val c = VideoDelayController(maxStepMs = 20)
        val p = Plant(c, audioLagUs = 100_000)
        p.run(5)
        p.audioLagUs = 250_000
        var last = c.delayUs
        repeat(30) {
            p.step()
            assertThat(abs(c.delayUs - last)).isAtMost(20_000)
            last = c.delayUs
        }
        assertThat(abs(p.residualUs())).isAtMost(15_000)
    }

    @Test
    fun `is clamped to the maximum delay and never negative`() {
        val high = VideoDelayController(maxDelayMs = 300)
        Plant(high, audioLagUs = 900_000).run(60)
        assertThat(high.delayUs).isEqualTo(300_000)

        val low = VideoDelayController()
        Plant(low, audioLagUs = -100_000).run(10)
        assertThat(low.delayUs).isEqualTo(0)
    }

    @Test
    fun `does not hunt on noise inside the deadband`() {
        val c = VideoDelayController(deadbandMs = 15)
        val p = Plant(c, audioLagUs = 120_000)
        p.run(10)
        val settled = c.delayUs
        p.noiseUs = 12_000
        p.run(60)
        assertThat(c.delayUs).isEqualTo(settled)
    }

    @Test
    fun `holds on a static screen even if the stale offset says otherwise`() {
        val c = VideoDelayController()
        val p = Plant(c, audioLagUs = 150_000)
        p.run(10)
        val settled = c.delayUs
        p.videoPerSec = 0 // nothing rendered: AvSync keeps returning an old smoothed offset
        p.audioLagUs = 400_000
        p.run(60)
        assertThat(c.delayUs).isEqualTo(settled)
    }

    @Test
    fun `holds through a short audio gap, winds down after a long one, then re-acquires`() {
        val c = VideoDelayController(maxStepMs = 20, audioLostMs = 10_000)
        val p = Plant(c, audioLagUs = 160_000)
        p.run(10)
        val settled = c.delayUs
        p.audioPerSec = 0
        p.run(10)
        assertThat(c.delayUs).isEqualTo(settled) // ≤ 10 s: hold
        p.run(20)
        assertThat(c.delayUs).isEqualTo(0) // slewed down 20 ms/s
        p.audioPerSec = 100
        p.step()
        assertThat(c.delayUs).isEqualTo(160_000) // re-acquired with one jump
    }

    @Test
    fun `waits for enough samples before acting`() {
        val c = VideoDelayController(minVideoSamples = 10, minAudioSamples = 20)
        val p = Plant(c, audioLagUs = 200_000)
        p.videoPerSec = 5
        p.run(5)
        assertThat(c.delayUs).isEqualTo(0)
    }

    @Test
    fun `applies the trim as the setpoint`() {
        val c = VideoDelayController(trimUs = 20_000)
        val p = Plant(c, audioLagUs = 200_000)
        p.run(20)
        assertThat(abs(p.residualUs() - 20_000)).isAtMost(15_000)
    }

    @Test
    fun `reset forgets the delay and acquisition`() {
        val c = VideoDelayController()
        Plant(c, audioLagUs = 100_000).run(10)
        c.reset()
        assertThat(c.delayUs).isEqualTo(0)
        val p = Plant(c, audioLagUs = 50_000)
        p.step()
        assertThat(c.delayUs).isEqualTo(50_000)
    }
}
