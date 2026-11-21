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
        val tv = ReceiverEndpoint("Living room", "192.168.1.50", 47_800)
        repo.savePrefs(prefs)
        repo.saveLastReceiver(tv)
        assertThat(repo.load()).isEqualTo(SenderSettings(prefs, tv))
    }
}
