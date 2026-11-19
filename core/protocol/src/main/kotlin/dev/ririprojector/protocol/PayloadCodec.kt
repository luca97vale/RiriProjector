package dev.ririprojector.protocol

import dev.ririprojector.protocol.ProtocolException.Reason

/** Growable big-endian buffer used to build control-message payloads. */
class PayloadWriter(initialCapacity: Int = 256) {
    var bytes: ByteArray = ByteArray(initialCapacity)
        private set
    var size: Int = 0
        private set

    fun reset(): PayloadWriter {
        size = 0
        return this
    }

    fun u8(value: Int) = apply {
        require(value in 0..0xFF) { "u8 out of range: $value" }
        ensure(1)
        bytes[size++] = value.toByte()
    }

    fun bool(value: Boolean) = u8(if (value) 1 else 0)

    fun u16(value: Int) = apply {
        require(value in 0..0xFFFF) { "u16 out of range: $value" }
        ensure(2)
        bytes[size++] = (value ushr 8).toByte()
        bytes[size++] = value.toByte()
    }

    fun i32(value: Int) = apply {
        ensure(4)
        for (i in 0 until 4) bytes[size++] = (value ushr (24 - 8 * i)).toByte()
    }

    fun i64(value: Long) = apply {
        ensure(8)
        for (i in 0 until 8) bytes[size++] = (value ushr (56 - 8 * i)).toByte()
    }

    fun string(value: String) = apply {
        val encoded = value.toByteArray(Charsets.UTF_8)
        u16(encoded.size)
        raw(encoded, 0, encoded.size)
    }

    fun bytesWithLength(value: ByteArray) = apply {
        i32(value.size)
        raw(value, 0, value.size)
    }

    fun raw(src: ByteArray, offset: Int, length: Int) = apply {
        ensure(length)
        System.arraycopy(src, offset, bytes, size, length)
        size += length
    }

    private fun ensure(extra: Int) {
        if (size + extra > bytes.size) bytes = bytes.copyOf(maxOf(bytes.size * 2, size + extra))
    }
}

/** Bounds-checked big-endian reader over `bytes[offset until limit]`. */
class PayloadReader(private val bytes: ByteArray, offset: Int = 0, private val limit: Int = bytes.size) {
    var position: Int = offset
        private set

    val remaining: Int get() = limit - position

    fun u8(): Int {
        need(1)
        return bytes[position++].toInt() and 0xFF
    }

    fun bool(): Boolean = when (val v = u8()) {
        0 -> false
        1 -> true
        else -> throw malformed("invalid boolean $v")
    }

    fun u16(): Int {
        need(2)
        return ((bytes[position++].toInt() and 0xFF) shl 8) or (bytes[position++].toInt() and 0xFF)
    }

    fun i32(): Int {
        need(4)
        var v = 0
        repeat(4) { v = (v shl 8) or (bytes[position++].toInt() and 0xFF) }
        return v
    }

    fun i64(): Long {
        need(8)
        var v = 0L
        repeat(8) { v = (v shl 8) or (bytes[position++].toLong() and 0xFF) }
        return v
    }

    fun string(): String {
        val len = u16()
        need(len)
        return String(bytes, position, len, Charsets.UTF_8).also { position += len }
    }

    fun bytesWithLength(): ByteArray {
        val len = i32()
        if (len < 0) throw malformed("negative length $len")
        need(len)
        return bytes.copyOfRange(position, position + len).also { position += len }
    }

    /** Fails if unread bytes remain, catching encoder/decoder drift early. */
    fun expectEnd() {
        if (remaining != 0) throw malformed("$remaining trailing bytes")
    }

    private fun need(n: Int) {
        if (n > remaining) throw malformed("needs $n bytes, $remaining left")
    }

    private fun malformed(msg: String) = ProtocolException(Reason.MALFORMED_PAYLOAD, msg)
}
