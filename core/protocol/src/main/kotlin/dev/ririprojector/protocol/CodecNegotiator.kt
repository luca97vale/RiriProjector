package dev.ririprojector.protocol

import dev.ririprojector.protocol.model.AudioCodec
import dev.ririprojector.protocol.model.AudioCodecCapability
import dev.ririprojector.protocol.model.AudioConfig
import dev.ririprojector.protocol.model.SenderCapabilities
import dev.ririprojector.protocol.model.StreamParams
import dev.ririprojector.protocol.model.StreamPreferences
import dev.ririprojector.protocol.model.VideoCodec
import dev.ririprojector.protocol.model.VideoCodecCapability
import dev.ririprojector.protocol.model.VideoConfig

sealed interface Negotiation {
    data class Success(val params: StreamParams) : Negotiation

    data class Failure(val reason: String) : Negotiation
}

/**
 * Pure stream negotiation (ADR-004, ADR-007): picks codec, resolution, fps, bitrate and audio
 * format from the sender's encoders, the receiver's HELLO and user preferences.
 */
object CodecNegotiator {
    const val MIN_BITRATE_BPS = 2_000_000
    const val MAX_BITRATE_BPS = 40_000_000
    private const val ALIGNMENT = 8
    private const val MIN_DIMENSION = 160

    /** Bits per pixel used for automatic bitrate; HEVC needs ~30% less than AVC. */
    fun bitsPerPixel(codec: VideoCodec): Double = when (codec) {
        VideoCodec.AVC -> 0.10
        VideoCodec.HEVC -> 0.07
    }

    fun negotiate(
        sender: SenderCapabilities,
        receiver: ControlMessage.Hello,
        prefs: StreamPreferences = StreamPreferences(),
        sessionId: Long = 0,
    ): Negotiation {
        if (receiver.protocolVersion != ProtocolInfo.PROTOCOL_VERSION) {
            return Negotiation.Failure("receiver speaks protocol v${receiver.protocolVersion}")
        }
        val order = (listOfNotNull(prefs.preferredVideoCodec) + listOf(VideoCodec.HEVC, VideoCodec.AVC)).distinct()
        // First pass: hardware decoder on the receiver (low-latency first). Only if no codec has one,
        // accept a software decoder rather than failing outright.
        val pair =
            pickPair(sender, receiver, order, requireHardwareDecoder = true)
                ?: pickPair(sender, receiver, order, requireHardwareDecoder = false)
                ?: return Negotiation.Failure("no common hardware video codec")
        val (enc, dec) = pair

        val size = fitResolution(sender, receiver, enc, dec, prefs.maxHeight)
            ?: return Negotiation.Failure("no usable resolution")
        val fps =
            listOfNotNull(
                sender.source.refreshRateHz,
                receiver.display.refreshRateHz,
                enc.maxFps,
                dec.maxFps,
                prefs.maxFps,
            )
                .min()
                .coerceAtLeast(1)
        val bitrate = prefs.bitrateBps?.coerceIn(MIN_BITRATE_BPS, MAX_BITRATE_BPS)
            ?: autoBitrate(enc.codec, size.first, size.second, fps)

        val video = VideoConfig(enc.codec, size.first, size.second, fps, bitrate)
        val audio = negotiateAudio(sender.audioEncoders, receiver.audioDecoders, prefs.preferredAudioCodec)
        return Negotiation.Success(StreamParams(sessionId, video, audio))
    }

    fun autoBitrate(codec: VideoCodec, width: Int, height: Int, fps: Int): Int {
        val bps = width.toDouble() * height * fps * bitsPerPixel(codec)
        return bps.toLong().coerceIn(MIN_BITRATE_BPS.toLong(), MAX_BITRATE_BPS.toLong()).toInt()
    }

    private fun pickPair(
        sender: SenderCapabilities,
        receiver: ControlMessage.Hello,
        order: List<VideoCodec>,
        requireHardwareDecoder: Boolean,
    ): Pair<VideoCodecCapability, VideoCodecCapability>? = order.firstNotNullOfOrNull { codec ->
        val enc = sender.videoEncoders.firstOrNull { it.codec == codec && it.hardwareAccelerated }
        val dec =
            receiver.videoDecoders
                .filter { it.codec == codec && (it.hardwareAccelerated || !requireHardwareDecoder) }
                .sortedWith(
                    compareByDescending<VideoCodecCapability> {
                        it.hardwareAccelerated
                    }.thenByDescending { it.lowLatency },
                )
                .firstOrNull()
        if (enc != null && dec != null) enc to dec else null
    }

    /**
     * Largest size with the source aspect ratio that fits every limit, each side rounded down to a
     * multiple of 8 (keeps 1080 lines intact; hardware codecs pad internally).
     *
     * The source keeps its real orientation and is fitted inside the receiver display as-is: the TV
     * does not rotate, so a portrait tablet becomes a pillarboxed portrait stream rather than a
     * full-height image the TV would have to downscale. Codec limits are checked per axis.
     */
    internal fun fitResolution(
        sender: SenderCapabilities,
        receiver: ControlMessage.Hello,
        enc: VideoCodecCapability,
        dec: VideoCodecCapability,
        prefMaxHeight: Int?,
    ): Pair<Int, Int>? {
        val srcW = sender.source.width.toDouble()
        val srcH = sender.source.height.toDouble()
        val maxW = minOf(receiver.display.width, enc.maxWidth, dec.maxWidth)
        val maxH = listOfNotNull(receiver.display.height, enc.maxHeight, dec.maxHeight, prefMaxHeight).min()

        val scale = minOf(1.0, maxW / srcW, maxH / srcH)
        val outW = align(srcW * scale)
        val outH = align(srcH * scale)
        if (outW < MIN_DIMENSION || outH < MIN_DIMENSION) return null
        return outW to outH
    }

    private fun align(value: Double): Int = (value.toInt() / ALIGNMENT) * ALIGNMENT

    internal fun negotiateAudio(
        encoders: List<AudioCodecCapability>,
        decoders: List<AudioCodecCapability>,
        preferred: AudioCodec,
    ): AudioConfig {
        if (preferred == AudioCodec.OPUS) {
            val rate = AudioConfig.DEFAULT_PCM.sampleRateHz
            val enc = encoders.firstOrNull {
                it.codec == AudioCodec.OPUS && rate in it.sampleRates &&
                    it.maxChannels >= 2
            }
            val dec = decoders.firstOrNull {
                it.codec == AudioCodec.OPUS && rate in it.sampleRates &&
                    it.maxChannels >= 2
            }
            if (enc != null && dec != null) {
                return AudioConfig(AudioCodec.OPUS, rate, channels = 2, frameDurationUs = 10_000)
            }
        }
        // PCM is always capturable (AudioPlaybackCapture) and playable (AudioTrack): audio is mandatory.
        return AudioConfig.DEFAULT_PCM
    }
}
