package dev.ririprojector.net

import com.google.common.truth.Truth.assertThat
import dev.ririprojector.net.BitratePolicy.Signals
import org.junit.Test

class BitratePolicyTest {
    private val clean = Signals(oldestQueuedVideoAgeUs = 0, receiverDropped = 0, lastRttUs = 5_000)

    @Test
    fun `send queue drop decreases at once and then holds off`() {
        val p = BitratePolicy(ceilingBps = 20_000_000, nowMs = 0)
        assertThat(p.onVideoDrop(1_000)).isEqualTo(15_000_000)
        assertThat(p.onVideoDrop(2_000)).isNull() // within 2 s
        assertThat(p.onVideoDrop(3_100)).isEqualTo(11_250_000)
    }

    @Test
    fun `stale queued video decreases on the tick`() {
        val p = BitratePolicy(20_000_000)
        assertThat(p.tick(1_000, clean.copy(oldestQueuedVideoAgeUs = 150_000))).isEqualTo(15_000_000)
    }

    @Test
    fun `rising receiver drops decrease but the first report only sets the baseline`() {
        val p = BitratePolicy(20_000_000)
        assertThat(p.tick(1_000, clean.copy(receiverDropped = 30))).isNull()
        assertThat(p.tick(2_000, clean.copy(receiverDropped = 30))).isNull()
        assertThat(p.tick(3_000, clean.copy(receiverDropped = 31))).isEqualTo(15_000_000)
    }

    @Test
    fun `rtt above the floor plus margin on two consecutive ticks decreases`() {
        val p = BitratePolicy(20_000_000)
        p.tick(1_000, clean.copy(lastRttUs = 5_000))
        assertThat(p.tick(2_000, clean.copy(lastRttUs = 60_000))).isNull() // one high tick
        assertThat(p.tick(3_000, clean.copy(lastRttUs = 5_000))).isNull() // streak broken
        assertThat(p.tick(4_000, clean.copy(lastRttUs = 60_000))).isNull()
        assertThat(p.tick(5_000, clean.copy(lastRttUs = 70_000))).isEqualTo(15_000_000)
    }

    @Test
    fun `increases 5 percent per tick only after 5 s clean and stops at the ceiling`() {
        val p = BitratePolicy(ceilingBps = 10_000_000, startBps = 9_000_000, nowMs = 0)
        assertThat(p.tick(4_000, clean)).isNull()
        assertThat(p.tick(5_000, clean)).isEqualTo(9_450_000)
        assertThat(p.tick(6_000, clean)).isEqualTo(9_922_500)
        assertThat(p.tick(7_000, clean)).isEqualTo(10_000_000)
        assertThat(p.tick(8_000, clean)).isNull()
    }

    @Test
    fun `congestion restarts the clean period`() {
        val p = BitratePolicy(ceilingBps = 10_000_000, nowMs = 0)
        p.onVideoDrop(6_000) // 7.5 Mbps
        assertThat(p.tick(10_000, clean)).isNull()
        assertThat(p.tick(11_000, clean)).isEqualTo(7_875_000)
    }

    @Test
    fun `never goes below the floor`() {
        val p = BitratePolicy(ceilingBps = 4_000_000)
        var t = 0L
        repeat(20) { p.onVideoDrop(t).also { t += 3_000 } }
        assertThat(p.currentBps).isEqualTo(1_000_000)
        assertThat(BitratePolicy.floorFor(20_000_000)).isEqualTo(3_000_000)
    }

    @Test
    fun `refit clamps to the new ceiling and keeps a lower value`() {
        val p = BitratePolicy(ceilingBps = 20_000_000)
        assertThat(p.refit(8_000_000)).isEqualTo(8_000_000)
        p.onVideoDrop(1_000) // 6 Mbps
        assertThat(p.refit(30_000_000)).isEqualTo(6_000_000)
        assertThat(p.ceilingBps).isEqualTo(30_000_000)
    }

    @Test
    fun `oscillating congestion only steps down, at most every 2 s, then recovers when clean`() {
        val p = BitratePolicy(ceilingBps = 20_000_000)
        var last = p.currentBps
        var decreases = 0
        for (s in 1..20) {
            // Receiver drops rise every other second: on, off, on, off...
            val changed = p.tick(s * 1_000L, clean.copy(receiverDropped = s / 2))
            if (changed != null) {
                assertThat(changed).isLessThan(last)
                decreases++
                last = changed
            }
        }
        assertThat(decreases).isAtMost(10)
        assertThat(p.currentBps).isAtLeast(p.floorBps)

        val atEnd = p.currentBps
        // Last congestion at 20 s: 5 s clean before the first +5 %.
        for (s in 21..24) assertThat(p.tick(s * 1_000L, clean.copy(receiverDropped = 10))).isNull()
        assertThat(p.tick(25_000, clean.copy(receiverDropped = 10))).isGreaterThan(atEnd)
    }

    @Test
    fun `new link forgets the receiver drop baseline`() {
        val p = BitratePolicy(20_000_000)
        p.tick(1_000, clean.copy(receiverDropped = 50))
        p.onNewLink()
        assertThat(p.tick(2_000, clean.copy(receiverDropped = 3))).isNull()
    }
}
