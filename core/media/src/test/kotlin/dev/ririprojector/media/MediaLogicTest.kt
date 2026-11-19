package dev.ririprojector.media

import com.google.common.truth.Truth.assertThat
import dev.ririprojector.media.DecoderBacklog.Decision.DECODE
import dev.ririprojector.media.DecoderBacklog.Decision.DROP
import dev.ririprojector.media.DecoderBacklog.Decision.DROP_AND_REQUEST_KEYFRAME
import dev.ririprojector.protocol.model.VideoCodec
import org.junit.Test

class CodecCatalogTest {
    private fun dec(
        name: String,
        mime: String = VideoCodec.HEVC.mime,
        hw: Boolean = true,
        ll: Boolean = false,
        secure: Boolean = false,
        w: Int = 3840,
        h: Int = 2160,
    ) = CodecDescriptor(name, mime, isEncoder = false, hardware = hw, lowLatency = ll, secure = secure, w, h, 60)

    @Test
    fun `hardware beats software and low-latency beats plain`() {
        val catalog =
            CodecCatalog(
                listOf(
                    dec("c2.android.hevc.decoder", hw = false, ll = true),
                    dec("c2.qti.hevc.decoder"),
                    dec("c2.qti.hevc.decoder.low_latency", ll = true),
                ),
            )
        assertThat(catalog.best(VideoCodec.HEVC, encoder = false)?.name).isEqualTo("c2.qti.hevc.decoder.low_latency")
    }

    @Test
    fun `secure decoders and encoders are never picked as decoders`() {
        val catalog =
            CodecCatalog(
                listOf(
                    dec("c2.qti.hevc.decoder.secure", secure = true, ll = true),
                    dec("c2.qti.hevc.encoder").copy(isEncoder = true),
                    dec("c2.qti.hevc.decoder"),
                ),
            )
        assertThat(catalog.best(VideoCodec.HEVC, encoder = false)?.name).isEqualTo("c2.qti.hevc.decoder")
        assertThat(catalog.best(VideoCodec.HEVC, encoder = true)?.name).isEqualTo("c2.qti.hevc.encoder")
    }

    @Test
    fun `larger max size breaks ties`() {
        val catalog = CodecCatalog(listOf(dec("small", w = 1920, h = 1080), dec("big")))
        assertThat(catalog.best(VideoCodec.HEVC, false)?.name).isEqualTo("big")
    }

    @Test
    fun `capabilities list one entry per supported codec`() {
        val catalog =
            CodecCatalog(
                listOf(
                    dec("omx.mtk.avc", mime = VideoCodec.AVC.mime, w = 1920, h = 1080),
                    dec("omx.mtk.hevc", ll = true),
                ),
            )
        val caps = catalog.capabilities(encoder = false)
        assertThat(caps.map { it.codec }).containsExactly(VideoCodec.AVC, VideoCodec.HEVC)
        val hevc = caps.single { it.codec == VideoCodec.HEVC }
        assertThat(hevc.lowLatency).isTrue()
        assertThat(hevc.maxWidth).isEqualTo(3840)
        assertThat(catalog.capabilities(encoder = true)).isEmpty()
    }

    @Test
    fun `name heuristics`() {
        assertThat(CodecCatalog.looksSoftware("OMX.google.h264.decoder")).isTrue()
        assertThat(CodecCatalog.looksSoftware("c2.android.hevc.decoder")).isTrue()
        assertThat(CodecCatalog.looksSoftware("OMX.MTK.VIDEO.DECODER.HEVC")).isFalse()
        assertThat(CodecCatalog.looksLowLatency("c2.qti.avc.decoder.low_latency")).isTrue()
        assertThat(CodecCatalog.looksLowLatency("c2.qti.avc.decoder")).isFalse()
    }
}

class DecoderBacklogTest {
    @Test
    fun `nothing decodes before config and a keyframe`() {
        val b = DecoderBacklog()
        assertThat(b.onAccessUnit(isConfig = false, isKeyframe = false)).isEqualTo(DROP_AND_REQUEST_KEYFRAME)
        assertThat(b.onAccessUnit(isConfig = false, isKeyframe = true)).isEqualTo(DROP) // keyframe without config
        assertThat(b.onAccessUnit(isConfig = true, isKeyframe = false)).isEqualTo(DECODE)
        assertThat(b.onAccessUnit(isConfig = false, isKeyframe = false)).isEqualTo(DROP)
        assertThat(b.onAccessUnit(isConfig = false, isKeyframe = true)).isEqualTo(DECODE)
        assertThat(b.onAccessUnit(isConfig = false, isKeyframe = false)).isEqualTo(DECODE)
        assertThat(b.dropped).isEqualTo(3)
    }

    @Test
    fun `backlog over the cap drops until the next keyframe and requests one once`() {
        val b = started(maxInFlight = 2) // keyframe in flight
        assertThat(b.onAccessUnit(false, false)).isEqualTo(DECODE) // 2 in flight
        assertThat(b.onAccessUnit(false, false)).isEqualTo(DROP_AND_REQUEST_KEYFRAME)
        b.onFrameOutput()
        b.onFrameOutput()
        // Room again, but P-frames still reference the dropped one.
        assertThat(b.onAccessUnit(false, false)).isEqualTo(DROP)
        assertThat(b.onAccessUnit(false, true)).isEqualTo(DECODE)
        assertThat(b.onAccessUnit(false, false)).isEqualTo(DECODE)
    }

    @Test
    fun `a new episode requests a keyframe again`() {
        val b = started(maxInFlight = 1)
        assertThat(b.onAccessUnit(false, false)).isEqualTo(DROP_AND_REQUEST_KEYFRAME)
        b.onFrameOutput()
        assertThat(b.onAccessUnit(false, true)).isEqualTo(DECODE)
        assertThat(b.onAccessUnit(false, false)).isEqualTo(DROP_AND_REQUEST_KEYFRAME)
    }

    @Test
    fun `failed submit waits for a keyframe without a second request`() {
        val b = started()
        assertThat(b.onAccessUnit(false, false)).isEqualTo(DECODE)
        b.onSubmitFailed(isConfig = false)
        assertThat(b.inFlight).isEqualTo(1)
        assertThat(b.onAccessUnit(false, false)).isEqualTo(DROP)
        assertThat(b.onAccessUnit(false, true)).isEqualTo(DECODE)
    }

    @Test
    fun `reset requires config again`() {
        val b = started()
        b.reset()
        assertThat(b.inFlight).isEqualTo(0)
        assertThat(b.onAccessUnit(false, true)).isEqualTo(DROP_AND_REQUEST_KEYFRAME)
    }

    @Test
    fun `output never makes in-flight negative`() {
        val b = DecoderBacklog()
        b.onFrameOutput()
        assertThat(b.inFlight).isEqualTo(0)
    }

    private fun started(maxInFlight: Int = 3) = DecoderBacklog(maxInFlight).apply {
        onAccessUnit(isConfig = true, isKeyframe = false)
        onAccessUnit(isConfig = false, isKeyframe = true)
    }
}

class LatencyStatsTest {
    @Test
    fun `empty window reports zero`() {
        val s = LatencyStats()
        assertThat(s.meanUs()).isEqualTo(0)
        assertThat(s.p95Us()).isEqualTo(0)
    }

    @Test
    fun `mean and p95 over 1 to 100`() {
        val s = LatencyStats(capacity = 100)
        (1..100).shuffled().forEach { s.add(it.toLong()) }
        assertThat(s.meanUs()).isEqualTo(50) // 50.5 truncated
        assertThat(s.p95Us()).isEqualTo(95)
    }

    @Test
    fun `window keeps only the latest samples`() {
        val s = LatencyStats(capacity = 4)
        listOf(1000L, 1000, 1000, 1000, 1, 2, 3, 4).forEach(s::add)
        assertThat(s.size).isEqualTo(4)
        assertThat(s.meanUs()).isEqualTo(2)
        assertThat(s.p95Us()).isEqualTo(4)
    }

    @Test
    fun `clear empties the window`() {
        val s = LatencyStats()
        s.add(10)
        s.clear()
        assertThat(s.size).isEqualTo(0)
    }
}
