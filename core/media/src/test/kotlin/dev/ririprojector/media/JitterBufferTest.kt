package dev.ririprojector.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JitterBufferTest {
    // 48 kHz stereo s16: 192 bytes per ms, 1920 per 10 ms packet.
    private val packet = 1920
    private val out = ByteArray(packet)

    private fun jb(initialTargetMs: Int = 40) = JitterBuffer(48_000, 2, initialTargetMs = initialTargetMs)

    private fun JitterBuffer.feed(ptsUs: Long, fill: Byte = 1) = push(
        ByteArray(packet) { fill },
        0,
        packet,
        ptsUs,
    )

    @Test
    fun `outputs silence until the target is buffered`() {
        val b = jb()
        b.feed(0)
        b.feed(10_000)
        b.feed(20_000)
        assertThat(b.pull(out, packet)).isEqualTo(JitterBuffer.NO_PTS)
        assertThat(out.all { it == 0.toByte() }).isTrue()
        b.feed(30_000)
        assertThat(b.pull(out, packet)).isEqualTo(0L)
        assertThat(out.all { it == 1.toByte() }).isTrue()
        assertThat(b.pull(out, packet)).isEqualTo(10_000L)
        assertThat(b.underruns).isEqualTo(0)
    }

    @Test
    fun `underrun outputs silence, counts, raises target and re-primes`() {
        val b = jb()
        repeat(4) { b.feed(it * 10_000L) }
        repeat(4) { b.pull(out, packet) }
        assertThat(b.pull(out, packet)).isEqualTo(JitterBuffer.NO_PTS)
        assertThat(b.underruns).isEqualTo(1)
        assertThat(b.targetMs).isEqualTo(50)
        // re-priming: 4 packets (40 ms) are below the new 50 ms target
        repeat(4) { b.feed(40_000L + it * 10_000L) }
        assertThat(b.pull(out, packet)).isEqualTo(JitterBuffer.NO_PTS)
        b.feed(80_000)
        assertThat(b.pull(out, packet)).isEqualTo(40_000L)
        assertThat(b.underruns).isEqualTo(1)
    }

    @Test
    fun `target is capped at the maximum`() {
        val b = jb()
        repeat(5) {
            repeat(8) { i -> b.feed(i * 10_000L) }
            repeat(20) { b.pull(out, packet) } // drains, then one underrun per cycle
        }
        assertThat(b.underruns).isEqualTo(5)
        assertThat(b.targetMs).isEqualTo(60)
    }

    @Test
    fun `build-up beyond target plus hysteresis drops back to target`() {
        val b = jb()
        repeat(10) { b.feed(it * 10_000L) } // 100 ms buffered, limit is 40 + 20
        val pts = b.pull(out, packet)
        // dropped the oldest 60 ms, played the packet at 60 ms
        assertThat(pts).isEqualTo(60_000L)
        assertThat(b.droppedMs).isEqualTo(60L)
        assertThat(b.bufferedMs).isEqualTo(30)
    }

    @Test
    fun `target shrinks after a stable period`() {
        val b = jb()
        var pts = 0L
        repeat(4) { b.feed(pts).also { pts += 10_000 } }
        repeat(1000) {
            // 10 s of steady playback
            b.feed(pts)
            pts += 10_000
            assertThat(b.pull(out, packet)).isNotEqualTo(JitterBuffer.NO_PTS)
        }
        assertThat(b.targetMs).isEqualTo(35)
        assertThat(b.underruns).isEqualTo(0)
    }

    @Test
    fun `overflow keeps the newest audio`() {
        val b = JitterBuffer(48_000, 2, capacityMs = 50, initialTargetMs = 20, hysteresisMs = 100, driftBandMs = 100)
        repeat(8) { b.feed(it * 10_000L, fill = it.toByte()) }
        assertThat(b.bufferedMs).isEqualTo(50)
        assertThat(b.pull(out, packet)).isEqualTo(30_000L)
        assertThat(out[0]).isEqualTo(3.toByte())
    }

    @Test
    fun `odd byte counts are trimmed to whole frames`() {
        val b = jb(initialTargetMs = 20)
        b.push(ByteArray(packet * 3 + 5), 2, packet * 3 + 3, 0)
        assertThat(b.bufferedMs).isEqualTo(30)
    }

    @Test
    fun `pts advance by the packet duration across pulls`() {
        val b = jb()
        repeat(8) { b.feed(it * 10_000L) }
        // 80 ms buffered: above target + hysteresis, so the first pull jumps to the newest 40 ms
        val first = b.pull(out, packet)
        assertThat(first).isEqualTo(40_000L)
        assertThat(b.pull(out, packet)).isEqualTo(50_000L)
        assertThat(b.pull(out, packet)).isEqualTo(60_000L)
    }

    @Test
    fun `pulls longer than the target never read past the buffered audio`() {
        val chunk = 30 * 192 // 30 ms pulls, target 20 ms
        val dst = ByteArray(chunk)
        val b = JitterBuffer(48_000, 2, initialTargetMs = 20)
        var pts = 0L
        repeat(20) { b.push(ByteArray(chunk) { 1 }, 0, chunk, pts).also { pts += 30_000 } } // 600 ms
        repeat(200) {
            b.push(ByteArray(chunk) { 1 }, 0, chunk, pts)
            pts += 30_000
            assertThat(b.pull(dst, chunk)).isNotEqualTo(JitterBuffer.NO_PTS)
            assertThat(dst.all { it == 1.toByte() }).isTrue()
            assertThat(b.bufferedMs).isAtLeast(0)
        }
    }

    /** 10 simulated minutes with the sender clock [ppm] off; one extra or missing frame per [every] packets. */
    private fun driftRun(extraFrame: Boolean, every: Int): JitterBuffer {
        val b = jb()
        val big = ByteArray(packet + 4) { 1 }
        val small = ByteArray(packet - 4) { 1 }
        val normal = ByteArray(packet) { 1 }
        var pts = 0L
        repeat(4) { b.push(normal, 0, packet, pts).also { pts += 10_000 } }
        repeat(60_000) { i ->
            val src = if (i % every == 0) (if (extraFrame) big else small) else normal
            b.push(src, 0, src.size, pts)
            pts += 10_000
            b.pull(out, packet)
        }
        return b
    }

    @Test
    fun `fast sender drift is absorbed by dropping single frames`() {
        val b = driftRun(extraFrame = true, every = 208) // +100 ppm
        assertThat(b.underruns).isEqualTo(0)
        assertThat(b.driftFrames).isGreaterThan(0L)
        assertThat(b.bufferedMs).isAtMost(b.targetMs + 20)
        assertThat(b.droppedMs).isLessThan(100L) // only single frames, no jumps
    }

    @Test
    fun `slow sender drift is absorbed by repeating single frames`() {
        val b = driftRun(extraFrame = false, every = 208) // -100 ppm
        assertThat(b.underruns).isEqualTo(0)
        assertThat(b.bufferedMs).isAtLeast(b.targetMs - 20)
        // The shrinking target is also reached by dropping single frames, so compare with no drift.
        val steady = driftRun(extraFrame = true, every = Int.MAX_VALUE)
        assertThat(b.driftFrames).isLessThan(steady.driftFrames)
    }

    @Test
    fun `clear empties and re-primes`() {
        val b = jb(initialTargetMs = 20)
        repeat(3) { b.feed(it * 10_000L) }
        b.clear()
        assertThat(b.bufferedMs).isEqualTo(0)
        assertThat(b.pull(out, packet)).isEqualTo(JitterBuffer.NO_PTS)
        assertThat(b.underruns).isEqualTo(0)
    }
}

class AvSyncTest {
    @Test
    fun `offset is null until both sides report`() {
        val s = AvSync()
        assertThat(s.offsetUs()).isNull()
        s.onVideoShown(1_000, 51_000)
        assertThat(s.offsetUs()).isNull()
    }

    @Test
    fun `clock offset cancels and positive means audio late`() {
        val s = AvSync()
        val clockOffset = 9_000_000_000L // receiver clock far from sender clock
        s.onVideoShown(1_000_000, 1_000_000 + clockOffset + 50_000)
        s.onAudioPlayed(1_000_000, 1_000_000 + clockOffset + 80_000)
        assertThat(s.offsetUs()).isEqualTo(30_000L)
    }

    @Test
    fun `smooths noisy samples`() {
        val s = AvSync(alpha = 0.5)
        s.onVideoShown(0, 0)
        s.onAudioPlayed(0, 20_000)
        s.onAudioPlayed(0, 40_000)
        assertThat(s.offsetUs()).isEqualTo(30_000L)
        s.reset()
        assertThat(s.offsetUs()).isNull()
    }
}
