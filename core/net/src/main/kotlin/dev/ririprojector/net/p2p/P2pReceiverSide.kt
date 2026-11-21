package dev.ririprojector.net.p2p

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.net.wifi.SupplicantState
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.Closeable
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withTimeout

/**
 * Receiver side, announcement (ADR-011). Once [start]ed it announces this receiver over P2P DNS-SD.
 * While [setIdle] is true it also runs service discovery and hands offers addressed to this device
 * to [onOffer] (main thread). Discovery scans off channel, so it runs only while no session streams.
 * All methods are called from the main thread.
 */
class P2pReceiverAgent(
    private val context: Context,
    private val name: String,
    private val port: Int,
    private val onOffer: (P2pOfferRecord) -> Unit,
) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private var ch: P2pChannel? = null
    private var receiver: BroadcastReceiver? = null
    private var ownAddress: String? = null
    private var idle = false
    private var lastOfferSsid: String? = null
    private val rearm = Runnable { discover() }

    /** False when the device has no Wi-Fi Direct; the receiver then works over the router only. */
    @SuppressLint("MissingPermission") // API 29+ callers hold the location / nearby permission
    fun start(): Boolean {
        if (ch != null) return true
        P2pLog.init(context)
        val c = P2pChannel.open(context) { P2pLog.w("agent: P2P channel lost") } ?: return false
        ch = c
        receiver =
            context.registerP2p(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION) { intent ->
                // API 28 reports the real address here; later versions redact it (see below).
                intent.parcelable<WifiP2pDevice>(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)?.deviceAddress
                    ?.lowercase()?.takeIf { it != REDACTED }?.let { ownAddress = it }
            }
        if (Build.VERSION.SDK_INT >= 29) {
            c.quietly { m, cc ->
                m.requestDeviceInfo(cc) { d -> d?.deviceAddress?.lowercase()?.let { ownAddress = it } }
            }
        }
        c.manager.setDnsSdResponseListeners(
            c.channel,
            { _, _, _ -> },
            { _, txt, _ ->
                P2pLog.i("agent: record ${P2pTxt.decode(txt)} idle=$idle own=$ownAddress")
                val offer = P2pTxt.decode(txt) as? P2pOfferRecord ?: return@setDnsSdResponseListeners
                if (idle && offer.to == ownAddress && offer.ssid != lastOfferSsid) {
                    lastOfferSsid = offer.ssid
                    P2pLog.i("agent: offer from ${offer.name}")
                    onOffer(offer)
                }
            },
        )
        val service =
            WifiP2pDnsSdServiceInfo.newInstance(
                P2pTxt.RECEIVER_INSTANCE,
                P2pTxt.SERVICE_TYPE,
                P2pTxt.encode(P2pReceiverRecord(name, port)),
            )
        c.manager.addLocalService(c.channel, service, logging("addLocalService"))
        // All services, as the spike verified: a request by type only asks for the PTR record, so the
        // TXT record that carries the offer never arrives.
        c.manager.addServiceRequest(
            c.channel,
            WifiP2pDnsSdServiceRequest.newInstance(),
            logging("addServiceRequest"),
        )
        P2pLog.i("agent: started as $name on port $port")
        return true
    }

    /** Discovery on (no session) or off (a session streams, in either mode). */
    fun setIdle(value: Boolean) {
        if (idle == value) return
        P2pLog.i("agent: idle=$value")
        idle = value
        main.removeCallbacks(rearm)
        if (value) discover() else ch?.quietly { m, c -> m.stopPeerDiscovery(c, null) }
    }

    /** Accept the same offer again (its join failed and the sender may still be waiting). */
    fun forgetOffer() {
        lastOfferSsid = null
    }

    /**
     * Service discovery fetches the sender's offer; its find also keeps this device visible to the
     * sender's peer discovery, which is how the tablet lists receivers.
     */
    @SuppressLint("MissingPermission")
    private fun discover() {
        val c = ch ?: return
        if (!idle) return
        c.manager.discoverServices(c.channel, logging("discoverServices"))
        main.postDelayed(rearm, REARM_MS)
    }

    override fun close() {
        idle = false
        main.removeCallbacks(rearm)
        val c = ch ?: return
        ch = null
        c.quietly { m, cc -> m.stopPeerDiscovery(cc, null) }
        c.quietly { m, cc -> m.clearServiceRequests(cc, null) }
        c.quietly { m, cc -> m.clearLocalServices(cc, null) }
        context.unregisterQuietly(receiver)
        receiver = null
        c.close()
    }

    private fun logging(what: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() {
            P2pLog.i("agent: $what ok")
        }

        override fun onFailure(reason: Int) {
            P2pLog.w("agent: $what failed ($reason)")
        }
    }

    private companion object {
        const val REARM_MS = 15_000L
        const val REDACTED = "02:00:00:00:00:00"
    }
}

/**
 * The receiver's membership of a sender's group (ADR-011). Closing it leaves the group and puts
 * the device back on its usual network. [lost] fires once when the group goes away.
 */
class P2pStation internal constructor(
    /** The sender's address in the group: a session from it is a Wi-Fi Direct session. */
    val groupOwnerAddress: String,
    private val lostState: MutableStateFlow<Boolean>,
    private val release: () -> Unit,
) : Closeable {
    private val closed = AtomicBoolean(false)

    val lost: Flow<Unit> = lostState.filter { it }.map { }.take(1)

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}

/**
 * Joins a sender's group with the offered credentials, without any dialog. API 28 (Fire OS 7)
 * cannot join a P2P group with credentials, so it joins as a legacy Wi-Fi station and leaves its
 * router while mirroring; API 29+ joins as a P2P client and keeps its router link.
 */
object P2pStationJoiner {
    const val DEFAULT_TIMEOUT_MS = 15_000L
    private const val POLL_MS = 200L
    private const val WATCH_MS = 1_000L

    suspend fun join(context: Context, offer: P2pOfferRecord, timeoutMs: Long = DEFAULT_TIMEOUT_MS): P2pStation =
        if (Build.VERSION.SDK_INT >=
            29
        ) {
            joinAsClient(context, offer, timeoutMs)
        } else {
            joinAsStation(context, offer, timeoutMs)
        }

    @Suppress("DEPRECATION") // the only dialog-free way on API 28
    private suspend fun joinAsStation(context: Context, offer: P2pOfferRecord, timeoutMs: Long): P2pStation {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: throw P2pFailure.Unsupported("station")
        val config =
            WifiConfiguration().apply {
                SSID = "\"${offer.ssid}\""
                preSharedKey = "\"${offer.psk}\""
            }
        val id = wifi.addNetwork(config)
        if (id < 0) throw P2pFailure.Error("addNetwork", id)
        val main = Handler(Looper.getMainLooper())
        val lost = MutableStateFlow(false)
        val watch =
            object : Runnable {
                override fun run() {
                    if (wifi.connectionInfo?.networkId != id) {
                        lost.value = true
                    } else {
                        main.postDelayed(this, WATCH_MS)
                    }
                }
            }

        fun release() {
            main.removeCallbacks(watch)
            runCatching { wifi.removeNetwork(id) }
            runCatching { wifi.reconnect() }
            P2pLog.i("station: left ${offer.ssid}, back to the usual network")
        }

        try {
            if (!wifi.enableNetwork(id, true)) throw P2pFailure.Error("enableNetwork", -1)
            withTimeout(timeoutMs) {
                while (true) {
                    val info = wifi.connectionInfo
                    val dhcp = wifi.dhcpInfo
                    if (info?.networkId == id && info.supplicantState == SupplicantState.COMPLETED &&
                        dhcp?.ipAddress != 0
                    ) {
                        break
                    }
                    delay(POLL_MS)
                }
            }
            val dhcp = wifi.dhcpInfo
            val gateway = P2pPolicy.ipv4(if (dhcp.gateway != 0) dhcp.gateway else dhcp.serverAddress)
            P2pLog.i("station: joined ${offer.ssid}, group owner $gateway")
            main.postDelayed(watch, WATCH_MS)
            return P2pStation(gateway, lost, ::release)
        } catch (e: TimeoutCancellationException) {
            release()
            throw P2pFailure.Timeout("station")
        } catch (e: CancellationException) {
            release()
            throw e
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    @TargetApi(29)
    @SuppressLint("UseRequiresApi", "MissingPermission") // the receiver requests location / nearby first
    private suspend fun joinAsClient(context: Context, offer: P2pOfferRecord, timeoutMs: Long): P2pStation {
        val lost = MutableStateFlow(false)
        val joined = MutableStateFlow<Pair<String, String>?>(null) // group owner address, interface
        var established = false
        val ch = P2pChannel.open(context) { lost.value = true } ?: throw P2pFailure.Unsupported("client")
        var receiver: BroadcastReceiver? = null

        fun release() {
            ch.quietly { m, c -> m.cancelConnect(c, null) }
            ch.quietly { m, c -> m.removeGroup(c, null) }
            context.unregisterQuietly(receiver)
            ch.close()
        }

        try {
            receiver =
                context.registerP2p(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) { intent ->
                    val info = intent.parcelable<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    val g = intent.parcelable<WifiP2pGroup>(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                    val owner = info?.groupOwnerAddress?.hostAddress
                    if (info?.groupFormed == true && !info.isGroupOwner && owner != null && g?.`interface` != null) {
                        joined.value = owner to g.`interface`
                    } else if (established) {
                        lost.value = true
                    }
                }
            val config = WifiP2pConfig.Builder().setNetworkName(offer.ssid).setPassphrase(offer.psk).build()
            return withTimeout(timeoutMs) {
                ch.await("connect") { m, c, l -> m.connect(c, config, l) }
                val (owner, iface) = joined.filter { it != null }.first()!!
                // groupFormed can precede DHCP: wait for our own address before reporting the link.
                while (NetworkInterface.getByName(iface)?.inetAddresses?.toList()?.none { it is Inet4Address } !=
                    false
                ) {
                    delay(POLL_MS)
                }
                established = true
                P2pLog.i("client: joined ${offer.ssid}, group owner $owner")
                P2pStation(owner, lost, ::release)
            }
        } catch (e: TimeoutCancellationException) {
            release()
            throw P2pFailure.Timeout("client")
        } catch (e: CancellationException) {
            release()
            throw e
        } catch (e: Exception) {
            release()
            throw e
        }
    }
}
