package dev.ririprojector.protocol

import com.google.common.truth.Truth.assertThat
import dev.ririprojector.protocol.model.AudioCodec
import dev.ririprojector.protocol.model.AudioCodecCapability
import dev.ririprojector.protocol.model.AudioConfig
import dev.ririprojector.protocol.model.DisplayInfo
import dev.ririprojector.protocol.model.StreamParams
import dev.ririprojector.protocol.model.SyncMode
import dev.ririprojector.protocol.model.VideoCodec
import dev.ririprojector.protocol.model.VideoCodecCapability
import dev.ririprojector.protocol.model.VideoConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import org.junit.Assert.assertThrows
import org.junit.Test

class MessageRoundTripTest {
    private val hello = ControlMessage.Hello(
        protocolVersion = 1,
        deviceName = "Living room TV é中",
        display = DisplayInfo(1920, 1080, 60),
        videoDecoders = listOf(
            VideoCodecCapability(VideoCodec.HEVC, 3840, 2160, 60, hardwareAccelerated = true, lowLatency = true),
            VideoCodecCapability(VideoCodec.AVC, 1920, 1080, 30, hardwareAccelerated = false, lowLatency = false),
        ),
        audioDecoders = listOf(
            AudioCodecCapability(AudioCodec.PCM_S16LE, 2, listOf(44100, 48000)),
            AudioCodecCapability(AudioCodec.OPUS, 6, listOf(48000)),
        ),
    )

    private val allControl: List<ControlMessage> = listOf(
        hello,
        ControlMessage.Hello(1, "", DisplayInfo(1, 1, 1), emptyList(), emptyList()),
        ControlMessage.Start(
            StreamParams(
                Long.MIN_VALUE + 7,
                VideoConfig(VideoCodec.HEVC, 1728, 1080, 60, 12_000_000),
                AudioConfig.DEFAULT_PCM,
            ),
        ),
        ControlMessage.Start(
            StreamParams(
                42,
                VideoConfig(VideoCodec.AVC, 1280, 720, 30, 4_000_000),
                AudioConfig.DEFAULT_PCM,
                SyncMode.LOW_LATENCY,
            ),
        ),
        ControlMessage.AudioSetup(AudioConfig.DEFAULT_PCM),
        ControlMessage.AudioSetup(
            AudioConfig(AudioCodec.OPUS, 48000, 2, 10_000),
            byteArrayOf(1, 2, 3, -1),
        ),
        ControlMessage.RequestKeyframe,
        ControlMessage.Ping(123_456_789_012L),
        ControlMessage.Ping(1L, offsetUs = -42_000L, rttUs = 3_500),
        ControlMessage.Pong(-5L, Long.MAX_VALUE, 0L),
        ControlMessage.Stats(5999, 3, 8000, 20000, 1, -1500),
        ControlMessage.Stats(6000, 0, 9000, 12000, 0, 3000, videoDelayUs = 180_000),
        ControlMessage.Stats(6000, 0, 9000, 12000, 0, ControlMessage.UNKNOWN_SIGNED, 0, 45_000, 70_000),
        ControlMessage.VideoReconfig(
            65_535,
            VideoConfig(VideoCodec.HEVC, 1080, 1920, 60, 8_000_000),
        ),
        ControlMessage.P2pOffer("DIRECT-aB-Riri", "abcdefghijkmnpqr", "Tab è 客"),
        ControlMessage.BitrateHint(8_000_000),
        ControlMessage.Bye(ByeReason.PROJECTION_REVOKED),
    )

    @Test
    fun `every control message type is covered by the fixture list`() {
        val covered = allControl.map { it.type }.toSet()
        val control = MessageType.entries.filter { !it.isMedia }.toSet()
        assertThat(covered).containsExactlyElementsIn(control)
    }

    @Test
    fun `every control message round-trips`() {
        val bytes = written { w -> allControl.forEach { w.writeControl(it) } }
        val reader = MessageReader(ByteArrayInputStream(bytes))

        val decoded = allControl.map {
            val p = reader.read()!!
            assertThat(p.type).isEqualTo(it.type)
            p.decodeControl()
        }

        assertThat(decoded).isEqualTo(allControl)
        assertThat(reader.read()).isNull()
    }

    @Test
    fun `control messages decode when delivered one byte per read`() {
        val bytes = written { w -> allControl.forEach { w.writeControl(it) } }
        val reader = MessageReader(ChunkedInputStream(bytes, 1))

        val decoded = allControl.map { reader.read()!!.decodeControl() }

        assertThat(decoded).isEqualTo(allControl)
        assertThat(reader.read()).isNull()
    }

    @Test
    fun `unknown bye reason code maps to ERROR`() {
        assertThat(ByeReason.fromCode(99)).isEqualTo(ByeReason.ERROR)
    }

    @Test
    fun `control sequence numbers increase by one`() {
        val bos = ByteArrayOutputStream()
        val w = MessageWriter(bos)

        val seqs = List(3) { w.writeControl(ControlMessage.RequestKeyframe) }

        assertThat(seqs).containsExactly(0L, 1L, 2L).inOrder()
        val r = MessageReader(ByteArrayInputStream(bos.toByteArray()))
        assertThat(List(3) { r.read()!!.ptsOrSeq }).containsExactly(0L, 1L, 2L).inOrder()
    }

    private fun payload(size: Int) = ByteArray(size) { (it * 7 + 3).toByte() }

    private fun heapBuffer(data: ByteArray): ByteBuffer = ByteBuffer.wrap(data)

    private fun directBuffer(data: ByteArray): ByteBuffer {
        val d = ByteBuffer.allocateDirect(data.size)
        d.put(data)
        d.flip()
        return d
    }

    /** Heap buffer with arrayOffset 5 and position 5 inside the slice. */
    private fun slicedHeap(data: ByteArray): ByteBuffer {
        val backing = ByteArray(data.size + 30)
        System.arraycopy(data, 0, backing, 10, data.size)
        val sliced = ByteBuffer.wrap(backing, 5, data.size + 20).slice()
        sliced.position(5)
        sliced.limit(5 + data.size)
        return sliced
    }

    private fun slicedDirect(data: ByteArray): ByteBuffer {
        val d = ByteBuffer.allocateDirect(data.size + 20)
        d.position(10)
        d.put(data)
        d.position(10)
        d.limit(10 + data.size)
        return d
    }

    private val bufferFactories: Map<String, (ByteArray) -> ByteBuffer> = mapOf(
        "heap" to ::heapBuffer,
        "direct" to ::directBuffer,
        "heap-offset" to ::slicedHeap,
        "direct-offset" to ::slicedDirect,
    )

    @Test
    fun `sliced heap fixture really has non-zero arrayOffset and position`() {
        val b = slicedHeap(payload(8))
        assertThat(b.arrayOffset()).isEqualTo(5)
        assertThat(b.position()).isEqualTo(5)
        assertThat(b.hasArray()).isTrue()
    }

    @Test
    fun `media packets round-trip for every buffer kind`() {
        val data = payload(1000)
        for ((name, make) in bufferFactories) {
            val bytes = written { w ->
                w.writeVideoConfig(0, make(data))
                w.writeVideoFrame(33_333, keyframe = true, data = make(data))
                w.writeVideoFrame(66_666, keyframe = false, data = make(data))
                w.writeAudioFrame(10_000, make(data))
            }
            val r = MessageReader(ByteArrayInputStream(bytes))

            val cfg = r.read()!!
            assertThat(cfg.type).isEqualTo(MessageType.CONFIG)
            assertThat(cfg.isCodecConfig).isTrue()
            assertThat(cfg.isKeyframe).isFalse()
            assertThat(cfg.copyPayload()).isEqualTo(data)

            val key = r.read()!!
            assertThat(key.type).isEqualTo(MessageType.FRAME)
            assertThat(key.isKeyframe).isTrue()
            assertThat(key.ptsOrSeq).isEqualTo(33_333L)
            assertThat(key.copyPayload()).isEqualTo(data)

            val delta = r.read()!!
            assertThat(delta.type).isEqualTo(MessageType.FRAME)
            assertThat(delta.isKeyframe).isFalse()
            assertThat(delta.isCodecConfig).isFalse()
            assertThat(delta.ptsOrSeq).isEqualTo(66_666L)
            assertThat(delta.copyPayload()).isEqualTo(data)

            val audio = r.read()!!
            assertThat(audio.type).isEqualTo(MessageType.AUDIO_FRAME)
            assertThat(audio.ptsOrSeq).isEqualTo(10_000L)
            assertThat(audio.copyPayload()).isEqualTo(data)

            assertThat(r.read()).isNull()
            assertThat(name).isNotEmpty()
        }
    }

    @Test
    fun `writing a media buffer does not consume it`() {
        for ((_, make) in bufferFactories) {
            val buf = make(payload(64))
            val pos = buf.position()
            val lim = buf.limit()

            written { it.writeVideoFrame(0, true, buf) }

            assertThat(buf.position()).isEqualTo(pos)
            assertThat(buf.limit()).isEqualTo(lim)
        }
    }

    @Test
    fun `media payload larger than the scratch buffer round-trips from a direct buffer`() {
        val data = payload(200_000)

        val bytes = written { it.writeVideoFrame(1, true, directBuffer(data)) }

        val p = MessageReader(ChunkedInputStream(bytes, 4096)).read()!!
        assertThat(p.payloadLength).isEqualTo(data.size)
        assertThat(p.copyPayload()).isEqualTo(data)
    }

    @Test
    fun `media packets decode when delivered one byte per read`() {
        val data = payload(300)
        val bytes = written { it.writeVideoFrame(42, true, directBuffer(data)) }

        val p = MessageReader(ChunkedInputStream(bytes, 1)).read()!!

        assertThat(p.ptsOrSeq).isEqualTo(42L)
        assertThat(p.copyPayload()).isEqualTo(data)
    }

    @Test
    fun `empty media payload round-trips`() {
        val bytes = written { it.writeAudioFrame(5, ByteBuffer.allocate(0)) }

        val p = MessageReader(ByteArrayInputStream(bytes)).read()!!

        assertThat(p.payloadLength).isEqualTo(0)
        assertThat(p.ptsOrSeq).isEqualTo(5L)
    }

    @Test
    fun `u48 pts boundaries round-trip`() {
        val values = listOf(0L, 1L, 0xFFFFFFFFL, 1L shl 32, FrameHeader.MAX_PTS_OR_SEQ)
        val bytes = written { w -> values.forEach { w.writeAudioFrame(it, ByteBuffer.allocate(1)) } }
        val r = MessageReader(ByteArrayInputStream(bytes))

        assertThat(values.map { r.read()!!.ptsOrSeq }).containsExactlyElementsIn(values).inOrder()
    }

    @Test
    fun `header encode rejects pts beyond u48`() {
        val dst = ByteArray(FrameHeader.SIZE)

        assertThrows(IllegalArgumentException::class.java) {
            FrameHeader.encode(dst, 0, MessageType.FRAME.code, 0, 0, FrameHeader.MAX_PTS_OR_SEQ + 1)
        }
    }

    @Test
    fun `header is big-endian with the documented layout`() {
        val bytes = written { it.writeVideoFrame(0x0102030405L, true, ByteBuffer.wrap(ByteArray(0x0102))) }

        assertThat(bytes.copyOfRange(0, 16)).isEqualTo(
            header(MessageType.FRAME.code, 0x0102, flags = HeaderFlags.KEYFRAME, pts = 0x0102030405L),
        )
        assertThat(bytes[0]).isEqualTo(0x52.toByte())
        assertThat(bytes[1]).isEqualTo(0x52.toByte())
    }

    @Test
    fun `writeMedia rejects non media types`() {
        val w = MessageWriter(ByteArrayOutputStream())

        assertThrows(IllegalArgumentException::class.java) {
            w.writeMedia(MessageType.PING, 0, 0, ByteBuffer.allocate(1))
        }
    }
}
