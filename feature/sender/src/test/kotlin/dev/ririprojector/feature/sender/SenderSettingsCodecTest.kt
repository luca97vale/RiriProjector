package dev.ririprojector.feature.sender

import com.google.common.truth.Truth.assertThat
import dev.ririprojector.net.ReceiverEndpoint
import dev.ririprojector.protocol.model.StreamPreferences
import dev.ririprojector.protocol.model.SyncMode
import dev.ririprojector.protocol.model.VideoCodec
import org.junit.Test

class SenderSettingsCodecTest {
    private class MapStore(val map: MutableMap<String, String> = mutableMapOf()) : KeyValueStore {
        override fun getString(key: String) = map[key]

        override fun putAll(values: Map<String, String?>) {
            values.forEach { (k, v) -> if (v == null) map.remove(k) else map[k] = v }
        }
    }

    private val tv = ReceiverEndpoint("Living room", "192.168.1.50", 47_800)
    private val defaults = SenderSettingsCodec.DEFAULT_PREFS

    private fun load(vararg pairs: Pair<String, String>) =
        SenderSettingsRepository(MapStore(mutableMapOf(*pairs))).load()

    private fun roundTrip(prefs: StreamPreferences): StreamPreferences {
        val repo = SenderSettingsRepository(MapStore())
        repo.savePrefs(prefs)
        return repo.load().prefs
    }

    @Test
    fun `empty store gives defaults`() {
        assertThat(SenderSettingsRepository(MapStore()).load()).isEqualTo(SenderSettings())
    }

    @Test
    fun `prefs and last receiver round trip`() {
        val repo = SenderSettingsRepository(MapStore())
        val prefs =
            StreamPreferences(
                preferredVideoCodec = VideoCodec.AVC,
                maxHeight = 720,
                maxFps = 30,
                bitrateBps = 16_000_000,
                audioEnabled = false,
                syncMode = SyncMode.LOW_LATENCY,
            )
        repo.savePrefs(prefs)
        repo.saveLastReceiver(tv)
        assertThat(repo.load()).isEqualTo(SenderSettings(prefs, tv))
    }

    @Test
    fun `every height round trips`() {
        SenderSettingsCodec.HEIGHTS.forEach {
            assertThat(roundTrip(defaults.copy(maxHeight = it)).maxHeight).isEqualTo(it)
        }
    }

    @Test
    fun `every fps round trips`() {
        SenderSettingsCodec.FPS.forEach {
            assertThat(roundTrip(defaults.copy(maxFps = it)).maxFps).isEqualTo(it)
        }
    }

    @Test
    fun `every bitrate choice round trips and zero means automatic`() {
        SenderSettingsCodec.BITRATES_MBPS.forEach { mbps ->
            val bps = if (mbps == 0) null else mbps * 1_000_000
            assertThat(roundTrip(defaults.copy(bitrateBps = bps)).bitrateBps).isEqualTo(bps)
        }
    }

    @Test
    fun `every codec and auto round trips`() {
        (VideoCodec.entries + listOf(null)).forEach {
            assertThat(roundTrip(defaults.copy(preferredVideoCodec = it)).preferredVideoCodec).isEqualTo(it)
        }
    }

    @Test
    fun `both sync modes round trip`() {
        SyncMode.entries.forEach {
            assertThat(roundTrip(defaults.copy(syncMode = it)).syncMode).isEqualTo(it)
        }
    }

    @Test
    fun `audio on and off round trip`() {
        listOf(true, false).forEach {
            assertThat(roundTrip(defaults.copy(audioEnabled = it)).audioEnabled).isEqualTo(it)
        }
    }

    @Test
    fun `unknown codec falls back to default`() {
        assertThat(load(SenderSettingsCodec.KEY_CODEC to "VP9").prefs.preferredVideoCodec)
            .isEqualTo(defaults.preferredVideoCodec)
    }

    @Test
    fun `unknown sync mode falls back to default`() {
        assertThat(load(SenderSettingsCodec.KEY_SYNC to "WARP").prefs.syncMode).isEqualTo(defaults.syncMode)
    }

    @Test
    fun `height not offered falls back to default`() {
        assertThat(load(SenderSettingsCodec.KEY_MAX_HEIGHT to "999").prefs.maxHeight).isEqualTo(defaults.maxHeight)
    }

    @Test
    fun `fps not offered falls back to default`() {
        assertThat(load(SenderSettingsCodec.KEY_FPS to "45").prefs.maxFps).isEqualTo(defaults.maxFps)
    }

    @Test
    fun `bitrate not offered falls back to default`() {
        assertThat(load(SenderSettingsCodec.KEY_BITRATE_MBPS to "12").prefs.bitrateBps).isEqualTo(defaults.bitrateBps)
    }

    @Test
    fun `non numeric height falls back to default`() {
        assertThat(load(SenderSettingsCodec.KEY_MAX_HEIGHT to "tall").prefs.maxHeight).isEqualTo(defaults.maxHeight)
    }

    @Test
    fun `non boolean audio falls back to default`() {
        assertThat(load(SenderSettingsCodec.KEY_AUDIO to "yes").prefs.audioEnabled).isEqualTo(defaults.audioEnabled)
    }

    @Test
    fun `one invalid field does not discard the valid ones`() {
        val s =
            load(
                SenderSettingsCodec.KEY_FPS to "45",
                SenderSettingsCodec.KEY_MAX_HEIGHT to "1440",
                SenderSettingsCodec.KEY_SYNC to "LOW_LATENCY",
            )
        assertThat(s.prefs.maxFps).isEqualTo(defaults.maxFps)
        assertThat(s.prefs.maxHeight).isEqualTo(1440)
        assertThat(s.prefs.syncMode).isEqualTo(SyncMode.LOW_LATENCY)
    }

    @Test
    fun `non numeric port gives no last receiver`() {
        val s = load(SenderSettingsCodec.KEY_LAST_HOST to "10.0.0.2", SenderSettingsCodec.KEY_LAST_PORT to "abc")
        assertThat(s.lastReceiver).isNull()
    }

    @Test
    fun `port zero gives no last receiver`() {
        val s = load(SenderSettingsCodec.KEY_LAST_HOST to "10.0.0.2", SenderSettingsCodec.KEY_LAST_PORT to "0")
        assertThat(s.lastReceiver).isNull()
    }

    @Test
    fun `port above 65535 gives no last receiver`() {
        val s = load(SenderSettingsCodec.KEY_LAST_HOST to "10.0.0.2", SenderSettingsCodec.KEY_LAST_PORT to "70000")
        assertThat(s.lastReceiver).isNull()
    }

    @Test
    fun `empty host gives no last receiver`() {
        val s = load(SenderSettingsCodec.KEY_LAST_HOST to "", SenderSettingsCodec.KEY_LAST_PORT to "47800")
        assertThat(s.lastReceiver).isNull()
    }

    @Test
    fun `missing host or port gives no last receiver`() {
        assertThat(load(SenderSettingsCodec.KEY_LAST_PORT to "47800").lastReceiver).isNull()
        assertThat(load(SenderSettingsCodec.KEY_LAST_HOST to "10.0.0.2").lastReceiver).isNull()
    }

    @Test
    fun `missing last name falls back to host`() {
        val s = load(SenderSettingsCodec.KEY_LAST_HOST to "10.0.0.2", SenderSettingsCodec.KEY_LAST_PORT to "47801")
        assertThat(s.lastReceiver).isEqualTo(ReceiverEndpoint("10.0.0.2", "10.0.0.2", 47_801))
    }

    @Test
    fun `stored wifi direct receiver decodes to null`() {
        val s =
            load(
                SenderSettingsCodec.KEY_LAST_NAME to "Direct TV",
                SenderSettingsCodec.KEY_LAST_HOST to "wifi-direct",
                SenderSettingsCodec.KEY_LAST_PORT to "47800",
            )
        assertThat(s.lastReceiver).isNull()
    }

    @Test
    fun `isRememberable rejects wifi direct placeholder`() {
        assertThat(SenderSettingsCodec.isRememberable(ReceiverEndpoint("TV", "wifi-direct", 47_800))).isFalse()
    }

    @Test
    fun `isRememberable accepts IPv4 and hostnames`() {
        assertThat(SenderSettingsCodec.isRememberable(tv)).isTrue()
        assertThat(SenderSettingsCodec.isRememberable(ReceiverEndpoint("TV", "firetv.local", 1234))).isTrue()
    }

    @Test
    fun `resolveQuickStart without last receiver is null`() {
        assertThat(SenderSettingsCodec.resolveQuickStart(null, listOf(tv))).isNull()
    }

    @Test
    fun `resolveQuickStart uses the NSD entry matched by name with its fresh host`() {
        val fresh = ReceiverEndpoint("Living room", "192.168.1.99", 47_801)
        val other = ReceiverEndpoint("Bedroom", "192.168.1.60", 47_800)
        val q = SenderSettingsCodec.resolveQuickStart(tv, listOf(other, fresh))
        assertThat(q).isEqualTo(QuickStart(fresh, advertised = true))
    }

    @Test
    fun `resolveQuickStart matches a manual endpoint by host and port`() {
        val manual = ReceiverEndpoint("10.0.0.7", "10.0.0.7", 47_800)
        val listed = ReceiverEndpoint("Some name", "10.0.0.7", 47_800)
        val q = SenderSettingsCodec.resolveQuickStart(manual, listOf(listed))
        assertThat(q).isEqualTo(QuickStart(listed, advertised = true))
    }

    @Test
    fun `resolveQuickStart without a match keeps the stored endpoint as not advertised`() {
        val other = ReceiverEndpoint("Bedroom", "192.168.1.60", 47_800)
        assertThat(SenderSettingsCodec.resolveQuickStart(tv, listOf(other)))
            .isEqualTo(QuickStart(tv, advertised = false))
        assertThat(SenderSettingsCodec.resolveQuickStart(tv, emptyList()))
            .isEqualTo(QuickStart(tv, advertised = false))
    }

    @Test
    fun `resolveQuickStart prefers the exact host match over a name match`() {
        // Two Fire TVs with the same default device name: the one at the stored address wins.
        val sameName = ReceiverEndpoint(tv.name, "192.168.1.77", 47_800)
        val exact = ReceiverEndpoint(tv.name, tv.host, tv.port)
        val q = SenderSettingsCodec.resolveQuickStart(tv, listOf(sameName, exact))
        assertThat(q).isEqualTo(QuickStart(exact, advertised = true))
    }

    @Test
    fun `isRememberable rejects IPv6 literals the manual field cannot round trip`() {
        assertThat(SenderSettingsCodec.isRememberable(ReceiverEndpoint("TV", "fe80::1", 47_800))).isFalse()
    }

    @Test
    fun `repository ignores non rememberable receivers`() {
        val store = MapStore()
        SenderSettingsRepository(store).saveLastReceiver(ReceiverEndpoint("Direct", "wifi-direct", 47_800))
        assertThat(store.map).isEmpty()
    }

    @Test
    fun `saving a receiver does not clobber prefs`() {
        val repo = SenderSettingsRepository(MapStore())
        val prefs = defaults.copy(maxHeight = 1440, maxFps = 30, audioEnabled = false)
        repo.savePrefs(prefs)
        repo.saveLastReceiver(tv)
        assertThat(repo.load().prefs).isEqualTo(prefs)
    }

    @Test
    fun `saving prefs does not clobber the last receiver`() {
        val repo = SenderSettingsRepository(MapStore())
        repo.saveLastReceiver(tv)
        repo.savePrefs(defaults.copy(maxHeight = 720))
        assertThat(repo.load().lastReceiver).isEqualTo(tv)
    }

    @Test
    fun `non rememberable save keeps the previously stored receiver`() {
        val repo = SenderSettingsRepository(MapStore())
        repo.saveLastReceiver(tv)
        repo.saveLastReceiver(ReceiverEndpoint("Direct", "wifi-direct", 47_800))
        assertThat(repo.load().lastReceiver).isEqualTo(tv)
    }

    /** The values the sender screen chips offer (SenderActivity.kt); the codec lists must match them. */
    @Test
    fun `choice lists match the sender screen chips`() {
        assertThat(SenderSettingsCodec.HEIGHTS).containsExactly(720, 1080, 1440).inOrder()
        assertThat(SenderSettingsCodec.FPS).containsExactly(30, 60).inOrder()
        assertThat(SenderSettingsCodec.BITRATES_MBPS).containsExactly(0, 8, 16, 30).inOrder()
    }
}
