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
    val maxPixels: Int = maxWidth * maxHeight,
    /** Largest `w × h × fps`, measured by [CodecProbe] (#34). */
    val maxPixelRate: Long = maxPixels.toLong() * maxFps,
    /** Exact `areSizeAndRateSupported(w, h, fps)` on device; null = trust the limits above. */
    val sizeAndRateSupported: ((width: Int, height: Int, fps: Int) -> Boolean)? = null,
)

/** Pure selection logic over the device's codecs (ADR-004, ADR-005). */
class CodecCatalog(private val codecs: List<CodecDescriptor>) {
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
        best(codec, encoder = true)?.sizeAndRateSupported?.invoke(w, h, fps) ?: true
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
