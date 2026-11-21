package dev.ririprojector.net

import com.google.common.truth.Truth.assertThat
import dev.ririprojector.protocol.ControlMessage
import dev.ririprojector.protocol.MessageType
import dev.ririprojector.protocol.model.VideoCodec
import dev.ririprojector.protocol.model.VideoConfig
import java.nio.ByteBuffer
import org.junit.Test

class ClockSyncTest {
    /** Sample for a receiver clock [offset] ahead, one-way [oneWay] µs each way, [hold] µs inside. */
    private fun ClockSync.exchange(t1: Long, offset: Long, oneWay: Long, hold: Long = 0, back: Long = oneWay) {
        val t2 = t1 + oneWay + offset
        val t3 = t2 + hold
        addSample(t1, t2, t3, t3 - offset + back)
    }

    @Test
    fun `symmetric exchange yields exact offset and rtt`() {
        val sync = ClockSync()
        sync.exchange(t1 = 1_000, offset = 5_000, oneWay = 300)
        assertThat(sync.rttUs).isEqualTo(600)
        assertThat(sync.lastRttUs).isEqualTo(600)
        assertThat(sync.offsetUs).isEqualTo(5_000)
        assertThat(sync.toSenderTime(10_000)).isEqualTo(5_000)
    }

    @Test
    fun `time held inside the receiver does not bias rtt or offset`() {
        val sync = ClockSync()
        sync.exchange(t1 = 1_000, offset = -7_000, oneWay = 300, hold = 40_000)
        assertThat(sync.rttUs).isEqualTo(600)
        assertThat(sync.offsetUs).isEqualTo(-7_000)
    }

    @Test
    fun `lowest rtt sample wins and lastRtt tracks the newest`() {
        val sync = ClockSync()
        sync.exchange(0, 5_000, oneWay = 4_000) // queued
        sync.exchange(10_000, 5_000, oneWay = 100) // clean
        sync.exchange(20_000, 5_000, oneWay = 9_000, back = 1_000) // asymmetric spike
        assertThat(sync.rttUs).isEqualTo(200)
        assertThat(sync.offsetUs).isEqualTo(5_000)
        assertThat(sync.lastRttUs).isEqualTo(10_000)
    }

    @Test
    fun `asymmetric path biases the offset by half the asymmetry, within rtt over 2`() {
        val sync = ClockSync()
        sync.exchange(t1 = 0, offset = 5_000, oneWay = 1_000, back = 3_000)
        assertThat(sync.rttUs).isEqualTo(4_000)
        assertThat(sync.offsetUs).isEqualTo(4_000) // true 5 000, error 1 000 ≤ rtt/2
    }

    @Test
    fun `old samples age out of the window`() {
        val sync = ClockSync(window = 2)
        sync.exchange(0, 0, oneWay = 50)
        sync.exchange(1_000, 0, oneWay = 500)
        sync.exchange(3_000, 0, oneWay = 500) // first sample evicted
        assertThat(sync.rttUs).isEqualTo(1_000)
    }

    @Test
    fun `negative rtt or hold is ignored`() {
        val sync = ClockSync()
        sync.addSample(1_000, 0, 0, 500)
        sync.addSample(1_000, 2_000, 1_500, 3_000)
        assertThat(sync.rttUs).isNull()
        assertThat(sync.lastRttUs).isNull()
    }

    @Test
    fun `reset forgets every sample`() {
        val sync = ClockSync()
        sync.exchange(0, 5_000, oneWay = 100)
        sync.reset()
        assertThat(sync.offsetUs).isNull()
        sync.exchange(10_000, -3_000, oneWay = 2_000)
        assertThat(sync.offsetUs).isEqualTo(-3_000)
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

    @Test
    fun `video control stays ordered after earlier frames and survives overflow`() {
        val q = SendQueue(maxVideoPackets = 3)
        val reconfig = ControlMessage.VideoReconfig(1, VideoConfig(VideoCodec.AVC, 1280, 720, 60, 4_000_000))
        q.offerVideo(1, keyframe = true, codecConfig = false, src = buf(10))
        q.offerVideoControl(reconfig)
        q.offerVideo(2, keyframe = false, codecConfig = false, src = buf(10))
        q.offerVideo(3, keyframe = false, codecConfig = false, src = buf(10)) // overflow: frames dropped

        assertThat(q.take(10)!!.control).isEqualTo(reconfig)
        assertThat(q.take(10)).isNull()
    }

    @Test
    fun `resetVideo without keyframe request does not call back but still waits for a keyframe`() {
        var requests = 0
        val q = SendQueue(onKeyframeNeeded = { requests++ })
        q.resetVideo(requestKeyframe = false)
        assertThat(q.offerVideo(1, keyframe = false, codecConfig = false, src = buf(10))).isFalse()
        assertThat(requests).isEqualTo(0)
        assertThat(q.offerVideo(2, keyframe = true, codecConfig = false, src = buf(10))).isTrue()

        q.resetVideo()
        assertThat(requests).isEqualTo(1)
    }

    @Test
    fun `oldest queued video age uses the first frame pts`() {
        val q = SendQueue()
        assertThat(q.oldestQueuedVideoAgeUs(1_000_000)).isEqualTo(0)
        q.offerVideo(0, keyframe = false, codecConfig = true, src = buf(4))
        q.offerVideo(900_000, keyframe = true, codecConfig = false, src = buf(10))
        q.offerVideo(950_000, keyframe = false, codecConfig = false, src = buf(10))
        assertThat(q.oldestQueuedVideoAgeUs(1_000_000)).isEqualTo(100_000)
    }

    @Test
    fun `an IDR flagged config plus key ends the keyframe wait`() {
        val q = SendQueue()
        q.resetVideo(requestKeyframe = false)
        assertThat(q.offerVideo(1, keyframe = true, codecConfig = true, src = buf(10))).isTrue()
        assertThat(q.offerVideo(2, keyframe = false, codecConfig = false, src = buf(10))).isTrue()
        assertThat(q.droppedVideoFrames).isEqualTo(0)
    }

    @Test
    fun `an uncounted reset flushes frames without counting them as drops`() {
        val q = SendQueue()
        q.offerVideo(1, keyframe = true, codecConfig = false, src = buf(10))
        q.offerVideo(2, keyframe = false, codecConfig = false, src = buf(10))
        q.resetVideo(requestKeyframe = false, countDropped = false)
        assertThat(q.droppedVideoFrames).isEqualTo(0)
        assertThat(q.take(0)).isNull()

        q.offerVideo(3, keyframe = true, codecConfig = false, src = buf(10))
        q.resetVideo()
        assertThat(q.droppedVideoFrames).isEqualTo(1)
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
    fun `backoff is 250 ms then capped at 500 ms`() {
        val sm = SessionStateMachine()
        assertThat((1..4).map { sm.backoffFor(it) }).containsExactly(250L, 500L, 500L, 500L).inOrder()
    }

    @Test
    fun `an outage longer than 30 s gives up`() {
        val sm = streaming()
        var now = 10_000L
        var s = sm.on(SessionEvent.Failed(now)).state
        while (s is SessionState.Reconnecting) {
            now = s.retryAtMs
            sm.on(SessionEvent.Tick(now))
            s = sm.on(SessionEvent.Failed(now)).state
        }
        assertThat(s).isEqualTo(SessionState.Closed(CloseReason.GAVE_UP))
        assertThat(now - 10_000).isAtLeast(30_000)
        assertThat(now - 10_000).isLessThan(30_600)
    }

    @Test
    fun `over Wi-Fi Direct an outage gives up after 1,5 s`() {
        val sm = streaming(dev.ririprojector.net.p2p.P2pPolicy.OUTAGE_LIMIT_MS)
        var now = 10_000L
        var s = sm.on(SessionEvent.Failed(now)).state
        while (s is SessionState.Reconnecting) {
            now = s.retryAtMs
            sm.on(SessionEvent.Tick(now))
            s = sm.on(SessionEvent.Failed(now)).state
        }
        assertThat(s).isEqualTo(SessionState.Closed(CloseReason.GAVE_UP))
        // With the receiver's 3 s silence timeout before the first failure: ≤ 5 s end to end.
        assertThat(now - 10_000).isAtLeast(1_500)
        assertThat(now - 10_000).isAtMost(2_000)
    }

    @Test
    fun `Wi-Fi Direct limit retries just before the limit and gives up exactly at it`() {
        val limit = dev.ririprojector.net.p2p.P2pPolicy.OUTAGE_LIMIT_MS
        val early = streaming(limit)
        early.on(SessionEvent.Failed(1_000))
        early.on(SessionEvent.Tick(1_250))
        assertThat(early.on(SessionEvent.Failed(1_000 + limit - 1)).state)
            .isInstanceOf(SessionState.Reconnecting::class.java)

        val late = streaming(limit)
        late.on(SessionEvent.Failed(1_000))
        late.on(SessionEvent.Tick(1_250))
        assertThat(late.on(SessionEvent.Failed(1_000 + limit)).state)
            .isEqualTo(SessionState.Closed(CloseReason.GAVE_UP))
    }

    @Test
    fun `a 2 s outage is survived with the router limit but not with the Wi-Fi Direct one`() {
        fun outcome(limit: Long): SessionState {
            val sm = streaming(limit)
            sm.on(SessionEvent.Failed(0))
            sm.on(SessionEvent.Tick(250))
            return sm.on(SessionEvent.Failed(2_000)).state
        }
        assertThat(outcome(dev.ririprojector.net.p2p.P2pPolicy.ROUTER_OUTAGE_LIMIT_MS))
            .isInstanceOf(SessionState.Reconnecting::class.java)
        assertThat(outcome(dev.ririprojector.net.p2p.P2pPolicy.OUTAGE_LIMIT_MS))
            .isEqualTo(SessionState.Closed(CloseReason.GAVE_UP))
    }

    @Test
    fun `a successful handshake restarts the outage clock`() {
        val sm = streaming()
        val r = sm.on(SessionEvent.Failed(0)).state as SessionState.Reconnecting
        sm.on(SessionEvent.Tick(r.retryAtMs))
        sm.on(SessionEvent.Connected)
        sm.on(SessionEvent.HandshakeCompleted)
        // 29 s after the first failure but just after a new outage started: keep trying.
        assertThat(sm.on(SessionEvent.Failed(29_000)).state).isInstanceOf(SessionState.Reconnecting::class.java)
        assertThat(sm.on(SessionEvent.Tick(29_250)).state).isEqualTo(SessionState.Connecting(1))
        assertThat(sm.on(SessionEvent.Failed(40_000)).state).isInstanceOf(SessionState.Reconnecting::class.java)
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

    private fun streaming(giveUpAfterMs: Long = 30_000) = SessionStateMachine(giveUpAfterMs = giveUpAfterMs).apply {
        on(SessionEvent.Start(0))
        on(SessionEvent.Connected)
        on(SessionEvent.HandshakeCompleted)
    }
}
