package dev.ririprojector.helper

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import dev.ririprojector.adb.AdbAuthException
import dev.ririprojector.adb.AdbClient
import dev.ririprojector.adb.AdbInvalidPairingCodeException
import dev.ririprojector.adb.AdbKey
import dev.ririprojector.adb.AdbMdns
import dev.ririprojector.adb.AdbPairingClient
import dev.ririprojector.adb.AdbProtocol
import dev.ririprojector.adb.PreferenceAdbKeyStore
import dev.ririprojector.adb.ShellAccess
import dev.ririprojector.adb.ShellAccessEvent
import dev.ririprojector.adb.ShellAccessState
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-side owner of the second-screen setup (ADR-014): pairing with Wireless debugging, finding
 * this device's adbd, starting the shell helper and holding its binder. One per process ([get]).
 * Blocking ADB work runs on its own `Riri/ShellAccess` thread; the pairing code is never logged.
 */
class ShellAccessManager private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "Riri/ShellAccess") }
    private val key: AdbKey by lazy {
        AdbKey(
            PreferenceAdbKeyStore(context.getSharedPreferences(PreferenceAdbKeyStore.FILE, Context.MODE_PRIVATE)),
            "RiriProjector@${Build.MODEL}",
        )
    }

    private val _state =
        MutableStateFlow(ShellAccess.initial(Build.VERSION.SDK_INT, prefs.getBoolean(KEY_PAIRED, false)))
    val state: StateFlow<ShellAccessState> = _state.asStateFlow()

    @Volatile private var pairingPort = -1

    @Volatile private var connectPort = -1
    private var pairingMdns: AdbMdns? = null
    private var connectMdns: AdbMdns? = null

    @Volatile
    var helper: IRiriHelper? = null
        private set
    private val listener = object : IRiriHelperListener.Stub() {
        override fun onDisplayEmpty(displayId: Int) = Unit
    }

    private fun on(event: ShellAccessEvent) {
        val next = ShellAccess.reduce(_state.value, event)
        _state.value = next
        prefs.edit().putBoolean(KEY_PAIRED, ShellAccess.isPaired(next)).apply()
    }

    /** Starts watching for this device's Wireless-debugging connect port (screen visible). */
    @Synchronized
    fun watch() {
        if (_state.value == ShellAccessState.Unsupported || connectMdns != null) return
        connectMdns = AdbMdns(context, AdbProtocol.SERVICE_TLS_CONNECT) { port ->
            connectPort = port
            on(ShellAccessEvent.ConnectPort(port))
        }.also { it.start() }
    }

    @Synchronized
    fun unwatch() {
        connectMdns?.stop()
        connectMdns = null
        stopPairingDiscovery()
    }

    /** The user opened "Pair device with pairing code": look for the pairing service. */
    @Synchronized
    fun startPairing() {
        if (_state.value == ShellAccessState.Unsupported) return
        on(ShellAccessEvent.PairingRequested)
        if (pairingMdns != null) return
        pairingMdns = AdbMdns(context, AdbProtocol.SERVICE_TLS_PAIRING) { port ->
            pairingPort = port
            on(ShellAccessEvent.PairingPort(port))
        }.also { it.start() }
    }

    @Synchronized
    private fun stopPairingDiscovery() {
        pairingMdns?.stop()
        pairingMdns = null
    }

    /** Pairs with [code] (6 digits from Settings), then starts the helper. [done] gets the outcome. */
    fun pair(code: String, done: (ShellAccessState) -> Unit = {}) {
        val digits = code.filter(Char::isDigit)
        worker.execute {
            val port = pairingPort
            if (Build.VERSION.SDK_INT < ShellAccess.MIN_SDK || port <= 0 || digits.length != 6) {
                on(ShellAccessEvent.PairingFailed(wrongCode = digits.length != 6))
                return@execute done(_state.value)
            }
            try {
                AdbPairingClient(LOOPBACK, port, digits, key).use { it.pair() }
                on(ShellAccessEvent.PairingSucceeded)
                stopPairingDiscovery()
                startBlocking()
            } catch (e: AdbInvalidPairingCodeException) {
                on(ShellAccessEvent.PairingFailed(wrongCode = true))
            } catch (e: Exception) {
                Log.w(TAG, "pairing failed: ${e.javaClass.simpleName}")
                on(ShellAccessEvent.PairingFailed(wrongCode = false))
            }
            done(_state.value)
        }
    }

    /** Starts the helper if it is not running (needs Wireless debugging on). */
    fun start(done: (ShellAccessState) -> Unit = {}) {
        worker.execute {
            startBlocking()
            done(_state.value)
        }
    }

    private fun startBlocking() {
        if (helper?.asBinder()?.isBinderAlive == true) return on(ShellAccessEvent.HelperStarted)
        if (!ShellAccess.isPaired(_state.value)) return
        val port = connectPort
        if (port <= 0) return on(ShellAccessEvent.ConnectPort(-1))
        on(ShellAccessEvent.StartRequested)
        HelperBinderSlot.reset()
        try {
            AdbClient(LOOPBACK, port, key).use { adb ->
                adb.connect()
                adb.shell(HelperCommand.stopOld())
                adb.shell(HelperCommand.start(context.applicationInfo.sourceDir, context.packageName, userId()))
            }
        } catch (e: AdbAuthException) {
            Log.w(TAG, "adbd refused our key: ${e.message}")
            return on(ShellAccessEvent.KeyRefused)
        } catch (e: Exception) {
            Log.w(TAG, "adb connect failed: ${e.javaClass.simpleName} ${e.message}")
            return on(ShellAccessEvent.ConnectFailed)
        }
        val binder = HelperBinderSlot.await(HELPER_START_TIMEOUT_MS) ?: return on(ShellAccessEvent.HelperTimedOut)
        val h = IRiriHelper.Stub.asInterface(binder)
        val sameBuild = runCatching {
            h.version() == HelperService.VERSION && h.apkPath() == context.applicationInfo.sourceDir
        }.getOrDefault(false)
        if (!sameBuild) {
            runCatching { h.exit() }
            return on(ShellAccessEvent.HelperTimedOut)
        }
        h.setListener(listener)
        binder.linkToDeath({
            helper = null
            on(ShellAccessEvent.HelperDied)
        }, 0)
        helper = h
        on(ShellAccessEvent.HelperStarted)
        Log.i(TAG, "helper ready")
    }

    /** Self-test through the running helper: create and release a small display. */
    fun selfTest(done: (Boolean) -> Unit) {
        worker.execute {
            val h = helper
            val ok = h != null && runCatching {
                val id = h.createDisplay(null, 1280, 720, SecondDisplay.densityDpi(1280, 720), 60)
                if (id >= 0) h.releaseDisplay(id)
                id >= 0
            }.getOrDefault(false)
            done(ok)
        }
    }

    /** Stops the helper and forgets the pairing flag (the key stays; Settings lists it until removed). */
    fun forget() {
        worker.execute {
            runCatching { helper?.exit() }
            helper = null
            on(ShellAccessEvent.Forget)
        }
    }

    private fun userId(): Int = Process.myUid() / 100_000

    companion object {
        private const val TAG = "Riri/ShellAccess"
        private const val PREFS = "riri_shell_access"
        private const val KEY_PAIRED = "paired"
        private const val LOOPBACK = "127.0.0.1"
        private const val HELPER_START_TIMEOUT_MS = 10_000L

        @Volatile private var instance: ShellAccessManager? = null

        fun get(context: Context): ShellAccessManager = instance ?: synchronized(this) {
            instance ?: ShellAccessManager(context.applicationContext).also { instance = it }
        }
    }
}
