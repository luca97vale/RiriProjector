package dev.ririprojector.protocol

import dev.ririprojector.protocol.ProtocolException.Reason
import java.io.InputStream

/**
 * A received packet. **Reused** by [MessageReader.read]: its contents are only valid until the
 * next call, so consume or copy the payload first.
 */
class Packet internal constructor() {
    /** Raw type byte; see [type]. */
    var typeCode: Int = 0
        internal set

    /** Null for message types unknown to this protocol version (skip them). */
    var type: MessageType? = null
        internal set
    var flags: Int = 0
        internal set

    /** Presentation time in µs for media packets, sequence number for control messages. */
    var ptsOrSeq: Long = 0
        internal set

    /** Payload storage; valid bytes are `payload[0 until payloadLength]`. */
    var payload: ByteArray = ByteArray(0)
        internal set
    var payloadLength: Int = 0
        internal set

    val isKeyframe: Boolean get() = flags and HeaderFlags.KEYFRAME != 0
    val isCodecConfig: Boolean get() = flags and HeaderFlags.CODEC_CONFIG != 0

    fun decodeControl(): ControlMessage {
        val t = type ?: throw ProtocolException(Reason.UNEXPECTED_TYPE, "unknown type 0x${typeCode.toString(16)}")
        return ControlCodec.decode(t, payload, 0, payloadLength)
    }

    /** Copies the payload out, for callers that must keep it beyond the next read. */
    fun copyPayload(): ByteArray = payload.copyOf(payloadLength)
}

/**
 * Reads v1 messages from [input]. Not thread-safe: one reader thread per stream.
 * Does not own [input]; closing it from another thread unblocks [read].
 */
class MessageReader(
    private val input: InputStream,
    private val maxPayloadBytes: Int = FrameHeader.DEFAULT_MAX_PAYLOAD_BYTES,
    initialPayloadCapacity: Int = 256 * 1024,
) {
    private val header = ByteArray(FrameHeader.SIZE)
    private val packet = Packet().apply { payload = ByteArray(initialPayloadCapacity) }

    /**
     * Blocks until a full packet is available. Returns null on a clean end of stream (peer closed
     * between packets); throws [ProtocolException] on malformed input or EOF mid-packet.
     */
    fun read(): Packet? {
        // Bulk read: InputStream.read() (single byte) allocates and costs a syscall on Android sockets.
        var got = 0
        while (got < FrameHeader.SIZE) {
            val n = input.read(header, got, FrameHeader.SIZE - got)
            if (n < 0) {
                if (got == 0) return null
                throw ProtocolException(Reason.TRUNCATED, "EOF after $got of ${FrameHeader.SIZE} header bytes")
            }
            got += n
        }

        val magic = ((header[0].toInt() and 0xFF) shl 8) or (header[1].toInt() and 0xFF)
        if (magic != FrameHeader.MAGIC) throw ProtocolException(Reason.BAD_MAGIC, "0x${magic.toString(16)}")
        val version = header[2].toInt() and 0xFF
        if (version != ProtocolInfo.PROTOCOL_VERSION) {
            throw ProtocolException(
                Reason.UNSUPPORTED_VERSION,
                "peer v$version, local v${ProtocolInfo.PROTOCOL_VERSION}",
            )
        }
        val length =
            ((header[6].toLong() and 0xFF) shl 24) or ((header[7].toLong() and 0xFF) shl 16) or
                ((header[8].toLong() and 0xFF) shl 8) or (header[9].toLong() and 0xFF)
        if (length > maxPayloadBytes) throw ProtocolException(Reason.PAYLOAD_TOO_LARGE, "$length > $maxPayloadBytes")
        var pts = 0L
        for (i in 10 until 16) pts = (pts shl 8) or (header[i].toLong() and 0xFF)

        val len = length.toInt()
        if (packet.payload.size <
            len
        ) {
            packet.payload = ByteArray(maxOf(len, minOf(packet.payload.size * 2, maxPayloadBytes)))
        }
        readFully(packet.payload, 0, len, "payload")

        packet.typeCode = header[3].toInt() and 0xFF
        packet.type = MessageType.fromCode(packet.typeCode)
        packet.flags = header[4].toInt() and 0xFF
        packet.ptsOrSeq = pts
        packet.payloadLength = len
        return packet
    }

    private fun readFully(dst: ByteArray, offset: Int, length: Int, what: String) {
        var done = 0
        while (done < length) {
            val n = input.read(dst, offset + done, length - done)
            if (n < 0) throw ProtocolException(Reason.TRUNCATED, "EOF after $done of $length $what bytes")
            done += n
        }
    }
}
