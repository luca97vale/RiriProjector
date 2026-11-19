package dev.ririprojector.protocol

import java.io.OutputStream
import java.nio.ByteBuffer

/**
 * Writes v1 messages to [out]. Not thread-safe: one writer thread per stream.
 * Does not own [out]; the caller closes it.
 *
 * Header and payload are separate `write` calls, so [out] should be a `BufferedOutputStream`
 * over the socket, flushed once per packet (or batch) with [flush]; otherwise `TCP_NODELAY`
 * would send the 16-byte header as its own segment.
 *
 * Media packets are written straight from the caller's [ByteBuffer] without per-packet allocations
 * (direct buffers are copied through a reusable scratch array that only grows).
 */
class MessageWriter(
    private val out: OutputStream,
    private val maxPayloadBytes: Int = FrameHeader.DEFAULT_MAX_PAYLOAD_BYTES,
) {
    private val header = ByteArray(FrameHeader.SIZE)
    private val control = PayloadWriter()
    private var scratch = ByteArray(64 * 1024)
    private var nextSeq = 0L

    /** Encodes and writes a control message; returns the sequence number used. */
    fun writeControl(message: ControlMessage): Long {
        ControlCodec.encode(message, control)
        val seq = nextSeq
        nextSeq = (nextSeq + 1) and FrameHeader.MAX_PTS_OR_SEQ
        writePacket(message.type.code, 0, seq, control.bytes, 0, control.size)
        return seq
    }

    /** Video codec-specific data (VPS/SPS/PPS) as emitted with `BUFFER_FLAG_CODEC_CONFIG`. */
    fun writeVideoConfig(ptsUs: Long, data: ByteBuffer) =
        writeMedia(MessageType.CONFIG, HeaderFlags.CODEC_CONFIG, ptsUs, data)

    fun writeVideoFrame(ptsUs: Long, keyframe: Boolean, data: ByteBuffer) =
        writeMedia(MessageType.FRAME, if (keyframe) HeaderFlags.KEYFRAME else 0, ptsUs, data)

    fun writeAudioFrame(ptsUs: Long, data: ByteBuffer) = writeMedia(MessageType.AUDIO_FRAME, 0, ptsUs, data)

    /** Writes `data[position until limit]` without changing the buffer's position. */
    fun writeMedia(type: MessageType, flags: Int, ptsUs: Long, data: ByteBuffer) {
        require(type.isMedia) { "$type is not a media packet" }
        val length = data.remaining()
        checkLength(length)
        if (data.hasArray()) {
            writePacket(type.code, flags, ptsUs, data.array(), data.arrayOffset() + data.position(), length)
        } else {
            if (scratch.size < length) scratch = ByteArray(maxOf(length, minOf(scratch.size * 2, maxPayloadBytes)))
            // Copy without allocating a duplicate() view, then restore the caller's position.
            val position = data.position()
            data.get(scratch, 0, length)
            data.position(position)
            writePacket(type.code, flags, ptsUs, scratch, 0, length)
        }
    }

    fun flush() = out.flush()

    private fun checkLength(length: Int) {
        if (length > maxPayloadBytes) {
            throw ProtocolException(ProtocolException.Reason.PAYLOAD_TOO_LARGE, "$length > $maxPayloadBytes")
        }
    }

    /** [ptsOrSeq] must already be in u48 range: media pts are validated, never silently wrapped. */
    private fun writePacket(typeCode: Int, flags: Int, ptsOrSeq: Long, bytes: ByteArray, offset: Int, length: Int) {
        checkLength(length)
        FrameHeader.encode(header, 0, typeCode, flags, length, ptsOrSeq)
        out.write(header, 0, FrameHeader.SIZE)
        if (length > 0) out.write(bytes, offset, length)
    }
}
