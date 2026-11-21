package dev.ririprojector.protocol

import com.google.common.truth.Truth.assertThat
import dev.ririprojector.protocol.model.AudioCodec
import dev.ririprojector.protocol.model.AudioCodecCapability
import dev.ririprojector.protocol.model.AudioConfig
import dev.ririprojector.protocol.model.DisplayInfo
import dev.ririprojector.protocol.model.SenderCapabilities
import dev.ririprojector.protocol.model.StreamParams
import dev.ririprojector.protocol.model.StreamPreferences
import dev.ririprojector.protocol.model.VideoCodec
import dev.ririprojector.protocol.model.VideoCodecCapability
import org.junit.Test

class CodecNegotiatorTest {
    private fun cap(codec: VideoCodec, hw: Boolean = true, w: Int = 3840, h: Int = 2160, fps: Int = 120) =
        VideoCodecCapability(codec, w, h, fps, hw, lowLatency = true)

    private val pcm = AudioCodecCapability(AudioCodec.PCM_S16LE, 2, listOf(48000))
    private val opus = AudioCodecCapability(AudioCodec.OPUS, 2, listOf(48000))

    private fun sender(
        source: DisplayInfo = DisplayInfo(2560, 1600, 120),
        enc: List<VideoCodecCapability> = listOf(cap(VideoCodec.HEVC), cap(VideoCodec.AVC)),
        audio: List<AudioCodecCapability> = listOf(pcm, opus),
    ) = SenderCapabilities(source, enc, audio)

    private fun hello(
        dec: List<VideoCodecCapability> = listOf(cap(VideoCodec.HEVC), cap(VideoCodec.AVC)),
        display: DisplayInfo = DisplayInfo(1920, 1080, 60),
        audio: List<AudioCodecCapability> = listOf(pcm, opus),
        version: Int = ProtocolInfo.PROTOCOL_VERSION,
    ) = ControlMessage.Hello(version, "tv", display, dec, audio)

    private fun ok(n: Negotiation): StreamParams {
        assertThat(n).isInstanceOf(Negotiation.Success::class.java)
        return (n as Negotiation.Success).params
    }

    @Test
    fun `HEVC on both sides is chosen`() {
        val p = ok(CodecNegotiator.negotiate(sender(), hello()))

        assertThat(p.video.codec).isEqualTo(VideoCodec.HEVC)
    }

    @Test
    fun `HEVC only on the sender side falls back to AVC`() {
        val p = ok(CodecNegotiator.negotiate(sender(), hello(dec = listOf(cap(VideoCodec.AVC)))))

        assertThat(p.video.codec).isEqualTo(VideoCodec.AVC)
    }

    @Test
    fun `HEVC only on the receiver side falls back to AVC`() {
        val p = ok(CodecNegotiator.negotiate(sender(enc = listOf(cap(VideoCodec.AVC))), hello()))

        assertThat(p.video.codec).isEqualTo(VideoCodec.AVC)
    }

    @Test
    fun `preferred AVC is honoured when HEVC is also available`() {
        val prefs = StreamPreferences(preferredVideoCodec = VideoCodec.AVC)

        val p = ok(CodecNegotiator.negotiate(sender(), hello(), prefs))

        assertThat(p.video.codec).isEqualTo(VideoCodec.AVC)
    }

    @Test
    fun `preferred codec unavailable falls back to the other one`() {
        val prefs = StreamPreferences(preferredVideoCodec = VideoCodec.HEVC)

        val p = ok(CodecNegotiator.negotiate(sender(), hello(dec = listOf(cap(VideoCodec.AVC))), prefs))

        assertThat(p.video.codec).isEqualTo(VideoCodec.AVC)
    }

    @Test
    fun `software only encoder is ignored`() {
        val s = sender(enc = listOf(cap(VideoCodec.HEVC, hw = false), cap(VideoCodec.AVC)))

        val p = ok(CodecNegotiator.negotiate(s, hello()))

        assertThat(p.video.codec).isEqualTo(VideoCodec.AVC)
    }

    @Test
    fun `only software encoders yields Failure`() {
        val s = sender(enc = listOf(cap(VideoCodec.HEVC, hw = false), cap(VideoCodec.AVC, hw = false)))

        assertThat(CodecNegotiator.negotiate(s, hello())).isInstanceOf(Negotiation.Failure::class.java)
    }

    @Test
    fun `no common codec yields Failure`() {
        val s = sender(enc = listOf(cap(VideoCodec.HEVC)))

        val n = CodecNegotiator.negotiate(s, hello(dec = listOf(cap(VideoCodec.AVC))))

        assertThat(n).isInstanceOf(Negotiation.Failure::class.java)
    }

    @Test
    fun `protocol version mismatch yields Failure`() {
        val n = CodecNegotiator.negotiate(sender(), hello(version = ProtocolInfo.PROTOCOL_VERSION + 1))

        assertThat(n).isInstanceOf(Negotiation.Failure::class.java)
    }

    @Test
    fun `session id is propagated`() {
        val p = ok(CodecNegotiator.negotiate(sender(), hello(), sessionId = 777))

        assertThat(p.sessionId).isEqualTo(777L)
    }

    @Test
    fun `portrait sender on a landscape receiver is fitted as-is inside its display`() {
        val s = sender(source = DisplayInfo(1600, 2560, 60))

        val v = ok(CodecNegotiator.negotiate(s, hello())).video

        // The TV does not rotate: full TV height (1080), pillarboxed width 675 aligned down to 672.
        assertThat(v.width).isEqualTo(672)
        assertThat(v.height).isEqualTo(1080)
        assertThat(v.width.toDouble() / v.height).isWithin(0.01).of(1600.0 / 2560.0)
    }

    @Test
    fun `landscape 2560x1600 fits 1080 height`() {
        val v = ok(CodecNegotiator.negotiate(sender(), hello())).video

        // Aligned to 8 so 1080 lines stay intact.
        assertThat(v.height).isEqualTo(1080)
        assertThat(v.width).isEqualTo(1728)
        assertThat(v.width % 8).isEqualTo(0)
    }

    @Test
    fun `software HEVC decoder loses to hardware AVC decoder`() {
        val rx =
            hello().copy(
                videoDecoders =
                    listOf(
                        VideoCodecCapability(
                            VideoCodec.HEVC,
                            1920,
                            1080,
                            60,
                            hardwareAccelerated = false,
                            lowLatency = false,
                        ),
                        VideoCodecCapability(
                            VideoCodec.AVC,
                            1920,
                            1080,
                            60,
                            hardwareAccelerated = true,
                            lowLatency = true,
                        ),
                    ),
            )

        val v = ok(CodecNegotiator.negotiate(sender(), rx)).video

        assertThat(v.codec).isEqualTo(VideoCodec.AVC)
    }

    @Test
    fun `software decoder is accepted when it is the only option`() {
        val rx =
            hello().copy(
                videoDecoders = listOf(
                    VideoCodecCapability(
                        VideoCodec.AVC,
                        1920,
                        1080,
                        60,
                        hardwareAccelerated = false,
                        lowLatency = false,
                    ),
                ),
            )

        val v = ok(CodecNegotiator.negotiate(sender(), rx)).video

        assertThat(v.codec).isEqualTo(VideoCodec.AVC)
    }

    @Test
    fun `source smaller than every limit is not upscaled`() {
        val s = sender(source = DisplayInfo(1280, 720, 60))

        val v = ok(CodecNegotiator.negotiate(s, hello())).video

        assertThat(v.width).isEqualTo(1280)
        assertThat(v.height).isEqualTo(720)
    }

    @Test
    fun `prefs maxHeight 720 is respected`() {
        val prefs = StreamPreferences(maxHeight = 720)

        val v = ok(CodecNegotiator.negotiate(sender(), hello(), prefs)).video

        assertThat(v.height).isEqualTo(720)
        assertThat(v.width % 16).isEqualTo(0)
    }

    @Test
    fun `fps is the minimum of all limits`() {
        // source 120, encoder 90, decoder 75; display and prefs vary
        val s = sender(source = DisplayInfo(1920, 1080, 120), enc = listOf(cap(VideoCodec.HEVC, fps = 90)))
        val dec = listOf(cap(VideoCodec.HEVC, fps = 75))

        fun fps(prefMax: Int?, display: Int) = ok(
            CodecNegotiator.negotiate(
                s,
                hello(dec = dec, display = DisplayInfo(1920, 1080, display)),
                StreamPreferences(maxFps = prefMax),
            ),
        ).video.fps

        assertThat(fps(48, 60)).isEqualTo(48)
        assertThat(fps(null, 60)).isEqualTo(60)
        assertThat(fps(null, 200)).isEqualTo(75)
        assertThat(fps(100, 200)).isEqualTo(75)
    }

    @Test
    fun `fps limited by encoder when it is the smallest`() {
        val s = sender(enc = listOf(cap(VideoCodec.HEVC, fps = 24)))

        assertThat(ok(CodecNegotiator.negotiate(s, hello())).video.fps).isEqualTo(24)
    }

    @Test
    fun `auto bitrate is clamped to 2 Mbps minimum`() {
        assertThat(CodecNegotiator.autoBitrate(VideoCodec.HEVC, 320, 240, 15)).isEqualTo(2_000_000)
    }

    @Test
    fun `auto bitrate is clamped to 40 Mbps maximum`() {
        assertThat(CodecNegotiator.autoBitrate(VideoCodec.AVC, 3840, 2160, 120)).isEqualTo(40_000_000)
    }

    @Test
    fun `auto bitrate in the middle is within bounds and lower for HEVC`() {
        val avc = CodecNegotiator.autoBitrate(VideoCodec.AVC, 1920, 1080, 60)
        val hevc = CodecNegotiator.autoBitrate(VideoCodec.HEVC, 1920, 1080, 60)

        assertThat(avc).isIn(2_000_000..40_000_000)
        assertThat(hevc).isLessThan(avc)
    }

    @Test
    fun `negotiate uses auto bitrate when none is set`() {
        val v = ok(CodecNegotiator.negotiate(sender(), hello())).video

        assertThat(v.bitrateBps).isEqualTo(CodecNegotiator.autoBitrate(v.codec, v.width, v.height, v.fps))
        assertThat(v.bitrateBps).isIn(CodecNegotiator.MIN_BITRATE_BPS..CodecNegotiator.MAX_BITRATE_BPS)
    }

    @Test
    fun `explicit bitrate is used and clamped`() {
        fun br(v: Int) =
            ok(CodecNegotiator.negotiate(sender(), hello(), StreamPreferences(bitrateBps = v))).video.bitrateBps

        assertThat(br(10_000_000)).isEqualTo(10_000_000)
        assertThat(br(100)).isEqualTo(2_000_000)
        assertThat(br(500_000_000)).isEqualTo(40_000_000)
    }

    @Test
    fun `audio defaults to PCM 48k stereo 10 ms`() {
        val a = ok(CodecNegotiator.negotiate(sender(), hello())).audio

        assertThat(a).isEqualTo(AudioConfig(AudioCodec.PCM_S16LE, 48_000, 2, 10_000))
    }

    @Test
    fun `Opus is used only when preferred and supported on both sides`() {
        val prefs = StreamPreferences(preferredAudioCodec = AudioCodec.OPUS)

        val both = ok(CodecNegotiator.negotiate(sender(), hello(), prefs)).audio
        val senderLacks = ok(CodecNegotiator.negotiate(sender(audio = listOf(pcm)), hello(), prefs)).audio
        val receiverLacks = ok(CodecNegotiator.negotiate(sender(), hello(audio = listOf(pcm)), prefs)).audio

        assertThat(both.codec).isEqualTo(AudioCodec.OPUS)
        assertThat(both.sampleRateHz).isEqualTo(48_000)
        assertThat(both.channels).isEqualTo(2)
        assertThat(senderLacks).isEqualTo(AudioConfig.DEFAULT_PCM)
        assertThat(receiverLacks).isEqualTo(AudioConfig.DEFAULT_PCM)
    }

    @Test
    fun `Opus supported on both sides but not preferred stays PCM`() {
        val a = ok(CodecNegotiator.negotiate(sender(), hello())).audio

        assertThat(a.codec).isEqualTo(AudioCodec.PCM_S16LE)
    }

    @Test
    fun `PCM audio is negotiated even when no audio decoders are advertised`() {
        val a = ok(CodecNegotiator.negotiate(sender(audio = emptyList()), hello(audio = emptyList()))).audio

        assertThat(a).isEqualTo(AudioConfig.DEFAULT_PCM)
    }

    @Test
    fun `refit after rotation keeps codec and fps and swaps orientation`() {
        val start = ok(CodecNegotiator.negotiate(sender(), hello())).video

        val v = CodecNegotiator.refit(start, sender(source = DisplayInfo(1600, 2560, 120)), hello())!!

        assertThat(v.codec).isEqualTo(start.codec)
        assertThat(v.fps).isEqualTo(start.fps)
        assertThat(v.width).isEqualTo(672)
        assertThat(v.height).isEqualTo(1080)
        assertThat(v.bitrateBps).isLessThan(start.bitrateBps)
    }

    @Test
    fun `refit with an odd one-app size stays aligned and keeps the aspect`() {
        val start = ok(CodecNegotiator.negotiate(sender(), hello())).video

        val v = CodecNegotiator.refit(start, sender(source = DisplayInfo(1234, 987, 120)), hello())!!

        assertThat(v.width % 8).isEqualTo(0)
        assertThat(v.height % 8).isEqualTo(0)
        assertThat(v.width.toDouble() / v.height).isWithin(0.02).of(1234.0 / 987.0)
        assertThat(v.width).isAtMost(1234)
    }

    @Test
    fun `refit keeps a fixed user bitrate`() {
        val prefs = StreamPreferences(bitrateBps = 12_000_000)
        val start = ok(CodecNegotiator.negotiate(sender(), hello(), prefs)).video

        val v = CodecNegotiator.refit(start, sender(source = DisplayInfo(1600, 2560, 120)), hello(), prefs)!!

        assertThat(v.bitrateBps).isEqualTo(12_000_000)
    }

    @Test
    fun `refit is null when the source becomes too small`() {
        val start = ok(CodecNegotiator.negotiate(sender(), hello())).video

        assertThat(CodecNegotiator.refit(start, sender(source = DisplayInfo(100, 100, 60)), hello())).isNull()
    }
}
