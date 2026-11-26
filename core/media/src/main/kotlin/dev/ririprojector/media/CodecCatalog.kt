package dev.ririprojector.media

import dev.ririprojector.protocol.EncoderPredicate
import dev.ririprojector.protocol.model.VideoCodec
import dev.ririprojector.protocol.model.VideoCodecCapability

/** Platform-independent description of one codec, built from `MediaCodecList` on device. */
data class CodecDescriptor(
    val name: String,
    val mime: String,
    val isEncoder: Boolean,
    val hardware: Boolean,
    val lowLatency: Boolean,
    val secure: Boolean,
    val maxWidth: Int,
    val maxHeight: Int,
    val maxFps: Int,
    val supportsIntraRefresh: Boolean = false,
    val supportsCbr: Boolean = false,
    /** Largest padded frame area, measured by [CodecProbe] (#34). */
    val maxPixels: Int = (maxWidth.toLong() * maxHeight).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
    /** Largest `w × h × fps`, measured by [CodecProbe] (#34). */
    val maxPixelRate: Long = maxPixels.toLong() * maxFps,
)

/** Exact `areSizeAndRateSupported(w, h, fps)` of one codec on device. */
fun interface SizeRateCheck {
    fun supported(width: Int, height: Int, fps: Int): Boolean

    companion object {
        /** Rounds the size up to the codec's alignment before [raw], as its internal padding does. */
        fun aligned(widthAlignment: Int, heightAlignment: Int, raw: SizeRateCheck): SizeRateCheck {
            val wa = widthAlignment.coerceAtLeast(1)
            val ha = heightAlignment.coerceAtLeast(1)
            return SizeRateCheck { w, h, fps -> raw.supported((w + wa - 1) / wa * wa, (h + ha - 1) / ha * ha, fps) }
        }
    }
}

/**
 * Pure selection logic over the device's codecs (ADR-004, ADR-005). [checks]: per encoder name; an
 * encoder without one is trusted on its limits alone.
 */
class CodecCatalog(
    private val codecs: List<CodecDescriptor>,
    private val checks: Map<String, SizeRateCheck> = emptyMap(),
) {
    /** Best codec for [codec]: non-secure, hardware first, then low-latency, then largest measured area. */
    fun best(codec: VideoCodec, encoder: Boolean): CodecDescriptor? = codecs
        .filter { it.mime == codec.mime && it.isEncoder == encoder && !it.secure }
        .sortedWith(
            compareByDescending<CodecDescriptor> { it.hardware }
                .thenByDescending { it.lowLatency }
                .thenByDescending { it.maxPixels },
        ).firstOrNull()

    /** One capability per codec type, from the best implementation of each (for HELLO / negotiation). */
    fun capabilities(encoder: Boolean): List<VideoCodecCapability> = VideoCodec.entries.mapNotNull { codec ->
        best(codec, encoder)?.let {
            VideoCodecCapability(
                codec,
                it.maxWidth,
                it.maxHeight,
                it.maxFps,
                it.hardware,
                it.lowLatency,
                it.maxPixels,
                it.maxPixelRate,
            )
        }
    }

    /**
     * The exact check of the encoder [best] picks for each codec, for the negotiator (#34): sizes
     * it rejects are never announced in START. Codecs without a device check accept everything.
     */
    fun encoderPredicate(): EncoderPredicate = EncoderPredicate { codec, w, h, fps ->
        best(codec, encoder = true)?.let { checks[it.name] }?.supported(w, h, fps) ?: true
    }

    companion object {
        /** Name heuristics for API 28, where `isHardwareAccelerated` does not exist. */
        fun looksSoftware(name: String): Boolean {
            val n = name.lowercase()
            return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("omx.ffmpeg.") ||
                n.contains(".sw.") || n.endsWith(".sw")
        }

        fun looksLowLatency(name: String): Boolean = name.lowercase().contains("low_latency")
    }
}
