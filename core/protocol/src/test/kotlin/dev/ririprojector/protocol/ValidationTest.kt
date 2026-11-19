package dev.ririprojector.protocol

import com.google.common.truth.Truth.assertThat
import dev.ririprojector.protocol.ProtocolException.Reason
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertThrows
import org.junit.Test

class ValidationTest {
    @Test
    fun `START with zero width is rejected as malformed`() {
        val payload =
            PayloadWriter()
                .i64(1) // session id
                .u8(1).u16(0).u16(1080).u16(60).i32(8_000_000) // AVC 0x1080 @60
                .u8(1).i32(48_000).u8(2).i32(10_000) // PCM audio

        val e =
            assertThrows(ProtocolException::class.java) {
                ControlCodec.decode(MessageType.START, payload.bytes, 0, payload.size)
            }

        assertThat(e.reason).isEqualTo(Reason.MALFORMED_PAYLOAD)
    }

    @Test
    fun `START with negative sample rate is rejected as malformed`() {
        val payload =
            PayloadWriter()
                .i64(1)
                .u8(1).u16(1920).u16(1080).u16(60).i32(8_000_000)
                .u8(1).i32(-48_000).u8(2).i32(10_000)

        val e =
            assertThrows(ProtocolException::class.java) {
                ControlCodec.decode(MessageType.START, payload.bytes, 0, payload.size)
            }

        assertThat(e.reason).isEqualTo(Reason.MALFORMED_PAYLOAD)
    }

    @Test
    fun `HELLO with zero display size decoded directly throws ProtocolException not IAE`() {
        val payload =
            PayloadWriter()
                .u8(ProtocolInfo.PROTOCOL_VERSION).string("tv")
                .u16(0).u16(0).u16(60)
                .u8(0).u8(0)

        val e =
            assertThrows(ProtocolException::class.java) {
                ControlCodec.decode(MessageType.HELLO, payload.bytes, 0, payload.size)
            }

        assertThat(e.reason).isEqualTo(Reason.MALFORMED_PAYLOAD)
    }

    @Test
    fun `negative media pts is rejected instead of wrapping`() {
        val writer = MessageWriter(ByteArrayOutputStream())

        assertThrows(IllegalArgumentException::class.java) {
            writer.writeAudioFrame(-1, ByteBuffer.wrap(byteArrayOf(1, 2)))
        }
    }

    @Test
    fun `direct buffer write leaves position untouched`() {
        val out = ByteArrayOutputStream()
        val data = ByteBuffer.allocateDirect(8).apply {
            put(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
            position(3)
        }

        MessageWriter(out).writeVideoFrame(42, keyframe = false, data = data)

        assertThat(data.position()).isEqualTo(3)
        assertThat(out.toByteArray().copyOfRange(FrameHeader.SIZE, FrameHeader.SIZE + 5))
            .isEqualTo(byteArrayOf(4, 5, 6, 7, 8))
    }
}
