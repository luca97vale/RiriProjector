package dev.ririprojector.feature.sender

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import dev.ririprojector.net.ReceiverEndpoint
import dev.ririprojector.net.nsd.NsdBrowser
import dev.ririprojector.protocol.model.StreamPreferences
import dev.ririprojector.protocol.model.VideoCodec
import kotlinx.coroutines.flow.catch

/** Tablet entry point: pick a receiver, tune the stream, start/stop mirroring. */
class SenderActivity : ComponentActivity() {
    private var pending: Pair<ReceiverEndpoint, StreamPreferences>? = null

    private val consent =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val (target, prefs) = pending ?: return@registerForActivityResult
            pending = null
            val data = result.data
            if (result.resultCode == Activity.RESULT_OK && data != null) {
                ProjectionService.start(this, result.resultCode, data, target, prefs)
            }
        }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val receivers = NsdBrowser(this).receivers().catch { emit(emptyList()) }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val found by receivers.collectAsState(initial = emptyList())
                    val state by SenderStatus.state.collectAsState()
                    SenderScreen(
                        receivers = found,
                        state = state,
                        onStart = ::requestProjection,
                        onStop = { ProjectionService.stop(this) },
                    )
                }
            }
        }
    }

    private fun requestProjection(target: ReceiverEndpoint, prefs: StreamPreferences) {
        pending = target to prefs
        consent.launch(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
    }
}

@Composable
private fun SenderScreen(
    receivers: List<ReceiverEndpoint>,
    state: SenderState,
    onStart: (ReceiverEndpoint, StreamPreferences) -> Unit,
    onStop: () -> Unit,
) {
    var manual by rememberSaveable { mutableStateOf("") }
    var codec by rememberSaveable { mutableStateOf<String?>(null) }
    var maxHeight by rememberSaveable { mutableStateOf(1080) }
    var fps by rememberSaveable { mutableStateOf(60) }
    var bitrateMbps by rememberSaveable { mutableStateOf(0) }
    val prefs =
        remember(codec, maxHeight, fps, bitrateMbps) {
            StreamPreferences(
                preferredVideoCodec = codec?.let { VideoCodec.valueOf(it) },
                maxHeight = maxHeight,
                maxFps = fps,
                bitrateBps = if (bitrateMbps > 0) bitrateMbps * 1_000_000 else null,
            )
        }
    val active = state is SenderState.Connecting || state is SenderState.Streaming

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
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
                Button(enabled = parsed != null && !active, onClick = { parsed?.let { onStart(it, prefs) } }) {
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
        }
    }
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
