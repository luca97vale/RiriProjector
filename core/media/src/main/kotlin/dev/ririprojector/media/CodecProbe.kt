package dev.ririprojector.media

/**
 * Measures a codec's real area and pixel-rate limits (#34). Per-axis maxima overstate them:
 * level/block-count limits cap the *area* well below `maxWidth × maxHeight`.
 *
 * Pure: the platform's `VideoCapabilities.isSizeSupported` and `getSupportedFrameRatesFor` are
 * passed in, so the search is tested on the JVM.
 */
object CodecProbe {
    data class Limits(val maxPixels: Int, val maxPixelRate: Long)

    private const val BLOCK = 16

    /** Common panel shapes; both orientations are searched. */
    val ASPECTS = listOf(16 to 9, 16 to 10, 4 to 3, 20 to 9)

    /**
     * For each aspect and orientation, binary-searches the largest 16-aligned size the codec
     * accepts within [maxWidth]×[maxHeight]. `maxPixels` is the largest area found, `maxPixelRate`
     * that area × its max frame rate ([maxFpsAt], null when unknown → [fallbackFps]). If no size
     * passes (odd vendor codec), falls back to the per-axis area, as before #34.
     */
    fun limits(
        maxWidth: Int,
        maxHeight: Int,
        fallbackFps: Int,
        isSizeSupported: (width: Int, height: Int) -> Boolean,
        maxFpsAt: (width: Int, height: Int) -> Double?,
    ): Limits {
        var bestW = 0
        var bestH = 0
        for ((a, b) in ASPECTS) {
            for ((rw, rh) in listOf(a to b, b to a)) {
                val size = largest(rw, rh, maxWidth, maxHeight, isSizeSupported) ?: continue
                if (size.first.toLong() * size.second > bestW.toLong() * bestH) {
                    bestW = size.first
                    bestH = size.second
                }
            }
        }
        if (bestW == 0) {
            val area = maxWidth.toLong() * maxHeight
            return Limits(area.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), area * fallbackFps)
        }
        val area = bestW * bestH
        val fps = maxFpsAt(bestW, bestH)?.toInt()?.takeIf { it > 0 } ?: fallbackFps
        return Limits(area, area.toLong() * fps)
    }

    /** Largest `(w, h)`, both multiples of 16 with ratio ≈ [rw]:[rh], that [ok] accepts. */
    private fun largest(rw: Int, rh: Int, maxW: Int, maxH: Int, ok: (Int, Int) -> Boolean): Pair<Int, Int>? {
        fun widthFor(h: Int) = (h.toLong() * rw / rh / BLOCK * BLOCK).toInt()
        var lo = 1 // in blocks of height
        var hi = maxH / BLOCK
        while (hi >= lo && widthFor(hi * BLOCK) > maxW) hi--
        var found: Pair<Int, Int>? = null
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val h = mid * BLOCK
            val w = widthFor(h)
            if (w >= BLOCK && ok(w, h)) {
                found = w to h
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }
}
