package dev.ririprojector.feature.sender

import android.Manifest
import android.app.Activity
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ririprojector.net.ReceiverEndpoint
import dev.ririprojector.net.nsd.NsdBrowser
import dev.ririprojector.protocol.model.StreamPreferences
import dev.ririprojector.protocol.model.SyncMode
import dev.ririprojector.protocol.model.VideoCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

/** Tablet entry point: pick a receiver, tune the stream, start/stop mirroring. */
class SenderActivity : ComponentActivity() {
    /** A mirroring request waiting for the audio permission and the consent dialog. */
    private data class Pending(
        val target: ReceiverEndpoint,
        val prefs: StreamPreferences,
        val singleApp: Boolean,
        val audioPackage: String?,
    )

    private var pending: Pending? = null

    private val consent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val p = pending ?: return@registerForActivityResult
            pending = null
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ProjectionService.start(this, result.resultCode, data, p.target, p.prefs, p.singleApp, p.audioPackage)
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
        pending?.let { (t, p, singleApp, audioPackage) ->
            outState.putString(KEY_HOST, t.host)
            outState.putInt(KEY_PORT, t.port)
            outState.putString(KEY_NAME, t.name)
            outState.putString(KEY_CODEC, p.preferredVideoCodec?.name)
            outState.putInt(KEY_MAX_HEIGHT, p.maxHeight ?: 1080)
            outState.putInt(KEY_FPS, p.maxFps ?: 60)
            outState.putInt(KEY_BITRATE, p.bitrateBps ?: 0)
            outState.putBoolean(KEY_AUDIO, p.audioEnabled)
            outState.putString(KEY_SYNC, p.syncMode.name)
            outState.putBoolean(KEY_SINGLE_APP, singleApp)
            outState.putString(KEY_AUDIO_PACKAGE, audioPackage)
        }
    }

    private fun restorePending(state: Bundle?) {
        val host = state?.getString(KEY_HOST) ?: return
        val target = runCatching {
            ReceiverEndpoint(state.getString(KEY_NAME) ?: host, host, state.getInt(KEY_PORT))
        }.getOrNull()
        val prefs =
            StreamPreferences(
                preferredVideoCodec = state.getString(KEY_CODEC)?.let { c ->
                    VideoCodec.entries.firstOrNull {
                        it.name ==
                            c
                    }
                },
                maxHeight = state.getInt(KEY_MAX_HEIGHT, 1080),
                maxFps = state.getInt(KEY_FPS, 60),
                bitrateBps = state.getInt(KEY_BITRATE, 0).takeIf { it > 0 },
                audioEnabled = state.getBoolean(KEY_AUDIO, true),
                syncMode = SyncMode.entries.firstOrNull { it.name == state.getString(KEY_SYNC) } ?: SyncMode.LIP_SYNC,
            )
        if (target != null) {
            pending =
                Pending(target, prefs, state.getBoolean(KEY_SINGLE_APP, false), state.getString(KEY_AUDIO_PACKAGE))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restorePending(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val receivers = NsdBrowser(this).receivers().catch { emit(emptyList()) }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val state by SenderStatus.state.collectAsStateWithLifecycle()
                    val active = state is SenderState.Connecting || state is SenderState.Streaming
                    // No mDNS browsing while mirroring (airtime) or while in the background.
                    val found by remember(active) { if (active) flowOf(emptyList()) else receivers }
                        .collectAsStateWithLifecycle(initialValue = emptyList())
                    SenderScreen(
                        receivers = found,
                        state = state,
                        onStart = ::requestProjection,
                        onStop = { ProjectionService.stop(this) },
                        loadApps = { AudioApps.launchable(this) },
                    )
                }
            }
        }
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** Audio permission first (only when audio is wanted), then the system consent dialog. */
    private fun requestProjection(target: ReceiverEndpoint, prefs: StreamPreferences, source: ShareSource) {
        pending = Pending(target, prefs, source.singleApp, source.audioPackage)
        if (prefs.audioEnabled && Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.RECORD_AUDIO)) {
            audioPermission.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchConsent()
        }
    }

    private fun launchConsent() {
        val p = pending ?: return
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // API 34+: the consent dialog offers exactly the scope chosen in the settings.
        val intent =
            when {
                Build.VERSION.SDK_INT < 34 -> mpm.createScreenCaptureIntent()
                p.singleApp -> mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())
                else -> mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            }
        consent.launch(intent)
    }

    private companion object {
        const val KEY_HOST = "pending.host"
        const val KEY_PORT = "pending.port"
        const val KEY_NAME = "pending.name"
        const val KEY_CODEC = "pending.codec"
        const val KEY_MAX_HEIGHT = "pending.maxHeight"
        const val KEY_FPS = "pending.fps"
        const val KEY_BITRATE = "pending.bitrate"
        const val KEY_AUDIO = "pending.audio"
        const val KEY_SYNC = "pending.sync"
        const val KEY_SINGLE_APP = "pending.singleApp"
        const val KEY_AUDIO_PACKAGE = "pending.audioPackage"
    }
}

/** What the consent dialog should offer, and which app's audio to capture when sharing one app. */
private data class ShareSource(val singleApp: Boolean, val audioPackage: String?)

@Composable
private fun SenderScreen(
    receivers: List<ReceiverEndpoint>,
    state: SenderState,
    onStart: (ReceiverEndpoint, StreamPreferences, ShareSource) -> Unit,
    onStop: () -> Unit,
    loadApps: () -> List<AudioApp>,
) {
    var manual by rememberSaveable { mutableStateOf("") }
    var codec by rememberSaveable { mutableStateOf<String?>(null) }
    var maxHeight by rememberSaveable { mutableStateOf(1080) }
    var fps by rememberSaveable { mutableStateOf(60) }
    var bitrateMbps by rememberSaveable { mutableStateOf(0) }
    var audio by rememberSaveable { mutableStateOf(true) }
    var sync by rememberSaveable { mutableStateOf(SyncMode.LIP_SYNC) }
    var singleApp by rememberSaveable { mutableStateOf(false) }
    var audioPackage by rememberSaveable { mutableStateOf<String?>(null) }
    var audioLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var pickingApp by remember { mutableStateOf(false) }
    val source = ShareSource(singleApp, if (singleApp && audio) audioPackage else null)
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
    val active = state is SenderState.Connecting || state is SenderState.Streaming

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
                        Modifier.fillMaxWidth().clickable(enabled = !active) { onStart(r, prefs, source) },
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(r.name, style = MaterialTheme.typography.titleMedium)
                        Text("${r.host}:${r.port}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
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
                    parsed?.let { onStart(it, prefs, source) }
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
            if (Build.VERSION.SDK_INT >= 34) { // older consent dialogs always share the whole screen
                ChoiceRow(
                    stringResource(R.string.sender_share),
                    listOf(
                        false to stringResource(R.string.sender_share_screen),
                        true to stringResource(R.string.sender_share_app),
                    ),
                    singleApp,
                ) { singleApp = it }
                if (singleApp && audio) {
                    // Playback capture is device-wide and Android does not say which app was
                    // picked in the consent dialog: the user names the audio source here (ADR-009).
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.sender_audio_from), style = MaterialTheme.typography.labelLarge)
                        OutlinedButton(onClick = { pickingApp = true }) {
                            Text(audioLabel ?: stringResource(R.string.sender_audio_from_none))
                        }
                        Text(
                            stringResource(
                                if (audioPackage ==
                                    null
                                ) {
                                    R.string.sender_audio_from_hint_none
                                } else {
                                    R.string.sender_audio_from_hint
                                },
                            ),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }

    if (pickingApp) {
        AppPickerDialog(
            loadApps = loadApps,
            onPick = { app ->
                audioPackage = app?.packageName
                audioLabel = app?.label
                pickingApp = false
            },
            onDismiss = { pickingApp = false },
        )
    }
}

/** Lists launchable apps (loaded off the main thread); "None" means video only. */
@Composable
private fun AppPickerDialog(loadApps: () -> List<AudioApp>, onPick: (AudioApp?) -> Unit, onDismiss: () -> Unit) {
    val apps by produceState<List<AudioApp>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { runCatching(loadApps).getOrDefault(emptyList()) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sender_audio_from)) },
        text = {
            val list = apps
            if (list == null) {
                CircularProgressIndicator()
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    item {
                        TextButton(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.sender_audio_from_none), Modifier.fillMaxWidth())
                        }
                    }
                    items(list, key = { it.packageName }) { app ->
                        TextButton(onClick = { onPick(app) }, modifier = Modifier.fillMaxWidth()) {
                            Text(app.label, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.sender_cancel)) } },
    )
}

@Composable
private fun StatusCard(state: SenderState, onStop: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (state) {
                SenderState.Idle -> Text(stringResource(R.string.sender_status_idle))

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

@Composable
private fun <T> ChoiceRow(label: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, text) ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(text) })
            }
        }
    }
}
