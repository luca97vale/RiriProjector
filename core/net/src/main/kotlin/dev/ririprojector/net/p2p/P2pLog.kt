package dev.ririprojector.net.p2p

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wi-Fi Direct event log. Fire OS hides app log lines, so debuggable builds also append to
 * `files/p2p.log` (read with `run-as <package> cat files/p2p.log`). Control-path only, never per frame.
 */
internal object P2pLog {
    private const val MAX_BYTES = 256 * 1024L

    @Volatile private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        file = File(context.filesDir, "p2p.log")
    }

    fun i(message: String) {
        Log.i(TAG, message)
        append("I", message)
    }

    fun w(message: String) {
        Log.w(TAG, message)
        append("W", message)
    }

    @Synchronized
    private fun append(level: String, message: String) {
        val f = file ?: return
        runCatching {
            if (f.length() > MAX_BYTES) f.delete()
            val time = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date())
            f.appendText("$time $level $message\n")
        }
    }
}
