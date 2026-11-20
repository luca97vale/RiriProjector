package dev.ririprojector.protocol

import dev.ririprojector.protocol.ProtocolException.Reason
import dev.ririprojector.protocol.model.AudioCodec
import dev.ririprojector.protocol.model.AudioCodecCapability
import dev.ririprojector.protocol.model.AudioConfig
import dev.ririprojector.protocol.model.DisplayInfo
import dev.ririprojector.protocol.model.StreamParams
import dev.ririprojector.protocol.model.SyncMode
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

    /**
     * S→R. [senderTimeUs] is sender `CLOCK_MONOTONIC` µs, echoed back in [Pong]. v3: carries the
     * sender's current estimate of receiver-minus-sender clock offset ([NO_OFFSET] until known)
     * and the best round-trip time, so the receiver can place sender pts on its own clock.
     */
    data class Ping(val senderTimeUs: Long, val offsetUs: Long = NO_OFFSET, val rttUs: Int = 0) : ControlMessage {
        override val type get() = MessageType.PING
    }

    /**
     * R→S, NTP-style (v3): receiver `CLOCK_MONOTONIC` µs when the PING was read ([receiverRxUs])
     * and right before this PONG was written ([receiverTxUs]).
     */
    data class Pong(val senderTimeUs: Long, val receiverRxUs: Long, val receiverTxUs: Long) : ControlMessage {
        override val type get() = MessageType.PONG
    }

    /** R→S once per second. Rates are scaled by 100 to stay integral on the wire. */
    data class Stats(
        val decodedFpsX100: Int,
        val droppedFrames: Int,
        val decodeLatencyMeanUs: Int,
        val decodeLatencyP95Us: Int,
        val audioUnderruns: Int,
        /** Audio minus video, positive = audio late; [UNKNOWN_SIGNED] until measurable. */
        val avOffsetUs: Int,
        /** Lip-sync delay the receiver applies to video (0 in LOW_LATENCY). Added in v2. */
        val videoDelayUs: Int = 0,
        /** Capture (sender pts) to shown on the receiver, mean / p95; [UNKNOWN] until known. v3. */
        val captureToShownUs: Int = UNKNOWN,
        val captureToShownP95Us: Int = UNKNOWN,
    ) : ControlMessage {
        override val type get() = MessageType.STATS
    }

    /**
     * S→R (v3): the video size changed (rotation, one-app resize). Sent in order with video: every
     * unit after it belongs to [video], starting with CONFIG and a keyframe. [generation] counts
     * reconfigurations in the session (u16, wraps).
     */
    data class VideoReconfig(val generation: Int, val video: VideoConfig) : ControlMessage {
        override val type get() = MessageType.VIDEO_RECONFIG
    }

    data class BitrateHint(val bitrateBps: Int) : ControlMessage {
        override val type get() = MessageType.BITRATE_HINT
    }

    data class Bye(val reason: ByeReason) : ControlMessage {
        override val type get() = MessageType.BYE
    }

    companion object {
        internal val EMPTY = ByteArray(0)

        /** [Ping.offsetUs] before the sender has a clock estimate. */
        const val NO_OFFSET = Long.MIN_VALUE

        /** Unknown value of a non-negative duration field (latencies). */
        const val UNKNOWN = -1

        /** Unknown value of a signed field (offsets). */
        const val UNKNOWN_SIGNED = Int.MIN_VALUE
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
                writeVideoConfig(out, p.video)
                writeAudioConfig(out, p.audio)
                out.u8(p.syncMode.code)
            }

            is ControlMessage.AudioSetup -> {
                writeAudioConfig(out, message.config)
                out.bytesWithLength(message.csd)
            }

            ControlMessage.RequestKeyframe -> Unit

            is ControlMessage.Ping -> out.i64(message.senderTimeUs).i64(message.offsetUs).i32(message.rttUs)

            is ControlMessage.Pong ->
                out.i64(message.senderTimeUs).i64(message.receiverRxUs).i64(message.receiverTxUs)

            is ControlMessage.Stats ->
                out.i32(message.decodedFpsX100).i32(message.droppedFrames).i32(message.decodeLatencyMeanUs)
                    .i32(message.decodeLatencyP95Us).i32(message.audioUnderruns).i32(message.avOffsetUs)
                    .i32(message.videoDelayUs).i32(message.captureToShownUs).i32(message.captureToShownP95Us)

            is ControlMessage.VideoReconfig -> {
                out.u16(message.generation)
                writeVideoConfig(out, message.video)
            }

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
                    val video = readVideoConfig(r)
                    val audio = readAudioConfig(r)
                    val sync = SyncMode.fromCode(r.u8()) ?: throw malformed("unknown sync mode")
                    ControlMessage.Start(StreamParams(sessionId, video, audio, sync))
                }

                MessageType.AUDIO_CONFIG -> ControlMessage.AudioSetup(readAudioConfig(r), r.bytesWithLength())

                MessageType.REQUEST_KEYFRAME -> ControlMessage.RequestKeyframe

                MessageType.PING -> ControlMessage.Ping(r.i64(), r.i64(), r.i32())

                MessageType.PONG -> ControlMessage.Pong(r.i64(), r.i64(), r.i64())

                MessageType.STATS ->
                    ControlMessage.Stats(
                        decodedFpsX100 = r.i32(),
                        droppedFrames = r.i32(),
                        decodeLatencyMeanUs = r.i32(),
                        decodeLatencyP95Us = r.i32(),
                        audioUnderruns = r.i32(),
                        avOffsetUs = r.i32(),
                        videoDelayUs = r.i32(),
                        captureToShownUs = r.i32(),
                        captureToShownP95Us = r.i32(),
                    )

                MessageType.VIDEO_RECONFIG -> ControlMessage.VideoReconfig(r.u16(), readVideoConfig(r))

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

    private fun writeVideoConfig(out: PayloadWriter, v: VideoConfig) {
        out.u8(v.codec.code).u16(v.width).u16(v.height).u16(v.fps).i32(v.bitrateBps)
    }

    private fun readVideoConfig(r: PayloadReader): VideoConfig {
        val codec = VideoCodec.fromCode(r.u8()) ?: throw malformed("unknown video codec")
        return VideoConfig(codec, r.u16(), r.u16(), r.u16(), r.i32())
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
