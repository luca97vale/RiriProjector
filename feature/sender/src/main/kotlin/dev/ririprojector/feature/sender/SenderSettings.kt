package dev.ririprojector.feature.sender

import dev.ririprojector.net.ReceiverEndpoint
import dev.ririprojector.protocol.model.StreamPreferences
import dev.ririprojector.protocol.model.SyncMode
import dev.ririprojector.protocol.model.VideoCodec

/** Flat string store behind the sender settings (SharedPreferences on device, a map in tests). */
interface KeyValueStore {
    fun getString(key: String): String?

    /** Writes all [values] at once; a null value removes the key. */
    fun putAll(values: Map<String, String?>)
}

/** What the tablet remembers across restarts (ADR-012). */
data class SenderSettings(
    val prefs: StreamPreferences = SenderSettingsCodec.DEFAULT_PREFS,
    /** The last receiver a session reached Streaming with; null if none or not TCP-connectable. */
    val lastReceiver: ReceiverEndpoint? = null,
)

/**
 * The quick-start target. [advertised]: the stored receiver matched a live NSD entry, so [endpoint]
 * is that entry (fresh IP) and the list marks it instead of showing a separate card.
 */
data class QuickStart(val endpoint: ReceiverEndpoint, val advertised: Boolean)

/**
 * Pure mapping between [SenderSettings] and string key/values. Every field is validated against the
 * choices the sender screen offers and falls back to its default on its own, so a value written by
 * another version never crashes the app. `preferredAudioCodec` is not persisted.
 */
object SenderSettingsCodec {
    const val VERSION = 1

    /** Choice lists of the sender screen; keep in sync with the chips in `SenderActivity.kt`. */
    val HEIGHTS = listOf(720, 1080, 1440)
    val FPS = listOf(30, 60)

    /** 0 = automatic. */
    val BITRATES_MBPS = listOf(0, 8, 16, 30)

    val DEFAULT_PREFS = StreamPreferences()

    /**
     * Placeholder host of Wi-Fi Direct sessions (`ProjectionService.DIRECT_HOST`, ADR-011): not a
     * TCP-connectable address, so never remembered. Keep in sync if it is renamed.
     */
    private const val WIFI_DIRECT_HOST = "wifi-direct"

    private const val MBPS = 1_000_000

    const val KEY_VERSION = "settings.version"
    const val KEY_CODEC = "stream.codec"
    const val KEY_MAX_HEIGHT = "stream.maxHeight"
    const val KEY_FPS = "stream.fps"
    const val KEY_BITRATE_MBPS = "stream.bitrateMbps"
    const val KEY_AUDIO = "stream.audio"
    const val KEY_SYNC = "stream.sync"
    const val KEY_LAST_NAME = "last.name"
    const val KEY_LAST_HOST = "last.host"
    const val KEY_LAST_PORT = "last.port"

    /** Codec stored when the user picked "Auto". */
    private const val AUTO = "AUTO"

    fun decode(read: (String) -> String?): SenderSettings {
        val d = DEFAULT_PREFS
        val codec = read(KEY_CODEC).let { stored ->
            if (stored == null || stored == AUTO) null else VideoCodec.entries.firstOrNull { it.name == stored }
        }
        val prefs =
            d.copy(
                preferredVideoCodec = codec,
                maxHeight = read(KEY_MAX_HEIGHT)?.toIntOrNull()?.takeIf { it in HEIGHTS } ?: d.maxHeight,
                maxFps = read(KEY_FPS)?.toIntOrNull()?.takeIf { it in FPS } ?: d.maxFps,
                bitrateBps = read(KEY_BITRATE_MBPS)?.toIntOrNull()?.takeIf { it in BITRATES_MBPS }
                    ?.let { if (it == 0) null else it * MBPS }
                    ?: d.bitrateBps,
                audioEnabled = read(KEY_AUDIO)?.toBooleanStrictOrNull() ?: d.audioEnabled,
                syncMode = SyncMode.entries.firstOrNull { it.name == read(KEY_SYNC) } ?: d.syncMode,
            )
        return SenderSettings(prefs, decodeReceiver(read))
    }

    private fun decodeReceiver(read: (String) -> String?): ReceiverEndpoint? {
        val host = read(KEY_LAST_HOST) ?: return null
        val port = read(KEY_LAST_PORT)?.toIntOrNull() ?: return null
        // The constructor rejects a blank host or a bad port.
        val endpoint = runCatching { ReceiverEndpoint(read(KEY_LAST_NAME) ?: host, host, port) }.getOrNull()
        return endpoint?.takeIf(::isRememberable)
    }

    fun encodePrefs(prefs: StreamPreferences): Map<String, String?> = mapOf(
        KEY_VERSION to VERSION.toString(),
        KEY_CODEC to (prefs.preferredVideoCodec?.name ?: AUTO),
        KEY_MAX_HEIGHT to prefs.maxHeight?.toString(),
        KEY_FPS to prefs.maxFps?.toString(),
        KEY_BITRATE_MBPS to (prefs.bitrateBps?.div(MBPS) ?: 0).toString(),
        KEY_AUDIO to prefs.audioEnabled.toString(),
        KEY_SYNC to prefs.syncMode.name,
    )

    fun encodeReceiver(r: ReceiverEndpoint): Map<String, String?> = mapOf(
        KEY_VERSION to VERSION.toString(),
        KEY_LAST_NAME to r.name,
        KEY_LAST_HOST to r.host,
        KEY_LAST_PORT to r.port.toString(),
    )

    /**
     * Only endpoints a later session can reach over TCP are remembered, and only if `host:port`
     * (the manual field pre-fill) parses back to the same endpoint: this rejects IPv6 literals,
     * which the manual field cannot express.
     */
    fun isRememberable(endpoint: ReceiverEndpoint): Boolean = endpoint.host != WIFI_DIRECT_HOST &&
        ReceiverEndpoint.parse("${endpoint.host}:${endpoint.port}")
            ?.let { it.host == endpoint.host && it.port == endpoint.port } == true

    /**
     * Matches [last] against the live NSD list: an exact host and port first (receiver names are
     * device names and may repeat), then the name (a Fire TV's DHCP lease may have changed its IP).
     * Without a match the stored endpoint is used as is.
     */
    fun resolveQuickStart(last: ReceiverEndpoint?, nsd: List<ReceiverEndpoint>): QuickStart? {
        last ?: return null
        val match =
            nsd.firstOrNull { it.host == last.host && it.port == last.port }
                ?: nsd.firstOrNull { it.name == last.name }
        return if (match != null) QuickStart(match, advertised = true) else QuickStart(last, advertised = false)
    }
}

/** Loads and saves [SenderSettings]; writes go to [store] in one batch. */
class SenderSettingsRepository(private val store: KeyValueStore) {
    fun load(): SenderSettings = SenderSettingsCodec.decode(store::getString)

    fun savePrefs(prefs: StreamPreferences) = store.putAll(SenderSettingsCodec.encodePrefs(prefs))

    /** Ignored for endpoints that cannot be reconnected over TCP (e.g. Wi-Fi Direct). */
    fun saveLastReceiver(r: ReceiverEndpoint) {
        if (SenderSettingsCodec.isRememberable(r)) store.putAll(SenderSettingsCodec.encodeReceiver(r))
    }
}
