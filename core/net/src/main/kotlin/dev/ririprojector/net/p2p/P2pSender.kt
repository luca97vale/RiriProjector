package dev.ririprojector.net.p2p

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.content.BroadcastReceiver
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.SystemClock
import dev.ririprojector.net.ReceiverEndpoint
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
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

/** A receiver found over Wi-Fi Direct: [deviceAddress] is its P2P device address. */
data class P2pReceiver(val name: String, val deviceAddress: String, val port: Int)

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
                context.registerP2p(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) { intent ->
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
                        }
                    }.build()
                val channel = P2pPolicy.channelFor(stationFrequency())
                // Set before the call: a stop while it is in flight must still remove the group it forms
                // (no foreign group exists, checked above).
                created = true
                try {
                    ch.await("createGroup") { m, c, l -> m.createGroup(c, config(channel), l) }
                } catch (e: P2pFailure) {
                    // Some drivers refuse a group owner on the station's channel (DFS): any 5 GHz one.
                    if (channel !is GroupChannel.Frequency || e is P2pFailure.Busy) throw e
                    P2pLog.w("group: ${channel.mhz} MHz refused (${e.message}), trying any 5 GHz channel")
                    ch.await("createGroup") { m, c, l -> m.createGroup(c, config(GroupChannel.Band5Ghz), l) }
                }
                val formed = group.filter { it != null }.first()!!
                phase("formed on ${formed.frequency} MHz")

                val offer = P2pOfferRecord(senderName, ssid, psk, target.deviceAddress)
                val service =
                    WifiP2pDnsSdServiceInfo.newInstance(
                        P2pTxt.OFFER_INSTANCE,
                        P2pTxt.SERVICE_TYPE,
                        P2pTxt.encode(offer),
                    )
                ch.await("addLocalService") { m, c, l -> m.addLocalService(c, service, l) }
                // Listen state, so the receiver's service discovery reaches the offer.
                val listening =
                    launch {
                        while (true) {
                            runCatching { ch.await("discoverPeers") { m, c, l -> m.discoverPeers(c, l) } }
                            delay(LISTEN_REARM_MS)
                        }
                    }
                var clientIp: String? = null
                while (clientIp == null) {
                    delay(POLL_MS)
                    clientIp = ch.groupInfo()?.clientList?.firstNotNullOfOrNull { it.ipAddress?.hostAddress }
                }
                listening.cancel()
                phase("receiver joined at $clientIp")
                // Stop scanning and announcing: both cost airtime while streaming.
                ch.quietly { m, c -> m.stopPeerDiscovery(c, null) }
                ch.quietly { m, c -> m.clearLocalServices(c, null) }
                established = true
                P2pLink(ReceiverEndpoint(target.name, clientIp, target.port), formed.frequency, lost, ::release)
            }
        } catch (e: TimeoutCancellationException) {
            release()
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

    companion object {
        /** Receiver discovery of the offer (~4 s), its join (~6 s) and DHCP, with margin. */
        const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val POLL_MS = 250L
        private const val LISTEN_REARM_MS = 10_000L
    }
}
