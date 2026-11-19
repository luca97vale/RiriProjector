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
    private const val ALIGNMENT = 16
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
        val order = listOfNotNull(prefs.preferredVideoCodec) + listOf(VideoCodec.HEVC, VideoCodec.AVC)
        val pair =
            order.distinct().firstNotNullOfOrNull { codec ->
                val enc = sender.videoEncoders.firstOrNull { it.codec == codec && it.hardwareAccelerated }
                val dec = receiver.videoDecoders.firstOrNull { it.codec == codec }
                if (enc != null && dec != null) enc to dec else null
            } ?: return Negotiation.Failure("no common hardware video codec")
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

    /**
     * Largest size with the source aspect ratio that fits every limit, each side rounded down to a
     * multiple of 16 (hardware encoders are most efficient and least buggy with aligned sizes).
     * Limits are applied in landscape terms, so a portrait tablet fits a landscape TV correctly.
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
        val portrait = srcH > srcW
        val longSide = maxOf(srcW, srcH)
        val shortSide = minOf(srcW, srcH)

        val maxLong = listOf(receiver.display.width, receiver.display.height).max()
            .let { minOf(it, maxOf(enc.maxWidth, enc.maxHeight), maxOf(dec.maxWidth, dec.maxHeight)) }
        val maxShortCandidates = mutableListOf(
            minOf(receiver.display.width, receiver.display.height),
            minOf(enc.maxWidth, enc.maxHeight),
            minOf(dec.maxWidth, dec.maxHeight),
        )
        prefMaxHeight?.let { maxShortCandidates += it }
        val maxShort = maxShortCandidates.min()

        val scale = minOf(1.0, maxLong / longSide, maxShort / shortSide)
        val outLong = align(longSide * scale)
        val outShort = align(shortSide * scale)
        if (outLong < MIN_DIMENSION || outShort < MIN_DIMENSION) return null
        return if (portrait) outShort to outLong else outLong to outShort
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
