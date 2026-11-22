package dev.ririprojector.feature.receiver

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.ririprojector.core.ui.DogLoaderMode
import dev.ririprojector.core.ui.DogLoaderView
import dev.ririprojector.core.ui.R as UiR

/**
 * The receiver's full-screen panel when no picture is on screen: the animated dog, the state
 * ("Ready" or "Error"), this receiver's name and address, a hint for the overlay and the role
 * switch. Side by side on landscape screens (TVs, projectors), stacked on portrait phones.
 * Opaque, because a SurfaceView keeps showing its last frame after a session ends.
 */
internal class WaitingPanel(context: Context, landscape: Boolean, private val large: Boolean, touch: Boolean) :
    LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val onSurface = color(UiR.color.riri_on_surface)
    private val muted = color(UiR.color.riri_muted)
    private val cyan = color(UiR.color.riri_cyan)
    private val coral = color(UiR.color.riri_coral)

    private val loader =
        DogLoaderView(context).apply {
            mode = DogLoaderMode.IDLE
            showPaws = false
            contentDescription = context.getString(R.string.receiver_loader_description)
        }
    private val dot = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL } }
    private val stateLabel = text(if (large) 16f else 13f, bold = true).apply { letterSpacing = 0.1f }
    private val title = text(if (large) 52f else 30f, bold = true)
    private val subtitle = text(if (large) 24f else 17f)
    private val address = text(if (large) 24f else 18f, bold = true)
    private val addressPill =
        LinearLayout(context).apply {
            orientation = VERTICAL
            background = rounded(color(UiR.color.riri_surface_high), 18f, color(UiR.color.riri_outline_variant), 1f)
            setPadding(dp(22), dp(14), dp(22), dp(14))
            addView(text(if (large) 15f else 12f, color = muted).apply { setText(R.string.receiver_address_label) })
            addView(address)
        }
    private val hint =
        text(if (large) 16f else 14f, color = muted).apply {
            setText(if (touch) R.string.receiver_hint_touch else R.string.receiver_hint_dpad)
        }
    val useAsSender =
        Button(context).apply {
            setText(R.string.receiver_use_as_sender)
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (large) 18f else 16f)
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(56)
            setPadding(dp(28), 0, dp(28), 0)
            stateListAnimator = null
            // 10-foot focus: white fill, cyan ring and a slight zoom, so it reads from the sofa.
            background =
                StateListDrawable().apply {
                    addState(intArrayOf(android.R.attr.state_pressed), rounded(cyan, 16f))
                    addState(intArrayOf(android.R.attr.state_focused), rounded(onSurface, 16f, cyan, 3f))
                    addState(
                        intArrayOf(),
                        rounded(color(UiR.color.riri_surface_high), 16f, color(UiR.color.riri_outline), 2f),
                    )
                }
            setTextColor(
                ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_pressed),
                        intArrayOf(android.R.attr.state_focused),
                        intArrayOf(),
                    ),
                    intArrayOf(color(UiR.color.riri_on_cyan), color(UiR.color.riri_navy), onSurface),
                ),
            )
            setOnFocusChangeListener { v, focused ->
                val s = if (focused) 1.06f else 1f
                v.animate().scaleX(s).scaleY(s).setDuration(150).start()
            }
        }
    private val column = LinearLayout(context).apply { orientation = VERTICAL }

    init {
        gravity = Gravity.CENTER
        setBackgroundColor(color(UiR.color.riri_navy))
        val pad = dp(if (large) 64 else 24)
        setPadding(pad, pad, pad, pad)
        val stateRow =
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(dot, LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(12) })
                addView(stateLabel)
            }
        val gap = dp(if (large) 20 else 14)
        column.addView(stateRow)
        column.addView(title, spaced(gap))
        column.addView(subtitle, spaced(gap / 2))
        column.addView(addressPill, spaced(gap))
        column.addView(hint, spaced(gap))
        column.addView(useAsSender, spaced(gap))
        addView(loader)
        addView(column)
        setLandscape(landscape)
    }

    /** Side by side on landscape screens, stacked on portrait ones; called again on rotation. */
    fun setLandscape(landscape: Boolean) {
        orientation = if (landscape) HORIZONTAL else VERTICAL
        val shortSide = minOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        val loaderSize = (shortSide * if (landscape) 0.55f else 0.5f).toInt()
        loader.layoutParams = LayoutParams(loaderSize, loaderSize)
        val g = if (landscape) Gravity.START else Gravity.CENTER_HORIZONTAL
        column.gravity = g
        listOf(title, subtitle, hint).forEach { it.gravity = g }
        column.layoutParams =
            if (landscape) {
                LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(if (large) 56 else 32) }
            } else {
                LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) }
            }
    }

    fun showWaiting(name: String, waiting: String, endpoint: String?) {
        setState(ready = true)
        title.text = name
        subtitle.text = waiting
        address.text = endpoint ?: ""
        addressPill.visibility = if (endpoint != null) View.VISIBLE else View.GONE
        hint.visibility = View.VISIBLE
    }

    fun showError(message: String) {
        setState(ready = false)
        title.setText(R.string.receiver_error_title)
        subtitle.text = message
        addressPill.visibility = View.GONE
        hint.visibility = View.GONE
    }

    private fun setState(ready: Boolean) {
        (dot.background as GradientDrawable).setColor(if (ready) cyan else coral)
        stateLabel.setTextColor(if (ready) color(UiR.color.riri_cyan_light) else coral)
        stateLabel.text =
            context.getString(if (ready) R.string.receiver_ready else R.string.receiver_error_label).uppercase()
    }

    private fun spaced(top: Int) = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
        topMargin =
            top
    }

    private fun text(sp: Float, bold: Boolean = false, color: Int = onSurface) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(fill: Int, radiusDp: Float, stroke: Int? = null, strokeDp: Float = 0f) =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radiusDp * density
            if (stroke != null) setStroke((strokeDp * density).toInt(), stroke)
        }

    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private fun dp(v: Int) = (v * density).toInt()
}
