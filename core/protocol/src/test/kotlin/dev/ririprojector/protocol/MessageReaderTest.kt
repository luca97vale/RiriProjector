package dev.ririprojector.protocol

import com.google.common.truth.Truth.assertThat
import dev.ririprojector.protocol.ProtocolException.Reason
import dev.ririprojector.protocol.model.AudioConfig
import dev.ririprojector.protocol.model.StreamParams
import dev.ririprojector.protocol.model.VideoCodec
import dev.ririprojector.protocol.model.VideoConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertThrows
import org.junit.Test

class MessageReaderTest {
    private fun reasonOf(block: () -> Unit): Reason = assertThrows(ProtocolException::class.java) { block() }.reason

    @Test
    fun `clean EOF between packets returns null`() {
        assertThat(MessageReader(ByteArrayInputStream(ByteArray(0))).read()).isNull()
    }

    @Test
    fun `EOF after a complete packet returns null on next read`() {
        val bytes = written { it.writeControl(ControlMessage.RequestKeyframe) }
        val r = MessageReader(ByteArrayInputStream(bytes))

        assertThat(r.read()).isNotNull()
        assertThat(r.read()).isNull()
    }

    @Test
    fun `EOF mid-header is TRUNCATED`() {
        val h = header(MessageType.PING.code, 0)

        for (n in listOf(1, 5, 15)) {
            val r = MessageReader(ByteArrayInputStream(h.copyOf(n)))
            assertThat(reasonOf { r.read() }).isEqualTo(Reason.TRUNCATED)
        }
    }

    @Test
    fun `EOF mid-payload is TRUNCATED`() {
        val bytes = header(MessageType.FRAME.code, 100) + ByteArray(40)
        val r = MessageReader(ByteArrayInputStream(bytes))

        assertThat(reasonOf { r.read() }).isEqualTo(Reason.TRUNCATED)
    }

    @Test
    fun `EOF mid-payload is TRUNCATED when delivered byte by byte`() {
        val bytes = header(MessageType.FRAME.code, 100) + ByteArray(40)
        val r = MessageReader(ChunkedInputStream(bytes, 1))

        assertThat(reasonOf { r.read() }).isEqualTo(Reason.TRUNCATED)
    }

    @Test
    fun `bad magic is rejected`() {
        val r = MessageReader(ByteArrayInputStream(header(MessageType.PING.code, 0, magic = 0x1234)))

        assertThat(reasonOf { r.read() }).isEqualTo(Reason.BAD_MAGIC)
    }

    @Test
    fun `unsupported version is rejected`() {
        val current = ProtocolInfo.PROTOCOL_VERSION
        for (v in listOf(0, current - 1, current + 1, 255)) { // includes the previous version (v1 peers)
            val r = MessageReader(ByteArrayInputStream(header(MessageType.PING.code, 0, version = v)))
            assertThat(reasonOf { r.read() }).isEqualTo(Reason.UNSUPPORTED_VERSION)
        }
    }

    @Test
    fun `reader rejects payload over the limit`() {
        val bytes = header(MessageType.FRAME.code, 1001) + ByteArray(1001)
        val r = MessageReader(ByteArrayInputStream(bytes), maxPayloadBytes = 1000)

        assertThat(reasonOf { r.read() }).isEqualTo(Reason.PAYLOAD_TOO_LARGE)
    }

    @Test
    fun `payload exactly at the limit is accepted`() {
        val bytes = header(MessageType.FRAME.code, 1000) + ByteArray(1000)
        val r = MessageReader(ByteArrayInputStream(bytes), maxPayloadBytes = 1000, initialPayloadCapacity = 16)

        assertThat(r.read()!!.payloadLength).isEqualTo(1000)
    }

    @Test
    fun `huge declared length is rejected before reading or allocating the payload`() {
        // 0xFFFFFFFF would be -1 as a signed Int; no payload bytes follow, so a reader that tried
        // to read the payload would report TRUNCATED instead.
        val counting = CountingInputStream(ByteArrayInputStream(header(MessageType.FRAME.code, 0xFFFFFFFFL)))
        val r = MessageReader(counting, maxPayloadBytes = 1024, initialPayloadCapacity = 16)

        assertThat(reasonOf { r.read() }).isEqualTo(Reason.PAYLOAD_TOO_LARGE)
        assertThat(counting.bytesRead).isEqualTo(FrameHeader.SIZE)
    }

    @Test
    fun `payload buffer growth never exceeds the configured maximum for large legal payloads`() {
        val bytes = header(MessageType.FRAME.code, 600) + ByteArray(600)
        val r = MessageReader(ByteArrayInputStream(bytes), maxPayloadBytes = 1000, initialPayloadCapacity = 16)

        val p = r.read()!!

        assertThat(p.payload.size).isAtMost(1000)
        assertThat(p.payloadLength).isEqualTo(600)
    }

    @Test
    fun `unknown type code is surfaced with null type and decodeControl throws`() {
        val bytes = header(0x55, 3) + byteArrayOf(1, 2, 3) + written { it.writeControl(ControlMessage.RequestKeyframe) }
        val r = MessageReader(ByteArrayInputStream(bytes))

        val unknown = r.read()!!
        assertThat(unknown.type).isNull()
        assertThat(unknown.typeCode).isEqualTo(0x55)
        assertThat(unknown.payloadLength).isEqualTo(3)
        assertThat(assertThrows(ProtocolException::class.java) { unknown.decodeControl() }.reason)
            .isEqualTo(Reason.UNEXPECTED_TYPE)

        // the stream stays in sync: the next packet is readable
        assertThat(r.read()!!.type).isEqualTo(MessageType.REQUEST_KEYFRAME)
    }

    @Test
    fun `decodeControl on a media packet is UNEXPECTED_TYPE`() {
        val bytes = written { it.writeVideoFrame(0, true, ByteBuffer.allocate(4)) }
        val p = MessageReader(ByteArrayInputStream(bytes)).read()!!

        assertThat(assertThrows(ProtocolException::class.java) { p.decodeControl() }.reason)
            .isEqualTo(Reason.UNEXPECTED_TYPE)
    }

    @Test
    fun `trailing bytes in a control payload are MALFORMED_PAYLOAD`() {
        val bytes = header(MessageType.PING.code, 9) + ByteArray(9)
        val p = MessageReader(ByteArrayInputStream(bytes)).read()!!

        assertThat(assertThrows(ProtocolException::class.java) { p.decodeControl() }.reason)
            .isEqualTo(Reason.MALFORMED_PAYLOAD)
    }

    @Test
    fun `short control payload is MALFORMED_PAYLOAD`() {
        val bytes = header(MessageType.PONG.code, 8) + ByteArray(8)
        val p = MessageReader(ByteArrayInputStream(bytes)).read()!!

        assertThat(assertThrows(ProtocolException::class.java) { p.decodeControl() }.reason)
            .isEqualTo(Reason.MALFORMED_PAYLOAD)
    }

    @Test
    fun `string length beyond payload is MALFORMED_PAYLOAD`() {
        // HELLO: version u8, then string length 0xFFFF with no bytes
        val payload = byteArrayOf(1, 0xFF.toByte(), 0xFF.toByte())
        val bytes = header(MessageType.HELLO.code, payload.size.toLong()) + payload
        val p = MessageReader(ByteArrayInputStream(bytes)).read()!!

        assertThat(assertThrows(ProtocolException::class.java) { p.decodeControl() }.reason)
            .isEqualTo(Reason.MALFORMED_PAYLOAD)
    }

    @Test
    fun `unknown video codec in START is MALFORMED_PAYLOAD`() {
        val good = written {
            it.writeControl(
                ControlMessage.Start(
                    StreamParams(1, VideoConfig(VideoCodec.AVC, 640, 480, 30, 1_000_000), AudioConfig.DEFAULT_PCM),
                ),
            )
        }
        good[FrameHeader.SIZE + 8] = 99 // video codec byte follows the i64 session id
        val p = MessageReader(ByteArrayInputStream(good)).read()!!

        assertThat(assertThrows(ProtocolException::class.java) { p.decodeControl() }.reason)
            .isEqualTo(Reason.MALFORMED_PAYLOAD)
    }

    @Test
    fun `packet and payload array are reused across reads when capacity suffices`() {
        val bytes = written { w ->
            w.writeVideoFrame(1, true, ByteBuffer.wrap(ByteArray(100) { 1 }))
            w.writeVideoFrame(2, false, ByteBuffer.wrap(ByteArray(50) { 2 }))
        }
        val r = MessageReader(ByteArrayInputStream(bytes), initialPayloadCapacity = 256)

        val first = r.read()!!
        val firstArray = first.payload
        val second = r.read()!!

        assertThat(second).isSameInstanceAs(first)
        assertThat(second.payload).isSameInstanceAs(firstArray)
        assertThat(second.payloadLength).isEqualTo(50)
        assertThat(second.ptsOrSeq).isEqualTo(2L)
        assertThat(second.copyPayload()).isEqualTo(ByteArray(50) { 2 })
    }

    @Test
    fun `payload array grows when a packet exceeds capacity`() {
        val bytes = written { it.writeVideoFrame(1, true, ByteBuffer.wrap(ByteArray(500) { 9 })) }
        val r = MessageReader(ByteArrayInputStream(bytes), initialPayloadCapacity = 16)

        val p = r.read()!!

        assertThat(p.payload.size).isAtLeast(500)
        assertThat(p.copyPayload()).isEqualTo(ByteArray(500) { 9 })
    }

    @Test
    fun `writer rejects payload over the limit and writes nothing`() {
        val bos = ByteArrayOutputStream()
        val w = MessageWriter(bos, maxPayloadBytes = 100)

        val e = assertThrows(ProtocolException::class.java) {
            w.writeVideoFrame(0, true, ByteBuffer.allocate(101))
        }

        assertThat(e.reason).isEqualTo(Reason.PAYLOAD_TOO_LARGE)
        assertThat(bos.size()).isEqualTo(0)
    }

    @Test
    fun `writer accepts payload exactly at the limit`() {
        val bos = ByteArrayOutputStream()
        MessageWriter(bos, maxPayloadBytes = 100).writeVideoFrame(0, true, ByteBuffer.allocate(100))

        assertThat(bos.size()).isEqualTo(FrameHeader.SIZE + 100)
    }

    private class CountingInputStream(private val delegate: InputStream) : InputStream() {
        var bytesRead = 0

        override fun read(): Int = delegate.read().also { if (it >= 0) bytesRead++ }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            delegate.read(b, off, len).also { if (it > 0) bytesRead += it }
    }
}
