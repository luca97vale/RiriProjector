package dev.ririprojector.media

import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaFormat
import com.google.common.truth.Truth.assertThat
import dev.ririprojector.protocol.model.VideoCodec
import dev.ririprojector.protocol.model.VideoConfig
import org.junit.Test

class EncoderSettingsTest {
    private val config = VideoConfig(VideoCodec.HEVC, 1728, 1080, 60, 12_000_000)
    private val qti =
        CodecDescriptor(
            name = "c2.qti.hevc.encoder",
            mime = VideoCodec.HEVC.mime,
            isEncoder = true,
            hardware = true,
            lowLatency = true,
            secure = false,
            maxWidth = 8192,
            maxHeight = 8192,
            maxFps = 240,
            supportsIntraRefresh = true,
            supportsCbr = true,
        )

    @Test
    fun `realtime keys on a modern qualcomm encoder`() {
        val keys = EncoderSettings.keys(config, qti, sdkInt = 36)
        assertThat(keys[MediaFormat.KEY_COLOR_FORMAT]).isEqualTo(CodecCapabilities.COLOR_FormatSurface)
        assertThat(keys[MediaFormat.KEY_BIT_RATE]).isEqualTo(12_000_000)
        assertThat(keys[MediaFormat.KEY_BITRATE_MODE]).isEqualTo(EncoderCapabilities.BITRATE_MODE_CBR)
        assertThat(keys[MediaFormat.KEY_FRAME_RATE]).isEqualTo(60)
        assertThat(keys[MediaFormat.KEY_MAX_B_FRAMES]).isEqualTo(0)
        assertThat(keys[MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER]).isEqualTo(100_000L)
        assertThat(keys[MediaFormat.KEY_PRIORITY]).isEqualTo(0)
        assertThat(keys[MediaFormat.KEY_LOW_LATENCY]).isEqualTo(1)
        assertThat(keys[EncoderSettings.QTI_LOW_LATENCY]).isEqualTo(1)
    }

    @Test
    fun `VBR when CBR is unsupported, no low-latency key on old APIs`() {
        val keys = EncoderSettings.keys(config, qti.copy(name = "OMX.MTK.VIDEO.ENCODER.HEVC", supportsCbr = false), 28)
        assertThat(keys[MediaFormat.KEY_BITRATE_MODE]).isEqualTo(EncoderCapabilities.BITRATE_MODE_VBR)
        assertThat(keys).doesNotContainKey(MediaFormat.KEY_LOW_LATENCY)
        assertThat(keys).doesNotContainKey(MediaFormat.KEY_MAX_B_FRAMES)
        assertThat(keys).doesNotContainKey(EncoderSettings.QTI_LOW_LATENCY)
    }

    @Test
    fun `plain fallback drops every optional realtime key`() {
        val keys = EncoderSettings.keys(config, qti, 36, lowLatencyKeys = false)
        assertThat(keys).doesNotContainKey(MediaFormat.KEY_PRIORITY)
        assertThat(keys).doesNotContainKey(MediaFormat.KEY_LOW_LATENCY)
        assertThat(keys).doesNotContainKey(MediaFormat.KEY_OPERATING_RATE)
        assertThat(keys).containsKey(MediaFormat.KEY_BIT_RATE)
    }
}
