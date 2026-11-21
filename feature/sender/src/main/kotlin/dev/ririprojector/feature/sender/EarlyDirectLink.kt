package dev.ririprojector.feature.sender

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.RequiresApi
import dev.ririprojector.net.p2p.P2pGroupOwner
import dev.ririprojector.net.p2p.P2pLink
import dev.ririprojector.net.p2p.P2pReceiver
import dev.ririprojector.net.p2p.P2pTxt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Starts the Wi-Fi Direct group as soon as the user picks a receiver (ADR-011), so the TV finds
 * the offer (2–7 s of P2P discovery) while the consent dialog is still on screen instead of after
 * it. [ProjectionService] [claim]s the attempt once consent is given. A declined consent [cancel]s
 * it, and an attempt nobody claims is closed after [UNCLAIMED_MS]. Main thread only.
 */
internal object EarlyDirectLink {
    private const val TAG = "Riri/EarlyDirect"

    /** Longer than the consent dialog plus the group's own 30 s timeout. */
    private const val UNCLAIMED_MS = 45_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var address: String? = null
    private var attempt: Deferred<P2pLink>? = null
    private var link: P2pLink? = null
    private var expiry: Job? = null

    @MainThread
    @RequiresApi(35)
    fun start(context: Context, receiver: P2pReceiver) {
        cancel()
        val app = context.applicationContext
        address = receiver.deviceAddress
        attempt = scope.async { P2pGroupOwner(app).open(receiver, senderName(app)).also { link = it } }
        expiry =
            scope.launch {
                delay(UNCLAIMED_MS)
                Log.i(TAG, "early group never claimed: closing it")
                cancel()
            }
    }

    /**
     * Hands over the attempt started for [deviceAddress], or null when there is none (another
     * receiver, process restarted). The caller then owns the link it yields.
     */
    @MainThread
    fun claim(deviceAddress: String): Deferred<P2pLink>? {
        val a = attempt?.takeIf { address == deviceAddress }
        if (a == null) {
            cancel()
            return null
        }
        expiry?.cancel()
        expiry = null
        attempt = null
        address = null
        link = null
        return a
    }

    /** Drops an unclaimed attempt: stops it, or closes the group it already opened. */
    @MainThread
    fun cancel() {
        expiry?.cancel()
        expiry = null
        attempt?.cancel() // open() releases its group when cancelled
        link?.close() // already open: nobody will use it
        attempt = null
        address = null
        link = null
    }

    fun senderName(context: Context): String = (
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: Build.MODEL
        ).let(P2pTxt::fitName)
}

/**
 * Awaits a claimed attempt. If the caller is cancelled first (stop during the join), the attempt
 * is stopped too, or its group closed if it had just opened: a claimed attempt has no other owner.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun Deferred<P2pLink>.awaitOrRelease(): P2pLink = try {
    await()
} catch (e: CancellationException) {
    cancel()
    if (isCompleted && !isCancelled) runCatching { getCompleted().close() }
    throw e
}
