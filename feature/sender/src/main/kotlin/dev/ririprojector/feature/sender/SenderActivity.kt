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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
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

@Composable
private fun SenderScreen(
    receivers: List<ReceiverEndpoint>,
    state: SenderState,
    onStart: (ReceiverEndpoint, StreamPreferences) -> Unit,
    onStop: () -> Unit,
    onUseAsReceiver: () -> Unit,
    settings: SenderSettings,
    onSettingsChange: (StreamPreferences) -> Unit,
) {
    val saved = settings.prefs
    val last = settings.lastReceiver
    var manual by rememberSaveable { mutableStateOf(last?.let { "${it.host}:${it.port}" } ?: "") }
    var codec by rememberSaveable { mutableStateOf(saved.preferredVideoCodec?.name) }
    var maxHeight by rememberSaveable { mutableStateOf(saved.maxHeight ?: 1080) }
    var fps by rememberSaveable { mutableStateOf(saved.maxFps ?: 60) }
    var bitrateMbps by rememberSaveable { mutableStateOf((saved.bitrateBps ?: 0) / 1_000_000) }
    var audio by rememberSaveable { mutableStateOf(saved.audioEnabled) }
    var sync by rememberSaveable { mutableStateOf(saved.syncMode) }
    val prefs =
        remember(codec, maxHeight, fps, bitrateMbps, audio, sync) {
            StreamPreferences(
                preferredVideoCodec = codec?.let { VideoCodec.valueOf(it) },
                maxHeight = maxHeight,
                maxFps = fps,
                bitrateBps = if (bitrateMbps > 0) bitrateMbps * 1_000_000 else null,
                audioEnabled = audio,
                syncMode = sync,
            )
        }
    LaunchedEffect(prefs) { onSettingsChange(prefs) }
    val quickStart = remember(last, receivers) { SenderSettingsCodec.resolveQuickStart(last, receivers) }
    val lastUsed = quickStart?.takeIf { it.advertised }?.endpoint
    val active =
        state !is SenderState.Idle && state !is SenderState.Ended

    Column(
        // targetSdk 35+ is edge-to-edge: keep content clear of the status and navigation bars.
        modifier = Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.sender_title), style = MaterialTheme.typography.headlineMedium)
            StatusCard(state, onStop)

            Text(stringResource(R.string.sender_receivers), style = MaterialTheme.typography.titleMedium)
            if (receivers.isEmpty()) {
                Text(stringResource(R.string.sender_placeholder), style = MaterialTheme.typography.bodyMedium)
            }
            receivers.forEach { r ->
                Card(
                    modifier =
                        Modifier.fillMaxWidth().clickable(enabled = !active) { onStart(r, prefs) },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium)
                        Text("${r.host}:${r.port}", style = MaterialTheme.typography.bodySmall)
                        val lastUsedLabel = stringResource(R.string.sender_last_used)
                        if (r == lastUsed) Text(lastUsedLabel, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            LastReceiverCard(quickStart, enabled = !active) { onStart(it, prefs) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = manual,
                    onValueChange = { manual = it },
                    label = { Text(stringResource(R.string.sender_manual_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.weight(1f),
                )
                val parsed = ReceiverEndpoint.parse(manual)
                Button(enabled = parsed != null && !active, onClick = {
                    parsed?.let { onStart(it, prefs) }
                }) {
                    Text(stringResource(R.string.sender_connect))
                }
            }

            HorizontalDivider()
            Text(stringResource(R.string.sender_settings), style = MaterialTheme.typography.titleMedium)
            ChoiceRow(
                stringResource(R.string.sender_codec),
                listOf(null to stringResource(R.string.sender_auto), "HEVC" to "HEVC", "AVC" to "H.264"),
                codec,
            ) { codec = it }
            ChoiceRow(
                stringResource(R.string.sender_resolution),
                listOf(720 to "720p", 1080 to "1080p", 1440 to "1440p"),
                maxHeight,
            ) {
                maxHeight = it
            }
            ChoiceRow(stringResource(R.string.sender_fps), listOf(30 to "30", 60 to "60"), fps) { fps = it }
            ChoiceRow(
                stringResource(R.string.sender_bitrate),
                listOf(0 to stringResource(R.string.sender_auto), 8 to "8 Mbps", 16 to "16 Mbps", 30 to "30 Mbps"),
                bitrateMbps,
            ) { bitrateMbps = it }
            ChoiceRow(
                stringResource(R.string.sender_audio),
                listOf(true to stringResource(R.string.sender_on), false to stringResource(R.string.sender_off)),
                audio,
            ) { audio = it }
            ChoiceRow(
                stringResource(R.string.sender_sync),
                listOf(
                    SyncMode.LIP_SYNC to stringResource(R.string.sender_sync_lip),
                    SyncMode.LOW_LATENCY to stringResource(R.string.sender_sync_latency),
                ),
                sync,
            ) { sync = it }

            HorizontalDivider()
            // Manual role choice on any device (ADR-001); off while a session is running.
            TextButton(enabled = !active, onClick = onUseAsReceiver) {
                Text(stringResource(R.string.sender_use_as_receiver))
            }
        }
    }
}

@Composable
private fun StatusCard(state: SenderState, onStop: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                SenderState.Idle -> Text(stringResource(R.string.sender_status_idle))

                is SenderState.Reconnecting -> {
                    Text(stringResource(R.string.sender_status_reconnecting, state.target.name))
                    OutlinedButton(onClick = onStop) { Text(stringResource(R.string.sender_stop)) }
                }

                is SenderState.Connecting -> {
                    Text(stringResource(R.string.sender_status_connecting, state.target.name))
                    OutlinedButton(onClick = onStop) { Text(stringResource(R.string.sender_stop)) }
                }

                is SenderState.Streaming -> {
                    val v = state.params.video
                    val s = state.stats
                    Text(
                        stringResource(R.string.sender_status_streaming, state.target.name),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        stringResource(
                            R.string.sender_stats,
                            v.codec.name,
                            v.width,
                            v.height,
                            s.encodedFps,
                            s.bitrateBps / 1_000_000.0,
                            s.rttMs ?: 0.0,
                            s.receiverFps,
                            s.receiverDecodeMs,
                            s.receiverDecodeP95Ms,
                            s.sendDropped,
                            s.receiverDropped,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        when (s.audio) {
                            AudioStatus.ON -> stringResource(
                                R.string.sender_audio_on,
                                s.receiverUnderruns,
                                s.avOffsetMs,
                            )

                            AudioStatus.OFF -> stringResource(R.string.sender_audio_off)

                            AudioStatus.UNAVAILABLE -> stringResource(R.string.sender_audio_unavailable)

                            AudioStatus.NO_APP -> stringResource(R.string.sender_audio_no_app)
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (s.latencyMs != null) {
                        Text(
                            stringResource(R.string.sender_latency, s.latencyMs, s.latencyP95Ms ?: s.latencyMs),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (s.receiverVideoDelayMs > 0) {
                        Text(
                            stringResource(R.string.sender_video_delay, s.receiverVideoDelayMs),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (s.contentHidden) {
                        Text(
                            stringResource(R.string.sender_content_hidden),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Button(onClick = onStop) { Text(stringResource(R.string.sender_stop)) }
                }

                is SenderState.Ended ->
                    Text(
                        state.message?.let { stringResource(R.string.sender_status_failed, it) }
                            ?: stringResource(R.string.sender_status_ended),
                    )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        // Wraps on narrow phones instead of pushing chips off screen.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(text) })
            }
        }
    }
}
