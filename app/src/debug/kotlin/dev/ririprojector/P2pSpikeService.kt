@file:Suppress("ktlint:standard:max-line-length") // throwaway spike harness, log lines only

package dev.ririprojector

import android.annotation.SuppressLint
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Debug-only Wi-Fi Direct spike harness (plan #21, step 1). Driven over adb:
 *
 * `am startservice -n dev.ririprojector.debug/dev.ririprojector.P2pSpikeService --es cmd <cmd> ...`
 *
 * Commands: `go` (TV group owner, `--ez disconnect true` drops the router link first), `gob`
 * (tablet 5 GHz group owner), `browse`, `join --es ssid S --es psk P [--es addr MAC]`,
 * `sta --es ssid S --es psk P` (legacy station join), `discover`, `status`, `stop`
 * (`--ez reconnect true` puts the station back on the router). Everything is appended to
 * `files/spike.log` (Fire OS hides app log lines): `run-as dev.ririprojector.debug cat files/spike.log`.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class P2pSpikeService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var p2p: WifiP2pManager
    private lateinit var wifi: WifiManager
    private var channel: WifiP2pManager.Channel? = null
    private var staNetworkId = -1
    private val t0 = SystemClock.elapsedRealtime()
    private val logFile by lazy { File(filesDir, "spike.log") }
    private val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                        val info = intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO)
                        val group = intent.getParcelableExtra<WifiP2pGroup>(WifiP2pManager.EXTRA_WIFI_P2P_GROUP)
                        val goAddr = info?.groupOwnerAddress?.hostAddress
                        log("CONNECTION_CHANGED formed=${info?.groupFormed} go=${info?.isGroupOwner} goAddr=$goAddr")
                        log("  ${describe(group)}")
                        group?.`interface`?.let { pollIpv4(it, 0) }
                    }

                    WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION ->
                        log("P2P_STATE ${intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)}")

                    WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION ->
                        log("DISCOVERY_CHANGED ${intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1)}")
                }
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        p2p = getSystemService(WifiP2pManager::class.java)
        wifi = applicationContext.getSystemService(WifiManager::class.java)
        channel = p2p.initialize(this, Looper.getMainLooper()) { log("CHANNEL DISCONNECTED") }
        registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
            },
        )
        log("service created on API ${Build.VERSION.SDK_INT} ${Build.MODEL}")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        log("service destroyed")
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ch = channel ?: return START_NOT_STICKY
        when (val cmd = intent?.getStringExtra("cmd")) {
            "go" -> {
                if (intent.getBooleanExtra("disconnect", false)) log("wifi.disconnect() = ${wifi.disconnect()}")
                p2p.createGroup(ch, listener("createGroup"))
            }

            "gob" -> {
                if (Build.VERSION.SDK_INT < 29) return log("gob needs API 29").let { START_NOT_STICKY }
                val pass = (1..16).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("")
                val config =
                    WifiP2pConfig.Builder()
                        .setNetworkName("DIRECT-rr-RiriSpike")
                        .setPassphrase(pass)
                        .apply {
                            val f = intent.getIntExtra("freq", 0)
                            if (f >
                                0
                            ) {
                                setGroupOperatingFrequency(f)
                            } else {
                                setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
                            }
                        }
                        .build()
                log("gob passphrase=$pass")
                p2p.createGroup(ch, config, listener("createGroup(5GHz)"))
            }

            "browse" -> browse(ch)

            "advertise" -> advertise(ch)

            "join" -> {
                if (Build.VERSION.SDK_INT < 29) return log("join needs API 29").let { START_NOT_STICKY }
                val b =
                    WifiP2pConfig.Builder()
                        .setNetworkName(intent.getStringExtra("ssid") ?: "")
                        .setPassphrase(intent.getStringExtra("psk") ?: "")
                intent.getStringExtra("addr")?.let { b.setDeviceAddress(android.net.MacAddress.fromString(it)) }
                log("join start")
                p2p.connect(ch, b.build(), listener("connect"))
            }

            "sta" -> {
                val conf =
                    WifiConfiguration().apply {
                        SSID = "\"${intent.getStringExtra("ssid")}\""
                        preSharedKey = "\"${intent.getStringExtra("psk")}\""
                    }
                staNetworkId = wifi.addNetwork(conf)
                log("addNetwork=$staNetworkId enable=${wifi.enableNetwork(staNetworkId, true)}")
                val revertSec = intent.getIntExtra("revertSec", 600)
                main.postDelayed({
                    log(
                        "revert after $revertSec s: removeNetwork=${wifi.removeNetwork(
                            staNetworkId,
                        )} reconnect=${wifi.reconnect()}",
                    )
                    staNetworkId = -1
                }, revertSec * 1000L)
                // Log the station link while away from adb.
                fun tick(n: Int) {
                    val ci = wifi.connectionInfo
                    log(
                        "sta tick ssid=${ci?.ssid} freq=${ci?.frequency} rssi=${ci?.rssi} speed=${ci?.linkSpeed} ip=${ci?.ipAddress}",
                    )
                    if (n > 0) main.postDelayed({ tick(n - 1) }, 15_000)
                }
                tick(revertSec / 15)
            }

            "discover" -> p2p.discoverPeers(ch, listener("discoverPeers"))

            "stopdiscover" -> p2p.stopPeerDiscovery(ch, listener("stopPeerDiscovery"))

            "status" -> status(ch)

            "stop" -> {
                p2p.clearLocalServices(ch, listener("clearLocalServices"))
                p2p.clearServiceRequests(ch, listener("clearServiceRequests"))
                p2p.stopPeerDiscovery(ch, listener("stopPeerDiscovery"))
                p2p.cancelConnect(ch, listener("cancelConnect"))
                p2p.removeGroup(ch, listener("removeGroup"))
                if (staNetworkId >=
                    0
                ) {
                    log("removeNetwork=${wifi.removeNetwork(staNetworkId)}").also { staNetworkId = -1 }
                }
                if (intent.getBooleanExtra("reconnect", false)) log("wifi.reconnect() = ${wifi.reconnect()}")
            }

            else -> log("unknown cmd $cmd")
        }
        return START_NOT_STICKY
    }

    private fun advertise(ch: WifiP2pManager.Channel) {
        p2p.requestGroupInfo(ch) { group ->
            if (group == null) return@requestGroupInfo log("advertise: no group yet")
            val txt =
                mapOf(
                    "v" to "1",
                    "name" to Build.MODEL,
                    "port" to "47800",
                    "ssid" to group.networkName,
                    "psk" to (group.passphrase ?: ""),
                )
            val info = WifiP2pDnsSdServiceInfo.newInstance("RiriSpike", "_ririproj._tcp", txt)
            p2p.addLocalService(ch, info, listener("addLocalService"))
        }
    }

    private fun browse(ch: WifiP2pManager.Channel) {
        val start = SystemClock.elapsedRealtime()
        p2p.setDnsSdResponseListeners(
            ch,
            { instance, type, dev ->
                log(
                    "SD service +${SystemClock.elapsedRealtime() - start} ms $instance $type from ${dev.deviceName} ${dev.deviceAddress} go=${dev.isGroupOwner}",
                )
            },
            { domain, txt, dev ->
                log("SD txt +${SystemClock.elapsedRealtime() - start} ms $domain $txt from ${dev.deviceAddress}")
            },
        )
        p2p.addServiceRequest(ch, WifiP2pDnsSdServiceRequest.newInstance(), listener("addServiceRequest"))
        p2p.discoverServices(ch, listener("discoverServices"))
        main.postDelayed({
            p2p.requestPeers(ch) { peers ->
                peers.deviceList.forEach {
                    log("peer ${it.deviceName} ${it.deviceAddress} go=${it.isGroupOwner} status=${it.status}")
                }
            }
        }, 10_000)
    }

    private fun status(ch: WifiP2pManager.Channel) {
        p2p.requestGroupInfo(ch) { log("status group: ${describe(it)}") }
        p2p.requestConnectionInfo(ch) {
            log(
                "status conn: formed=${it.groupFormed} go=${it.isGroupOwner} goAddr=${it.groupOwnerAddress?.hostAddress}",
            )
        }
        val ci = wifi.connectionInfo
        log(
            "status sta: ssid=${ci?.ssid} freq=${ci?.frequency} rssi=${ci?.rssi} speed=${ci?.linkSpeed} ip=${ci?.ipAddress}",
        )
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }.forEach { nif ->
            log(
                "nif ${nif.name}: ${nif.inetAddresses.toList().filterIsInstance<Inet4Address>().map {
                    it.hostAddress
                }}",
            )
        }
    }

    private fun describe(g: WifiP2pGroup?): String {
        if (g == null) return "group=null"
        val freq = if (Build.VERSION.SDK_INT >= 29) g.frequency.toString() else "n/a"
        return "group ssid=${g.networkName} psk=${g.passphrase} if=${g.`interface`} owner=${g.isGroupOwner} freq=$freq clients=${g.clientList.map { c ->
            c.deviceAddress + "/" + (if (Build.VERSION.SDK_INT >= 35) c.ipAddress?.hostAddress else "?")
        }}"
    }

    private fun pollIpv4(name: String, attempt: Int) {
        val ip = runCatching {
            NetworkInterface.getByName(name)?.inetAddresses?.toList()?.filterIsInstance<Inet4Address>()?.firstOrNull()
        }.getOrNull()
        if (ip != null) return log("ipv4 on $name after ${attempt * 100} ms: ${ip.hostAddress}")
        if (attempt <
            100
        ) {
            main.postDelayed({ pollIpv4(name, attempt + 1) }, 100)
        } else {
            log("no ipv4 on $name after 10 s")
        }
    }

    private fun listener(what: String) = object : WifiP2pManager.ActionListener {
        override fun onSuccess() = log("$what ok")

        override fun onFailure(reason: Int) = log("$what FAILED reason=$reason")
    }

    private fun log(msg: String) {
        val line = "${stamp.format(Date())} +${SystemClock.elapsedRealtime() - t0} $msg"
        Log.i(TAG, line)
        runCatching { logFile.appendText(line + "\n") }
    }

    private companion object {
        const val TAG = "Riri/P2pSpike"
    }
}
