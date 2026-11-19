package dev.ririprojector.media

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
)

/** Pure selection logic over the device's codecs (ADR-004, ADR-005). */
class CodecCatalog(private val codecs: List<CodecDescriptor>) {
    /** Best codec for [codec]: non-secure, hardware first, then low-latency, then largest size. */
    fun best(codec: VideoCodec, encoder: Boolean): CodecDescriptor? = codecs
        .filter { it.mime == codec.mime && it.isEncoder == encoder && !it.secure }
        .sortedWith(
            compareByDescending<CodecDescriptor> { it.hardware }
                .thenByDescending { it.lowLatency }
                .thenByDescending { it.maxWidth.toLong() * it.maxHeight },
        ).firstOrNull()

    /** One capability per codec type, from the best implementation of each (for HELLO / negotiation). */
    fun capabilities(encoder: Boolean): List<VideoCodecCapability> = VideoCodec.entries.mapNotNull { codec ->
        best(codec, encoder)?.let {
            VideoCodecCapability(codec, it.maxWidth, it.maxHeight, it.maxFps, it.hardware, it.lowLatency)
        }
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
