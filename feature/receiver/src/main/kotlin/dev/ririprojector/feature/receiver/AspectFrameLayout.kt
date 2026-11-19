package dev.ririprojector.feature.receiver

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/** Fits its content to [aspectRatio] (width / height) inside the space it is given, letterboxing. */
class AspectFrameLayout(context: Context) : FrameLayout(context) {
    var aspectRatio: Double = 0.0
        set(value) {
            if (value != field) {
                field = value
                requestLayout()
            }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = View.MeasureSpec.getSize(widthMeasureSpec)
        val h = View.MeasureSpec.getSize(heightMeasureSpec)
        if (aspectRatio <= 0 || w == 0 || h == 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val (fw, fh) = fit(w, h, aspectRatio)
        super.onMeasure(
            View.MeasureSpec.makeMeasureSpec(fw, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(fh, View.MeasureSpec.EXACTLY),
        )
    }

    companion object {
        /** Largest size with [ratio] that fits in [w]x[h]. */
        fun fit(w: Int, h: Int, ratio: Double): Pair<Int, Int> = if (w.toDouble() / h > ratio) {
            (h * ratio).toInt() to h
        } else {
            w to (w / ratio).toInt()
        }
    }
}
