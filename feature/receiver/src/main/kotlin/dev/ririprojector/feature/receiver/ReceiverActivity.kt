package dev.ririprojector.feature.receiver

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import dev.ririprojector.core.ui.R as UiR
import dev.ririprojector.net.WifiBand
import dev.ririprojector.net.devlog.DevEvent
import dev.ririprojector.net.devlog.DevEventLog
import dev.ririprojector.net.devlog.DevLevel
import dev.ririprojector.net.devlog.DevLog
import dev.ririprojector.net.p2p.WifiDirectSupport
import dev.ririprojector.protocol.ByeReason
import dev.ririprojector.protocol.model.DisplayInfo
import java.net.Inet4Address
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Receiver entry point (any TV, set-top box, projector, phone or tablet): full-screen decoder
 * surface, waiting screen and a stats overlay.
 */
class ReceiverActivity : Activity() {
    private val scope: CoroutineScope = MainScope()
    private var collector: Job? = null
    private lateinit var controller: ReceiverController
    private lateinit var container: AspectFrameLayout
    private lateinit var panel: WaitingPanel
    private lateinit var statsOverlay: TextView
    private var overlayVisible = false
    private lateinit var root: FrameLayout
    private lateinit var streamLog: ScrollView
    private lateinit var streamLogText: TextView
    private var streaming = false

    // Developer mode (#33): stored per device, applied in onStart.
    private val devPrefs by lazy { getSharedPreferences(DEV_PREFS, MODE_PRIVATE) }
    private var developerMode = false
    private var devCollector: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()

        val surfaceView = SurfaceView(this)
        container = AspectFrameLayout(this).apply { addView(surfaceView) }
        val large = resources.configuration.smallestScreenWidthDp >= 600
        val metrics = resources.displayMetrics
        val touch = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
        panel = WaitingPanel(this, landscape = metrics.widthPixels > metrics.heightPixels, large = large, touch = touch)
        // Manual role choice on any device (ADR-001); shown only while no stream is on screen.
        panel.useAsSender.setOnClickListener {
            startActivity(Intent(ACTION_USE_AS_SENDER).setPackage(packageName))
            finish()
        }
        panel.devToggle.setOnClickListener {
            devPrefs.edit().putBoolean(KEY_DEV_MODE, !developerMode).apply()
            applyDeveloperMode()
        }
        panel.copyLog.setOnClickListener { copyDevLog() }
        val density = metrics.density
        statsOverlay =
            TextView(this).apply {
                setTextColor(ContextCompat.getColor(context, UiR.color.riri_on_surface))
                background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, UiR.color.riri_overlay))
                    cornerRadius = 20 * density
                    setStroke((1 * density).toInt(), ContextCompat.getColor(context, UiR.color.riri_outline))
                }
                textSize = if (large) 16f else 13f
                setLineSpacing(4 * density, 1f)
                val pad = (20 * density).toInt()
                setPadding(pad, pad, pad, pad)
                visibility = View.GONE
            }
        streamLogText =
            TextView(this).apply {
                setTextColor(ContextCompat.getColor(context, UiR.color.riri_on_surface))
                typeface = Typeface.MONOSPACE
                textSize = if (large) 13f else 11f
            }
        streamLog =
            ScrollView(this).apply {
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                background = GradientDrawable().apply {
                    setColor(ContextCompat.getColor(context, UiR.color.riri_overlay))
                    cornerRadius = 20 * density
                }
                val pad = (16 * density).toInt()
                setPadding(pad, pad, pad, pad)
                visibility = View.GONE
                addView(streamLogText)
            }
        root =
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(container, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
                addView(panel, FrameLayout.LayoutParams(MATCH, MATCH))
                addView(
                    statsOverlay,
                    FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply {
                        val m = (24 * density).toInt()
                        setMargins(m, m, m, m)
                    },
                )
                // Developer mode: the log under the stats, shown with them while streaming, at most 40 % high.
                addView(
                    streamLog,
                    FrameLayout.LayoutParams(MATCH, (metrics.heightPixels * 0.4f).toInt(), Gravity.BOTTOM).apply {
                        val m = (24 * density).toInt()
                        setMargins(m, m, m, m)
                    },
                )
                // Touch receivers (phones, tablets) have no D-pad: a tap toggles the overlay. The
                // screen itself holds the initial focus, so OK on a remote keeps toggling the
                // overlay; the role button needs a deliberate D-pad move first.
                setOnClickListener { toggleOverlay() }
                isFocusable = true
                defaultFocusHighlightEnabled = false
            }
        setContentView(root)
        root.requestFocus()

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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        panel.setLandscape(newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE)
    }

    override fun onStart() {
        super.onStart()
        controller.start()
        applyDeveloperMode()
        collector = scope.launch { controller.state.collect(::render) }
        // API 29+ needs a runtime permission for Wi-Fi Direct (none on Fire OS 7). Asked once;
        // without it the receiver simply stays router-only.
        val missing = WifiDirectSupport.missingPermissions(this)
        if (WifiDirectSupport.hasFeature(this) && missing.isNotEmpty() && !askedWifiDirect) {
            askedWifiDirect = true
            requestPermissions(missing.toTypedArray(), REQUEST_WIFI_DIRECT)
        }
    }

    private var askedWifiDirect = false

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_WIFI_DIRECT) controller.startWifiDirect()
    }

    override fun onPause() {
        // Back/finish: the user is done with the receiver, so the sender should stop too.
        controller.leaveReason = if (isFinishing) ByeReason.USER_STOPPED else ByeReason.NORMAL
        super.onPause()
    }

    override fun onStop() {
        collector?.cancel()
        devCollector?.cancel()
        devCollector = null
        controller.stop()
        super.onStop()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_INFO -> {
            toggleOverlay()
            true
        }

        else -> super.onKeyDown(keyCode, event)
    }

    /**
     * Developer mode (#33): applies the stored choice. The log is collected only while it is on,
     * and only the newest lines are rendered.
     */
    private fun applyDeveloperMode() {
        developerMode = devPrefs.getBoolean(KEY_DEV_MODE, false)
        DevLog.enabled = developerMode
        panel.setDeveloperMode(developerMode)
        devCollector?.cancel()
        devCollector = null
        if (developerMode) {
            devCollector = scope.launch {
                DevLog.events.collect { events ->
                    val text = devLogText(events.takeLast(DEV_LINES_SHOWN))
                    panel.setLog(text)
                    streamLogText.text = text
                    streamLog.post { streamLog.fullScroll(View.FOCUS_DOWN) }
                }
            }
        }
        updateStreamLog()
    }

    private fun devLogText(events: List<DevEvent>): CharSequence {
        val warn = ContextCompat.getColor(this, UiR.color.riri_coral)
        val out = SpannableStringBuilder()
        events.forEachIndexed { i, e ->
            if (i > 0) out.append('\n')
            val start = out.length
            out.append(DevEventLog.formatLine(e))
            if (e.level != DevLevel.INFO) {
                out.setSpan(ForegroundColorSpan(warn), start, out.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return out
    }

    /** The log under the stats while streaming: only with the overlay, so the picture stays clean. */
    private fun updateStreamLog() {
        streamLog.visibility = if (developerMode && streaming && overlayVisible) View.VISIBLE else View.GONE
    }

    private fun copyDevLog() {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.receiver_dev_log_title), DevLog.format()))
        // API 33+ shows its own confirmation.
        if (Build.VERSION.SDK_INT <
            33
        ) {
            Toast.makeText(this, R.string.receiver_dev_log_copied, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleOverlay() {
        overlayVisible = !overlayVisible
        statsOverlay.visibility = if (overlayVisible) View.VISIBLE else View.GONE
        updateStreamLog()
    }

    private fun render(state: ReceiverState) {
        when (state) {
            is ReceiverState.Waiting -> {
                streaming = false
                updateStreamLog()
                panel.visibility = View.VISIBLE
                val direct = if (state.directReady) getString(R.string.receiver_wifi_direct_ready) else ""
                panel.showWaiting(
                    name = state.name,
                    waiting = getString(
                        R.string.receiver_waiting_with_direct,
                        getString(R.string.receiver_waiting),
                        direct,
                    ),
                    endpoint = state.address?.let { "$it:${state.port}" },
                )
                statsOverlay.text = ""
            }

            is ReceiverState.Streaming -> {
                // Focus must not stay in the hidden panel: OK on the remote toggles the overlay again.
                if (panel.hasFocus()) root.requestFocus()
                panel.visibility = View.GONE
                if (!streaming) {
                    streaming = true
                    updateStreamLog()
                }
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
                        s.audioBufferedMs,
                        s.audioUnderruns,
                        s.avOffsetMs?.let { getString(R.string.receiver_av_offset, it) }
                            ?: getString(R.string.receiver_av_unknown),
                        s.decoderName,
                        linkLabel(state.direct, state.peer),
                        s.videoDelayMs,
                        s.latency?.let {
                            getString(
                                R.string.receiver_latency,
                                (it.meanUs / 1000).toInt(),
                                (it.p95Us / 1000).toInt(),
                                (it.uncertaintyUs / 1000).toInt(),
                            )
                        } ?: getString(R.string.receiver_av_unknown),
                        s.pipeline,
                    )
            }

            is ReceiverState.Failed -> {
                streaming = false
                updateStreamLog()
                panel.visibility = View.VISIBLE
                panel.showError(state.message)
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

    /** Peer address, link type and the Wi-Fi band in use (#23), e.g. "192.168.1.6 · Wi-Fi 5 GHz (5220 MHz)". */
    private fun linkLabel(direct: Boolean, peer: String): String {
        val base = if (direct) getString(R.string.receiver_link_direct, peer) else peer
        // API 29+ joins Wi-Fi Direct as a P2P client: connectionInfo then describes the router, not the link.
        val band = if (direct && Build.VERSION.SDK_INT >= 29) null else WifiBand.describe(wifiFrequencyMhz())
        return if (band != null) getString(R.string.receiver_link_band, base, band) else base
    }

    @Suppress("DEPRECATION") // frequency only: no location-gated fields are read
    private fun wifiFrequencyMhz(): Int =
        applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.frequency ?: -1

    private fun localIpv4(): String? {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses.map {
            it.address
        }.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    companion object {
        /** Switch this device to sender mode and remember it (handled by the app's launcher activity). */
        const val ACTION_USE_AS_SENDER = "dev.ririprojector.action.USE_AS_SENDER"

        private const val MATCH = FrameLayout.LayoutParams.MATCH_PARENT
        private const val WRAP = FrameLayout.LayoutParams.WRAP_CONTENT
        private const val REQUEST_WIFI_DIRECT = 21
        private const val DEV_PREFS = "riri_receiver_settings"
        private const val KEY_DEV_MODE = "dev.mode"

        /** Lines rendered on screen; the copied log has all of them. */
        private const val DEV_LINES_SHOWN = 40
    }
}
