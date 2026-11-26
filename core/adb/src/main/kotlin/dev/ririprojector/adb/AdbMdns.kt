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

        override fun onServiceFound(info: NsdServiceInfo) = enqueue(info)

        override fun onServiceLost(info: NsdServiceInfo) {
            if (info.serviceName == serviceName) onPort(-1)
        }
    }

    // resolveService allows one resolve at a time before API 34 (FAILURE_ALREADY_ACTIVE): other devices
    // on the LAN with Wireless debugging on would otherwise crowd out our own. Serialize them.
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    @Synchronized
    private fun enqueue(info: NsdServiceInfo) {
        if (!running || pending.any { it.serviceName == info.serviceName }) return
        pending.addLast(info)
        if (!resolving) resolveNext()
    }

    @Synchronized
    private fun resolveNext() {
        val next = pending.removeFirstOrNull()
        resolving = next != null && running
        if (!resolving) return pending.clear()
        @Suppress("DEPRECATION") // resolveService is the one-shot path on every supported API level.
        runCatching { nsd.resolveService(next, resolver) }.onFailure { resolveNext() }
    }

    private val resolver = object : NsdManager.ResolveListener {
        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
            // Another resolve of this process (the other AdbMdns, receiver discovery) is in flight.
            if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE && retries++ < MAX_RETRIES) {
                synchronized(this@AdbMdns) { pending.addFirst(info) }
                Thread.sleep(RETRY_MS)
            } else {
                retries = 0
            }
            resolveNext()
        }

        override fun onServiceResolved(info: NsdServiceInfo) {
            retries = 0
            handle(info)
            resolveNext()
        }
    }
    private var retries = 0

    private fun handle(info: NsdServiceInfo) {
        @Suppress("DEPRECATION")
        val address = info.host?.hostAddress ?: return
        if (running && isOwnAddress(address)) {
            serviceName = info.serviceName
            onPort(info.port)
        }
    }

    @Synchronized
    fun start() {
        if (running) return
        running = true
        nsd.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discovery)
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        pending.clear()
        runCatching { nsd.stopServiceDiscovery(discovery) }
    }

    private fun isOwnAddress(address: String): Boolean = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .any { it.hostAddress == address }
    }.getOrDefault(false)

    private companion object {
        const val TAG = "Riri/AdbMdns"
        const val RETRY_MS = 100L
        const val MAX_RETRIES = 20
    }
}
