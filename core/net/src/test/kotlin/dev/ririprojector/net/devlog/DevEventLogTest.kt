package dev.ririprojector.net.devlog

import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertThrows
import org.junit.Test

class DevEventLogTest {
    @Volatile private var now = 10_000L
    private val log = DevEventLog(capacity = 5, clockMs = { now })

    private fun messages() = log.events.value.map { it.message }

    @Test
    fun `timestamps are relative to the start of the attempt`() {
        log.begin("start")
        now += 1_234
        log.add(DevLevel.INFO, "Net", "connected")
        assertThat(log.events.value.map { it.atMs }).containsExactly(0L, 1_234L).inOrder()
    }

    @Test
    fun `capacity bound drops the oldest line`() {
        repeat(7) { log.add(DevLevel.INFO, "Net", "line $it") }
        assertThat(messages()).containsExactly("line 2", "line 3", "line 4", "line 5", "line 6").inOrder()
    }

    @Test
    fun `begin clears the log and resets t0`() {
        log.add(DevLevel.INFO, "Net", "old")
        now += 5_000
        log.begin("new attempt")
        now += 200
        log.add(DevLevel.INFO, "Net", "step")
        assertThat(messages()).containsExactly("new attempt", "step").inOrder()
        assertThat(log.events.value.map { it.atMs }).containsExactly(0L, 200L).inOrder()
        assertThat(log.events.value[0].source).isEqualTo(DevEventLog.SOURCE_SESSION)
    }

    @Test
    fun `beginUnlessRecent inside the window appends and keeps history`() {
        log.begin("first")
        now += 1_000
        log.add(DevLevel.INFO, "Net", "step")
        now += 1_000
        val began = log.beginUnlessRecent("second", windowMs = 5_000)
        assertThat(began).isFalse()
        assertThat(messages()).containsExactly("first", "step", "second").inOrder()
        assertThat(log.events.value.last().atMs).isEqualTo(2_000L)
    }

    @Test
    fun `beginUnlessRecent outside the window clears and begins`() {
        log.begin("first")
        now += 5_000
        log.add(DevLevel.INFO, "Net", "step")
        val began = log.beginUnlessRecent("second", windowMs = 5_000)
        assertThat(began).isTrue()
        assertThat(messages()).containsExactly("second")
        assertThat(log.events.value.single().atMs).isEqualTo(0L)
    }

    @Test
    fun `beginUnlessRecent with no previous mark begins`() {
        assertThat(log.beginUnlessRecent("first", windowMs = 5_000)).isTrue()
    }

    @Test
    fun `accepted beginUnlessRecent refreshes the window`() {
        log.begin("a")
        now += 4_000
        log.beginUnlessRecent("b", windowMs = 5_000)
        now += 4_000
        assertThat(log.beginUnlessRecent("c", windowMs = 5_000)).isFalse()
        assertThat(messages()).containsExactly("a", "b", "c").inOrder()
    }

    @Test
    fun `touch refreshes the window`() {
        log.begin("a")
        now += 4_000
        log.touch()
        now += 4_000
        assertThat(log.beginUnlessRecent("b", windowMs = 5_000)).isFalse()
        assertThat(messages()).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `touch does not add a line`() {
        log.begin("a")
        log.touch()
        assertThat(messages()).containsExactly("a")
    }

    @Test
    fun `plain add does not refresh the window`() {
        log.begin("a")
        now += 4_000
        log.add(DevLevel.INFO, "Net", "background")
        now += 4_000
        assertThat(log.beginUnlessRecent("b", windowMs = 5_000)).isTrue()
        assertThat(messages()).containsExactly("b")
    }

    @Test
    fun `session end then reconnect within the window keeps the history`() {
        log.begin("offer")
        now += 60_000
        log.add(DevLevel.WARN, "Session", "ended: connection lost")
        log.touch()
        now += 2_000
        val began = log.beginUnlessRecent("offer again", windowMs = 5_000)
        assertThat(began).isFalse()
        assertThat(messages()).containsExactly("offer", "ended: connection lost", "offer again").inOrder()
    }

    @Test
    fun `consecutive identical lines collapse with repeat count and last time`() {
        log.begin("t")
        now += 100
        log.add(DevLevel.WARN, "Net", "stall")
        now += 100
        log.add(DevLevel.WARN, "Net", "stall")
        now += 100
        log.add(DevLevel.WARN, "Net", "stall")
        val e = log.events.value.last()
        assertThat(log.events.value).hasSize(2)
        assertThat(e.repeat).isEqualTo(3)
        assertThat(e.atMs).isEqualTo(100L)
        assertThat(e.lastAtMs).isEqualTo(300L)
    }

    @Test
    fun `non consecutive duplicates are kept`() {
        log.add(DevLevel.INFO, "Net", "a")
        log.add(DevLevel.INFO, "Net", "b")
        log.add(DevLevel.INFO, "Net", "a")
        assertThat(messages()).containsExactly("a", "b", "a").inOrder()
    }

    @Test
    fun `same message with different level or source is not collapsed`() {
        log.add(DevLevel.INFO, "Net", "x")
        log.add(DevLevel.WARN, "Net", "x")
        log.add(DevLevel.WARN, "P2P", "x")
        assertThat(log.events.value).hasSize(3)
        assertThat(log.events.value.map { it.repeat }).containsExactly(1, 1, 1)
    }

    @Test
    fun `collapsing at capacity does not drop older lines`() {
        repeat(5) { log.add(DevLevel.INFO, "Net", "line $it") }
        log.add(DevLevel.INFO, "Net", "line 4")
        assertThat(log.events.value).hasSize(5)
        assertThat(messages().first()).isEqualTo("line 0")
        assertThat(log.events.value.last().repeat).isEqualTo(2)
    }

    @Test
    fun `explicit time before t0 clamps to zero`() {
        log.begin("t")
        log.add(DevLevel.INFO, "Net", "early", atMs = now - 500)
        assertThat(log.events.value.last().atMs).isEqualTo(0L)
    }

    @Test
    fun `explicit time is an absolute clock reading`() {
        log.begin("t")
        now += 5_000
        log.add(DevLevel.INFO, "Net", "stamped", atMs = now - 3_000)
        assertThat(log.events.value.last().atMs).isEqualTo(2_000L)
    }

    @Test
    fun `clear empties the log and forgets the mark`() {
        log.begin("a")
        log.clear()
        assertThat(log.events.value).isEmpty()
        assertThat(log.format()).isEmpty()
        assertThat(log.beginUnlessRecent("b", windowMs = 5_000)).isTrue()
    }

    @Test
    fun `formatLine exact output`() {
        val e = DevEvent(1_234, DevLevel.WARN, "P2P", "message")
        assertThat(DevEventLog.formatLine(e)).isEqualTo("+1.234 s  W  [P2P] message")
    }

    @Test
    fun `formatLine pads milliseconds and shows repeats`() {
        val e = DevEvent(5, DevLevel.ERROR, "Net", "boom", repeat = 3, lastAtMs = 65_078)
        assertThat(DevEventLog.formatLine(e)).isEqualTo("+0.005 s  E  [Net] boom  (×3, last +65.078 s)")
    }

    @Test
    fun `format joins lines with newlines`() {
        log.begin("go")
        now += 1_500
        log.add(DevLevel.INFO, "Net", "up")
        assertThat(log.format()).isEqualTo("+0.000 s  I  [Session] go\n+1.500 s  I  [Net] up")
    }

    @Test
    fun `constructor rejects zero capacity`() {
        assertThrows(IllegalArgumentException::class.java) { DevEventLog(capacity = 0) }
    }

    @Test
    fun `published snapshot is not changed by later adds`() {
        log.add(DevLevel.INFO, "Net", "one")
        val snapshot = log.events.value
        log.add(DevLevel.INFO, "Net", "two")
        log.add(DevLevel.INFO, "Net", "one")
        assertThat(snapshot).hasSize(1)
        assertThat(snapshot.single().message).isEqualTo("one")
        assertThat(snapshot.single().repeat).isEqualTo(1)
    }

    @Test
    fun `concurrent adds respect the cap`() {
        val big = DevEventLog(capacity = 50, clockMs = { now })
        runThreads(8) { t -> repeat(200) { big.add(DevLevel.INFO, "T$t", "m$it") } }
        assertThat(big.events.value).hasSize(50)
    }

    @Test
    fun `concurrent adds under the cap lose nothing`() {
        val big = DevEventLog(capacity = 1_000, clockMs = { now })
        runThreads(8) { t -> repeat(100) { big.add(DevLevel.INFO, "T$t", "m$it") } }
        assertThat(big.events.value).hasSize(800)
        assertThat(big.events.value.map { it.source to it.message }.toSet()).hasSize(800)
    }

    private fun runThreads(n: Int, body: (Int) -> Unit) {
        val start = CountDownLatch(1)
        val done = CountDownLatch(n)
        repeat(n) { t ->
            Thread {
                start.await()
                try {
                    body(t)
                } finally {
                    done.countDown()
                }
            }.start()
        }
        start.countDown()
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue()
    }
}
