package dev.ririprojector.helper

import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * The helper's binder, running as shell (ADR-014). Only [appUid] may call it; it acts only on the
 * displays it created.
 */
internal class HelperService(
    private val appUid: Int,
    private val userId: Int,
    private val apk: String,
    private val onExit: () -> Unit,
) : IRiriHelper.Stub() {
    private val displays = ConcurrentHashMap<Int, VirtualDisplay>()
    private val latency = LatencyHistogram()
    private val dropped = AtomicLong()

    @Volatile
    var lastActivityAt = SystemClock.uptimeMillis()
        private set

    val hasDisplays: Boolean get() = displays.isNotEmpty()

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        val caller = Binder.getCallingUid()
        if (code != INTERFACE_TRANSACTION && caller != appUid) {
            Log.w(TAG, "refused a call from uid $caller")
            throw SecurityException("caller $caller is not RiriProjector")
        }
        lastActivityAt = SystemClock.uptimeMillis()
        return super.onTransact(code, data, reply, flags)
    }

    override fun version(): Int = VERSION

    override fun apkPath(): String = apk

    override fun createDisplay(surface: Surface?, width: Int, height: Int, dpi: Int, fps: Int): Int {
        if (width !in 160..8192 || height !in 160..8192 || dpi !in 72..1280) return -1
        val display = runCatching {
            ShellPlatform.displayManager.createVirtualDisplay(
                "RiriProjector second screen",
                width,
                height,
                dpi,
                surface,
                SecondDisplay.flags(Build.VERSION.SDK_INT),
            )
        }.onFailure { Log.e(TAG, "createVirtualDisplay failed", it) }.getOrNull() ?: return -1
        val id = display.display.displayId
        displays[id] = display
        Log.i(TAG, "display $id ${width}x$height $dpi dpi")
        return id
    }

    override fun setSurface(displayId: Int, surface: Surface?) {
        displays[displayId]?.surface = surface
    }

    override fun releaseDisplay(displayId: Int) {
        displays.remove(displayId)?.release()
    }

    override fun launch(component: String, displayId: Int): Int {
        if (!displays.containsKey(displayId) || !COMPONENT.matches(component)) return -1
        // `am` as an argument list: nothing goes through a shell.
        val process = ProcessBuilder(
            "am", "start", "--display", displayId.toString(), "--user", userId.toString(),
            "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER",
            "-f", "0x10200000", "-n", component,
        ).redirectErrorStream(true).start()
        return try {
            process.outputStream.close()
            if (process.waitFor(LAUNCH_TIMEOUT_S, TimeUnit.SECONDS)) process.exitValue() else -2
        } finally {
            // `am` prints a line or two: never enough to fill the pipe before it exits.
            runCatching { process.inputStream.close() }
            process.destroyForcibly()
        }
    }

    override fun injectKey(
        displayId: Int,
        action: Int,
        keyCode: Int,
        repeat: Int,
        metaState: Int,
        source: Int,
        receivedAtNanos: Long,
    ) {
        if (!displays.containsKey(displayId) || !SecondDisplay.isAllowedKey(keyCode)) return countDrop()
        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, action, keyCode, repeat, metaState, -1, 0, 0, source)
        record(ShellPlatform.inject(event, displayId), receivedAtNanos)
    }

    override fun injectMotion(
        displayId: Int,
        action: Int,
        buttonState: Int,
        pointerIds: IntArray,
        toolTypes: IntArray,
        xs: FloatArray,
        ys: FloatArray,
        source: Int,
        receivedAtNanos: Long,
    ) {
        val n = pointerIds.size
        if (!displays.containsKey(displayId) || n == 0 || n > MAX_POINTERS || toolTypes.size != n || xs.size != n ||
            ys.size != n
        ) {
            return countDrop()
        }
        val props = Array(n) {
            MotionEvent.PointerProperties().apply {
                id = pointerIds[it]
                toolType = toolTypes[it]
            }
        }
        val coords = Array(n) {
            MotionEvent.PointerCoords().apply {
                x = xs[it]
                y = ys[it]
                pressure = 1f
                size = 1f
            }
        }
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(now, now, action, n, props, coords, 0, buttonState, 1f, 1f, 0, 0, source, 0)
        try {
            record(ShellPlatform.inject(event, displayId), receivedAtNanos)
        } finally {
            event.recycle()
        }
    }

    override fun injectScroll(
        displayId: Int,
        x: Float,
        y: Float,
        vScroll: Float,
        hScroll: Float,
        receivedAtNanos: Long,
    ) {
        if (!displays.containsKey(displayId)) return countDrop()
        val props = arrayOf(MotionEvent.PointerProperties().apply { toolType = MotionEvent.TOOL_TYPE_MOUSE })
        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                this.x = x
                this.y = y
                setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
                setAxisValue(MotionEvent.AXIS_HSCROLL, hScroll)
            },
        )
        val now = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(
            now, now, MotionEvent.ACTION_SCROLL, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_MOUSE, 0,
        )
        try {
            record(ShellPlatform.inject(event, displayId), receivedAtNanos)
        } finally {
            event.recycle()
        }
    }

    override fun stats(): LongArray {
        val (count, p95, max) = latency.drain().toList()
        return longArrayOf(count, dropped.getAndSet(0), p95, max)
    }

    override fun setListener(listener: IRiriHelperListener?) {
        val binder = listener?.asBinder() ?: return
        // The app went away (crash, update, force stop): nothing should survive it.
        binder.linkToDeath({ exit() }, 0)
    }

    override fun exit() {
        displays.values.forEach { runCatching { it.release() } }
        displays.clear()
        onExit()
    }

    private fun record(injected: Boolean, receivedAtNanos: Long) {
        if (!injected) return countDrop()
        // System.nanoTime is CLOCK_MONOTONIC in both processes.
        if (receivedAtNanos > 0) latency.add((System.nanoTime() - receivedAtNanos) / 1000)
    }

    private fun countDrop() {
        dropped.incrementAndGet()
    }

    companion object {
        const val VERSION = 1
        private const val TAG = "Riri/Helper"
        private const val MAX_POINTERS = 10
        private const val LAUNCH_TIMEOUT_S = 5L

        private val COMPONENT = Regex("^[A-Za-z][A-Za-z0-9_.]*/[A-Za-z0-9_.$]+$")

        /** IBinder.INTERFACE_TRANSACTION ('_NTF'): allowed so the descriptor can be queried. */
        private const val INTERFACE_TRANSACTION = IBinder.INTERFACE_TRANSACTION
    }
}
