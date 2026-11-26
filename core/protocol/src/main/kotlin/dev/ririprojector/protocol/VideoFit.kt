package dev.ririprojector.protocol

import dev.ririprojector.protocol.model.VideoCodecCapability
import kotlin.math.sqrt

/** A video size in pixels. */
data class Size(val width: Int, val height: Int)

/**
 * What a codec (or a chain of them) can take, measured on device (#34). Area and rate are in
 * *padded* pixels: codecs work in 16×16 blocks, so 1080×1928 costs 1088×1936.
 */
data class VideoLimits(val maxWidth: Int, val maxHeight: Int, val maxPaddedPixels: Long, val maxPixelRate: Long) {
    /** The tighter of both limits, e.g. the sender's encoder and the receiver's decoder. */
    fun intersect(other: VideoLimits) = VideoLimits(
        minOf(maxWidth, other.maxWidth),
        minOf(maxHeight, other.maxHeight),
        minOf(maxPaddedPixels, other.maxPaddedPixels),
        minOf(maxPixelRate, other.maxPixelRate),
    )

    fun accepts(width: Int, height: Int, fps: Int): Boolean {
        if (width > maxWidth || height > maxHeight) return false
        val padded = VideoFit.padded(width) * VideoFit.padded(height)
        return padded <= maxPaddedPixels && padded * fps <= maxPixelRate
    }

    companion object {
        val UNLIMITED = VideoLimits(Int.MAX_VALUE, Int.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)

        fun of(cap: VideoCodecCapability) =
            VideoLimits(cap.maxWidth, cap.maxHeight, cap.maxPixels.toLong(), cap.maxPixelRate)

        /** A display (or any target) as a size limit only. */
        fun ofSize(width: Int, height: Int) = UNLIMITED.copy(maxWidth = width, maxHeight = height)
    }
}

/** Pure size fitting shared by mirroring (#34) and the second-screen display (#35). */
object VideoFit {
    const val ALIGNMENT = 8
    const val MIN_DIMENSION = 160
    private const val BLOCK = 16

    fun padded(value: Int): Long = ((value + BLOCK - 1) / BLOCK * BLOCK).toLong()

    /**
     * Largest size with the aspect ratio [aspectW]:[aspectH], each side a multiple of 8 and never
     * larger than the aspect size itself (no upscaling), that [limits] and [accepts] take at [fps]
     * and whose height is ≤ [capHeight] when set. Null if nothing ≥ 160 px per side fits.
     */
    fun fitWithin(
        aspectW: Int,
        aspectH: Int,
        limits: VideoLimits,
        fps: Int,
        capHeight: Int? = null,
        accepts: (width: Int, height: Int, fps: Int) -> Boolean = { _, _, _ -> true },
    ): Size? {
        val srcW = aspectW.toDouble()
        val srcH = aspectH.toDouble()
        val maxH = minOf(limits.maxHeight, capHeight ?: Int.MAX_VALUE)
        var scale = minOf(1.0, limits.maxWidth / srcW, maxH / srcH)
        // Area and rate: first guess from the ratio, then the loop below settles the rounding.
        val areaBudget = minOf(limits.maxPaddedPixels, limits.maxPixelRate / fps.coerceAtLeast(1))
        if (areaBudget < Long.MAX_VALUE) scale = minOf(scale, sqrt(areaBudget / (srcW * srcH)))
        var w = align(srcW * scale)
        var h = align(srcH * scale)
        // Shrink in 8-px steps of the long side, keeping the ratio, until every limit holds.
        while (w >= MIN_DIMENSION && h >= MIN_DIMENSION) {
            if (h <= maxH && limits.accepts(w, h, fps) && accepts(w, h, fps)) return Size(w, h)
            scale *= if (w >= h) (w - ALIGNMENT) / w.toDouble() else (h - ALIGNMENT) / h.toDouble()
            w = align(srcW * scale)
            h = align(srcH * scale)
        }
        return null
    }

    private fun align(value: Double): Int = (value.toInt() / ALIGNMENT) * ALIGNMENT
}
