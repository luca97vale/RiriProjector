package dev.ririprojector.net

import com.google.common.truth.Truth.assertThat
import dev.ririprojector.protocol.ByeReason
import dev.ririprojector.protocol.ControlMessage
import dev.ririprojector.protocol.MessageType
import dev.ririprojector.protocol.ProtocolInfo
import dev.ririprojector.protocol.model.AudioCodec
import dev.ririprojector.protocol.model.AudioCodecCapability
import dev.ririprojector.protocol.model.DisplayInfo
import dev.ririprojector.protocol.model.SenderCapabilities
import dev.ririprojector.protocol.model.StreamPreferences
import dev.ririprojector.protocol.model.VideoCodec
import dev.ririprojector.protocol.model.VideoCodecCapability
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

class TransportTest {
    private val hw = { c: VideoCodec ->
        VideoCodecCapability(c, 3840, 2160, 60, hardwareAccelerated = true, lowLatency = true)
    }
    private val pcm = AudioCodecCapability(AudioCodec.PCM_S16LE, 2, listOf(48_000))
    private val hello =
        ControlMessage.Hello(
            ProtocolInfo.PROTOCOL_VERSION,
            "tv",
            DisplayInfo(1920, 1080, 60),
            listOf(hw(VideoCodec.AVC)),
            listOf(pcm),
        )
    private val senderCaps =
        SenderCapabilities(DisplayInfo(2560, 1600, 120), listOf(hw(VideoCodec.HEVC), hw(VideoCodec.AVC)), listOf(pcm))

    private val accepted = LinkedBlockingQueue<Connection>()
    private val server = ReceiverServer(port = 0) { accepted.put(it) }.start()
    private val endpoint get() = ReceiverEndpoint("tv", "127.0.0.1", server.boundPort)

    @After
    fun tearDown() = server.close()

    @Test
    fun `handshake negotiates over a real socket`() {
        val receiverSide = CompletableFuture.supplyAsync {
            val conn = accepted.poll(5, TimeUnit.SECONDS)!!
            Handshake.asReceiver(conn, hello)
        }
        SenderClient.connect(endpoint).use { conn ->
            val (gotHello, params) = Handshake.asSender(conn, senderCaps, StreamPreferences(), sessionId = 7)

            assertThat(gotHello).isEqualTo(hello)
            assertThat(params.video.codec).isEqualTo(VideoCodec.AVC)
            assertThat(receiverSide.get(5, TimeUnit.SECONDS)).isEqualTo(params)
            assertThat(params.sessionId).isEqualTo(7)
        }
    }

    @Test
    fun `incompatible receiver gets BYE and sender fails`() {
        val noVideo = hello.copy(videoDecoders = emptyList())
        val receiverSide = CompletableFuture.supplyAsync {
            val conn = accepted.poll(5, TimeUnit.SECONDS)!!
            runCatching { Handshake.asReceiver(conn, noVideo) }.exceptionOrNull()
        }
        SenderClient.connect(endpoint).use { conn ->
            val e = assertThrows(HandshakeException::class.java) {
                Handshake.asSender(conn, senderCaps, StreamPreferences(), 1)
            }
            assertThat(e.bye).isEqualTo(ByeReason.INCOMPATIBLE)
        }
        val receiverError = receiverSide.get(5, TimeUnit.SECONDS) as HandshakeException
        assertThat(receiverError.bye).isEqualTo(ByeReason.INCOMPATIBLE)
    }

    @Test
    fun `sender times out when receiver never says HELLO`() {
        ServerSocket(0).use { silent ->
            val conn = SenderClient.connect(ReceiverEndpoint("x", "127.0.0.1", silent.localPort))
            conn.use {
                val e = assertThrows(HandshakeException::class.java) {
                    Handshake.asSender(it, senderCaps, StreamPreferences(), 1, timeoutMs = 200)
                }
                assertThat(e.message).contains("timed out")
            }
        }
    }

    @Test
    fun `another client is rejected with BYE BUSY while a session is active`() {
        val otherAccepted = LinkedBlockingQueue<Connection>()
        ReceiverServer(port = 0, samePeer = { _, _ -> false }) { otherAccepted.put(it) }.start().use { busy ->
            val ep = ReceiverEndpoint("tv", "127.0.0.1", busy.boundPort)
            SenderClient.connect(ep).use {
                otherAccepted.poll(5, TimeUnit.SECONDS)!!
                SenderClient.connect(ep).use { second ->
                    second.setReadTimeout(2_000)
                    val packet = second.reader.read()!!
                    assertThat(packet.type).isEqualTo(MessageType.BYE)
                    assertThat(packet.decodeControl()).isEqualTo(ControlMessage.Bye(ByeReason.BUSY))
                    assertThat(second.reader.read()).isNull()
                }
            }
        }
    }

    @Test
    fun `the same sender reconnecting replaces its stale session`() {
        SenderClient.connect(endpoint).use {
            val first = accepted.poll(5, TimeUnit.SECONDS)!!
            SenderClient.connect(endpoint).use {
                val second = accepted.poll(5, TimeUnit.SECONDS)
                assertThat(second).isNotNull()
                assertThat(first.isClosed).isTrue()
                // The stale session ending late must not free the new session's slot.
                server.sessionEnded(first)
                SenderClient.connect(endpoint).use { third ->
                    assertThat(accepted.poll(5, TimeUnit.SECONDS)).isNotNull() // same address: preempts again
                    assertThat(second!!.isClosed).isTrue()
                    third.setReadTimeout(200)
                }
            }
        }
    }

    @Test
    fun `slot is freed after sessionEnded`() {
        SenderClient.connect(endpoint).use { }
        server.sessionEnded(accepted.poll(5, TimeUnit.SECONDS)!!)

        SenderClient.connect(endpoint).use {
            assertThat(accepted.poll(5, TimeUnit.SECONDS)).isNotNull()
        }
    }

    @Test
    fun `remote close ends the reader cleanly and media flows in order`() {
        SenderClient.connect(endpoint).use { sender ->
            val rx = accepted.poll(5, TimeUnit.SECONDS)!!
            repeat(3) { i ->
                sender.writer.writeVideoFrame(
                    i * 16_666L,
                    keyframe = i == 0,
                    data = ByteBuffer.wrap(
                        ByteArray(1_000) {
                            i.toByte()
                        },
                    ),
                )
            }
            sender.writer.flush()
            sender.close()

            val pts = mutableListOf<Long>()
            while (true) {
                val p = rx.reader.read() ?: break
                pts += p.ptsOrSeq
            }
            assertThat(pts).containsExactly(0L, 16_666L, 33_332L).inOrder()
            rx.close()
        }
    }

    @Test
    fun `closing a connection unblocks a pending read`() {
        SenderClient.connect(endpoint).use { sender ->
            val rx = accepted.poll(5, TimeUnit.SECONDS)!!
            val read = CompletableFuture.supplyAsync { runCatching { rx.reader.read() } }
            Thread.sleep(100)
            rx.close()
            val result = read.get(2, TimeUnit.SECONDS)
            assertThat(result.isFailure || result.getOrNull() == null).isTrue()
            assertThat(rx.isClosed).isTrue()
            sender.close()
        }
    }

    @Test
    fun `a throwing onConnection frees the slot`() {
        var first = true
        val flaky =
            ReceiverServer(port = 0) {
                if (first) {
                    first = false
                    throw IllegalStateException("boom")
                }
                accepted.put(it)
            }.start()
        try {
            val ep = ReceiverEndpoint("tv", "127.0.0.1", flaky.boundPort)
            SenderClient.connect(ep).use { c ->
                c.setReadTimeout(2_000)
                assertThat(c.reader.read()).isNull() // closed, not BYE(BUSY)
            }
            SenderClient.connect(ep).use {
                assertThat(accepted.poll(5, TimeUnit.SECONDS)).isNotNull()
            }
        } finally {
            flaky.close()
        }
    }

    @Test
    fun `handshake times out even if the peer keeps sending PING`() {
        val spammer = CompletableFuture.runAsync {
            val conn = accepted.poll(5, TimeUnit.SECONDS)!!
            runCatching {
                repeat(100) {
                    conn.writer.writeControl(ControlMessage.Ping(it.toLong()))
                    conn.writer.flush()
                    Thread.sleep(20)
                }
            }
        }
        SenderClient.connect(endpoint).use { conn ->
            val start = System.nanoTime()
            val e = assertThrows(HandshakeException::class.java) {
                Handshake.asSender(conn, senderCaps, StreamPreferences(), 1, timeoutMs = 300)
            }
            assertThat(e.message).contains("timed out")
            assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1_500)
        }
        spammer.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `endpoint parsing accepts host and host-port`() {
        assertThat(
            ReceiverEndpoint.parse("192.168.1.50"),
        ).isEqualTo(ReceiverEndpoint("192.168.1.50", "192.168.1.50", ReceiverEndpoint.DEFAULT_PORT))
        assertThat(ReceiverEndpoint.parse(" 10.0.0.2:5000 ")?.port).isEqualTo(5000)
        assertThat(ReceiverEndpoint.parse("10.0.0.2:abc")).isNull()
        assertThat(ReceiverEndpoint.parse("10.0.0.2:70000")).isNull()
        assertThat(ReceiverEndpoint.parse("")).isNull()
    }
}
