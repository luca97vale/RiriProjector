package dev.ririprojector.net.p2p

import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pRecordsTest {
    private val offer = P2pOfferRecord("Tab S9", "DIRECT-ab-Riri", "abcdefgh12345678", "76:e2:0c:bd:fd:c9")

    @Test fun receiverRoundTrip() {
        val r = P2pReceiverRecord("FireTVStick di Luca", 47_800)
        assertEquals(r, P2pTxt.decode(P2pTxt.encode(r)))
    }

    @Test fun offerRoundTrip() {
        assertEquals(offer, P2pTxt.decode(P2pTxt.encode(offer)))
    }

    @Test fun offerKeepsLongestValidFields() {
        val o = offer.copy(ssid = "DIRECT-" + "x".repeat(25), psk = "p".repeat(63), name = "n".repeat(63))
        assertEquals(o, P2pTxt.decode(P2pTxt.encode(o)))
        o.let { P2pTxt.encode(it) }.forEach { (k, v) -> assertTrue("$k=$v".toByteArray().size <= 255) }
    }

    @Test fun macIsNormalizedToLowerCase() {
        val txt = P2pTxt.encode(offer) + ("to" to "76:E2:0C:BD:FD:C9")
        assertEquals(offer, P2pTxt.decode(txt))
    }

    @Test fun rejectsUnknownVersionRoleAndMissingFields() {
        val good = P2pTxt.encode(offer)
        assertNull(P2pTxt.decode(good + ("v" to "2")))
        assertNull(P2pTxt.decode(good + ("role" to "other")))
        listOf("v", "role", "name", "ssid", "psk", "to").forEach { assertNull(it, P2pTxt.decode(good - it)) }
        assertNull(P2pTxt.decode(mapOf("v" to "1", "role" to "rx", "name" to "TV", "port" to "0")))
        assertNull(P2pTxt.decode(mapOf("v" to "1", "role" to "rx", "name" to "TV", "port" to "x")))
    }

    @Test fun rejectsBadCredentials() {
        val good = P2pTxt.encode(offer)
        assertNull(P2pTxt.decode(good + ("ssid" to "MyRouter")))
        assertNull(P2pTxt.decode(good + ("ssid" to "DIRECT-" + "x".repeat(26))))
        assertNull(P2pTxt.decode(good + ("psk" to "short")))
        assertNull(P2pTxt.decode(good + ("psk" to "p".repeat(64))))
        assertNull(P2pTxt.decode(good + ("to" to "76:e2:0c:bd:fd")))
    }

    @Test fun encodeRefusesInvalidRecords() {
        val bad =
            listOf(offer.copy(psk = "short"), offer.copy(ssid = "Home"), offer.copy(to = "x"), offer.copy(name = " "))
        bad.forEach { r -> assertTrue(runCatching { P2pTxt.encode(r) }.isFailure) }
        assertTrue(runCatching { P2pTxt.encode(P2pReceiverRecord("TV", 70_000)) }.isFailure)
    }

    @Test fun offerToStringHidesThePassphrase() {
        assertTrue("abcdefgh12345678" !in offer.toString())
    }

    @Test fun groupSharesTheStations5GhzChannel() {
        assertEquals(GroupChannel.Frequency(5_220), P2pPolicy.channelFor(5_220))
        assertEquals(GroupChannel.Band5Ghz, P2pPolicy.channelFor(2_432))
        assertEquals(GroupChannel.Band5Ghz, P2pPolicy.channelFor(null))
        assertEquals(GroupChannel.Band5Ghz, P2pPolicy.channelFor(-1))
    }

    @Test fun generatedCredentialsAreValid() {
        val random = SecureRandom()
        repeat(100) {
            val o = offer.copy(ssid = P2pPolicy.newSsid(random), psk = P2pPolicy.newPassphrase(random))
            assertEquals(o, P2pTxt.decode(P2pTxt.encode(o)))
        }
    }

    @Test fun permissionsPerApiLevel() {
        assertTrue(WifiDirectSupport.requiredPermissions(28).isEmpty())
        assertEquals(2, WifiDirectSupport.requiredPermissions(30).size)
        assertEquals(listOf("android.permission.NEARBY_WIFI_DEVICES"), WifiDirectSupport.requiredPermissions(36))
    }

    @Test fun dhcpAddressesAreLittleEndian() {
        assertEquals("192.168.49.1", P2pPolicy.ipv4(0x0131A8C0))
        assertEquals("192.168.49.229", P2pPolicy.ipv4(0xE531A8C0.toInt()))
    }

    @Test fun failureCodesMapToKinds() {
        assertTrue(P2pFailure.of(1, "x") is P2pFailure.Unsupported)
        assertTrue(P2pFailure.of(2, "x") is P2pFailure.Busy)
        assertEquals(0, (P2pFailure.of(0, "x") as P2pFailure.Error).code)
        assertEquals(3, (P2pFailure.of(3, "x") as P2pFailure.Error).code)
    }
}
