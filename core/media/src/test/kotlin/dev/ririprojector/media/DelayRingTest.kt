package dev.ririprojector.media

import com.google.common.truth.Truth.assertThat
import dev.ririprojector.media.DelayRing.Offer
import org.junit.Test

class DelayRingTest {
    private fun unit(size: Int, fill: Int) = ByteArray(size) { fill.toByte() }

    private fun DelayRing.add(
        size: Int,
        pts: Long,
        key: Boolean = false,
        config: Boolean = false,
        arrival: Long = pts,
        delay: Long = 0,
    ) = offer(unit(size, pts.toInt()), 0, size, pts, config, key, arrival, delay)

    /** Pops the head, checking its bytes are intact, and returns its pts. */
    private fun DelayRing.pop(): Long {
        val pts = headPtsUs
        for (i in 0 until headLength) assertThat(buffer[headOffset + i]).isEqualTo(pts.toInt().toByte())
        releaseHead()
        return pts
    }

    @Test
    fun `keeps fifo order and schedules at arrival plus delay`() {
        val r = DelayRing(1_000)
        r.add(100, pts = 1, arrival = 1_000, delay = 50_000)
        r.add(100, pts = 2, arrival = 2_000, delay = 50_000)
        assertThat(r.headDueUs).isEqualTo(51_000)
        assertThat(r.pop()).isEqualTo(1)
        assertThat(r.headDueUs).isEqualTo(52_000)
        assertThat(r.pop()).isEqualTo(2)
        assertThat(r.isEmpty).isTrue()
    }

    @Test
    fun `a shrinking delay never schedules a unit before the previous one`() {
        val r = DelayRing(1_000)
        r.add(10, pts = 1, arrival = 0, delay = 100_000)
        r.add(10, pts = 2, arrival = 10_000, delay = 20_000)
        r.pop()
        assertThat(r.headDueUs).isEqualTo(100_000)
    }

    @Test
    fun `keeps units contiguous across the wrap`() {
        val r = DelayRing(1_000)
        repeat(3) { r.add(300, pts = it.toLong() + 1) } // 0..900
        r.pop() // frees 0..300
        assertThat(r.add(250, pts = 4)).isEqualTo(Offer.QUEUED) // tail has only 100 B: wraps to 0
        assertThat(r.add(60, pts = 5)).isEqualTo(Offer.OVERFLOW) // 250..300 is only 50 B
        assertThat(r.pop()).isEqualTo(2)
        assertThat(r.pop()).isEqualTo(3)
        assertThat(r.pop()).isEqualTo(4)
    }

    @Test
    fun `soak - random sizes never corrupt queued units`() {
        val r = DelayRing(10_000, maxEntries = 16)
        val rnd = java.util.Random(42)
        val expected = ArrayDeque<Long>()
        var pts = 0L
        repeat(20_000) {
            if (rnd.nextBoolean() && r.size < 16) {
                pts++
                // Keyframe every unit so overflow never leaves the ring in dropping mode.
                if (r.add(1 + rnd.nextInt(3_000), pts = pts, key = true) == Offer.QUEUED) expected.addLast(pts)
            } else if (!r.isEmpty) {
                assertThat(r.pop()).isEqualTo(expected.removeFirst())
            }
        }
    }

    @Test
    fun `on overflow drops until the next keyframe and reports once`() {
        val r = DelayRing(1_000)
        assertThat(r.add(600, pts = 1, key = true)).isEqualTo(Offer.QUEUED)
        assertThat(r.add(600, pts = 2)).isEqualTo(Offer.OVERFLOW)
        r.pop()
        assertThat(r.add(100, pts = 3)).isEqualTo(Offer.DROPPED) // fits, but not decodable
        assertThat(r.add(50, pts = 4, config = true)).isEqualTo(Offer.QUEUED) // config still queues
        assertThat(r.add(100, pts = 5, key = true)).isEqualTo(Offer.QUEUED)
        assertThat(r.add(100, pts = 6)).isEqualTo(Offer.QUEUED)
        assertThat(r.dropped).isEqualTo(2)
        assertThat(r.headIsConfig).isTrue()
        r.pop()
        assertThat(r.headIsKeyframe).isTrue()
    }

    @Test
    fun `an empty unit never makes a full wrapped ring look unwrapped`() {
        val r = DelayRing(1_000)
        r.add(600, pts = 1)
        r.add(300, pts = 2) // 600..900
        r.pop() // head = 2 at 600
        assertThat(r.add(600, pts = 3)).isEqualTo(Offer.QUEUED) // 0..600, tail end == head offset
        assertThat(r.add(0, pts = 4)).isEqualTo(Offer.DROPPED)
        assertThat(r.add(300, pts = 5, key = true)).isEqualTo(Offer.OVERFLOW) // no room: not over 2
        assertThat(r.pop()).isEqualTo(2) // bytes intact
        assertThat(r.pop()).isEqualTo(3)
    }

    @Test
    fun `reports overflow again when a keyframe cannot fit either`() {
        val r = DelayRing(1_000)
        r.add(900, pts = 1, key = true)
        assertThat(r.add(200, pts = 2)).isEqualTo(Offer.OVERFLOW)
        assertThat(r.add(200, pts = 3)).isEqualTo(Offer.DROPPED)
        assertThat(r.add(200, pts = 4, key = true)).isEqualTo(Offer.OVERFLOW) // ask again
    }

    @Test
    fun `descriptor limit counts as overflow`() {
        val r = DelayRing(1_000, maxEntries = 2)
        r.add(1, pts = 1)
        r.add(1, pts = 2)
        assertThat(r.add(1, pts = 3)).isEqualTo(Offer.OVERFLOW)
    }

    @Test
    fun `a unit larger than the ring is dropped`() {
        val r = DelayRing(100)
        assertThat(r.add(101, pts = 1, key = true)).isEqualTo(Offer.OVERFLOW)
        assertThat(r.isEmpty).isTrue()
    }

    @Test
    fun `clear empties and leaves dropping mode`() {
        val r = DelayRing(100)
        r.add(80, pts = 1)
        r.add(80, pts = 2)
        r.clear()
        assertThat(r.isEmpty).isTrue()
        assertThat(r.add(10, pts = 3)).isEqualTo(Offer.QUEUED)
        assertThat(r.headDueUs).isEqualTo(3)
    }

    @Test
    fun `capacity follows bitrate with a floor`() {
        assertThat(VideoDelayLine.capacityFor(8_000_000, 300)).isEqualTo(2 * 1024 * 1024)
        assertThat(VideoDelayLine.capacityFor(40_000_000, 300)).isEqualTo(5_000_000)
    }
}
