package dev.ririprojector.net.p2p

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.net.wifi.ScanResult
import android.net.wifi.WifiAvailableChannel
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.SystemClock
import dev.ririprojector.net.Handshake
import dev.ririprojector.net.HandshakeException
import dev.ririprojector.net.ReceiverEndpoint
import dev.ririprojector.net.SenderClient
import dev.ririprojector.net.WifiBand
import dev.ririprojector.protocol.ControlMessage
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A receiver found over Wi-Fi Direct: [deviceAddress] is its P2P device address. [lanHost] is its address on the router when the sender also found it
 * there (same name over NSD): the offer then goes over the router too (ADR-011 fast path).
 */
data class P2pReceiver(val name: String, val deviceAddress: String, val port: Int, val lanHost: String? = null)

/**
 * Sender side, discovery (ADR-011): lists nearby Wi-Fi Direct devices found by plain peer
 * discovery. The tablet's P2P DNS-SD queries get no answer from a Fire TV that is not a group owner
 * (seen on Fire OS 7), while peer discovery finds it within seconds; whether the picked device runs
 * the receiver shows when it takes the offer. Collect only while the list is on screen; cancelling
 * stops the discovery (it scans off channel). Needs `NEARBY_WIFI_DEVICES` (API 33+).
 */
class P2pReceiverBrowser(private val context: Context) {
    @SuppressLint("MissingPermission") // the caller requests NEARBY_WIFI_DEVICES first
    fun receivers(): Flow<List<P2pReceiver>> = callbackFlow {
        P2pLog.init(context)
        val ch = P2pChannel.open(context) { close(P2pFailure.LinkLost("browse")) }
        if (ch == null) {
            close(P2pFailure.Unsupported("browse"))
            return@callbackFlow
        }
        var peers = emptyList<P2pReceiver>()
        var last: List<P2pReceiver>? = null

        fun publish() {
            if (peers != last) {
                P2pLog.i("browse: peers ${peers.map { it.name }}")
                last = peers
                trySend(peers)
            }
        }
        val receiver =
            context.registerP2p(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION) {
                ch.quietly { m, c ->
                    m.requestPeers(c) { list ->
                        peers =
                            list?.deviceList.orEmpty()
                                .filter { it.deviceName.isNotBlank() }
                                .map {
                                    P2pReceiver(
                                        it.deviceName,
                                        it.deviceAddress.lowercase(),
                                        ReceiverEndpoint.DEFAULT_PORT,
                                    )
                                }
                                .sortedBy { it.name }
                        publish()
                    }
                }
            }
        publish()
        launch {
            try {
                while (true) {
                    // A discovery round ends by itself; re-arm it while the list is collected.
                    ch.await("discoverPeers") { m, c, l -> m.discoverPeers(c, l) }
                    delay(REARM_MS)
                }
            } catch (e: P2pFailure) {
                P2pLog.w("browse: ${e.message}")
                close(e)
            }
        }
        awaitClose {
            ch.quietly { m, c -> m.stopPeerDiscovery(c, null) }
            context.unregisterQuietly(receiver)
            ch.close()
        }
    }

    private companion object {
        const val REARM_MS = 15_000L
    }
}

/**
 * The sender's own group for one session (ADR-011). Closing it removes the group; [lost] fires once
 * when the group or its receiver goes away. [frequencyMhz] is the group's channel.
 */
class P2pLink internal constructor(
    val endpoint: ReceiverEndpoint,
    val frequencyMhz: Int,
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
 * Sender side, link (ADR-011, topology B′): creates a group on the tablet's own 5 GHz channel,
 * offers its credentials to the picked receiver over P2P DNS-SD, and completes once that receiver
 * has joined and has an IPv4 address. API 35+ (`WifiP2pDevice.getIpAddress`).
 */
@TargetApi(35) // callers check API 35+ (no androidx.annotation in this module)
@SuppressLint("UseRequiresApi")
class P2pGroupOwner(private val context: Context) {
    @SuppressLint("MissingPermission") // the caller requests NEARBY_WIFI_DEVICES first
    suspend fun open(target: P2pReceiver, senderName: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): P2pLink {
        val lost = MutableStateFlow(false)
        val group = MutableStateFlow<WifiP2pGroup?>(null)
        val discovering = MutableStateFlow(false)
        var created = false
        val ssid = P2pPolicy.newSsid()
        val psk = P2pPolicy.newPassphrase()
        var established = false
        P2pLog.init(context)
        val ch = P2pChannel.open(context) { lost.value = true } ?: throw P2pFailure.Unsupported("group")
        var receiver: BroadcastReceiver? = null
        val t0 = SystemClock.elapsedRealtime()
        fun phase(name: String) = P2pLog.i("group: $name after ${SystemClock.elapsedRealtime() - t0} ms")

        fun release() {
            ch.quietly { m, c -> m.clearLocalServices(c, null) }
            ch.quietly { m, c -> m.stopPeerDiscovery(c, null) }
            // Only the group this call created: never another app's (Quick Share, Smart View).
            if (created) ch.quietly { m, c -> m.removeGroup(c, null) }
            context.unregisterQuietly(receiver)
            ch.close()
        }

        try {
            receiver =
                context.registerP2p(
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION,
                    WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION,
                ) { intent ->
                    if (intent.action == WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION) {
                        discovering.value =
                            intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, 0) ==
                            WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                        return@registerP2p
                    }
                    val info = intent.parcelable<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                    val g = intent.parcelable<WifiP2pGroup>(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                    // Ours only: the first (sticky) broadcast may describe an older group.
                    group.value = g?.takeIf { info?.groupFormed == true && it.isGroupOwner && it.networkName == ssid }
                    // After the receiver joined: the group went away or the receiver left it.
                    if (established && (info?.groupFormed != true || g == null || g.clientList.isEmpty())) {
                        lost.value = true
                    }
                }
            return withTimeout(timeoutMs) {
                // A group of ours left over by a crash would make createGroup fail; another app's group
                // is not ours to remove.
                val existing = ch.groupInfo()
                if (existing != null) {
                    if (!P2pPolicy.isOwnSsid(existing.networkName)) throw P2pFailure.Busy("group")
                    runCatching { ch.await("removeGroup") { m, c, l -> m.removeGroup(c, l) } }
                }
                fun config(channel: GroupChannel) =
                    WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(psk).apply {
                        when (channel) {
                            is GroupChannel.Frequency -> setGroupOperatingFrequency(channel.mhz)
                            GroupChannel.Band5Ghz -> setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                            GroupChannel.Band2Ghz -> setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                        }
                    }.build()
                val station = stationFrequency()
                val crossBand = crossBandGo()
                val channel = P2pPolicy.channelFor(station, crossBand)
                // Remembered on timeout: a cross-band group nobody joined means the signals lied.
                crossBandFromStation24 = crossBand && station != null && WifiBand.of(station) == WifiBand.GHZ_2_4
                P2pLog.i("group: station ${station ?: "none"} MHz, crossBand=$crossBand -> $channel")
                // Published before the group exists (its name and passphrase are already chosen): the TV's
                // discovery can fetch it while the group forms, and the join waits for the network.
                val offer = P2pOfferRecord(senderName, ssid, psk, target.deviceAddress)
                val service =
                    WifiP2pDnsSdServiceInfo.newInstance(
                        P2pTxt.OFFER_INSTANCE,
                        P2pTxt.SERVICE_TYPE,
                        P2pTxt.encode(offer),
                    )
                ch.await("addLocalService") { m, c, l -> m.addLocalService(c, service, l) }
                // Set before the call: a stop while it is in flight must still remove the group it forms
                // (no foreign group exists, checked above).
                created = true
                try {
                    ch.await("createGroup") { m, c, l -> m.createGroup(c, config(channel), l) }
                } catch (e: P2pFailure) {
                    // Some drivers refuse a group owner on the station's channel (DFS): same band, any channel.
                    if (channel !is GroupChannel.Frequency || e is P2pFailure.Busy) throw e
                    val fallback = P2pPolicy.fallbackFor(channel)
                    P2pLog.w(
                        "group: ${channel.mhz} MHz refused (${e.message}), trying $fallback: " +
                            "different channel, time slicing expected",
                    )
                    ch.await("createGroup") { m, c, l -> m.createGroup(c, config(fallback), l) }
                }
                val formed = group.filter { it != null }.first()!!
                phase("formed on ${formed.frequency} MHz")
                // Fast path: over the router the offer arrives in tens of ms, while P2P discovery takes 4–24 s
                // on a Fire TV. After formation, so the receiver finds the network on its first scan.
                val lanOffer =
                    target.lanHost?.let { host ->
                        launch(Dispatchers.IO) {
                            val endpoint = ReceiverEndpoint(target.name, host, target.port)
                            // The receiver's 2.4 GHz router link stalls now and then: a few short attempts.
                            for (attempt in 1..LAN_ATTEMPTS) {
                                try {
                                    SenderClient.connect(endpoint, LAN_CONNECT_MS).use {
                                        Handshake.offerP2p(
                                            it,
                                            ControlMessage.P2pOffer(ssid, psk, senderName),
                                            LAN_HANDSHAKE_MS,
                                        )
                                    }
                                    phase("offer sent over the router to $host")
                                    break
                                } catch (e: IOException) {
                                    // BUSY (already streaming) or another version: retrying cannot help.
                                    val final = (e as? HandshakeException)?.bye != null || attempt == LAN_ATTEMPTS
                                    P2pLog.w("group: router offer to $host failed (${e.message}), attempt $attempt")
                                    if (final) break
                                }
                            }
                        }
                    }
                // Listen state, so the receiver's service discovery reaches the offer.
                // Restarted only when it stops (the browser's global stopPeerDiscovery, another app, the
                // framework's own time limit): every restart begins with a channel scan, during which
                // the receiver's queries cannot reach this device.
                val listening =
                    launch {
                        while (true) {
                            runCatching { ch.await("discoverPeers") { m, c, l -> m.discoverPeers(c, l) } }
                            withTimeoutOrNull(DISCOVERY_STARTED_WAIT_MS) { discovering.first { it } }
                            withTimeoutOrNull(LISTEN_REARM_MS) { discovering.first { !it } }
                        }
                    }
                var clientIp: String? = null
                while (clientIp == null) {
                    delay(POLL_MS)
                    clientIp = ch.groupInfo()?.clientList?.firstNotNullOfOrNull { it.ipAddress?.hostAddress }
                }
                listening.cancel()
                lanOffer?.cancel() // bounded by its socket timeouts either way
                phase("receiver joined at $clientIp")
                // Stop scanning and announcing: both cost airtime while streaming.
                ch.quietly { m, c -> m.stopPeerDiscovery(c, null) }
                ch.quietly { m, c -> m.clearLocalServices(c, null) }
                established = true
                P2pLink(ReceiverEndpoint(target.name, clientIp, target.port), formed.frequency, lost, ::release)
            }
        } catch (e: TimeoutCancellationException) {
            release()
            if (crossBandFromStation24 && !established) {
                // Cross-band 5 GHz group with a 2.4 GHz station and nobody joined: the radio
                // time-slices after all (#32). Later attempts in this process share the station channel.
                dbsUnreliable = true
                P2pLog.w("group: nobody joined the cross-band group; sharing the station channel from now on")
            }
            throw P2pFailure.Timeout("group")
        } catch (e: CancellationException) {
            release()
            throw e
        } catch (e: Exception) {
            release()
            throw e
        }
    }

    @Suppress("DEPRECATION") // frequency only: no location-gated fields are read
    private fun stationFrequency(): Int? =
        context.applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo?.frequency?.takeIf {
            it > 0
        }

    private var crossBandFromStation24 = false

    /**
     * Whether this radio can run a 5 GHz group next to its station link on another band (#32): chip
     * DBS, and 5 GHz group-owner channels usable under the current concurrency.
     */
    @SuppressLint("MissingPermission") // NEARBY_WIFI_DEVICES is granted before any group is created
    private fun crossBandGo(): Boolean {
        if (dbsUnreliable) return false
        val wifi = context.applicationContext.getSystemService(WifiManager::class.java) ?: return false
        if (!wifi.isDualBandSimultaneousSupported) return false
        return try {
            wifi.getUsableChannels(ScanResult.WIFI_BAND_5_GHZ, WifiAvailableChannel.OP_MODE_WIFI_DIRECT_GO)
                .isNotEmpty()
        } catch (e: UnsupportedOperationException) {
            true // the DBS flag alone decides
        } catch (e: SecurityException) {
            true
        }
    }

    companion object {
        /** Set once a cross-band group got no join although the radio claimed DBS (#32). */
        @Volatile
        private var dbsUnreliable = false

        /** Receiver discovery of the offer (~4 s), its join (~6 s) and DHCP, with margin. */
        const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val POLL_MS = 250L
        private const val LISTEN_REARM_MS = 60_000L
        private const val DISCOVERY_STARTED_WAIT_MS = 2_000L
        private const val LAN_CONNECT_MS = 1_500
        private const val LAN_HANDSHAKE_MS = 2_000
        private const val LAN_ATTEMPTS = 3
    }
}
