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
        val b = JitterBuffer(48_000, 2, capacityMs = 50, initialTargetMs = 20, hysteresisMs = 100)
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
