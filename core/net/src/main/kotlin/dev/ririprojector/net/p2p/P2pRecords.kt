package dev.ririprojector.net.p2p

/**
 * What each side announces over Wi-Fi P2P DNS-SD (ADR-011). Separate from the router NSD TXT
 * (ADR-006) and from the wire protocol: versioned on its own with `v`.
 *
 * - The receiver (TV) announces itself with a [P2pReceiverRecord] while its screen is up and idle.
 * - The sender (tablet) creates a group and announces a [P2pOfferRecord] addressed to the picked
 *   receiver's P2P device address; the receiver joins that group with the credentials.
 */
sealed interface P2pRecord {
    val name: String
}

/** A receiver that can be mirrored to over Wi-Fi Direct; [port] is its `ReceiverServer` port. */
data class P2pReceiverRecord(override val name: String, val port: Int) : P2pRecord

/**
 * A sender's group, offered to the receiver whose P2P device address is [to]. The passphrase is
 * random per session and the group exists only until the session ends.
 */
data class P2pOfferRecord(override val name: String, val ssid: String, val psk: String, val to: String) : P2pRecord {
    override fun toString(): String = "P2pOfferRecord(name=$name, ssid=$ssid, psk=***, to=$to)"
}

/** TXT encoding of [P2pRecord]s. Pure; rejects anything malformed instead of guessing. */
object P2pTxt {
    const val SERVICE_TYPE = "_ririproj._tcp"
    const val RECEIVER_INSTANCE = "RiriProjector"
    const val OFFER_INSTANCE = "RiriProjectorOffer"
    const val VERSION = "1"

    private const val KEY_VERSION = "v"
    private const val KEY_ROLE = "role"
    private const val KEY_NAME = "name"
    private const val KEY_PORT = "port"
    private const val KEY_SSID = "ssid"
    private const val KEY_PSK = "psk"
    private const val KEY_TO = "to"
    private const val ROLE_RECEIVER = "rx"
    private const val ROLE_OFFER = "offer"

    /** A TXT entry is one length byte: "key=value" must fit in 255 bytes. */
    private const val MAX_NAME_BYTES = 63
    private const val MAX_SSID_BYTES = 32
    private val MAC = Regex("^[0-9a-f]{2}(:[0-9a-f]{2}){5}$")

    fun encode(record: P2pRecord): Map<String, String> = when (record) {
        is P2pReceiverRecord -> {
            require(validName(record.name) && record.port in 1..65_535) { "invalid receiver record" }
            mapOf(
                KEY_VERSION to VERSION,
                KEY_ROLE to ROLE_RECEIVER,
                KEY_NAME to record.name,
                KEY_PORT to "${record.port}",
            )
        }

        is P2pOfferRecord -> {
            require(validName(record.name) && validSsid(record.ssid) && validPsk(record.psk) && validMac(record.to)) {
                "invalid offer record"
            }
            mapOf(
                KEY_VERSION to VERSION,
                KEY_ROLE to ROLE_OFFER,
                KEY_NAME to record.name,
                KEY_SSID to record.ssid,
                KEY_PSK to record.psk,
                KEY_TO to record.to.lowercase(),
            )
        }
    }

    fun decode(txt: Map<String, String>): P2pRecord? {
        if (txt[KEY_VERSION] != VERSION) return null
        val name = txt[KEY_NAME]?.takeIf(::validName) ?: return null
        return when (txt[KEY_ROLE]) {
            ROLE_RECEIVER -> {
                val port = txt[KEY_PORT]?.toIntOrNull()?.takeIf { it in 1..65_535 } ?: return null
                P2pReceiverRecord(name, port)
            }

            ROLE_OFFER -> {
                val ssid = txt[KEY_SSID]?.takeIf(::validSsid) ?: return null
                val psk = txt[KEY_PSK]?.takeIf(::validPsk) ?: return null
                val to = txt[KEY_TO]?.lowercase()?.takeIf(::validMac) ?: return null
                P2pOfferRecord(name, ssid, psk, to)
            }

            else -> null
        }
    }

    private fun validName(name: String) = name.isNotBlank() && name.toByteArray().size <= MAX_NAME_BYTES

    /** Wi-Fi Direct group names must start with "DIRECT-xy" (`WifiP2pConfig.Builder`). */
    private fun validSsid(ssid: String) = ssid.startsWith("DIRECT-") && ssid.toByteArray().size in 9..MAX_SSID_BYTES

    /** WPA2 passphrase: 8–63 printable ASCII characters. */
    private fun validPsk(psk: String) = psk.length in 8..63 && psk.all { it.code in 0x20..0x7e }

    private fun validMac(mac: String) = MAC.matches(mac.lowercase())
}
