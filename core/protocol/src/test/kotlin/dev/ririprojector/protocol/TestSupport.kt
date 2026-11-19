package dev.ririprojector.protocol

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Delivers at most [chunk] bytes per read() call, simulating fragmented TCP delivery. */
internal class ChunkedInputStream(private val data: ByteArray, private val chunk: Int = 1) : InputStream() {
    private var pos = 0

    override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        if (pos >= data.size) return -1
        val n = minOf(len, chunk, data.size - pos)
        System.arraycopy(data, pos, b, off, n)
        pos += n
        return n
    }
}

internal fun header(
    type: Int,
    payloadLen: Long,
    version: Int = ProtocolInfo.PROTOCOL_VERSION,
    magic: Int = FrameHeader.MAGIC,
    flags: Int = 0,
    pts: Long = 0,
): ByteArray {
    val b = ByteArray(FrameHeader.SIZE)
    b[0] = (magic ushr 8).toByte()
    b[1] = magic.toByte()
    b[2] = version.toByte()
    b[3] = type.toByte()
    b[4] = flags.toByte()
    for (i in 0 until 4) b[6 + i] = (payloadLen ushr (24 - 8 * i)).toByte()
    for (i in 0 until 6) b[10 + i] = (pts ushr (40 - 8 * i)).toByte()
    return b
}

internal fun written(block: (MessageWriter) -> Unit): ByteArray {
    val bos = ByteArrayOutputStream()
    val w = MessageWriter(bos)
    block(w)
    w.flush()
    return bos.toByteArray()
}
