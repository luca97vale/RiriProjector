package dev.ririprojector.feature.sender

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.ririprojector.core.ui.DogLoaderMode
import dev.ririprojector.core.ui.DogLoaderView
import dev.ririprojector.core.ui.R as UiR
import dev.ririprojector.net.ReceiverEndpoint
import dev.ririprojector.protocol.model.StreamPreferences
import dev.ririprojector.protocol.model.SyncMode
import dev.ririprojector.protocol.model.VideoCodec

/** Screens wider than this get two panes (receivers left, status and settings right). */
private val TWO_PANE_MIN_WIDTH = 840.dp

/** Keeps the stream preferences across rotation through the same codec that persists them. */
private val PrefsSaver =
    Saver<StreamPreferences, HashMap<String, String?>>(
        save = { HashMap(SenderSettingsCodec.encodePrefs(it)) },
        restore = { map -> SenderSettingsCodec.decode { map[it] }.prefs },
    )

@Composable
internal fun SenderScreen(
    receivers: List<ReceiverEndpoint>,
    state: SenderState,
    onStart: (ReceiverEndpoint, StreamPreferences) -> Unit,
    onStop: () -> Unit,
    direct: DirectUi,
    onUseAsReceiver: () -> Unit,
    settings: SenderSettings,
    onSettingsChange: (StreamPreferences) -> Unit,
    appVersion: String,
) {
    val last = settings.lastReceiver
    var manual by rememberSaveable { mutableStateOf(last?.let { "${it.host}:${it.port}" } ?: "") }
    var prefs by rememberSaveable(stateSaver = PrefsSaver) { mutableStateOf(settings.prefs) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(prefs) { onSettingsChange(prefs) }
    val quickStart = remember(last, receivers) { SenderSettingsCodec.resolveQuickStart(last, receivers) }
    val active = state !is SenderState.Idle && state !is SenderState.Ended
    val start: (ReceiverEndpoint) -> Unit = { onStart(it, prefs) }

    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        if (maxWidth >= TWO_PANE_MIN_WIDTH) {
            Row(Modifier.fillMaxSize()) {
                Pane(Modifier.width(440.dp).fillMaxHeight().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Header(onSettings = null)
                    if (!active) {
                        LastReceiverCard(quickStart, start)
                        ReceiverList(receivers, quickStart, start)
                        ManualEntry(manual, { manual = it }, start)
                        DirectSection(direct, prefs)
                    }
                }
                Pane(Modifier.weight(1f).fillMaxHeight(), maxContentWidth = 720.dp) {
                    StatusPanel(state, onStop, idleText = stringResource(R.string.sender_status_idle_wide), direct.link)
                    if (!active) {
                        SettingsContent(prefs, { prefs = it }, onUseAsReceiver, appVersion, columns = 2)
                    }
                }
            }
        } else if (showSettings) {
            BackHandler { showSettings = false }
            Pane(Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalIconButton(onClick = { showSettings = false }, modifier = Modifier.size(48.dp)) {
                        Icon(
                            painterResource(R.drawable.ic_back),
                            contentDescription = stringResource(R.string.sender_back),
                        )
                    }
                    Text(stringResource(R.string.sender_settings), style = MaterialTheme.typography.titleLarge)
                }
                SettingsContent(prefs, { prefs = it }, onUseAsReceiver.takeIf { !active }, appVersion, columns = 1)
            }
        } else {
            Pane(Modifier.fillMaxSize()) {
                Header(onSettings = { showSettings = true })
                StatusPanel(state, onStop, idleText = stringResource(R.string.sender_status_idle), direct.link)
                if (!active) {
                    LastReceiverCard(quickStart, start)
                    ReceiverList(receivers, quickStart, start)
                    ManualEntry(manual, { manual = it }, start)
                    DirectSection(direct, prefs)
                    SettingsSummary(prefs) { showSettings = true }
                    TextButton(onClick = onUseAsReceiver, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Icon(
                            painterResource(R.drawable.ic_swap),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.sender_use_as_receiver))
                    }
                }
            }
        }
    }
}

@Composable
private fun Pane(
    modifier: Modifier,
    maxContentWidth: androidx.compose.ui.unit.Dp = 640.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            Modifier.widthIn(max = maxContentWidth).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content,
        )
    }
}

@Composable
private fun Header(onSettings: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Image(
            painterResource(UiR.drawable.riri_dog_art),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.sender_brand), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.sender_title),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (onSettings != null) {
            FilledTonalIconButton(onClick = onSettings, modifier = Modifier.size(48.dp)) {
                Icon(
                    painterResource(R.drawable.ic_settings),
                    contentDescription = stringResource(R.string.sender_settings),
                )
            }
        }
    }
}

@Composable
internal fun BrandCard(
    modifier: Modifier = Modifier,
    border: Color? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = container,
        border = androidx.compose.foundation.BorderStroke(1.dp, border ?: MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(content = content)
    }
}

@Composable
private fun SectionLabel(text: String, color: Color = LocalRiriColors.current.cyanLight) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun IconTile(icon: Int, tint: Color = LocalRiriColors.current.cyanLight) {
    Surface(shape = RoundedCornerShape(12.dp), color = LocalRiriColors.current.tile, modifier = Modifier.size(44.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.padding(11.dp))
    }
}

@Composable
private fun ReceiverList(
    receivers: List<ReceiverEndpoint>,
    quickStart: QuickStart?,
    onStart: (ReceiverEndpoint) -> Unit,
) {
    val lastUsed = quickStart?.takeIf { it.advertised }?.endpoint
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            stringResource(R.string.sender_receivers),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(R.string.sender_same_network),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    BrandCard {
        receivers.forEach { r ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) {
                    onStart(r)
                }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconTile(R.drawable.ic_tv)
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${r.host}:${r.port}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (r == lastUsed) Chip(stringResource(R.string.sender_last_used))
                Icon(
                    painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(Modifier.padding(start = 74.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.5.dp)
            Text(
                stringResource(R.string.sender_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun Chip(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = LocalRiriColors.current.cyanLight,
        modifier = Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun ManualEntry(manual: String, onChange: (String) -> Unit, onStart: (ReceiverEndpoint) -> Unit) {
    val parsed = ReceiverEndpoint.parse(manual)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = manual,
            onValueChange = onChange,
            label = { Text(stringResource(R.string.sender_manual_label)) },
            placeholder = { Text(stringResource(R.string.sender_manual_hint)) },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(
            enabled = parsed != null,
            onClick = { parsed?.let(onStart) },
            modifier = Modifier.heightIn(min = 56.dp),
            shape = MaterialTheme.shapes.medium,
        ) { Text(stringResource(R.string.sender_connect)) }
    }
}

@Composable
private fun SettingsSummary(prefs: StreamPreferences, onClick: () -> Unit) {
    val auto = stringResource(R.string.sender_auto)
    BrandCard(Modifier.clickable(role = Role.Button, onClick = onClick)) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.sender_settings), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(
                        R.string.sender_settings_summary,
                        prefs.preferredVideoCodec?.label() ?: auto,
                        prefs.maxHeight ?: 1080,
                        prefs.maxFps ?: 60,
                        prefs.bitrateBps?.let { "${it / 1_000_000} Mbps" } ?: auto,
                        stringResource(
                            if (prefs.audioEnabled) R.string.sender_audio_state_on else R.string.sender_audio_state_off,
                        ),
                        stringResource(
                            if (prefs.syncMode ==
                                SyncMode.LIP_SYNC
                            ) {
                                R.string.sender_sync_lip
                            } else {
                                R.string.sender_sync_latency
                            },
                        ),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                painterResource(R.drawable.ic_chevron),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun VideoCodec.label(): String = if (this == VideoCodec.AVC) "H.264" else name

/** The six stream settings as segmented buttons; persisted by the caller (ADR-012). */
@Composable
private fun SettingsContent(
    prefs: StreamPreferences,
    onChange: (StreamPreferences) -> Unit,
    onUseAsReceiver: (() -> Unit)?,
    appVersion: String,
    columns: Int,
) {
    val auto = stringResource(R.string.sender_auto)
    Text(
        stringResource(R.string.sender_saved_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val video: List<@Composable () -> Unit> =
        listOf(
            {
                Choice(
                    stringResource(R.string.sender_codec),
                    listOf(null to auto, VideoCodec.HEVC to "HEVC", VideoCodec.AVC to "H.264"),
                    prefs.preferredVideoCodec,
                    hint = stringResource(R.string.sender_codec_hint),
                ) { onChange(prefs.copy(preferredVideoCodec = it)) }
            },
            {
                Choice(
                    stringResource(R.string.sender_resolution),
                    SenderSettingsCodec.HEIGHTS.map { it to "${it}p" },
                    prefs.maxHeight,
                ) { onChange(prefs.copy(maxHeight = it)) }
            },
            {
                Choice(stringResource(R.string.sender_fps), SenderSettingsCodec.FPS.map { it to "$it" }, prefs.maxFps) {
                    onChange(prefs.copy(maxFps = it))
                }
            },
            {
                Choice(
                    stringResource(R.string.sender_bitrate),
                    SenderSettingsCodec.BITRATES_MBPS.map { it to if (it == 0) auto else "$it Mbps" },
                    (prefs.bitrateBps ?: 0) / 1_000_000,
                ) { onChange(prefs.copy(bitrateBps = if (it > 0) it * 1_000_000 else null)) }
            },
        )
    val audio: List<@Composable () -> Unit> =
        listOf(
            {
                Choice(
                    stringResource(R.string.sender_audio),
                    listOf(true to stringResource(R.string.sender_on), false to stringResource(R.string.sender_off)),
                    prefs.audioEnabled,
                ) { onChange(prefs.copy(audioEnabled = it)) }
            },
            {
                Choice(
                    stringResource(R.string.sender_sync),
                    listOf(
                        SyncMode.LIP_SYNC to stringResource(R.string.sender_sync_lip),
                        SyncMode.LOW_LATENCY to stringResource(R.string.sender_sync_latency),
                    ),
                    prefs.syncMode,
                    hint = stringResource(R.string.sender_sync_hint),
                ) { onChange(prefs.copy(syncMode = it)) }
            },
        )
    SectionLabel(stringResource(R.string.sender_section_video))
    SettingsGroup(video, columns)
    SectionLabel(stringResource(R.string.sender_section_audio))
    SettingsGroup(audio, columns)
    SectionLabel(stringResource(R.string.sender_section_device))
    BrandCard {
        if (onUseAsReceiver != null) {
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onUseAsReceiver).heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_swap),
                    contentDescription = null,
                    tint = LocalRiriColors.current.cyanLight,
                )
                Text(stringResource(R.string.sender_use_as_receiver), style = MaterialTheme.typography.titleSmall)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Row(
            Modifier.heightIn(min = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.sender_version), modifier = Modifier.weight(1f))
            Text(appVersion, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsGroup(items: List<@Composable () -> Unit>, columns: Int) {
    BrandCard {
        items.chunked(columns).forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                row.forEach { item -> Column(Modifier.weight(1f)) { item() } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun <T> Choice(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    hint: String? = null,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, (value, text) ->
                SegmentedButton(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                    icon = {},
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary,
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                        inactiveContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                    ),
                    label = { Text(text, maxLines = 1, fontSize = 13.sp) },
                )
            }
        }
        if (hint != null) {
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One-tap reconnect to the last receiver when it is not in the discovered list (ADR-012). */
@Composable
private fun LastReceiverCard(quickStart: QuickStart?, onStart: (ReceiverEndpoint) -> Unit) {
    if (quickStart == null || quickStart.advertised) return
    val r = quickStart.endpoint
    BrandCard(border = MaterialTheme.colorScheme.primary) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel(stringResource(R.string.sender_last_receiver))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconTile(R.drawable.ic_history)
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.sender_last_not_seen, r.host, r.port),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Button(onClick = {
                onStart(r)
            }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), shape = MaterialTheme.shapes.medium) {
                Text(stringResource(R.string.sender_mirror_again))
            }
        }
    }
}

/** Status: idle line, dog loader while connecting, live tiles while streaming, the reason when stopped. */
@Composable
private fun StatusPanel(state: SenderState, onStop: () -> Unit, idleText: String, link: DirectLink?) {
    val extra = LocalRiriColors.current
    when (state) {
        SenderState.Idle ->
            BrandCard(container = MaterialTheme.colorScheme.surfaceContainerLow) {
                Row(
                    Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Dot(MaterialTheme.colorScheme.outline)
                    Text(idleText, style = MaterialTheme.typography.bodyLarge)
                }
            }

        is SenderState.Connecting -> LoaderCard(
            DogLoaderMode.LOADING,
            stringResource(R.string.sender_status_connecting, state.target.name),
            stringResource(R.string.sender_connecting_hint),
            null,
            onStop,
        )

        is SenderState.Reconnecting -> LoaderCard(
            DogLoaderMode.RECONNECTING,
            stringResource(R.string.sender_status_reconnecting, state.target.name),
            stringResource(R.string.sender_reconnect_hint),
            stringResource(R.string.sender_reconnect_attempt, state.attempt),
            onStop,
        )

        is SenderState.Streaming -> Streaming(state, onStop, link)

        is SenderState.Ended -> {
            val failed = state.message != null
            BrandCard(
                container = if (failed) extra.errorContainer else MaterialTheme.colorScheme.surfaceContainer,
                border = if (failed) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else null,
            ) {
                Row(
                    Modifier.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Dot(if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline)
                    Text(
                        state.message?.let { stringResource(R.string.sender_status_failed, it) }
                            ?: stringResource(R.string.sender_status_ended),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
    }
}

@Composable
private fun Dot(color: Color) {
    Spacer(Modifier.size(12.dp).clip(CircleShape).background(color))
}

@Composable
private fun LoaderCard(mode: DogLoaderMode, title: String, hint: String, badge: String?, onStop: () -> Unit) {
    val loaderDescription = stringResource(R.string.sender_loader_description)
    BrandCard(container = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            AndroidView(
                factory = { DogLoaderView(it).apply { contentDescription = loaderDescription } },
                update = {
                    it.mode = mode
                    it.showPaws = mode == DogLoaderMode.LOADING
                },
                modifier = Modifier.size(
                    width = 220.dp,
                    height = if (mode ==
                        DogLoaderMode.LOADING
                    ) {
                        268.dp
                    } else {
                        220.dp
                    },
                ),
            )
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (badge != null) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelLarge,
                    color = LocalRiriColors.current.onWarnContainer,
                    modifier = Modifier.clip(CircleShape).background(LocalRiriColors.current.warnContainer)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            FilledTonalButton(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Text(stringResource(R.string.sender_stop))
            }
        }
    }
}

@Composable
private fun Streaming(state: SenderState.Streaming, onStop: () -> Unit, link: DirectLink?) {
    val v = state.params.video
    val s = state.stats
    val extra = LocalRiriColors.current
    BrandCard(container = MaterialTheme.colorScheme.surfaceContainerLow, border = MaterialTheme.colorScheme.primary) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Dot(MaterialTheme.colorScheme.primary)
                SectionLabel(stringResource(R.string.sender_live))
                link?.let { Chip(stringResource(R.string.sender_direct_link, it.frequencyMhz)) }
            }
            Text(
                stringResource(R.string.sender_status_streaming, state.target.name),
                style = MaterialTheme.typography.headlineSmall,
            )
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(
                    MaterialTheme.colorScheme.error,
                    MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                shape = MaterialTheme.shapes.medium,
            ) {
                Icon(painterResource(R.drawable.ic_stop), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.sender_stop), fontWeight = FontWeight.Bold)
            }
        }
    }
    if (s.contentHidden) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(extra.warnContainer).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painterResource(R.drawable.ic_warning),
                contentDescription = null,
                tint = extra.onWarnContainer,
                modifier = Modifier.size(20.dp),
            )
            Text(
                stringResource(R.string.sender_content_hidden),
                color = extra.onWarnContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    BrandCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel(stringResource(R.string.sender_tile_latency), MaterialTheme.colorScheme.onSurfaceVariant)
            if (s.latencyMs != null) {
                Text(
                    stringResource(R.string.sender_tile_latency_value, s.latencyMs),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.sender_tile_latency_detail, s.latencyP95Ms ?: s.latencyMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(stringResource(R.string.sender_tile_latency_unknown), style = MaterialTheme.typography.titleLarge)
            }
        }
    }
    val tiles =
        listOf(
            Triple(
                stringResource(R.string.sender_tile_video),
                stringResource(R.string.sender_tile_video_value, s.encodedFps),
                stringResource(R.string.sender_tile_video_detail, v.codec.label(), v.width, v.height),
            ),
            Triple(
                stringResource(R.string.sender_tile_bitrate),
                stringResource(R.string.sender_tile_bitrate_value, s.bitrateBps / 1_000_000.0),
                s.rttMs?.let { stringResource(R.string.sender_tile_bitrate_detail, it) } ?: "",
            ),
            Triple(
                stringResource(R.string.sender_tile_receiver),
                stringResource(R.string.sender_tile_receiver_value, s.receiverFps),
                stringResource(R.string.sender_tile_receiver_detail, s.receiverDecodeMs, s.receiverDecodeP95Ms),
            ),
            Triple(
                stringResource(R.string.sender_tile_dropped),
                stringResource(R.string.sender_tile_dropped_value, s.sendDropped, s.receiverDropped),
                stringResource(R.string.sender_tile_dropped_detail),
            ),
        )
    tiles.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { (label, value, detail) ->
                BrandCard(Modifier.weight(1f)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        SectionLabel(label, MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = MaterialTheme.typography.titleLarge)
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
    BrandCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(painterResource(R.drawable.ic_volume), contentDescription = null, tint = extra.cyanLight)
                Text(
                    when (s.audio) {
                        AudioStatus.ON -> stringResource(R.string.sender_audio_card_on)
                        AudioStatus.OFF -> stringResource(R.string.sender_audio_off)
                        AudioStatus.UNAVAILABLE -> stringResource(R.string.sender_audio_unavailable)
                        AudioStatus.NO_APP -> stringResource(R.string.sender_audio_no_app)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Chip(
                    stringResource(
                        if (state.params.syncMode ==
                            SyncMode.LIP_SYNC
                        ) {
                            R.string.sender_sync_lip
                        } else {
                            R.string.sender_sync_latency
                        },
                    ),
                )
            }
            if (s.audio == AudioStatus.ON) {
                val lines = buildList {
                    add(stringResource(R.string.sender_audio_card_detail, s.receiverUnderruns, s.avOffsetMs))
                    if (s.receiverVideoDelayMs >
                        0
                    ) {
                        add(stringResource(R.string.sender_video_delay, s.receiverVideoDelayMs))
                    }
                    add(stringResource(R.string.sender_audio_card_blocked))
                }
                Text(
                    lines.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    var details by rememberSaveable { mutableStateOf(false) }
    BrandCard(Modifier.clickable(role = Role.Button) { details = !details }) {
        Column(
            Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.sender_tech_details), style = MaterialTheme.typography.titleSmall)
            if (details) {
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
                    ) + if (s.encoderName.isNotEmpty()) "\n${s.encoderName}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Wi-Fi Direct (ADR-011): opt-in search, then the receivers found over a direct link. */
@Composable
private fun DirectSection(direct: DirectUi, prefs: StreamPreferences) {
    if (!direct.available) return
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            stringResource(R.string.sender_direct_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
    }
    Text(
        stringResource(R.string.sender_direct_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (!direct.searching) {
        FilledTonalButton(
            onClick = direct.onSearch,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            shape = MaterialTheme.shapes.medium,
        ) { Text(stringResource(R.string.sender_direct_search)) }
        return
    }
    BrandCard {
        direct.receivers.forEach { r ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) {
                    direct.onStart(r, prefs)
                }.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                IconTile(R.drawable.ic_tv)
                Column(Modifier.weight(1f)) {
                    Text(r.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        stringResource(R.string.sender_direct_title),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    painterResource(R.drawable.ic_chevron),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(Modifier.padding(start = 74.dp), color = MaterialTheme.colorScheme.outlineVariant)
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.5.dp)
            Text(
                stringResource(R.string.sender_direct_searching),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
