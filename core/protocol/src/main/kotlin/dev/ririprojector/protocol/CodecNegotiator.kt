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

/** Exact local check of the encoder (`areSizeAndRateSupported` on device). */
fun interface EncoderPredicate {
    fun accepts(codec: VideoCodec, width: Int, height: Int, fps: Int): Boolean
}

/**
 * Pure stream negotiation (ADR-004, ADR-007): picks codec, resolution, fps, bitrate and audio
 * format from the sender's encoders, the receiver's HELLO and user preferences.
 */
object CodecNegotiator {
    const val MIN_BITRATE_BPS = 2_000_000
    const val MAX_BITRATE_BPS = 40_000_000

    val ACCEPT_ALL = EncoderPredicate { _, _, _, _ -> true }

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
        encoderAccepts: EncoderPredicate = ACCEPT_ALL,
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
        val size = fitResolution(sender, receiver, enc, dec, prefs.maxHeight, fps, encoderAccepts)
            ?: return Negotiation.Failure("no usable resolution")
        val bitrate = prefs.bitrateBps?.coerceIn(MIN_BITRATE_BPS, MAX_BITRATE_BPS)
            ?: autoBitrate(enc.codec, size.width, size.height, fps)

        val video = VideoConfig(enc.codec, size.width, size.height, fps, bitrate)
        val audio = negotiateAudio(sender.audioEncoders, receiver.audioDecoders, prefs.preferredAudioCodec)
        return Negotiation.Success(StreamParams(sessionId, video, audio, prefs.syncMode))
    }

    /**
     * Recomputes the video size after the source changed size (rotation, one-app resize), keeping
     * the negotiated codec and fps. [sender] carries the new source size. The bitrate is the
     * user's fixed value, or the automatic one for the new size; callers clamp it to their policy.
     * Null if the codec is no longer usable or no size fits.
     */
    fun refit(
        current: VideoConfig,
        sender: SenderCapabilities,
        receiver: ControlMessage.Hello,
        prefs: StreamPreferences = StreamPreferences(),
        encoderAccepts: EncoderPredicate = ACCEPT_ALL,
    ): VideoConfig? {
        val order = listOf(current.codec)
        val (enc, dec) =
            pickPair(sender, receiver, order, requireHardwareDecoder = true)
                ?: pickPair(sender, receiver, order, requireHardwareDecoder = false)
                ?: return null
        val (w, h) = fitResolution(sender, receiver, enc, dec, prefs.maxHeight, current.fps, encoderAccepts)
            ?: return null
        val bitrate = prefs.bitrateBps?.coerceIn(MIN_BITRATE_BPS, MAX_BITRATE_BPS)
            ?: autoBitrate(current.codec, w, h, current.fps)
        return current.copy(width = w, height = h, bitrateBps = bitrate)
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
     * Largest size with the source aspect ratio that fits every limit at [fps], each side a multiple
     * of 8 (keeps 1080 lines intact; hardware codecs pad internally), never upscaled (#34).
     *
     * The source keeps its real orientation and is fitted inside the receiver display as-is: the
     * receiver does not rotate the stream, so a portrait sender on a landscape TV becomes a
     * pillarboxed portrait stream rather than a full-height image the TV would have to downscale.
     * Codec limits are per axis, padded area and pixel rate, for encoder and decoder; [encoderAccepts]
     * is the local encoder's exact check. [prefMaxHeight] null = Auto (no cap).
     */
    internal fun fitResolution(
        sender: SenderCapabilities,
        receiver: ControlMessage.Hello,
        enc: VideoCodecCapability,
        dec: VideoCodecCapability,
        prefMaxHeight: Int?,
        fps: Int,
        encoderAccepts: EncoderPredicate = ACCEPT_ALL,
    ): Size? {
        val limits = VideoLimits.of(enc)
            .intersect(VideoLimits.of(dec))
            .intersect(VideoLimits.ofSize(receiver.display.width, receiver.display.height))
        return VideoFit.fitWithin(sender.source.width, sender.source.height, limits, fps, prefMaxHeight) { w, h, f ->
            encoderAccepts.accepts(enc.codec, w, h, f)
        }
    }

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
