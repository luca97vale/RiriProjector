package dev.ririprojector.media

import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import dev.ririprojector.protocol.model.VideoCodec

/** Reads the device's video codecs from `MediaCodecList` into a [CodecCatalog]. */
object AndroidCodecs {
    private const val TAG = "Riri/Codecs"

    fun catalog(): CodecCatalog {
        val descriptors = mutableListOf<CodecDescriptor>()
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            for (codec in VideoCodec.entries) {
                if (info.supportedTypes.none { it.equals(codec.mime, ignoreCase = true) }) continue
                runCatching { describe(info, codec.mime) }
                    .onSuccess { descriptors += it }
                    .onFailure { Log.w(TAG, "skipping ${info.name}: ${it.message}") }
            }
        }
        return CodecCatalog(descriptors)
    }

    private fun describe(info: MediaCodecInfo, mime: String): CodecDescriptor {
        val caps = info.getCapabilitiesForType(mime)
        val video = caps.videoCapabilities ?: error("no video capabilities for $mime")
        val maxW = video.supportedWidths.upper
        val maxH = video.supportedHeights.upper
        // Frame rate achievable at 1080p (or the max size if smaller), capped to something sane.
        val probeW = minOf(1920, maxW)
        val probeH = minOf(1080, maxH)
        val maxFps =
            runCatching { video.getSupportedFrameRatesFor(probeW, probeH).upper }
                .getOrElse { video.supportedFrameRates.upper.toDouble() }
                .toInt()
                .coerceIn(1, 240)
        val hardware =
            if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !CodecCatalog.looksSoftware(info.name)
        val lowLatency =
            (Build.VERSION.SDK_INT >= 30 && caps.isFeatureSupported(CodecCapabilities.FEATURE_LowLatency)) ||
                CodecCatalog.looksLowLatency(info.name)
        val secure = caps.isFeatureSupported(CodecCapabilities.FEATURE_SecurePlayback) || info.name.endsWith(".secure")
        val encoderCaps = if (info.isEncoder) caps.encoderCapabilities else null
        return CodecDescriptor(
            name = info.name,
            mime = mime,
            isEncoder = info.isEncoder,
            hardware = hardware,
            lowLatency = lowLatency,
            secure = secure,
            maxWidth = maxW,
            maxHeight = maxH,
            maxFps = maxFps,
            supportsIntraRefresh = info.isEncoder && caps.isFeatureSupported(CodecCapabilities.FEATURE_IntraRefresh),
            supportsCbr = encoderCaps?.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CBR) == true,
        )
    }
}
