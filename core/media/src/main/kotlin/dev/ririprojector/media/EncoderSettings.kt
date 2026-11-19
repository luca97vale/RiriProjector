package dev.ririprojector.media

import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaFormat
import dev.ririprojector.protocol.model.VideoConfig

/**
 * Encoder format keys for realtime screen mirroring (ADR-004), as a plain ordered map so the
 * policy is testable on the JVM. `MediaFormat.KEY_*` are compile-time constants.
 */
object EncoderSettings {
    const val I_FRAME_INTERVAL_S = 2
    const val REPEAT_FRAME_AFTER_US = 100_000L

    /** Vendor keys, ignored by encoders that do not know them. */
    const val QTI_LOW_LATENCY = "vendor.qti-ext-enc-low-latency.enable"

    fun keys(
        config: VideoConfig,
        codec: CodecDescriptor,
        sdkInt: Int,
        lowLatencyKeys: Boolean = true,
    ): Map<String, Any> {
        val keys = LinkedHashMap<String, Any>()
        keys[MediaFormat.KEY_COLOR_FORMAT] = CodecCapabilities.COLOR_FormatSurface
        keys[MediaFormat.KEY_BIT_RATE] = config.bitrateBps
        keys[MediaFormat.KEY_BITRATE_MODE] =
            if (codec.supportsCbr) EncoderCapabilities.BITRATE_MODE_CBR else EncoderCapabilities.BITRATE_MODE_VBR
        keys[MediaFormat.KEY_FRAME_RATE] = config.fps
        // Safety net only: lost frames are repaired by on-demand sync frames (REQUEST_KEYFRAME).
        keys[MediaFormat.KEY_I_FRAME_INTERVAL] = I_FRAME_INTERVAL_S
        // A static screen produces no new frames; repeat the last one so the receiver keeps flowing.
        keys[MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER] = REPEAT_FRAME_AFTER_US
        if (sdkInt >= 29) {
            keys[MediaFormat.KEY_MAX_B_FRAMES] = 0
            // A 120 Hz panel drives the VirtualDisplay at up to 120 fps; KEY_FRAME_RATE is not a cap.
            keys[MediaFormat.KEY_MAX_FPS_TO_ENCODER] = config.fps.toFloat()
        }
        if (!lowLatencyKeys) return keys
        keys[MediaFormat.KEY_PRIORITY] = 0 // realtime
        keys[MediaFormat.KEY_OPERATING_RATE] = config.fps
        if (sdkInt >= 30 && codec.lowLatency) keys[MediaFormat.KEY_LOW_LATENCY] = 1
        if (codec.name.lowercase().startsWith("c2.qti.") || codec.name.startsWith("OMX.qcom.")) {
            keys[QTI_LOW_LATENCY] = 1
        }
        return keys
    }

    fun apply(format: MediaFormat, keys: Map<String, Any>) {
        for ((k, v) in keys) {
            when (v) {
                is Int -> format.setInteger(k, v)
                is Long -> format.setLong(k, v)
                is Float -> format.setFloat(k, v)
                is String -> format.setString(k, v)
                else -> error("unsupported value for $k: $v")
            }
        }
    }
}
