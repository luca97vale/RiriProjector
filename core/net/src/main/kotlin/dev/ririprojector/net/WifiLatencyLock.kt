package dev.ririprojector.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import java.io.Closeable

/**
 * Keeps Wi-Fi out of power save while streaming (ADR-002). In power save the radio sleeps between
 * beacons and the access point buffers our packets, so they arrive in clumps of up to ~100 ms;
 * audio then underruns no matter how the jitter buffer is tuned.
 *
 * API 29+: `WIFI_MODE_FULL_LOW_LATENCY` (honoured while the app is in the foreground with the
 * screen on, e.g. the receiver); below (API 28, e.g. Fire OS 7): `WIFI_MODE_FULL_HIGH_PERF`.
 * Best effort: a device that refuses the lock just streams as before. Close to release.
 */
class WifiLatencyLock private constructor(private val lock: WifiManager.WifiLock?) : Closeable {
    override fun close() {
        runCatching { if (lock?.isHeld == true) lock.release() }
    }

    companion object {
        private const val TAG = "Riri/WifiLock"

        @SuppressLint("WakelockTimeout") // held exactly as long as the stream, released in close()
        @Suppress("DEPRECATION") // HIGH_PERF is the only option below API 29
        fun acquire(context: Context, tag: String): WifiLatencyLock {
            val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            val mode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY
                } else {
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF
                }
            val lock =
                runCatching {
                    wifi?.createWifiLock(mode, tag)?.apply {
                        setReferenceCounted(false)
                        acquire()
                    }
                }.onFailure { Log.w(TAG, "cannot hold a Wi-Fi lock: ${it.message}") }.getOrNull()
            return WifiLatencyLock(lock)
        }
    }
}
