package dev.ririprojector.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class JitterBufferTest {
    // 48 kHz stereo s16: 192 bytes per ms, 1920 per 10 ms packet.
    private val packet = 1920
    private val out = ByteArray(packet)

    private fun jb() = JitterBuffer(48_000, 2) // target 20 ms, first priming at 20 + 20 ms

    private fun JitterBuffer.feed(ptsUs: Long, fill: Byte = 1) = push(
        ByteArray(packet) { fill },
        0,
        packet,
        ptsUs,
    )

    /**
     * Plays [seconds] of 10 ms pulls; [packetsAt] says how many 10 ms packets arrive before the
     * pull of each step (the arrival pattern). Returns the largest level seen.
     */
    private fun JitterBuffer.run(seconds: Int, startStep: Int = 0, packetsAt: (Int) -> Int): Int {
        var maxLevel = 0
        for (step in startStep until startStep + seconds * 100) {
            repeat(packetsAt(step)) { feed(pts).also { pts += 10_000 } }
            pull(out, packet)
            maxLevel = maxOf(maxLevel, bufferedMs)
        }
        return maxLevel
    }

    private var pts = 0L

    @Test
    fun `outputs silence until target plus the assumed gap is buffered`() {
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
    fun `underrun outputs silence, raises target and re-primes to cover the gap just seen`() {
        val b = jb()
        repeat(4) { b.feed(it * 10_000L) }
        repeat(4) { assertThat(b.pull(out, packet)).isNotEqualTo(JitterBuffer.NO_PTS) }
        assertThat(b.pull(out, packet)).isEqualTo(JitterBuffer.NO_PTS) // 50 ms without a packet
        assertThat(b.underruns).isEqualTo(1)
        // The next packet tells the gap (50 ms, coverable): margin +10, restart at 30 + 50 ms.
        repeat(7) { b.feed(40_000L + it * 10_000L) }
        assertThat(b.targetMs).isEqualTo(30)
        assertThat(b.pull(out, packet)).isEqualTo(JitterBuffer.NO_PTS)
        b.feed(110_000)
        assertThat(b.pull(out, packet)).isEqualTo(40_000L)
        assertThat(b.underruns).isEqualTo(1)
    }

    @Test
    fun `target is capped at the maximum`() {
        val b = JitterBuffer(48_000, 2, maxTargetMs = 25)
        // Steady packets, then a 100 ms gap: one underrun, then the low-water control covers it.
        b.run(20) { step ->
            if (step in 1_000 until 1_010) {
                0
            } else if (step == 1_010) {
                11
            } else {
                1
            }
        }
        assertThat(b.underruns).isEqualTo(1)
        assertThat(b.targetMs).isEqualTo(25)
    }

    @Test
    fun `a stall longer than the cover limit neither raises the margin nor sizes the restart`() {
        val b = jb()
        b.run(20) { 1 }
        b.run(1, startStep = 2_000) { if (it < 2_040) 0 else 1 } // 400 ms stall
        assertThat(b.underruns).isEqualTo(1)
        assertThat(b.targetMs).isEqualTo(20)
        b.run(10, startStep = 2_100) { 1 }
        assertThat(b.bufferedMs).isAtMost(b.targetMs + 6)
    }

    @Test
    fun `clumped arrival never underruns and keeps the low-water mark at the target`() {
        // Six packets every 60 ms, as measured on Wi-Fi: the old buffer discarded every clump.
        val b = jb()
        b.run(30) { if (it % 6 == 0) 6 else 0 }
        val maxLevel = b.run(300, startStep = 3_000) { if (it % 6 == 0) 6 else 0 }

        assertThat(b.underruns).isEqualTo(0)
        assertThat(b.peakGapMs).isEqualTo(60)
        assertThat(b.lowWaterMs).isIn(b.targetMs - 6..b.targetMs + 6)
        assertThat(maxLevel).isAtMost(b.targetMs + 60 + 10)
    }

    @Test
    fun `a rare longer gap is learned once and then covered`() {
        // Clumps every 60 ms, but every 5 s one clump is late by another 60 ms.
        val pattern = { step: Int ->
            when {
                step % 6 != 0 -> 0

                step % 498 == 0 -> 0

                // the late clump...
                (step - 6) % 498 == 0 -> 12

                // ...arrives with the next one
                else -> 6
            }
        }
        val b = jb()
        b.run(60, packetsAt = pattern)
        val learned = b.underruns
        b.run(240, startStep = 6_000, packetsAt = pattern)

        assertThat(learned).isAtMost(2)
        assertThat(b.underruns).isEqualTo(learned)
    }

    @Test
    fun `steady arrival trims the buffer down to the target`() {
        val b = jb()
        b.run(60) { 1 }
        assertThat(b.underruns).isEqualTo(0)
        assertThat(b.bufferedMs).isAtMost(b.targetMs + 6)
        assertThat(b.droppedMs).isLessThan(60L) // single frames only
    }

    @Test
    fun `a stall's backlog is trimmed as it arrives instead of adding latency`() {
        val b = jb()
        b.run(20) { 1 }
        b.run(1, startStep = 2_000) { 0 } // 1 s outage: underrun, then silence
        b.run(1, startStep = 2_100) { if (it == 2_100) 50 else 1 } // the backlog arrives at once
        assertThat(b.bufferedMs).isAtMost(b.targetMs + 20 + 50)
        assertThat(b.droppedMs).isAtLeast(400L)
        b.run(20, startStep = 2_200) { 1 }

        assertThat(b.bufferedMs).isAtMost(b.targetMs + 6)
        assertThat(b.underruns).isEqualTo(1)
    }

    @Test
    fun `audio piled up for the whole history jumps back at once`() {
        val b = jb()
        b.run(20) { 1 }
        b.run(1, startStep = 2_000) { if (it == 2_000) 9 else 1 } // +90 ms, then steady
        assertThat(b.bufferedMs).isAtLeast(100)
        b.run(9, startStep = 2_100) { 1 }
        assertThat(b.bufferedMs).isAtMost(b.targetMs + 6)
        assertThat(b.droppedMs).isAtLeast(80L)
    }

    @Test
    fun `target shrinks after a stable period`() {
        val b = jb()
        b.run(31) { 1 }
        assertThat(b.targetMs).isEqualTo(15)
        assertThat(b.underruns).isEqualTo(0)
    }

    @Test
    fun `overflow keeps the newest audio`() {
        val b = JitterBuffer(48_000, 2, capacityMs = 50, initialTargetMs = 10, initialGapMs = 10)
        repeat(8) { b.feed(it * 10_000L, fill = it.toByte()) }
        assertThat(b.bufferedMs).isEqualTo(50)
        assertThat(b.pull(out, packet)).isEqualTo(30_000L)
        assertThat(out[0]).isEqualTo(3.toByte())
    }

    @Test
    fun `odd byte counts are trimmed to whole frames`() {
        val b = jb()
        b.push(ByteArray(packet * 3 + 5), 2, packet * 3 + 3, 0)
        assertThat(b.bufferedMs).isEqualTo(30)
    }

    @Test
    fun `pts advance by the packet duration across pulls and a clump is kept`() {
        val b = jb()
        repeat(8) { b.feed(it * 10_000L) }
        assertThat(b.pull(out, packet)).isEqualTo(0L)
        assertThat(b.pull(out, packet)).isEqualTo(10_000L)
        assertThat(b.pull(out, packet)).isEqualTo(20_000L)
        assertThat(b.droppedMs).isEqualTo(0L)
    }

    @Test
    fun `pulls longer than the target never read past the buffered audio`() {
        val chunk = 30 * 192 // 30 ms pulls, target 20 ms
        val dst = ByteArray(chunk)
        val b = jb()
        var p = 0L
        repeat(20) { b.push(ByteArray(chunk) { 1 }, 0, chunk, p).also { p += 30_000 } } // 600 ms
        repeat(400) {
            b.push(ByteArray(chunk) { 1 }, 0, chunk, p)
            p += 30_000
            assertThat(b.pull(dst, chunk)).isNotEqualTo(JitterBuffer.NO_PTS)
            assertThat(dst.all { it == 1.toByte() }).isTrue()
            assertThat(b.bufferedMs).isAtLeast(0)
        }
    }

    /** 10 simulated minutes with the sender clock off; one extra or missing frame per [every] packets. */
    private fun driftRun(extraFrame: Boolean, every: Int): JitterBuffer {
        val b = jb()
        val big = ByteArray(packet + 4) { 1 }
        val small = ByteArray(packet - 4) { 1 }
        val normal = ByteArray(packet) { 1 }
        var p = 0L
        repeat(4) { b.push(normal, 0, packet, p).also { p += 10_000 } }
        repeat(60_000) { i ->
            val src = if (i % every == 0) (if (extraFrame) big else small) else normal
            b.push(src, 0, src.size, p)
            p += 10_000
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
        // The shrinking target is also reached by dropping single frames, so compare with no drift.
        val steady = driftRun(extraFrame = true, every = Int.MAX_VALUE)
        assertThat(b.driftFrames).isLessThan(steady.driftFrames)
    }

    @Test
    fun `clear empties and re-primes`() {
        val b = jb()
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
