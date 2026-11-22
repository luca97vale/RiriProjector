package dev.ririprojector.net.p2p

import dev.ririprojector.net.WifiBand
import java.io.IOException
import java.security.SecureRandom

/** Why a Wi-Fi Direct step failed; the UI maps each kind to a localized message. */
sealed class P2pFailure(message: String) : IOException(message) {
    /** The device has no Wi-Fi Direct, or it is turned off. */
    class Unsupported(what: String) : P2pFailure("$what: Wi-Fi Direct unsupported")

    /** Another app holds Wi-Fi Direct (e.g. a screen-sharing or file-sharing app). */
    class Busy(what: String) : P2pFailure("$what: Wi-Fi Direct busy")

    class Timeout(what: String) : P2pFailure("$what: timed out")

    class PermissionDenied(what: String) : P2pFailure("$what: permission denied")

    /** The group or its link went away after it was formed. */
    class LinkLost(what: String) : P2pFailure("$what: Wi-Fi Direct link lost")

    class Error(what: String, val code: Int) : P2pFailure("$what: Wi-Fi Direct error $code")

    companion object {
        // WifiP2pManager.ERROR, P2P_UNSUPPORTED, BUSY, NO_SERVICE_REQUESTS.
        private const val P2P_UNSUPPORTED = 1
        private const val BUSY = 2

        /** Maps a `WifiP2pManager.ActionListener.onFailure` reason. */
        fun of(reason: Int, what: String): P2pFailure = when (reason) {
            P2P_UNSUPPORTED -> Unsupported(what)
            BUSY -> Busy(what)
            else -> Error(what, reason)
        }
    }
}

/** How the sender's group picks its channel. */
sealed interface GroupChannel {
    /** Share the station's channel: one channel for both links, no time slicing. */
    data class Frequency(val mhz: Int) : GroupChannel

    /** Let the driver choose a 5 GHz channel. */
    data object Band5Ghz : GroupChannel

    /** Let the driver choose a 2.4 GHz channel (the station's one was refused or allows no P2P). */
    data object Band2Ghz : GroupChannel
}

/** Pure decisions of the Wi-Fi Direct link (ADR-011). */
object P2pPolicy {
    /** Over Wi-Fi Direct a lost group cannot come back by itself: give up after this outage. */
    const val OUTAGE_LIMIT_MS = 1_500L

    /** Over the router the receiver may come back (restart, roaming): ADR-010. */
    const val ROUTER_OUTAGE_LIMIT_MS = 30_000L

    /**
     * The receiver shows up in the group before its DHCP lease and routes are ready: the first connect
     * may time out (seen twice in 4 runs), so it is retried for this long.
     */
    const val FIRST_CONNECT_WINDOW_MS = 10_000L

    /**
     * "5 GHz when possible" (#32). Most senders have one radio (the tablet in spike #21, the Pixel 10
     * in #32): a group on another channel than the station link makes the radio switch channels,
     * which blocks writes for hundreds of ms and can miss the receiver's join entirely. So the group
     * shares the station's channel, and goes on 5 GHz only when that costs no switching: a 5 GHz
     * station link, a radio that can run a 5 GHz group next to a 2.4 GHz station ([crossBandGo]), or
     * no station link at all.
     */
    fun channelFor(stationFrequencyMhz: Int?, crossBandGo: Boolean): GroupChannel {
        val band = stationFrequencyMhz?.let { WifiBand.of(it) }
        return when {
            band == WifiBand.GHZ_5 -> GroupChannel.Frequency(stationFrequencyMhz!!)

            band == WifiBand.GHZ_2_4 && crossBandGo -> GroupChannel.Band5Ghz

            // Channel 14 allows no Wi-Fi Direct: another 2.4 GHz channel, time slicing expected.
            stationFrequencyMhz == CHANNEL_14_MHZ -> GroupChannel.Band2Ghz

            band == WifiBand.GHZ_2_4 -> GroupChannel.Frequency(stationFrequencyMhz!!)

            // No link, or 6 GHz (a Fire OS 7 receiver cannot join there): any 5 GHz channel.
            else -> GroupChannel.Band5Ghz
        }
    }

    /** The driver refused [channel] (e.g. DFS): keep its band, let the driver pick the channel. */
    fun fallbackFor(channel: GroupChannel.Frequency): GroupChannel =
        if (WifiBand.of(channel.mhz) == WifiBand.GHZ_2_4) GroupChannel.Band2Ghz else GroupChannel.Band5Ghz

    /**
     * A timed-out attempt proves the radio cannot run a cross-band group only if that is what
     * happened: a 2.4 GHz station, a group formed on 5 GHz, the receiver reached (the router offer
     * was delivered), and still no join. Any other timeout (receiver closed, out of range, the group
     * never formed) says nothing about the radio.
     */
    fun crossBandUnreliable(
        stationFrequencyMhz: Int?,
        formedFrequencyMhz: Int?,
        offerDelivered: Boolean,
        joined: Boolean,
    ): Boolean = !joined &&
        offerDelivered &&
        stationFrequencyMhz != null &&
        WifiBand.of(stationFrequencyMhz) == WifiBand.GHZ_2_4 &&
        formedFrequencyMhz != null &&
        WifiBand.of(formedFrequencyMhz) == WifiBand.GHZ_5

    private const val CHANNEL_14_MHZ = 2_484

    /** `DhcpInfo` addresses are little-endian ints: 0x0131A8C0 is 192.168.49.1. */
    fun ipv4(littleEndian: Int): String =
        "${littleEndian and 0xff}.${littleEndian shr 8 and 0xff}.${littleEndian shr 16 and 0xff}.${littleEndian ushr 24}"

    private const val PSK_ALPHABET = "abcdefghijkmnpqrstuvwxyz23456789"
    private const val SSID_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"

    /** A fresh group name: "DIRECT-" + two random characters (required prefix) + "-Riri". */
    fun newSsid(random: SecureRandom = SecureRandom()): String =
        "DIRECT-" + (1..2).map { SSID_ALPHABET[random.nextInt(SSID_ALPHABET.length)] }.joinToString("") + "-Riri"

    private val OWN_SSID = Regex("^DIRECT-[$SSID_ALPHABET]{2}-Riri$")

    /**
     * Whether [ssid] is one of our group names, with or without the quotes `WifiConfiguration`
     * adds. Only our own groups and saved networks are ever removed. Pure.
     */
    fun isOwnSsid(ssid: String?): Boolean = ssid != null && OWN_SSID.matches(ssid.removeSurrounding("\""))

    /** A fresh 16-character passphrase (~80 bits). */
    fun newPassphrase(random: SecureRandom = SecureRandom()): String =
        (1..16).map { PSK_ALPHABET[random.nextInt(PSK_ALPHABET.length)] }.joinToString("")
}
