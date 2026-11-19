package dev.ririprojector.feature.receiver

import android.app.Activity
import android.graphics.Color
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import dev.ririprojector.protocol.model.DisplayInfo
import java.net.Inet4Address
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Fire TV entry point: full-screen decoder surface, waiting screen and a stats overlay. */
class ReceiverActivity : Activity() {
    private val scope: CoroutineScope = MainScope()
    private var collector: Job? = null
    private lateinit var controller: ReceiverController
    private lateinit var container: AspectFrameLayout
    private lateinit var status: TextView
    private lateinit var statsOverlay: TextView
    private var overlayVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val surfaceView = SurfaceView(this)
        container = AspectFrameLayout(this).apply { addView(surfaceView) }
        status =
            TextView(this).apply {
                setTextColor(Color.WHITE)
                textSize = 26f
                gravity = Gravity.CENTER
            }
        statsOverlay =
            TextView(this).apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(0x99000000.toInt())
                textSize = 14f
                typeface = android.graphics.Typeface.MONOSPACE
                setPadding(24, 16, 24, 16)
                visibility = View.GONE
            }
        val root =
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(container, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
                addView(status, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(statsOverlay, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START))
            }
        setContentView(root)

        controller = ReceiverController(applicationContext, deviceName(), displayInfo(), ::localIpv4)
        controller.onStreamStarted = { params ->
            runOnUiThread { container.aspectRatio = params.video.width.toDouble() / params.video.height }
        }
        surfaceView.holder.addCallback(
            object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = controller.setSurface(holder.surface)

                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

                override fun surfaceDestroyed(holder: SurfaceHolder) = controller.setSurface(null)
            },
        )
    }

    override fun onStart() {
        super.onStart()
        controller.start()
        collector = scope.launch { controller.state.collect(::render) }
    }

    override fun onStop() {
        collector?.cancel()
        controller.stop()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
            overlayVisible = !overlayVisible
            statsOverlay.visibility = if (overlayVisible) View.VISIBLE else View.GONE
            true
        }

        else -> super.onKeyDown(keyCode, event)
    }

    private fun render(state: ReceiverState) {
        when (state) {
            is ReceiverState.Waiting -> {
                status.visibility = View.VISIBLE
                status.text =
                    if (state.address != null) {
                        getString(R.string.receiver_waiting_at, state.name, state.address, state.port)
                    } else {
                        getString(R.string.receiver_waiting)
                    }
                statsOverlay.text = ""
            }

            is ReceiverState.Streaming -> {
                status.visibility = View.GONE
                val v = state.params.video
                val s = state.stats
                statsOverlay.text =
                    getString(
                        R.string.receiver_overlay,
                        v.codec.name,
                        v.width,
                        v.height,
                        s.fps,
                        v.fps,
                        s.bitrateBps / 1_000_000.0,
                        s.decodeMeanUs / 1000.0,
                        s.decodeP95Us / 1000.0,
                        s.dropped,
                        s.resets,
                        s.decoderName,
                        state.peer,
                    )
            }

            is ReceiverState.Failed -> {
                status.visibility = View.VISIBLE
                status.text = getString(R.string.receiver_failed, state.message)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private fun deviceName(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL

    @Suppress("DEPRECATION")
    private fun displayInfo(): DisplayInfo {
        val d = if (Build.VERSION.SDK_INT >= 30) display!! else windowManager.defaultDisplay
        val mode = d.mode
        return DisplayInfo(mode.physicalWidth, mode.physicalHeight, mode.refreshRate.toInt().coerceAtLeast(1))
    }

    private fun localIpv4(): String? {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map {
            it.address
        }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    private companion object {
        const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
    }
}
