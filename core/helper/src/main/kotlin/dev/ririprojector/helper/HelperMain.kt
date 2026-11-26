package dev.ririprojector.helper

import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import kotlin.system.exitProcess

/**
 * Entry point of the shell helper, started by [HelperCommand] with `app_process` (ADR-014):
 * `HelperMain <package> <userId>` hands a [HelperService] to the app and serves it until the app
 * dies, calls `exit()`, or 10 minutes pass with no display and no call.
 * `HelperMain --selftest` (over USB adb, no pairing) checks display creation and prints the result.
 */
object HelperMain {
    private const val TAG = "Riri/Helper"
    private const val IDLE_LIMIT_MS = 10 * 60 * 1000L
    private const val IDLE_CHECK_MS = 30 * 1000L
    const val METHOD_SEND_BINDER = "sendBinder"
    const val EXTRA_BINDER = "dev.ririprojector.helper.BINDER"

    @JvmStatic
    fun main(args: Array<String>) {
        if (Process.myUid() != SHELL_UID && Process.myUid() != 0) {
            System.err.println("riri_helper must run as shell")
            exitProcess(3)
        }
        if (args.firstOrNull() == "--selftest") exitProcess(selfTest())
        val packageName = args.getOrNull(0) ?: exitProcess(2)
        val userId = args.getOrNull(1)?.toIntOrNull() ?: exitProcess(2)

        Looper.prepareMainLooper()
        val apk = System.getenv("CLASSPATH").orEmpty()
        // ActivityThread and DisplayManager need a Looper: build them here, not on a binder thread.
        runCatching {
            ShellPlatform.context
            ShellPlatform.displayManager
        }.onFailure {
            Log.e(TAG, "platform init failed", it)
            exitProcess(6)
        }
        val appUid = runCatching { ShellPlatform.packageUid(packageName, userId) }
            .getOrElse {
                Log.e(TAG, "package $packageName not found")
                exitProcess(4)
            }
        val service = HelperService(appUid, userId, apk) { exitProcess(0) }
        val extras = Bundle().apply { putBinder(EXTRA_BINDER, service) }
        val delivered = runCatching {
            ShellPlatform.callProvider("$packageName.helper", userId, METHOD_SEND_BINDER, extras) != null
        }.onFailure { Log.e(TAG, "binder handoff failed", it) }.getOrDefault(false)
        if (!delivered) exitProcess(5)
        Log.i(TAG, "helper v${HelperService.VERSION} ready for uid $appUid (API ${Build.VERSION.SDK_INT})")

        val handler = Handler(Looper.getMainLooper())
        handler.post(
            object : Runnable {
                override fun run() {
                    val idle = SystemClock.uptimeMillis() - service.lastActivityAt
                    if (!service.hasDisplays && idle > IDLE_LIMIT_MS) {
                        Log.i(TAG, "idle, exiting")
                        exitProcess(0)
                    }
                    handler.postDelayed(this, IDLE_CHECK_MS)
                }
            },
        )
        Looper.loop()
    }

    /** Creates and releases a 1280×720 display; prints `OK` or the failure. Returns the exit code. */
    private fun selfTest(): Int {
        Looper.prepareMainLooper()
        val reader = ImageReader.newInstance(1280, 720, PixelFormat.RGBA_8888, 2)
        return try {
            val flags = SecondDisplay.flags(Build.VERSION.SDK_INT)
            val display = ShellPlatform.displayManager.createVirtualDisplay(
                "RiriProjector self-test",
                1280,
                720,
                SecondDisplay.densityDpi(1280, 720),
                reader.surface,
                flags,
            )
            val id = display.display.displayId
            display.release()
            println("OK API ${Build.VERSION.SDK_INT} display $id flags 0x${Integer.toHexString(flags)}")
            0
        } catch (t: Throwable) {
            var root = t
            while (root.cause != null) root = root.cause!!
            println("FAIL API ${Build.VERSION.SDK_INT}: $root")
            root.stackTrace.take(8).forEach { println("    at $it") }
            1
        } finally {
            reader.close()
        }
    }

    private const val SHELL_UID = 2000
}
