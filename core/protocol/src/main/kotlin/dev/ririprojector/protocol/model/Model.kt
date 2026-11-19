package dev.ririprojector.protocol.model

/** Video codecs the app can negotiate; [mime] matches `MediaFormat.MIMETYPE_VIDEO_*`. */
enum class VideoCodec(val code: Int, val mime: String) {
    AVC(1, "video/avc"),
    HEVC(2, "video/hevc"),
    ;

    companion object {
        fun fromCode(code: Int): VideoCodec? = entries.firstOrNull { it.code == code }
    }
}

/** Audio formats on the wire (ADR-007). PCM is always playable through `AudioTrack`. */
enum class AudioCodec(val code: Int, val mime: String) {
    PCM_S16LE(1, "audio/raw"),
    OPUS(2, "audio/opus"),
    ;

    companion object {
        fun fromCode(code: Int): AudioCodec? = entries.firstOrNull { it.code == code }
    }
}

data class DisplayInfo(val width: Int, val height: Int, val refreshRateHz: Int) {
    init {
        require(width > 0 && height > 0) { "invalid display size ${width}x$height" }
        require(refreshRateHz > 0) { "invalid refresh rate $refreshRateHz" }
    }
}

/** What one side can encode (sender) or decode (receiver) for a video codec. */
data class VideoCodecCapability(
    val codec: VideoCodec,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFps: Int,
    val hardwareAccelerated: Boolean,
    val lowLatency: Boolean,
)

data class AudioCodecCapability(val codec: AudioCodec, val maxChannels: Int, val sampleRates: List<Int>)

data class VideoConfig(val codec: VideoCodec, val width: Int, val height: Int, val fps: Int, val bitrateBps: Int) {
    init {
        require(width in 16..8192 && height in 16..8192) { "invalid video size ${width}x$height" }
        require(fps in 1..240) { "invalid fps $fps" }
        require(bitrateBps > 0) { "invalid bitrate $bitrateBps" }
    }
}

data class AudioConfig(val codec: AudioCodec, val sampleRateHz: Int, val channels: Int, val frameDurationUs: Int) {
    init {
        require(sampleRateHz in 8_000..192_000) { "invalid sample rate $sampleRateHz" }
        require(channels in 1..8) { "invalid channel count $channels" }
        require(frameDurationUs in 2_500..120_000) { "invalid frame duration $frameDurationUs" }
    }

    /** Bytes in one PCM s16 packet; meaningless for compressed codecs. */
    val pcmBytesPerFrame: Int get() = (sampleRateHz.toLong() * frameDurationUs / 1_000_000L).toInt() * channels * 2

    companion object {
        val DEFAULT_PCM =
            AudioConfig(AudioCodec.PCM_S16LE, sampleRateHz = 48_000, channels = 2, frameDurationUs = 10_000)
    }
}

/** Local sender facts fed to the negotiator. */
data class SenderCapabilities(
    val source: DisplayInfo,
    val videoEncoders: List<VideoCodecCapability>,
    val audioEncoders: List<AudioCodecCapability>,
)

/** User-facing stream settings; nulls mean "automatic". */
data class StreamPreferences(
    val preferredVideoCodec: VideoCodec? = null,
    val maxHeight: Int? = 1080,
    val maxFps: Int? = 60,
    val bitrateBps: Int? = null,
    val preferredAudioCodec: AudioCodec = AudioCodec.PCM_S16LE,
)

/** Final parameters announced in START. */
data class StreamParams(val sessionId: Long, val video: VideoConfig, val audio: AudioConfig)
