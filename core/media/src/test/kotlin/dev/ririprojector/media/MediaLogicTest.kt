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
    private var now = 0L

    /** Advances the fake clock by [ms] and offers one unit. */
    private fun DecoderBacklog.au(isConfig: Boolean = false, key: Boolean = false, ms: Long = 16) =
        onAccessUnit(isConfig, key, (now + ms * 1000).also { now = it })

    private fun started(maxLagUs: Long = 60_000, hardCap: Int = 8) = DecoderBacklog(maxLagUs, hardCap).apply {
        au(isConfig = true)
        au(key = true)
    }

    @Test
    fun `nothing decodes before config and a keyframe`() {
        val b = DecoderBacklog()
        assertThat(b.au()).isEqualTo(DROP_AND_REQUEST_KEYFRAME)
        assertThat(b.au(key = true)).isEqualTo(DROP) // keyframe without config
        assertThat(b.au(isConfig = true)).isEqualTo(DECODE)
        assertThat(b.au()).isEqualTo(DROP)
        assertThat(b.au(key = true)).isEqualTo(DECODE)
        assertThat(b.au()).isEqualTo(DECODE)
        assertThat(b.dropped).isEqualTo(3)
    }

    @Test
    fun `a burst is fine while the decoder keeps up`() {
        val b = started()
        repeat(5) { assertThat(b.au(ms = 0)).isEqualTo(DECODE) } // 6 in flight, none old
        repeat(6) { b.onFrameOutput() }
        assertThat(b.au()).isEqualTo(DECODE)
    }

    @Test
    fun `decoder lagging beyond the limit drops until a keyframe and requests one once`() {
        val b = started(maxLagUs = 60_000)
        assertThat(b.au(ms = 50)).isEqualTo(DECODE) // oldest waited 50 ms
        assertThat(b.au(ms = 20)).isEqualTo(DROP_AND_REQUEST_KEYFRAME) // 70 ms > 60 ms
        assertThat(b.au()).isEqualTo(DROP)
        b.onFrameOutput()
        b.onFrameOutput()
        assertThat(b.au()).isEqualTo(DROP) // P-frames still reference the dropped one
        assertThat(b.au(key = true)).isEqualTo(DECODE)
        assertThat(b.au()).isEqualTo(DECODE)
    }

    @Test
    fun `hard cap applies even when frames are fresh`() {
        val b = started(hardCap = 3)
        assertThat(b.au(ms = 0)).isEqualTo(DECODE)
        assertThat(b.au(ms = 0)).isEqualTo(DECODE)
        assertThat(b.au(ms = 0)).isEqualTo(DROP_AND_REQUEST_KEYFRAME)
    }

    @Test
    fun `a leaked in-flight unit costs one episode, not an IDR per keyframe`() {
        val b = started()
        // The decoder swallows the keyframe without output: it stays "in flight" forever.
        assertThat(b.au(ms = 100)).isEqualTo(DROP_AND_REQUEST_KEYFRAME)
        assertThat(b.au(key = true)).isEqualTo(DECODE) // resyncs the window
        b.onFrameOutput()
        repeat(20) {
            assertThat(b.au()).isEqualTo(DECODE)
            b.onFrameOutput()
        }
    }

    @Test
    fun `failed submit waits for a keyframe without a second request`() {
        val b = started()
        assertThat(b.au()).isEqualTo(DECODE)
        b.onSubmitFailed(isConfig = false)
        assertThat(b.inFlight).isEqualTo(1)
        assertThat(b.au()).isEqualTo(DROP)
        assertThat(b.au(key = true)).isEqualTo(DECODE)
    }

    @Test
    fun `reset requires config again`() {
        val b = started()
        b.reset()
        assertThat(b.inFlight).isEqualTo(0)
        assertThat(b.haveConfig).isFalse()
        assertThat(b.au(key = true)).isEqualTo(DROP_AND_REQUEST_KEYFRAME)
        assertThat(b.au(isConfig = true)).isEqualTo(DECODE) // replayed CSD
        assertThat(b.au(key = true)).isEqualTo(DECODE)
    }

    @Test
    fun `output never makes in-flight negative`() {
        val b = DecoderBacklog()
        b.onFrameOutput()
        assertThat(b.inFlight).isEqualTo(0)
    }
}

class KeyframeRequestLimiterTest {
    @Test
    fun `at most one request per interval`() {
        val l = KeyframeRequestLimiter(minIntervalMs = 300)
        assertThat(l.tryAcquire(1_000)).isTrue()
        assertThat(l.tryAcquire(1_100)).isFalse()
        assertThat(l.tryAcquire(1_299)).isFalse()
        assertThat(l.tryAcquire(1_300)).isTrue()
    }

    @Test
    fun `first request is always allowed`() {
        assertThat(KeyframeRequestLimiter().tryAcquire(0)).isTrue()
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
