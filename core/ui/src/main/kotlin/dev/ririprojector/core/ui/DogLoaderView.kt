package dev.ririprojector.core.ui

import android.animation.TimeAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * The RiriProjector dog as a loader: the launcher artwork on a rounded tile that bobs its head,
 * the projector beam from its eye pulsing, the projected screen glowing, an optional spinning ring
 * and a row of paw prints lighting up in turn. Timing lives in [DogLoaderMotion].
 *
 * Draws without allocating (paths and rects are built on resize); the animator runs only while
 * the view is attached and visible, and not at all when the system turned animations off.
 */
class DogLoaderView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var mode: DogLoaderMode = DogLoaderMode.LOADING
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    /** Paw prints under the tile. Changing it changes the measured height. */
    var showPaws: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    private val density = resources.displayMetrics.density
    private val cyan = ContextCompat.getColor(context, R.color.riri_cyan)
    private val tan = ContextCompat.getColor(context, R.color.riri_tan)
    private val beamColor = ContextCompat.getColor(context, R.color.riri_cyan_light)

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = beamColor }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pawPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var art: Bitmap? = null
    private val artSrc = Rect()
    private val tile = RectF()
    private val ringRect = RectF()
    private val glowRect = RectF()
    private val tileClip = Path()
    private val beam = Path()
    private val paw = Path()
    private val pawOrigins = FloatArray(DogLoaderMotion.PAW_COUNT * 2)
    private var pawScale = 1f
    private var liftPx = 0f

    private var timeMs = 0L
    private val animator = TimeAnimator().apply {
        setTimeListener { _, totalTime, _ ->
            timeMs = totalTime
            invalidate()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val side = (DEFAULT_SIDE_DP * density).toInt()
        val pawsArea = if (showPaws) (PAWS_AREA_DP * density).toInt() else 0
        setMeasuredDimension(
            resolveSize(side, widthMeasureSpec),
            resolveSize(side + pawsArea, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val pawsArea = if (showPaws) min(PAWS_AREA_DP * density, h * 0.2f) else 0f
        val side = min(w.toFloat(), h - pawsArea)
        val cx = w / 2f
        val top = 0f
        val stroke = side * 0.018f
        ringPaint.strokeWidth = stroke
        ringRect.set(cx - side / 2 + stroke, top + stroke, cx + side / 2 - stroke, top + side - stroke)

        val t = side * TILE_FRACTION
        tile.set(cx - t / 2, top + (side - t) / 2, cx + t / 2, top + (side + t) / 2)
        val radius = t * 0.24f
        tileClip.reset()
        tileClip.addRoundRect(tile, radius, radius, Path.Direction.CW)
        liftPx = side * 0.02f

        beam.reset()
        beam.moveTo(tx(0.09f), ty(0.39f))
        beam.lineTo(tx(0.43f), ty(0.39f))
        beam.lineTo(tx(0.51f), ty(0.70f))
        beam.lineTo(tx(0.47f), ty(0.70f))
        beam.close()
        glowRect.set(tx(0.07f), ty(0.17f), tx(0.43f), ty(0.39f))
        glowPaint.strokeWidth = side * 0.012f

        // Paws: four prints of 24 units, alternating up and down like footsteps.
        pawScale = min(pawsArea * 0.55f, side * 0.1f) / 24f
        val step = 24f * pawScale * 1.6f
        val rowWidth = step * (DogLoaderMotion.PAW_COUNT - 1) + 24f * pawScale
        val startX = cx - rowWidth / 2
        for (i in 0 until DogLoaderMotion.PAW_COUNT) {
            pawOrigins[i * 2] = startX + i * step
            pawOrigins[i * 2 + 1] = side + pawsArea * 0.1f + if (i % 2 == 1) pawsArea * 0.3f else 0f
        }
        paw.reset()
        paw.addOval(RectF(7f, 12f, 17f, 20f), Path.Direction.CW)
        for ((x, y) in PAW_TOES) paw.addCircle(x, y, 2f, Path.Direction.CW)

        loadArt(t.toInt())
    }

    private fun tx(f: Float) = tile.left + tile.width() * f

    private fun ty(f: Float) = tile.top + tile.height() * f

    /** Decodes the artwork at no more than twice the tile size: the source is 1024 px. */
    private fun loadArt(targetPx: Int) {
        if (targetPx <= 0 || (art?.width ?: 0) >= targetPx) return
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeResource(resources, R.drawable.riri_dog_art, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
        art = BitmapFactory.decodeResource(
            resources,
            R.drawable.riri_dog_art,
            BitmapFactory.Options().apply { inSampleSize = sample },
        )
        art?.let { artSrc.set(0, 0, it.width, it.height) }
    }

    override fun onDraw(canvas: Canvas) {
        val animated = animator.isRunning
        val ringColor = if (mode == DogLoaderMode.RECONNECTING) tan else cyan

        if (mode != DogLoaderMode.IDLE) {
            ringPaint.color = ringColor
            ringPaint.alpha = 40
            canvas.drawOval(ringRect, ringPaint)
            ringPaint.alpha = 255
            val start = if (animated) DogLoaderMotion.ringDegrees(timeMs, mode) else 0f
            canvas.drawArc(ringRect, start - 90f, 100f, false, ringPaint)
        }

        val bob = if (animated) DogLoaderMotion.bob(timeMs) else 0f
        val beamAlpha =
            if (animated) DogLoaderMotion.beamAlpha(timeMs, mode) else DogLoaderMotion.staticBeamAlpha(mode)
        val save = canvas.save()
        canvas.translate(0f, -liftPx * (bob + 1f) / 2f)
        canvas.rotate(bob * DogLoaderMotion.BOB_DEGREES, tile.centerX(), tile.centerY())
        canvas.clipPath(tileClip)
        art?.let { canvas.drawBitmap(it, artSrc, tile, bitmapPaint) }
        beamPaint.alpha = (beamAlpha * 255).toInt()
        canvas.drawPath(beam, beamPaint)
        val glowR = glowRect.height() * 0.12f
        glowFill.color = cyan
        glowFill.alpha = (beamAlpha * 70).toInt()
        canvas.drawRoundRect(glowRect, glowR, glowR, glowFill)
        glowPaint.color = beamColor
        glowPaint.alpha = (beamAlpha * 255).toInt()
        canvas.drawRoundRect(glowRect, glowR, glowR, glowPaint)
        canvas.restoreToCount(save)

        if (showPaws) {
            pawPaint.color = ringColor
            for (i in 0 until DogLoaderMotion.PAW_COUNT) {
                val alpha = if (animated) DogLoaderMotion.pawAlpha(timeMs, i) else STATIC_PAW_ALPHA
                pawPaint.alpha = (alpha * 255).toInt()
                val s = canvas.save()
                canvas.translate(pawOrigins[i * 2], pawOrigins[i * 2 + 1])
                canvas.scale(pawScale, pawScale)
                canvas.drawPath(paw, pawPaint)
                canvas.restoreToCount(s)
            }
        }
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (isVisible && isAttachedToWindow && ValueAnimator.areAnimatorsEnabled()) {
            if (!animator.isStarted) animator.start()
        } else {
            animator.cancel()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val DEFAULT_SIDE_DP = 220f
        const val PAWS_AREA_DP = 48f
        const val TILE_FRACTION = 0.7f
        const val STATIC_PAW_ALPHA = 0.6f
        val PAW_TOES = listOf(5.5f to 10f, 9.5f to 6f, 14.5f to 6f, 18.5f to 10f)
    }
}
