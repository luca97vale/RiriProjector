// Derived from Shizuku (https://github.com/RikkaApps/Shizuku, commit b844bc4, Apache-2.0),
// manager/src/main/java/moe/shizuku/manager/adb/AdbMdns.kt; modified for RiriProjector (callback
// API, own-address check only, no LiveData). See LICENSE-Shizuku.
package dev.ririprojector.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.net.NetworkInterface

/**
 * Finds this device's own Wireless-debugging port ([AdbProtocol.SERVICE_TLS_PAIRING] or
 * [AdbProtocol.SERVICE_TLS_CONNECT]) via NSD. Services of other devices on the LAN are ignored.
 * [onPort] gets the port, or -1 when the service goes away. Callbacks run on NSD's thread.
 */
class AdbMdns(context: Context, private val serviceType: String, private val onPort: (Int) -> Unit) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var running = false
    private var serviceName: String? = null

    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) = Unit

        override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
            Log.w(TAG, "discovery of $type failed: $errorCode")
        }

        override fun onDiscoveryStopped(type: String) = Unit

        override fun onStopDiscoveryFailed(type: String, errorCode: Int) = Unit

        override fun onServiceFound(info: NsdServiceInfo) {
            @Suppress("DEPRECATION") // resolveService is the API-28..33 path; fine for one-shot resolves.
            nsd.resolveService(info, resolver())
        }

        override fun onServiceLost(info: NsdServiceInfo) {
            if (info.serviceName == serviceName) onPort(-1)
        }
    }

    private fun resolver() = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) = Unit

        override fun onServiceResolved(info: NsdServiceInfo) {
            @Suppress("DEPRECATION")
            val address = info.host?.hostAddress ?: return
            if (running && isOwnAddress(address)) {
                serviceName = info.serviceName
                onPort(info.port)
            }
        }
    }

    fun start() {
        if (running) return
        running = true
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    fun stop() {
        if (!running) return
        running = false
        runCatching { nsd.stopServiceDiscovery(discovery) }
    }

    private fun isOwnAddress(address: String): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .any { it.hostAddress == address }
    }.getOrDefault(false)

    private companion object {
        const val TAG = "Riri/AdbMdns"
    }
}
