package dev.ririprojector.feature.sender

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.ririprojector.net.ReceiverEndpoint
import dev.ririprojector.net.WifiBand
import dev.ririprojector.net.devlog.DevLog
import dev.ririprojector.net.nsd.NsdBrowser
import dev.ririprojector.net.p2p.P2pFailure
import dev.ririprojector.net.p2p.P2pReceiver
import dev.ririprojector.net.p2p.P2pReceiverBrowser
import dev.ririprojector.net.p2p.WifiDirectSupport
import dev.ririprojector.protocol.model.StreamPreferences
import dev.ririprojector.protocol.model.SyncMode
import dev.ririprojector.protocol.model.VideoCodec
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch

/** Sender entry point (any phone or tablet): pick a receiver, tune the stream, start/stop mirroring. */
class SenderActivity : ComponentActivity() {
    /** A mirroring request waiting for the audio permission and the consent dialog. */
    private data class Pending(
        val target: ReceiverEndpoint,
        val prefs: StreamPreferences,
        /** Wall-clock time the consent dialog was shown: a shared app comes to the front after it. */
        val consentShownAtMs: Long = 0,
        /** Wi-Fi Direct session: the receiver's P2P device address (ADR-011). */
        val directAddress: String? = null,
    )

    private var pending: Pending? = null

    private val consent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val p = pending ?: return@registerForActivityResult
            pending = null
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                DevLog.info(DEV_SOURCE) { "Screen capture allowed" }
                ProjectionService.start(
                    this,
                    result.resultCode,
                    data,
                    p.target,
                    p.prefs,
                    p.consentShownAtMs,
                    System.currentTimeMillis(),
                    p.directAddress,
                )
            } else {
                DevLog.warn(DEV_SOURCE) { "Screen capture declined: share cancelled" }
                if (p.directAddress != null) EarlyDirectLink.cancel() // drop the group started at the pick
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** The Wi-Fi Direct receiver list is open (discovery runs while it is, ADR-011). */
    private var directSearch by mutableStateOf(false)

    private val nearbyPermission =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                directSearch = true
            } else {
                Toast.makeText(this, R.string.sender_direct_permission, Toast.LENGTH_LONG).show()
            }
        }

    private fun searchDirect() {
        val missing = WifiDirectSupport.missingPermissions(this)
        if (missing.isEmpty()) directSearch = true else nearbyPermission.launch(missing.toTypedArray())
    }

    // Granted or not, continue: without it the session mirrors video only and says so.
    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { launchConsent() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The consent dialog may outlive this instance (rotation): keep what the result is for.
        pending?.let { p0 ->
            val (t, p, consentShownAtMs) = p0
            outState.putString(KEY_HOST, t.host)
            outState.putInt(KEY_PORT, t.port)
            outState.putString(KEY_NAME, t.name)
            outState.putString(KEY_CODEC, p.preferredVideoCodec?.name)
            outState.putInt(KEY_MAX_HEIGHT, p.maxHeight ?: 1080)
            outState.putInt(KEY_FPS, p.maxFps ?: 60)
            outState.putInt(KEY_BITRATE, p.bitrateBps ?: 0)
            outState.putBoolean(KEY_AUDIO, p.audioEnabled)
            outState.putString(KEY_SYNC, p.syncMode.name)
            outState.putLong(KEY_CONSENT_SHOWN_AT, consentShownAtMs)
            outState.putString(KEY_DIRECT, p0.directAddress)
        }
    }

    private fun restorePending(state: Bundle?) {
        val host = state?.getString(KEY_HOST) ?: return
        val target = runCatching {
            ReceiverEndpoint(state.getString(KEY_NAME) ?: host, host, state.getInt(KEY_PORT))
        }.getOrNull()
        val codecName = state.getString(KEY_CODEC)
        val prefs =
            StreamPreferences(
                preferredVideoCodec = VideoCodec.entries.firstOrNull { it.name == codecName },
                maxHeight = state.getInt(KEY_MAX_HEIGHT, 1080),
                maxFps = state.getInt(KEY_FPS, 60),
                bitrateBps = state.getInt(KEY_BITRATE, 0).takeIf { it > 0 },
                audioEnabled = state.getBoolean(KEY_AUDIO, true),
                syncMode = SyncMode.entries.firstOrNull { it.name == state.getString(KEY_SYNC) } ?: SyncMode.LIP_SYNC,
            )
        if (target != null) {
            pending = Pending(target, prefs, state.getLong(KEY_CONSENT_SHOWN_AT), state.getString(KEY_DIRECT))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restorePending(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val receivers = NsdBrowser(this).receivers().catch { emit(emptyList()) }
        val settings = SenderSettingsRepository(SharedPreferencesStore.create(this))
        val initialSettings = settings.load()
        var lastReceiver by mutableStateOf(initialSettings.lastReceiver)
        var developerMode by mutableStateOf(initialSettings.developerMode)
        DevLog.enabled = developerMode
        // Remember a receiver once a session reached Streaming with it; once per target (ADR-012).
        lifecycleScope.launch {
            SenderStatus.state.mapNotNull { (it as? SenderState.Streaming)?.target }
                .distinctUntilChanged()
                .collect { target ->
                    settings.saveLastReceiver(target)
                    if (SenderSettingsCodec.isRememberable(target)) lastReceiver = target
                }
        }
        setContent {
            RiriTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val state by SenderStatus.state.collectAsStateWithLifecycle()
                    val active =
                        state !is SenderState.Idle && state !is SenderState.Ended
                    // No mDNS browsing while mirroring (airtime) or while in the background.
                    val found by remember(active) { if (active) flowOf(emptyList()) else receivers }
                        .collectAsStateWithLifecycle(initialValue = emptyList())
                    // Wi-Fi Direct discovery scans off channel: only while its list is shown.
                    val searching = directSearch && !active
                    val directFound by remember(searching) {
                        if (searching) {
                            P2pReceiverBrowser(this).receivers().catch { e ->
                                // BUSY, Wi-Fi off, channel lost: say so instead of searching forever.
                                directSearch = false
                                val message =
                                    if (e is P2pFailure.Busy) {
                                        getString(R.string.sender_error_p2p_busy)
                                    } else {
                                        getString(R.string.sender_error_p2p_failed, e.message ?: e.javaClass.simpleName)
                                    }
                                Toast.makeText(this@SenderActivity, message, Toast.LENGTH_LONG).show()
                                emit(emptyList())
                            }
                        } else {
                            flowOf(emptyList())
                        }
                    }.collectAsStateWithLifecycle(initialValue = emptyList())
                    val direct by SenderStatus.direct.collectAsStateWithLifecycle()
                    SenderScreen(
                        receivers = found,
                        state = state,
                        onStart = { target, prefs -> requestProjection(target, prefs) },
                        onStop = { ProjectionService.stop(this) },
                        direct =
                            DirectUi(
                                available = WifiDirectSupport.usableAsSender(this),
                                searching = searching,
                                receivers = directFound,
                                link = direct,
                                onSearch = ::searchDirect,
                                onStart = { r, prefs ->
                                    directSearch = false // stop scanning before the group forms
                                    // Also on the router list: the offer can go there too (ADR-011 fast path).
                                    val lan = found.singleOrNull { it.name == r.name }?.host
                                    requestProjection(
                                        ReceiverEndpoint(r.name, ProjectionService.DIRECT_HOST, r.port),
                                        prefs,
                                        r.copy(lanHost = lan),
                                    )
                                },
                            ),
                        onUseAsReceiver = ::useAsReceiver,
                        settings = initialSettings.copy(lastReceiver = lastReceiver),
                        onSettingsChange = settings::savePrefs,
                        developer =
                            DeveloperUi(
                                enabled = developerMode,
                                onEnabledChange = {
                                    developerMode = it
                                    DevLog.enabled = it
                                    settings.saveDeveloperMode(it)
                                },
                                onCopy = ::copyDevLog,
                                onShare = ::shareDevLog,
                            ),
                        appVersion = appVersion(),
                    )
                }
            }
        }
    }

    /** Hands over to the app trampoline, which remembers the choice and opens the receiver. */
    private fun useAsReceiver() {
        startActivity(Intent(ACTION_USE_AS_RECEIVER).setPackage(packageName))
        finish()
    }

    private fun appVersion(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Usage access prompt (once), audio permission (when audio is on), then the consent dialog. */
    private fun requestProjection(target: ReceiverEndpoint, prefs: StreamPreferences, direct: P2pReceiver? = null) {
        pending = Pending(target, prefs, directAddress = direct?.deviceAddress)
        if (DevLog.enabled) logShareStart(target, prefs, direct)
        // Let the TV find the offer while the consent dialog is up (ADR-011).
        if (direct != null && Build.VERSION.SDK_INT >= WifiDirectSupport.MIN_SENDER_SDK) {
            EarlyDirectLink.start(this, direct)
        }
        if (prefs.audioEnabled && shouldAskUsageAccess()) askUsageAccess() else continueRequest()
    }

    private fun continueRequest() {
        val p = pending ?: return
        if (p.prefs.audioEnabled && Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.RECORD_AUDIO)) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchConsent()
        }
    }

    /**
     * Without Usage access a one-app share cannot be told apart from the entire screen, so every
     * app's sound would be mirrored (ADR-009). Asked once, the first time it matters.
     */
    private fun shouldAskUsageAccess(): Boolean = Build.VERSION.SDK_INT >= 34 &&
        !SharedAppDetector.hasUsageAccess(this) &&
        !getPreferences(MODE_PRIVATE).getBoolean(KEY_USAGE_ACCESS_ASKED, false)

    private fun askUsageAccess() {
        getPreferences(MODE_PRIVATE).edit().putBoolean(KEY_USAGE_ACCESS_ASKED, true).apply()
        AlertDialog.Builder(this)
            .setTitle(R.string.sender_usage_access_title)
            .setMessage(R.string.sender_usage_access_hint)
            .setPositiveButton(R.string.sender_usage_access_allow) { _, _ ->
                pending = null // the user taps the receiver again after granting it
                startActivity(SharedAppDetector.usageAccessSettings())
            }.setNegativeButton(R.string.sender_usage_access_skip) { _, _ -> continueRequest() }
            .setOnCancelListener { pending = null }
            .show()
    }

    private fun launchConsent() {
        val p = (pending ?: return).copy(consentShownAtMs = System.currentTimeMillis())
        pending = p
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // API 34+: the system dialog offers "entire screen" or "one app"; the session finds out
        // which one was chosen (ADR-009).
        val intent =
            if (Build.VERSION.SDK_INT >= 34) {
                mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
            } else {
                mpm.createScreenCaptureIntent()
            }
        DevLog.info(DEV_SOURCE) { "Waiting for screen-capture consent" }
        consent.launch(intent)
    }

    /** Developer mode (#33): a new log for this share, with what this device's Wi-Fi looks like. */
    private fun logShareStart(target: ReceiverEndpoint, prefs: StreamPreferences, direct: P2pReceiver?) {
        val how = if (direct != null) "Wi-Fi Direct" else "${target.host}:${target.port}"
        DevLog.begin("Share to ${target.name} ($how)")
        DevLog.info(DEV_SOURCE) { "Preferences: $prefs" }
        if (direct != null) {
            DevLog.info(DEV_SOURCE) {
                "Receiver P2P address ${direct.deviceAddress}; router fast path: ${direct.lanHost ?: "not on the same network"}"
            }
        }
        @Suppress("DEPRECATION") // frequency and RSSI only: no location-gated fields are read
        val info = applicationContext.getSystemService(WifiManager::class.java)?.connectionInfo
        val band = info?.frequency?.let { WifiBand.describe(it) }
        if (band == null) {
            DevLog.warn(DEV_SOURCE) { "This device is not on a Wi-Fi network" }
        } else {
            DevLog.info(DEV_SOURCE) { "This device's Wi-Fi: $band, RSSI ${info.rssi} dBm" }
        }
    }

    private fun copyDevLog() {
        val clipboard = getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.sender_dev_log_title), DevLog.format()))
        // API 33+ shows its own confirmation.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.sender_dev_log_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareDevLog() {
        val send =
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, getString(R.string.sender_dev_log_title))
                .putExtra(Intent.EXTRA_TEXT, DevLog.format())
        startActivity(Intent.createChooser(send, getString(R.string.sender_dev_log_share)))
    }

    companion object {
        /** Switch this device to receiver mode and remember it (handled by the app's launcher activity). */
        const val ACTION_USE_AS_RECEIVER = "dev.ririprojector.action.USE_AS_RECEIVER"

        private const val KEY_HOST = "pending.host"
        private const val KEY_PORT = "pending.port"
        private const val KEY_NAME = "pending.name"
        private const val KEY_CODEC = "pending.codec"
        private const val KEY_MAX_HEIGHT = "pending.maxHeight"
        private const val KEY_FPS = "pending.fps"
        private const val KEY_BITRATE = "pending.bitrate"
        private const val KEY_AUDIO = "pending.audio"
        private const val KEY_SYNC = "pending.sync"
        private const val KEY_CONSENT_SHOWN_AT = "pending.consentShownAt"
        private const val KEY_DIRECT = "pending.direct"
        private const val KEY_USAGE_ACCESS_ASKED = "usageAccessAsked"
        private const val DEV_SOURCE = "Sender"
    }
}

/** Developer mode (#33): the settings switch and the log panel's actions. */
internal class DeveloperUi(
    val enabled: Boolean,
    val onEnabledChange: (Boolean) -> Unit,
    val onCopy: () -> Unit,
    val onShare: () -> Unit,
)

/** The Wi-Fi Direct section of the screen (ADR-011). */
internal class DirectUi(
    /** API 35+ with Wi-Fi Direct hardware; otherwise the section is hidden. */
    val available: Boolean,
    val searching: Boolean,
    val receivers: List<P2pReceiver>,
    val link: DirectLink?,
    val onSearch: () -> Unit,
    val onStart: (P2pReceiver, StreamPreferences) -> Unit,
)
