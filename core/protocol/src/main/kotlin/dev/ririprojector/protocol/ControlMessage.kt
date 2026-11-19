package dev.ririprojector.protocol

import dev.ririprojector.protocol.ProtocolException.Reason
import dev.ririprojector.protocol.model.AudioCodec
import dev.ririprojector.protocol.model.AudioCodecCapability
import dev.ririprojector.protocol.model.AudioConfig
import dev.ririprojector.protocol.model.DisplayInfo
import dev.ririprojector.protocol.model.StreamParams
import dev.ririprojector.protocol.model.VideoCodec
import dev.ririprojector.protocol.model.VideoCodecCapability
import dev.ririprojector.protocol.model.VideoConfig

/** Low-rate messages that are worth materializing as objects (everything except media packets). */
sealed interface ControlMessage {
    val type: MessageType

    /** R→S: receiver introduces itself and its decode capabilities. */
    data class Hello(
        val protocolVersion: Int,
        val deviceName: String,
        val display: DisplayInfo,
        val videoDecoders: List<VideoCodecCapability>,
        val audioDecoders: List<AudioCodecCapability>,
    ) : ControlMessage {
        override val type get() = MessageType.HELLO
    }

    /** S→R: negotiated parameters; media follows. */
    data class Start(val params: StreamParams) : ControlMessage {
        override val type get() = MessageType.START
    }

    /** S→R: audio format, plus codec-specific data for compressed codecs (empty for PCM). */
    data class AudioSetup(val config: AudioConfig, val csd: ByteArray = EMPTY) : ControlMessage {
        override val type get() = MessageType.AUDIO_CONFIG

        override fun equals(other: Any?): Boolean =
            other is AudioSetup && config == other.config && csd.contentEquals(other.csd)

        override fun hashCode(): Int = 31 * config.hashCode() + csd.contentHashCode()
    }

    data object RequestKeyframe : ControlMessage {
        override val type get() = MessageType.REQUEST_KEYFRAME
    }

    /** Sender monotonic clock in µs, echoed back in [Pong]. */
    data class Ping(val senderTimeUs: Long) : ControlMessage {
        override val type get() = MessageType.PING
    }

    data class Pong(val senderTimeUs: Long, val receiverTimeUs: Long) : ControlMessage {
        override val type get() = MessageType.PONG
    }

    /** R→S once per second. Rates are scaled by 100 to stay integral on the wire. */
    data class Stats(
        val decodedFpsX100: Int,
        val droppedFrames: Int,
        val decodeLatencyMeanUs: Int,
        val decodeLatencyP95Us: Int,
        val audioUnderruns: Int,
        val avOffsetUs: Int,
    ) : ControlMessage {
        override val type get() = MessageType.STATS
    }

    data class BitrateHint(val bitrateBps: Int) : ControlMessage {
        override val type get() = MessageType.BITRATE_HINT
    }

    data class Bye(val reason: ByeReason) : ControlMessage {
        override val type get() = MessageType.BYE
    }

    companion object {
        internal val EMPTY = ByteArray(0)
    }
}

enum class ByeReason(val code: Int) {
    NORMAL(0),
    USER_STOPPED(1),
    PROJECTION_REVOKED(2),
    BUSY(3),
    INCOMPATIBLE(4),
    ERROR(5),
    ;

    companion object {
        fun fromCode(code: Int): ByeReason = entries.firstOrNull { it.code == code } ?: ERROR
    }
}

/** Binary encoding of [ControlMessage] payloads. */
object ControlCodec {
    fun encode(message: ControlMessage, out: PayloadWriter): PayloadWriter {
        out.reset()
        when (message) {
            is ControlMessage.Hello -> {
                out.u8(message.protocolVersion).string(message.deviceName)
                out.u16(message.display.width).u16(message.display.height).u16(message.display.refreshRateHz)
                out.u8(message.videoDecoders.size)
                message.videoDecoders.forEach { writeVideoCap(out, it) }
                out.u8(message.audioDecoders.size)
                message.audioDecoders.forEach { cap ->
                    out.u8(cap.codec.code).u8(cap.maxChannels).u8(cap.sampleRates.size)
                    cap.sampleRates.forEach { out.i32(it) }
                }
            }

            is ControlMessage.Start -> {
                val p = message.params
                out.i64(p.sessionId)
                out.u8(
                    p.video.codec.code,
                ).u16(p.video.width).u16(p.video.height).u16(p.video.fps).i32(p.video.bitrateBps)
                writeAudioConfig(out, p.audio)
            }

            is ControlMessage.AudioSetup -> {
                writeAudioConfig(out, message.config)
                out.bytesWithLength(message.csd)
            }

            ControlMessage.RequestKeyframe -> Unit

            is ControlMessage.Ping -> out.i64(message.senderTimeUs)

            is ControlMessage.Pong -> out.i64(message.senderTimeUs).i64(message.receiverTimeUs)

            is ControlMessage.Stats ->
                out.i32(message.decodedFpsX100).i32(message.droppedFrames).i32(message.decodeLatencyMeanUs)
                    .i32(message.decodeLatencyP95Us).i32(message.audioUnderruns).i32(message.avOffsetUs)

            is ControlMessage.BitrateHint -> out.i32(message.bitrateBps)

            is ControlMessage.Bye -> out.u8(message.reason.code)
        }
        return out
    }

    /** Decodes a control payload; any invalid value surfaces as [ProtocolException], never IAE. */
    fun decode(type: MessageType, bytes: ByteArray, offset: Int, length: Int): ControlMessage = try {
        decodeUnchecked(type, bytes, offset, length)
    } catch (e: IllegalArgumentException) {
        throw ProtocolException(Reason.MALFORMED_PAYLOAD, e.message ?: "invalid value")
    }

    private fun decodeUnchecked(type: MessageType, bytes: ByteArray, offset: Int, length: Int): ControlMessage {
        val r = PayloadReader(bytes, offset, offset + length)
        val message: ControlMessage =
            when (type) {
                MessageType.HELLO -> {
                    val version = r.u8()
                    val name = r.string()
                    val display = DisplayInfo(r.u16(), r.u16(), r.u16())
                    val video = List(r.u8()) { readVideoCap(r) }
                    val audio =
                        List(r.u8()) {
                            val codec = AudioCodec.fromCode(r.u8()) ?: throw malformed("unknown audio codec")
                            val channels = r.u8()
                            AudioCodecCapability(codec, channels, List(r.u8()) { r.i32() })
                        }
                    ControlMessage.Hello(version, name, display, video, audio)
                }

                MessageType.START -> {
                    val sessionId = r.i64()
                    val codec = VideoCodec.fromCode(r.u8()) ?: throw malformed("unknown video codec")
                    val video = VideoConfig(codec, r.u16(), r.u16(), r.u16(), r.i32())
                    ControlMessage.Start(StreamParams(sessionId, video, readAudioConfig(r)))
                }

                MessageType.AUDIO_CONFIG -> ControlMessage.AudioSetup(readAudioConfig(r), r.bytesWithLength())

                MessageType.REQUEST_KEYFRAME -> ControlMessage.RequestKeyframe

                MessageType.PING -> ControlMessage.Ping(r.i64())

                MessageType.PONG -> ControlMessage.Pong(r.i64(), r.i64())

                MessageType.STATS -> ControlMessage.Stats(r.i32(), r.i32(), r.i32(), r.i32(), r.i32(), r.i32())

                MessageType.BITRATE_HINT -> ControlMessage.BitrateHint(r.i32())

                MessageType.BYE -> ControlMessage.Bye(ByeReason.fromCode(r.u8()))

                MessageType.CONFIG, MessageType.FRAME, MessageType.AUDIO_FRAME ->
                    throw ProtocolException(Reason.UNEXPECTED_TYPE, "$type is a media packet, not a control message")
            }
        r.expectEnd()
        return message
    }

    private fun writeVideoCap(out: PayloadWriter, cap: VideoCodecCapability) {
        out.u8(cap.codec.code).u16(cap.maxWidth).u16(cap.maxHeight).u16(cap.maxFps)
        out.bool(cap.hardwareAccelerated).bool(cap.lowLatency)
    }

    private fun readVideoCap(r: PayloadReader): VideoCodecCapability {
        val codec = VideoCodec.fromCode(r.u8()) ?: throw malformed("unknown video codec")
        return VideoCodecCapability(codec, r.u16(), r.u16(), r.u16(), r.bool(), r.bool())
    }

    private fun writeAudioConfig(out: PayloadWriter, c: AudioConfig) {
        out.u8(c.codec.code).i32(c.sampleRateHz).u8(c.channels).i32(c.frameDurationUs)
    }

    private fun readAudioConfig(r: PayloadReader): AudioConfig {
        val codec = AudioCodec.fromCode(r.u8()) ?: throw malformed("unknown audio codec")
        return AudioConfig(codec, r.i32(), r.u8(), r.i32())
    }

    private fun malformed(msg: String) = ProtocolException(Reason.MALFORMED_PAYLOAD, msg)
}
