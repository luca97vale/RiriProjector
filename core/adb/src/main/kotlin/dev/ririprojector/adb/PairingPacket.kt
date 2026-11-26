// Derived from Shizuku (https://github.com/RikkaApps/Shizuku, commit b844bc4, Apache-2.0),
// manager/src/main/java/moe/shizuku/manager/adb/AdbPairingClient.kt (PairingPacketHeader, PeerInfo);
// modified for RiriProjector (pure helpers). See LICENSE-Shizuku.
package dev.ririprojector.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Pairing-protocol framing (adb pairing_connection): a 6-byte big-endian header. Pure. */
internal object PairingPacket {
    const val HEADER_SIZE = 6
    const val VERSION: Byte = 1
    const val TYPE_SPAKE2_MSG: Byte = 0
    const val TYPE_PEER_INFO: Byte = 1
    const val MAX_PEER_INFO_SIZE = 8192
    const val MAX_PAYLOAD_SIZE = MAX_PEER_INFO_SIZE * 2
    const val PEER_INFO_RSA_PUB_KEY: Byte = 0

    fun header(type: Byte, payloadSize: Int): ByteArray = ByteBuffer.allocate(HEADER_SIZE)
        .order(ByteOrder.BIG_ENDIAN)
        .put(VERSION)
        .put(type)
        .putInt(payloadSize)
        .array()

    /** Returns `(type, payloadSize)`, or throws [AdbException] for anything a peer must not send. */
    fun parseHeader(bytes: ByteArray): Pair<Byte, Int> {
        require(bytes.size == HEADER_SIZE) { "header is $HEADER_SIZE bytes" }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val version = b.get()
        val type = b.get()
        val size = b.int
        if (version != VERSION) throw AdbException("pairing header version $version")
        if (type != TYPE_SPAKE2_MSG && type != TYPE_PEER_INFO) throw AdbException("pairing packet type $type")
        if (size <= 0 || size > MAX_PAYLOAD_SIZE) throw AdbException("pairing payload size $size")
        return type to size
    }

    /** Our PeerInfo: a type byte plus the ADB public key, zero-padded to 8192 bytes. */
    fun peerInfo(adbPublicKey: ByteArray): ByteArray {
        val out = ByteArray(MAX_PEER_INFO_SIZE)
        out[0] = PEER_INFO_RSA_PUB_KEY
        adbPublicKey.copyInto(out, 1, 0, minOf(adbPublicKey.size, MAX_PEER_INFO_SIZE - 1))
        return out
    }
}
