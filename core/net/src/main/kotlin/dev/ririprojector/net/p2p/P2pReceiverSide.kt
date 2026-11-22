package dev.ririprojector.net.p2p

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.net.DhcpInfo
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.withContext
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
    private val requeryTick: Runnable = Runnable { requery() }
    private var request: WifiP2pDnsSdServiceRequest? = null
    private var closed = false
    private val reopen: Runnable =
        Runnable {
            when {
                closed -> Unit
                start() -> if (idle) discover()
                else -> main.postDelayed(reopen, REOPEN_RETRY_MS) // e.g. Wi-Fi still off after standby
            }
        }

    /** False when the device has no Wi-Fi Direct; the receiver then works over the router only. */
    @SuppressLint("MissingPermission") // API 29+ callers hold the location / nearby permission
    fun start(): Boolean {
        if (ch != null) return true
        P2pLog.init(context)
        // Built first: an invalid record must fail before anything is registered.
        val service =
            WifiP2pDnsSdServiceInfo.newInstance(
                P2pTxt.RECEIVER_INSTANCE,
                P2pTxt.SERVICE_TYPE,
                P2pTxt.encode(P2pReceiverRecord(P2pTxt.fitName(name), port)),
            )
        var opened: P2pChannel? = null
        val c = P2pChannel.open(context) { onChannelLost(opened) } ?: return false
        opened = c
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
        c.manager.addLocalService(c.channel, service, logging("addLocalService"))
        // All services, as the spike verified: a request by type only asks for the PTR record, so the
        // TXT record that carries the offer never arrives.
        val first = WifiP2pDnsSdServiceRequest.newInstance()
        request = first
        c.manager.addServiceRequest(c.channel, first, logging("addServiceRequest"))
        P2pLog.i("agent: started as $name on port $port")
        return true
    }

    /** Discovery on (no session) or off (a session streams, in either mode). */
    fun setIdle(value: Boolean) {
        if (idle == value) return
        P2pLog.i("agent: idle=$value")
        idle = value
        main.removeCallbacks(rearm)
        main.removeCallbacks(requeryTick)
        if (value) discover() else ch?.quietly { m, c -> m.stopPeerDiscovery(c, null) }
    }

    /** The offer for [ssid] already arrived another way (over the router): ignore its P2P copy. */
    fun markOffered(ssid: String) {
        lastOfferSsid = ssid
    }

    /** Accept the same offer again (its join failed and the sender may still be waiting). */
    fun forgetOffer() {
        lastOfferSsid = null
    }

    /**
     * Service discovery fetches the sender's offer; its find also keeps this device visible to the
     * sender's peer discovery, which is how the tablet lists receivers. Rounds stay long: a new round
     * starts with a full channel scan, during which the TV cannot be found.
     */
    @SuppressLint("MissingPermission")
    private fun discover() {
        val c = ch ?: return
        if (!idle) return
        c.manager.discoverServices(
            c.channel,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit

                override fun onFailure(reason: Int) {
                    // BUSY right after leaving a group: try again soon rather than in REARM_MS.
                    P2pLog.w("agent: discoverServices failed ($reason), retrying")
                    if (reason == NO_SERVICE_REQUESTS) restoreRequest()
                    main.removeCallbacks(rearm)
                    if (idle) main.postDelayed(rearm, RETRY_MS)
                }
            },
        )
        main.postDelayed(rearm, REARM_MS)
        main.removeCallbacks(requeryTick)
        main.postDelayed(requeryTick, REQUERY_MS)
    }

    /**
     * The framework queries each peer only once per discovery round, and the sender publishes its offer
     * only after the user picks this device. Replacing the request makes the framework send a fresh query
     * to the peers it already knows, without the channel scan a new round would cost. Measured: with it
     * every offer arrived (4/4), without it none did within 60 s (0/3).
     */
    private fun requery() {
        val c = ch ?: return
        if (!idle) return
        val next = WifiP2pDnsSdServiceRequest.newInstance()
        val old = request
        // The old request goes only once the new one is in: a failed add must never leave none, which
        // would make every later discovery fail with NO_SERVICE_REQUESTS.
        c.quietly { m, cc ->
            m.addServiceRequest(
                cc,
                next,
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (ch !== c) return
                        request = next
                        if (old != null) c.quietly { m2, c2 -> m2.removeServiceRequest(c2, old, null) }
                    }

                    override fun onFailure(reason: Int) = Unit // keep the old request; next tick retries
                },
            )
        }
        main.postDelayed(requeryTick, REQUERY_MS)
    }

    /** Recovers from a request the framework no longer has (NO_SERVICE_REQUESTS). */
    private fun restoreRequest() {
        val c = ch ?: return
        val fresh = WifiP2pDnsSdServiceRequest.newInstance()
        c.quietly { m, cc -> m.clearServiceRequests(cc, null) }
        c.quietly { m, cc -> m.addServiceRequest(cc, fresh, logging("addServiceRequest (restored)")) }
        request = fresh
    }

    /**
     * The framework dropped our channel (Wi-Fi restarted, the TV woke from standby): its services and
     * requests are gone, so reopen it after a short pause instead of staying silent.
     */
    private fun onChannelLost(lostChannel: P2pChannel?) {
        if (lostChannel == null || ch !== lostChannel) return // our own close(), or an old channel
        P2pLog.w("agent: P2P channel lost, reopening")
        ch = null
        main.removeCallbacks(rearm)
        main.removeCallbacks(requeryTick)
        request = null
        context.unregisterQuietly(receiver)
        receiver = null
        lostChannel.close()
        main.postDelayed(reopen, REOPEN_MS)
    }

    override fun close() {
        idle = false
        main.removeCallbacks(rearm)
        main.removeCallbacks(requeryTick)
        closed = true
        main.removeCallbacks(reopen)
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
        const val RETRY_MS = 2_000L
        const val REQUERY_MS = 2_000L
        const val NO_SERVICE_REQUESTS = 3 // WifiP2pManager.NO_SERVICE_REQUESTS
        const val REOPEN_MS = 2_000L
        const val REOPEN_RETRY_MS = 10_000L
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

    /**
     * API 28: removes saved networks of ours left behind when the TV lost power or the process died
     * mid-session (each holds a passphrase). Runs before each join, off the main thread (Wi-Fi
     * service IPC), when no station of ours is joined.
     */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission") // API 28: an app may list and remove the networks it added
    private suspend fun removeStaleNetworks(wifi: WifiManager) = withContext(Dispatchers.IO) {
        runCatching {
            wifi.configuredNetworks.orEmpty().filter { P2pPolicy.isOwnSsid(it.SSID) }.forEach {
                // Refused for networks another install of the app added.
                if (wifi.removeNetwork(it.networkId)) P2pLog.i("station: removed stale ${it.SSID}")
            }
        }
    }

    @Suppress("DEPRECATION") // the only dialog-free way on API 28
    private suspend fun joinAsStation(context: Context, offer: P2pOfferRecord, timeoutMs: Long): P2pStation {
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
            ?: throw P2pFailure.Unsupported("station")
        removeStaleNetworks(wifi)
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
            val dhcp =
                withTimeout(timeoutMs) {
                    var ready: DhcpInfo? = null
                    while (ready == null) {
                        val info = wifi.connectionInfo
                        val d = wifi.dhcpInfo // one snapshot: it can be null while associating
                        if (info?.networkId == id && info.supplicantState == SupplicantState.COMPLETED &&
                            d != null && d.ipAddress != 0
                        ) {
                            ready = d
                        } else {
                            delay(POLL_MS)
                        }
                    }
                    ready
                }
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
            // Only the sender's group we joined: never another app's group.
            if (joined.value != null) ch.quietly { m, c -> m.removeGroup(c, null) }
            context.unregisterQuietly(receiver)
            ch.close()
        }

        try {
            receiver =
                context.registerP2p(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) { intent ->
                    val info = intent.parcelable<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    val g = intent.parcelable<WifiP2pGroup>(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                    val owner = info?.groupOwnerAddress?.hostAddress
                    // The offered group only: the first (sticky) broadcast may describe an older one.
                    if (info?.groupFormed == true && !info.isGroupOwner && owner != null && g?.`interface` != null &&
                        g.networkName == offer.ssid
                    ) {
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
