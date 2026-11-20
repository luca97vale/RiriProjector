package dev.ririprojector.net.p2p

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import android.os.Parcelable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

internal const val TAG = "Riri/P2p"

/** A channel to the P2P framework, or null when the device has no Wi-Fi Direct. */
internal class P2pChannel private constructor(val manager: WifiP2pManager, val channel: WifiP2pManager.Channel) {
    fun close() {
        runCatching { channel.close() }
    }

    companion object {
        fun open(context: Context, onDisconnected: () -> Unit): P2pChannel? {
            val manager = context.getSystemService(WifiP2pManager::class.java) ?: return null
            val channel = manager.initialize(context, Looper.getMainLooper()) { onDisconnected() } ?: return null
            return P2pChannel(manager, channel)
        }
    }
}

/** Runs one `WifiP2pManager` action and waits for its listener; failures become [P2pFailure]. */
internal suspend fun P2pChannel.await(
    what: String,
    call: (WifiP2pManager, WifiP2pManager.Channel, WifiP2pManager.ActionListener) -> Unit,
) = suspendCancellableCoroutine { cont ->
    call(
        manager,
        channel,
        object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (cont.isActive) cont.resume(Unit)
            }

            override fun onFailure(reason: Int) {
                if (cont.isActive) cont.resumeWithException(P2pFailure.of(reason, what))
            }
        },
    )
}

/** Fire-and-forget cleanup call: never throws, never waits. */
internal fun P2pChannel.quietly(call: (WifiP2pManager, WifiP2pManager.Channel) -> Unit) {
    runCatching { call(manager, channel) }
}

@SuppressLint("MissingPermission") // callers hold NEARBY_WIFI_DEVICES
internal suspend fun P2pChannel.groupInfo(): WifiP2pGroup? = suspendCancellableCoroutine { cont ->
    manager.requestGroupInfo(channel) { if (cont.isActive) cont.resume(it) }
}

/** Registers [onReceive] for [actions] on the main thread; returns the receiver to unregister. */
internal fun Context.registerP2p(vararg actions: String, onReceive: (Intent) -> Unit): BroadcastReceiver {
    val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = onReceive(intent)
        }
    val filter = IntentFilter().apply { actions.forEach(::addAction) }
    if (Build.VERSION.SDK_INT >= 33) {
        registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
    } else {
        registerReceiver(receiver, filter)
    }
    return receiver
}

internal fun Context.unregisterQuietly(receiver: BroadcastReceiver?) {
    if (receiver != null) runCatching { unregisterReceiver(receiver) }
}

@Suppress("DEPRECATION")
internal inline fun <reified T : Parcelable> Intent.parcelable(key: String): T? = if (Build.VERSION.SDK_INT >= 33) {
    getParcelableExtra(key, T::class.java)
} else {
    getParcelableExtra(key)
}
