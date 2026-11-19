package dev.ririprojector.net

import com.google.common.truth.Truth.assertThat
import dev.ririprojector.protocol.ControlMessage
import dev.ririprojector.protocol.MessageType
import java.nio.ByteBuffer
import org.junit.Test

class ClockSyncTest {
    @Test
    fun `symmetric exchange yields exact offset and rtt`() {
        val sync = ClockSync()
        // sender sends at 1000, receiver clock is +5000 ahead, one-way 300us
        sync.addSample(sentUs = 1_000, receiverUs = 1_000 + 300 + 5_000, receivedUs = 1_600)
        assertThat(sync.rttUs).isEqualTo(600)
        assertThat(sync.offsetUs).isEqualTo(5_000)
        assertThat(sync.toSenderTime(10_000)).isEqualTo(5_000)
    }

    @Test
    fun `lowest rtt sample wins`() {
        val sync = ClockSync()
        sync.addSample(0, 5_000 + 4_000, 8_000) // queued: rtt 8000, offset 5000
        sync.addSample(10_000, 10_000 + 100 + 5_000, 10_200) // clean: rtt 200, offset 5000
        sync.addSample(20_000, 20_000 + 9_000 + 5_000, 21_000) // asymmetric spike
        assertThat(sync.rttUs).isEqualTo(200)
        assertThat(sync.offsetUs).isEqualTo(5_000)
    }

    @Test
    fun `old samples age out of the window`() {
        val sync = ClockSync(window = 2)
        sync.addSample(0, 100, 100) // rtt 100
        sync.addSample(1_000, 1_500, 2_000) // rtt 1000
        sync.addSample(3_000, 3_500, 4_000) // rtt 1000, first sample evicted
        assertThat(sync.rttUs).isEqualTo(1_000)
    }

    @Test
    fun `negative rtt is ignored`() {
        val sync = ClockSync()
        sync.addSample(1_000, 0, 500)
        assertThat(sync.rttUs).isNull()
    }
}

class SendQueueTest {
    private fun buf(size: Int) = ByteBuffer.wrap(ByteArray(size) { 7 })

    @Test
    fun `priority is control then audio then video`() {
        val q = SendQueue()
        q.offerVideo(1, keyframe = true, codecConfig = false, src = buf(10))
        q.offerAudio(2, buf(4))
        q.offerControl(ControlMessage.RequestKeyframe)

        assertThat(q.take(10)!!.type).isEqualTo(MessageType.REQUEST_KEYFRAME)
        assertThat(q.take(10)!!.type).isEqualTo(MessageType.AUDIO_FRAME)
        assertThat(q.take(10)!!.type).isEqualTo(MessageType.FRAME)
        assertThat(q.take(10)).isNull()
    }

    @Test
    fun `payload is copied so the source buffer can be reused`() {
        val q = SendQueue()
        val src = buf(5)
        q.offerVideo(1, keyframe = true, codecConfig = false, src = src)
        src.array().fill(0)

        val p = q.take(10)!!
        val out = ByteArray(p.length).also { p.payload().get(it) }
        assertThat(out).isEqualTo(ByteArray(5) { 7 })
        assertThat(src.position()).isEqualTo(0)
    }

    @Test
    fun `overflow flushes queued video, waits for keyframe and asks for one once`() {
        var requests = 0
        val q = SendQueue(maxVideoPackets = 3, onKeyframeNeeded = { requests++ })
        q.offerVideo(0, keyframe = true, codecConfig = false, src = buf(10))
        q.offerVideo(1, keyframe = false, codecConfig = false, src = buf(10))
        q.offerVideo(2, keyframe = false, codecConfig = false, src = buf(10))

        assertThat(q.offerVideo(3, keyframe = false, codecConfig = false, src = buf(10))).isFalse()
        assertThat(q.offerVideo(4, keyframe = false, codecConfig = false, src = buf(10))).isFalse()
        assertThat(requests).isEqualTo(1)
        assertThat(q.offerVideo(5, keyframe = true, codecConfig = false, src = buf(10))).isTrue()
        assertThat(q.offerVideo(6, keyframe = false, codecConfig = false, src = buf(10))).isTrue()

        val pts = generateSequence { q.take(0) }.map { it.ptsUs }.toList()
        // overflow flushes all queued video (I0, P1, P2); a fresh keyframe was requested
        assertThat(pts).containsExactly(5L, 6L).inOrder()
        assertThat(q.droppedVideoFrames).isEqualTo(5)
    }

    @Test
    fun `byte bound also triggers overflow`() {
        val q = SendQueue(maxVideoPackets = 100, maxVideoBytes = 25)
        q.offerVideo(0, keyframe = true, codecConfig = false, src = buf(10))
        q.offerVideo(1, keyframe = false, codecConfig = false, src = buf(10))
        assertThat(q.offerVideo(2, keyframe = false, codecConfig = false, src = buf(10))).isFalse()
    }

    @Test
    fun `codec config survives overflow`() {
        val q = SendQueue(maxVideoPackets = 2)
        q.offerVideo(0, keyframe = false, codecConfig = true, src = buf(4))
        q.offerVideo(1, keyframe = true, codecConfig = false, src = buf(10))
        q.offerVideo(2, keyframe = true, codecConfig = false, src = buf(10))

        val types = generateSequence { q.take(0) }.map { it.type }.toList()
        assertThat(types).containsExactly(MessageType.CONFIG, MessageType.FRAME).inOrder()
    }

    @Test
    fun `audio overflow drops the oldest packet`() {
        val q = SendQueue(maxAudioPackets = 2)
        q.offerAudio(1, buf(4))
        q.offerAudio(2, buf(4))
        q.offerAudio(3, buf(4))
        val pts = generateSequence { q.take(0) }.map { it.ptsUs }.toList()
        assertThat(pts).containsExactly(2L, 3L).inOrder()
        assertThat(q.droppedAudioPackets).isEqualTo(1)
    }

    @Test
    fun `recycled buffers are reused`() {
        val q = SendQueue()
        q.offerAudio(1, buf(100))
        val first = q.take(0)!!
        val array = first.data
        q.recycle(first)
        q.offerAudio(2, buf(80))
        assertThat(q.take(0)!!.data).isSameInstanceAs(array)
    }

    @Test
    fun `a recycled packet reused for control never returns its old buffer twice`() {
        val q = SendQueue()
        q.offerVideo(1, keyframe = true, codecConfig = false, src = ByteBuffer.wrap(ByteArray(100) { 1 }))
        q.recycle(q.take(0)!!) // packet A + buffer X back in the pools
        q.offerControl(ControlMessage.RequestKeyframe) // reuses packet A
        q.offerVideo(2, keyframe = true, codecConfig = false, src = ByteBuffer.wrap(ByteArray(100) { 2 })) // takes X
        val control = q.take(0)!!
        assertThat(control.length).isEqualTo(0)
        q.recycle(control) // must not put X back
        q.offerVideo(3, keyframe = true, codecConfig = false, src = ByteBuffer.wrap(ByteArray(100) { 3 }))
        val b = q.take(0)!!
        assertThat(b.ptsUs).isEqualTo(2)
        assertThat(b.data.copyOf(b.length).all { it == 2.toByte() }).isTrue()
        val c = q.take(0)!!
        assertThat(c.data).isNotSameInstanceAs(b.data)
    }

    @Test
    fun `audio packets do not take video buffers`() {
        val q = SendQueue()
        q.offerVideo(1, keyframe = true, codecConfig = false, src = buf(100_000))
        val v = q.take(0)!!
        val big = v.data
        q.recycle(v)
        q.offerAudio(2, buf(100))
        assertThat(q.take(0)!!.data).isNotSameInstanceAs(big)
    }

    @Test
    fun `keyframe callback runs outside the lock`() {
        lateinit var q: SendQueue
        var queuedFromCallback = -1
        q = SendQueue(maxVideoPackets = 1, onKeyframeNeeded = {
            // Another thread must be able to use the queue while the callback runs.
            val t = Thread { queuedFromCallback = q.queuedVideoPackets }.apply { start() }
            t.join(1_000)
        })
        q.offerVideo(1, keyframe = true, codecConfig = false, src = buf(4))
        q.offerVideo(2, keyframe = false, codecConfig = false, src = buf(4))
        assertThat(queuedFromCallback).isEqualTo(0)
    }

    @Test
    fun `close wakes a blocked taker`() {
        val q = SendQueue()
        val t = Thread { q.take(5_000) }.apply { start() }
        Thread.sleep(50)
        q.close()
        t.join(1_000)
        assertThat(t.isAlive).isFalse()
    }
}

class SessionStateMachineTest {
    @Test
    fun `happy path reaches streaming and asks for a keyframe`() {
        val sm = SessionStateMachine()
        assertThat(sm.on(SessionEvent.Start(0)).actions).containsExactly(SessionAction.OpenConnection)
        sm.on(SessionEvent.Connected)
        val t = sm.on(SessionEvent.HandshakeCompleted)
        assertThat(t.state).isEqualTo(SessionState.Streaming)
        assertThat(t.actions).containsExactly(SessionAction.RequestKeyframe)
    }

    @Test
    fun `failure while streaming retries after 250 ms`() {
        val sm = streaming()
        val t = sm.on(SessionEvent.Failed(nowMs = 1_000))
        assertThat(t.state).isEqualTo(SessionState.Reconnecting(attempt = 1, retryAtMs = 1_250))
        assertThat(t.actions).containsExactly(SessionAction.CloseConnection)

        assertThat(sm.on(SessionEvent.Tick(1_200)).state).isInstanceOf(SessionState.Reconnecting::class.java)
        val retry = sm.on(SessionEvent.Tick(1_250))
        assertThat(retry.state).isEqualTo(SessionState.Connecting(1))
        assertThat(retry.actions).containsExactly(SessionAction.OpenConnection)
    }

    @Test
    fun `backoff grows and caps at 2 s`() {
        val sm = SessionStateMachine()
        assertThat(
            (1..6).map {
                sm.backoffFor(it)
            },
        ).containsExactly(250L, 500L, 1_000L, 2_000L, 2_000L, 2_000L).inOrder()
    }

    @Test
    fun `consecutive connect failures increase the attempt and eventually give up`() {
        val sm = SessionStateMachine(maxAttempts = 3)
        sm.on(SessionEvent.Start(0))
        var now = 0L
        repeat(2) {
            val s = sm.on(SessionEvent.Failed(now)).state as SessionState.Reconnecting
            now = s.retryAtMs
            sm.on(SessionEvent.Tick(now))
        }
        val last = sm.on(SessionEvent.Failed(now))
        assertThat(last.state).isEqualTo(SessionState.Closed(CloseReason.GAVE_UP))
    }

    @Test
    fun `fatal failure closes immediately`() {
        val sm = streaming()
        assertThat(
            sm.on(SessionEvent.Failed(0, fatal = true)).state,
        ).isEqualTo(SessionState.Closed(CloseReason.FATAL_ERROR))
    }

    @Test
    fun `stop from any state closes once`() {
        val sm = streaming()
        assertThat(sm.on(SessionEvent.Stop).actions).containsExactly(SessionAction.CloseConnection)
        assertThat(sm.on(SessionEvent.Stop).actions).isEmpty()
        assertThat(sm.on(SessionEvent.Start(0)).state).isEqualTo(SessionState.Closed(CloseReason.STOPPED))
    }

    @Test
    fun `reconnection after a streaming failure fits within 3 s`() {
        val sm = streaming()
        val r = sm.on(SessionEvent.Failed(nowMs = 0)).state as SessionState.Reconnecting
        assertThat(r.retryAtMs).isLessThan(3_000)
    }

    private fun streaming() = SessionStateMachine().apply {
        on(SessionEvent.Start(0))
        on(SessionEvent.Connected)
        on(SessionEvent.HandshakeCompleted)
    }
}
