package dev.ririprojector.net.p2p

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.security.SecureRandom
import org.junit.Test

class P2pPolicyTest {
    private val offer = P2pOfferRecord("Tab", "DIRECT-ab-Riri", "abcdefgh12345678", "76:e2:0c:bd:fd:c9")

    @Test
    fun `outage limits are 1,5 s over Wi-Fi Direct and 30 s over the router`() {
        assertThat(P2pPolicy.OUTAGE_LIMIT_MS).isEqualTo(1_500L)
        assertThat(P2pPolicy.ROUTER_OUTAGE_LIMIT_MS).isEqualTo(30_000L)
    }

    @Test
    fun `channelFor shares the frequency at both 5 GHz boundaries`() {
        assertThat(P2pPolicy.channelFor(4_900)).isEqualTo(GroupChannel.Frequency(4_900))
        assertThat(P2pPolicy.channelFor(5_900)).isEqualTo(GroupChannel.Frequency(5_900))
    }

    @Test
    fun `channelFor falls back to Band5Ghz just outside the 5 GHz range`() {
        assertThat(P2pPolicy.channelFor(4_899)).isEqualTo(GroupChannel.Band5Ghz)
        assertThat(P2pPolicy.channelFor(5_901)).isEqualTo(GroupChannel.Band5Ghz)
    }

    @Test
    fun `channelFor falls back to Band5Ghz for 2,4 GHz, 6 GHz and no station link`() {
        assertThat(P2pPolicy.channelFor(2_412)).isEqualTo(GroupChannel.Band5Ghz)
        assertThat(P2pPolicy.channelFor(2_484)).isEqualTo(GroupChannel.Band5Ghz)
        assertThat(P2pPolicy.channelFor(6_115)).isEqualTo(GroupChannel.Band5Ghz)
        assertThat(P2pPolicy.channelFor(null)).isEqualTo(GroupChannel.Band5Ghz)
        assertThat(P2pPolicy.channelFor(0)).isEqualTo(GroupChannel.Band5Ghz)
    }

    @Test
    fun `ipv4 handles the extreme addresses`() {
        assertThat(P2pPolicy.ipv4(0)).isEqualTo("0.0.0.0")
        assertThat(P2pPolicy.ipv4(-1)).isEqualTo("255.255.255.255")
    }

    @Test
    fun `ipv4 decodes the lowest byte as the first octet`() {
        // 10.0.0.2 stored little-endian: 0x0200000A
        assertThat(P2pPolicy.ipv4(0x0200000A)).isEqualTo("10.0.0.2")
        // High bit set in the last octet must not produce a negative number.
        assertThat(P2pPolicy.ipv4(0x80000001.toInt())).isEqualTo("1.0.0.128")
    }

    @Test
    fun `failure reasons other than unsupported and busy map to Error keeping the code`() {
        listOf(0, 3, 4, -1, 99).forEach {
            val f = P2pFailure.of(it, "step")
            assertThat(f).isInstanceOf(P2pFailure.Error::class.java)
            assertThat((f as P2pFailure.Error).code).isEqualTo(it)
        }
    }

    @Test
    fun `failure carries the step name and is an IOException`() {
        val f = P2pFailure.of(2, "createGroup")
        assertThat(f).isInstanceOf(IOException::class.java)
        assertThat(f).isInstanceOf(P2pFailure.Busy::class.java)
        assertThat(f.message).contains("createGroup")
        assertThat(P2pFailure.of(1, "discover")).isInstanceOf(P2pFailure.Unsupported::class.java)
    }

    @Test
    fun `newSsid has the DIRECT prefix, two random characters and the Riri suffix`() {
        repeat(200) {
            val ssid = P2pPolicy.newSsid()
            assertThat(ssid).matches("DIRECT-[A-HJ-NP-Za-km-z2-9]{2}-Riri")
            assertThat(ssid.length).isEqualTo(14)
        }
    }

    @Test
    fun `newPassphrase is 16 characters without ambiguous ones`() {
        repeat(200) {
            assertThat(P2pPolicy.newPassphrase()).matches("[abcdefghijkmnpqrstuvwxyz23456789]{16}")
        }
    }

    @Test
    fun `newPassphrase differs between calls`() {
        val set = (1..50).map { P2pPolicy.newPassphrase() }.toSet()
        assertThat(set).hasSize(50)
    }

    @Test
    fun `newSsid draws from a wide alphabet`() {
        val chars = (1..2_000).flatMap { P2pPolicy.newSsid().substring(7, 9).toList() }.toSet()
        assertThat(chars.size).isAtLeast(40)
    }

    @Test
    fun `generated credentials always pass the TXT validation`() {
        val random = SecureRandom()
        repeat(200) {
            val o = offer.copy(ssid = P2pPolicy.newSsid(random), psk = P2pPolicy.newPassphrase(random))
            assertThat(P2pTxt.decode(P2pTxt.encode(o))).isEqualTo(o)
        }
    }

    @Test
    fun `requiredPermissions switches at API 29 and 33`() {
        assertThat(WifiDirectSupport.requiredPermissions(28)).isEmpty()
        val location = listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION")
        assertThat(WifiDirectSupport.requiredPermissions(29)).containsExactlyElementsIn(location)
        assertThat(WifiDirectSupport.requiredPermissions(32)).containsExactlyElementsIn(location)
        assertThat(WifiDirectSupport.requiredPermissions(33)).containsExactly("android.permission.NEARBY_WIFI_DEVICES")
        assertThat(WifiDirectSupport.requiredPermissions(35)).containsExactly("android.permission.NEARBY_WIFI_DEVICES")
    }

    @Test
    fun `sender requires API 35`() {
        assertThat(WifiDirectSupport.MIN_SENDER_SDK).isEqualTo(35)
    }

    @Test
    fun `offer toString hides the passphrase but keeps the other fields`() {
        val s = offer.toString()
        assertThat(s).doesNotContain(offer.psk)
        assertThat(s).contains("psk=***")
        assertThat(s).contains(offer.ssid)
        assertThat(s).contains(offer.to)
    }

    @Test
    fun `receiver record encodes the documented keys`() {
        assertThat(P2pTxt.encode(P2pReceiverRecord("TV", 47_800))).containsExactly(
            "v",
            "1",
            "role",
            "rx",
            "name",
            "TV",
            "port",
            "47800",
        )
    }

    @Test
    fun `offer record encodes the documented keys with a lowercase address`() {
        val txt = P2pTxt.encode(offer.copy(to = "76:E2:0C:BD:FD:C9"))
        assertThat(txt).containsExactly(
            "v", "1", "role", "offer", "name", "Tab", "ssid", "DIRECT-ab-Riri",
            "psk", "abcdefgh12345678", "to", "76:e2:0c:bd:fd:c9",
        )
    }

    @Test
    fun `port boundaries 1 and 65535 are accepted, 0 and 65536 rejected`() {
        listOf(1, 65_535).forEach {
            val r = P2pReceiverRecord("TV", it)
            assertThat(P2pTxt.decode(P2pTxt.encode(r))).isEqualTo(r)
        }
        listOf(0, 65_536, -1).forEach {
            val txt = mapOf("v" to "1", "role" to "rx", "name" to "TV", "port" to "$it")
            assertThat(P2pTxt.decode(txt)).isNull()
            assertThat(runCatching { P2pTxt.encode(P2pReceiverRecord("TV", it)) }.isFailure).isTrue()
        }
    }

    @Test
    fun `name limit is 63 bytes, not characters`() {
        assertThat(P2pTxt.decode(P2pTxt.encode(P2pReceiverRecord("n".repeat(63), 1)))).isNotNull()
        val tooLong = mapOf("v" to "1", "role" to "rx", "name" to "n".repeat(64), "port" to "1")
        assertThat(P2pTxt.decode(tooLong)).isNull()
        // 32 two-byte characters = 64 bytes.
        val multibyte = mapOf("v" to "1", "role" to "rx", "name" to "è".repeat(32), "port" to "1")
        assertThat(P2pTxt.decode(multibyte)).isNull()
    }

    @Test
    fun `blank and empty names are rejected`() {
        listOf("", " ", "   ").forEach {
            val txt = mapOf("v" to "1", "role" to "rx", "name" to it, "port" to "1")
            assertThat(P2pTxt.decode(txt)).isNull()
        }
    }

    @Test
    fun `ssid must start with DIRECT- and have at least two more characters`() {
        assertThat(P2pTxt.decode(P2pTxt.encode(offer.copy(ssid = "DIRECT-ab")))).isNotNull()
        listOf("DIRECT-a", "DIRECT-", "direct-ab-Riri", "xDIRECT-ab", "").forEach {
            assertThat(P2pTxt.decode(P2pTxt.encode(offer) + ("ssid" to it))).isNull()
        }
    }

    @Test
    fun `ssid of 32 bytes is accepted and 33 rejected`() {
        val ok = "DIRECT-" + "x".repeat(25)
        assertThat(P2pTxt.decode(P2pTxt.encode(offer) + ("ssid" to ok))).isNotNull()
        assertThat(P2pTxt.decode(P2pTxt.encode(offer) + ("ssid" to ok + "x"))).isNull()
    }

    @Test
    fun `passphrase must be 8 to 63 printable ASCII characters`() {
        val good = P2pTxt.encode(offer)
        assertThat(P2pTxt.decode(good + ("psk" to "12345678"))).isNotNull()
        assertThat(P2pTxt.decode(good + ("psk" to "1234567"))).isNull()
        assertThat(P2pTxt.decode(good + ("psk" to "abc\u0000defgh"))).isNull()
        assertThat(P2pTxt.decode(good + ("psk" to "abcdefgèh"))).isNull()
        assertThat(P2pTxt.decode(good + ("psk" to "abcd efgh!"))).isNotNull()
    }

    @Test
    fun `address must be six colon-separated hex pairs`() {
        val good = P2pTxt.encode(offer)
        listOf(
            "76-e2-0c-bd-fd-c9",
            "76:e2:0c:bd:fd:c9:00",
            "76:e2:0c:bd:fd:cg",
            "76e20cbdfdc9",
            "",
            " 76:e2:0c:bd:fd:c9",
        )
            .forEach { assertThat(P2pTxt.decode(good + ("to" to it))).isNull() }
    }

    @Test
    fun `records decode to their own kind and a receiver without its port is rejected`() {
        assertThat(P2pTxt.decode(P2pTxt.encode(P2pReceiverRecord("TV", 1))))
            .isInstanceOf(P2pReceiverRecord::class.java)
        assertThat(P2pTxt.decode(P2pTxt.encode(offer))).isInstanceOf(P2pOfferRecord::class.java)
        assertThat(P2pTxt.decode(P2pTxt.encode(offer) + ("role" to "rx"))).isNull()
    }

    @Test
    fun `decode ignores unknown extra keys and rejects an empty map`() {
        assertThat(P2pTxt.decode(P2pTxt.encode(offer) + ("future" to "x"))).isEqualTo(offer)
        assertThat(P2pTxt.decode(emptyMap())).isNull()
    }
}
