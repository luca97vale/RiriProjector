package dev.ririprojector.protocol

/** Message types of the wire format (ADR-003); VIDEO_RECONFIG since v3. */
enum class MessageType(val code: Int, val isMedia: Boolean = false) {
    HELLO(0x01),
    START(0x02),
    CONFIG(0x03, isMedia = true),
    FRAME(0x04, isMedia = true),
    AUDIO_CONFIG(0x05),
    AUDIO_FRAME(0x06, isMedia = true),
    VIDEO_RECONFIG(0x07),
    P2P_OFFER(0x08),
    REQUEST_KEYFRAME(0x10),
    PING(0x11),
    PONG(0x12),
    STATS(0x13),
    BITRATE_HINT(0x14),
    BYE(0x7F),
    ;

    companion object {
        private val byCode = arrayOfNulls<MessageType>(256).also { table -> entries.forEach { table[it.code] = it } }

        /** Returns null for codes this version does not know (forward compatibility). */
        fun fromCode(code: Int): MessageType? = if (code in 0..255) byCode[code] else null
    }
}

/** Bit flags of the header `flags` byte. */
object HeaderFlags {
    const val KEYFRAME: Int = 0x01
    const val CODEC_CONFIG: Int = 0x02
}

/**
 * Fixed 16-byte big-endian header:
 * `magic u16 | version u8 | type u8 | flags u8 | reserved u8 | payloadLen u32 | ptsOrSeq u48`.
 */
object FrameHeader {
    const val SIZE: Int = 16
    const val MAGIC: Int = 0x5252
    const val MAX_PTS_OR_SEQ: Long = (1L shl 48) - 1

    /** Default upper bound for a single payload; larger values are a protocol error. */
    const val DEFAULT_MAX_PAYLOAD_BYTES: Int = 8 * 1024 * 1024

    /** Writes a header into [dst] at [offset]. Allocation-free. */
    fun encode(
        dst: ByteArray,
        offset: Int,
        typeCode: Int,
        flags: Int,
        payloadLength: Int,
        ptsOrSeq: Long,
        version: Int = ProtocolInfo.PROTOCOL_VERSION,
    ) {
        require(payloadLength >= 0) { "negative payload length" }
        require(ptsOrSeq in 0..MAX_PTS_OR_SEQ) { "ptsOrSeq out of u48 range: $ptsOrSeq" }
        dst[offset] = (MAGIC ushr 8).toByte()
        dst[offset + 1] = MAGIC.toByte()
        dst[offset + 2] = version.toByte()
        dst[offset + 3] = typeCode.toByte()
        dst[offset + 4] = flags.toByte()
        dst[offset + 5] = 0
        dst[offset + 6] = (payloadLength ushr 24).toByte()
        dst[offset + 7] = (payloadLength ushr 16).toByte()
        dst[offset + 8] = (payloadLength ushr 8).toByte()
        dst[offset + 9] = payloadLength.toByte()
        for (i in 0 until 6) {
            dst[offset + 10 + i] = (ptsOrSeq ushr (40 - 8 * i)).toByte()
        }
    }
}
