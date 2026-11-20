package dev.ririprojector.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CaptureLatencyTest {
    @Test
    fun `no snapshot before a clock estimate`() {
        val c = CaptureLatency()
        c.onShown(ptsUs = 1_000, shownAtUs = 50_000)
        assertThat(c.snapshot()).isNull()
    }

    @Test
    fun `latency places the sender pts on the receiver clock`() {
        val c = CaptureLatency()
        c.setClock(offsetUs = 1_000_000, rttUs = 4_000) // receiver 1 s ahead
        c.onShown(ptsUs = 10_000, shownAtUs = 1_010_000 + 60_000)
        c.onShown(ptsUs = 26_000, shownAtUs = 1_026_000 + 80_000)

        val s = c.snapshot()!!
        assertThat(s.meanUs).isEqualTo(70_000)
        assertThat(s.p95Us).isEqualTo(80_000)
        assertThat(s.uncertaintyUs).isEqualTo(2_000)
    }

    @Test
    fun `negative and absurd samples are skipped`() {
        val c = CaptureLatency()
        c.setClock(offsetUs = -500_000, rttUs = 0)
        c.onShown(ptsUs = 1_000_000, shownAtUs = 400_000) // -100 ms: stale offset
        c.onShown(ptsUs = 0, shownAtUs = 20_000_000) // 20 s
        assertThat(c.snapshot()).isNull()
    }

    @Test
    fun `losing the clock estimate discards the window, refinements do not`() {
        val c = CaptureLatency()
        c.setClock(0, 1_000)
        c.onShown(0, 500_000)
        c.setClock(null, 0) // new link: the old peer's samples are meaningless
        assertThat(c.snapshot()).isNull()
        c.setClock(100_000, 1_000)
        c.onShown(0, 140_000)
        assertThat(c.snapshot()!!.meanUs).isEqualTo(40_000)

        c.setClock(108_000, 16_000) // an 8 ms refinement on a noisy link: samples kept, shifted
        assertThat(c.snapshot()!!.meanUs).isEqualTo(32_000)

        c.reset()
        assertThat(c.snapshot()).isNull()
    }

    @Test
    fun `small offset refinements keep the window and shift the result`() {
        val c = CaptureLatency()
        c.setClock(offsetUs = 100_000, rttUs = 4_000)
        repeat(10) { c.onShown(ptsUs = it * 16_000L, shownAtUs = it * 16_000L + 160_000) } // 60 ms
        c.setClock(offsetUs = 101_500, rttUs = 2_000) // ClockSync found a better sample

        val s = c.snapshot()!!
        assertThat(s.meanUs).isEqualTo(58_500)
        assertThat(s.uncertaintyUs).isEqualTo(1_000)
    }
}
