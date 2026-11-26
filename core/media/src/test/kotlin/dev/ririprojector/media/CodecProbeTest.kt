package dev.ririprojector.media

import com.google.common.truth.Truth.assertThat
import dev.ririprojector.protocol.model.VideoCodec
import org.junit.Test

class CodecProbeTest {
    private val area1080 = 1920 * 1088

    @Test
    fun `unlimited codec reports the largest axis-bounded area`() {
        val l = CodecProbe.limits(3840, 2160, 60, { _, _ -> true }, { _, _ -> 60.0 })

        // 16:9 at 2160 lines is 3840x2160, the whole box.
        assertThat(l.maxPixels).isEqualTo(3840 * 2160)
        assertThat(l.maxPixelRate).isEqualTo(3840L * 2160 * 60)
    }

    @Test
    fun `area-limited codec reports its block area, not its axes`() {
        // Pixel 10 HEVC encoder: 3840x2176 axes, ~1920x1088 of area.
        val l = CodecProbe.limits(3840, 2176, 60, { w, h -> w * h <= area1080 }, { _, _ -> 60.0 })

        assertThat(l.maxPixels).isAtMost(area1080)
        assertThat(l.maxPixels).isAtLeast(1920 * 1072)
    }

    @Test
    fun `rate comes from the frame rate at the largest size`() {
        val l = CodecProbe.limits(3840, 2160, 120, { _, _ -> true }, { w, h -> if (w * h > area1080) 30.0 else 120.0 })

        assertThat(l.maxPixelRate).isEqualTo(3840L * 2160 * 30)
    }

    @Test
    fun `unknown frame rate falls back to the given fps`() {
        val l = CodecProbe.limits(1920, 1080, 50, { _, _ -> true }, { _, _ -> null })

        assertThat(l.maxPixelRate).isEqualTo(l.maxPixels.toLong() * 50)
    }

    @Test
    fun `codec that rejects every probe falls back to the per-axis area`() {
        val l = CodecProbe.limits(1920, 1080, 30, { _, _ -> false }, { _, _ -> 30.0 })

        assertThat(l).isEqualTo(CodecProbe.Limits(1920 * 1080, 1920L * 1080 * 30))
    }

    @Test
    fun `portrait-only axes are searched in portrait`() {
        val l = CodecProbe.limits(1088, 2432, 60, { w, h -> w <= 1088 && h <= 2432 }, { _, _ -> 60.0 })

        // 9:20 portrait fills it best: 1088 wide.
        assertThat(l.maxPixels).isGreaterThan(1088 * 1920)
    }

    @Test
    fun `catalog ranks encoders by measured area and announces it`() {
        fun enc(name: String, pixels: Int) = CodecDescriptor(
            name, VideoCodec.HEVC.mime, isEncoder = true, hardware = true, lowLatency = false, secure = false,
            maxWidth = 3840, maxHeight = 2176, maxFps = 60, maxPixels = pixels,
        )
        val catalog = CodecCatalog(listOf(enc("big.axes", area1080), enc("real.4k", 3840 * 2160)))

        assertThat(catalog.best(VideoCodec.HEVC, encoder = true)?.name).isEqualTo("real.4k")
        assertThat(catalog.capabilities(encoder = true).single().maxPixels).isEqualTo(3840 * 2160)
    }

    @Test
    fun `encoder predicate uses the device check of the best encoder`() {
        val d = CodecDescriptor(
            "hw", VideoCodec.AVC.mime, isEncoder = true, hardware = true, lowLatency = false, secure = false,
            maxWidth = 1920, maxHeight = 1080, maxFps = 60,
        )
        val p = CodecCatalog(listOf(d), mapOf("hw" to SizeRateCheck { w, _, _ -> w <= 1280 })).encoderPredicate()

        assertThat(p.accepts(VideoCodec.AVC, 1280, 720, 60)).isTrue()
        assertThat(p.accepts(VideoCodec.AVC, 1920, 1080, 60)).isFalse()
        assertThat(p.accepts(VideoCodec.HEVC, 1920, 1080, 60)).isTrue()
    }

    @Test
    fun `aligned check rounds up to the codec alignment before asking`() {
        val strict16 = SizeRateCheck { w, h, _ -> w % 16 == 0 && h % 16 == 0 }
        val check = SizeRateCheck.aligned(16, 16, strict16)

        assertThat(strict16.supported(1728, 1080, 60)).isFalse()
        assertThat(check.supported(1728, 1080, 60)).isTrue()
    }

    @Test
    fun `binary search survives a lower size bound`() {
        val l = CodecProbe.limits(1920, 1088, 60, { w, h ->
            w >= 128 && h >= 128 && w * h <= area1080
        }, { _, _ -> 60.0 })

        assertThat(l.maxPixels).isAtLeast(1920 * 1072)
    }
}
