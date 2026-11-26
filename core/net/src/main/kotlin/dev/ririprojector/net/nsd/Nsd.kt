package dev.ririprojector.net.nsd

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.ririprojector.net.ReceiverEndpoint
import dev.ririprojector.protocol.ProtocolInfo
import java.io.Closeable
import java.util.ArrayDeque
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate

private const val TAG = "Riri/Nsd"
private const val RESOLVE_RETRY_MS = 150L

/** Discovery could not start; [errorCode] is an `NsdManager.FAILURE_*` value. */
class NsdDiscoveryException(val errorCode: Int) : java.io.IOException("NSD discovery failed: $errorCode")

/** NSD wants the type without the trailing dot. */
private val SERVICE_TYPE = ProtocolInfo.SERVICE_TYPE

/** Advertises this receiver on the LAN (ADR-006). Close to unregister. */
class NsdPublisher(context: Context) {
    private val nsd = context.applicationContext.getSystemService(NsdManager::class.java)

    fun publish(name: String, port: Int): Closeable {
        val info =
            NsdServiceInfo().apply {
                serviceName = name
                serviceType = SERVICE_TYPE
                setPort(port)
                setAttribute("v", ProtocolInfo.PROTOCOL_VERSION.toString())
                setAttribute("name", name)
            }
        val listener =
            object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) =
                    Log.i(TAG, "registered ${info.serviceName}").let { }

                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) =
                    Log.w(TAG, "registration failed: $errorCode").let { }

                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit

                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            }
        nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        return Closeable { runCatching { nsd.unregisterService(listener) } }
    }
}

/**
 * Discovers receivers. Emits the full current list on every change. Holds a Wi-Fi multicast lock
 * while collected (some devices drop mDNS otherwise) and resolves one service at a time
 * (`resolveService` rejects concurrent resolves before API 34).
 */
class NsdBrowser(context: Context) {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(NsdManager::class.java)

    fun receivers(): Flow<List<ReceiverEndpoint>> = callbackFlow {
        val found = LinkedHashMap<String, ReceiverEndpoint>()
        val pending = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        val lock =
            appContext.getSystemService(WifiManager::class.java)
                ?.createMulticastLock("riri-nsd")
                ?.apply {
                    setReferenceCounted(false)
                    acquire()
                }

        fun publishList() {
            trySend(synchronized(found) { found.values.toList() })
        }

        lateinit var resolveNext: () -> Unit
        val retry = Handler(Looper.getMainLooper())

        val resolveListener =
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    synchronized(pending) { resolving = false }
                    if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                        // Another resolve of this process (e.g. the second-screen setup's ADB discovery)
                        // is in flight: try again shortly instead of losing the receiver.
                        synchronized(pending) { pending.addLast(info) }
                        retry.postDelayed({ resolveNext() }, RESOLVE_RETRY_MS)
                        return
                    }
                    Log.w(TAG, "resolve failed for ${info.serviceName}: $errorCode")
                    resolveNext()
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    hostOf(info)?.let { host ->
                        val name = info.attributes["name"]?.let { String(it) } ?: info.serviceName
                        runCatching { ReceiverEndpoint(name, host, info.port) }.getOrNull()?.let {
                            synchronized(found) { found[info.serviceName] = it }
                            publishList()
                        }
                    }
                    synchronized(pending) { resolving = false }
                    resolveNext()
                }
            }

        resolveNext = {
            val next =
                synchronized(pending) {
                    if (resolving || pending.isEmpty()) null else pending.removeFirst().also { resolving = true }
                }
            if (next != null) {
                try {
                    @Suppress("DEPRECATION")
                    nsd.resolveService(next, resolveListener)
                } catch (e: RuntimeException) {
                    Log.w(TAG, "resolve rejected for ${next.serviceName}: ${e.message}")
                    synchronized(pending) { resolving = false }
                    resolveNext()
                }
            }
        }

        val discoveryListener =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(serviceType: String) = Unit

                override fun onDiscoveryStopped(serviceType: String) = Unit

                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    Log.w(TAG, "discovery failed: $errorCode")
                    close(NsdDiscoveryException(errorCode))
                }

                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

                override fun onServiceFound(info: NsdServiceInfo) {
                    synchronized(pending) { pending.addLast(info) }
                    resolveNext()
                }

                override fun onServiceLost(info: NsdServiceInfo) {
                    synchronized(pending) { pending.removeAll { it.serviceName == info.serviceName } }
                    synchronized(found) { found.remove(info.serviceName) }
                    publishList()
                }
            }

        publishList()
        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        awaitClose {
            retry.removeCallbacksAndMessages(null)
            runCatching { nsd.stopServiceDiscovery(discoveryListener) }
            runCatching { lock?.release() }
        }
    }.conflate()

    private fun hostOf(info: NsdServiceInfo): String? = if (Build.VERSION.SDK_INT >= 34) {
        info.hostAddresses.firstOrNull { it is java.net.Inet4Address }?.hostAddress
            ?: info.hostAddresses.firstOrNull()?.hostAddress
    } else {
        @Suppress("DEPRECATION")
        info.host?.hostAddress
    }
}
