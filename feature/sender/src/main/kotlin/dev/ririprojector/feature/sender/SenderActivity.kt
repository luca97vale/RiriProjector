package dev.ririprojector.feature.sender

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
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
import dev.ririprojector.net.nsd.NsdBrowser
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
    )

    private var pending: Pending? = null

    private val consent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val p = pending ?: return@registerForActivityResult
            pending = null
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ProjectionService.start(
                    this,
                    result.resultCode,
                    data,
                    p.target,
                    p.prefs,
                    p.consentShownAtMs,
                    System.currentTimeMillis(),
                )
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Granted or not, continue: without it the session mirrors video only and says so.
    private val audioPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { launchConsent() }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The consent dialog may outlive this instance (rotation): keep what the result is for.
        pending?.let { (t, p, consentShownAtMs) ->
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
        if (target != null) pending = Pending(target, prefs, state.getLong(KEY_CONSENT_SHOWN_AT))
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
                    SenderScreen(
                        receivers = found,
                        state = state,
                        onStart = ::requestProjection,
                        onStop = { ProjectionService.stop(this) },
                        onUseAsReceiver = ::useAsReceiver,
                        settings = initialSettings.copy(lastReceiver = lastReceiver),
                        onSettingsChange = settings::savePrefs,
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
    private fun requestProjection(target: ReceiverEndpoint, prefs: StreamPreferences) {
        pending = Pending(target, prefs)
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
        consent.launch(intent)
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
        private const val KEY_USAGE_ACCESS_ASKED = "usageAccessAsked"
    }
}
