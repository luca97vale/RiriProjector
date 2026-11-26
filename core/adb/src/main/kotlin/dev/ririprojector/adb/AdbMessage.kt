// Derived from Shizuku (https://github.com/RikkaApps/Shizuku, commit b844bc4, Apache-2.0),
// manager/src/main/java/moe/shizuku/manager/adb/AdbMessage.kt; modified for RiriProjector
// (stream reader with bounds and magic/checksum validation). See LICENSE-Shizuku.
package dev.ririprojector.adb

import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One ADB packet: 24-byte little-endian header plus payload. Pure, JVM-tested. */
class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val data: ByteArray = EMPTY) {
    val magic: Int get() = command xor -1

    fun toByteArray(): ByteArray = ByteBuffer.allocate(HEADER_LENGTH + data.size)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(command)
        .putInt(arg0)
        .putInt(arg1)
        .putInt(data.size)
        .putInt(checksum(data))
        .putInt(magic)
        .put(data)
        .array()

    override fun toString(): String = "AdbMessage(${name(command)}, arg0=$arg0, arg1=$arg1, ${data.size} bytes)"

    companion object {
        const val HEADER_LENGTH = 24

        /** adbd never sends more than its max payload (1 MiB); anything larger is a broken peer. */
        const val MAX_DATA = 1024 * 1024
        private val EMPTY = ByteArray(0)

        /** A message whose payload is [text] plus the NUL terminator ADB expects. */
        fun ofString(command: Int, arg0: Int, arg1: Int, text: String) =
            AdbMessage(command, arg0, arg1, (text + "\u0000").toByteArray())

        /** Legacy ADB checksum: the sum of the unsigned bytes. */
        fun checksum(data: ByteArray): Int {
            var sum = 0
            for (b in data) sum += b.toInt() and 0xFF
            return sum
        }

        /** Reads and validates one message; throws [AdbException] for a malformed header. */
        fun read(input: DataInputStream): AdbMessage {
            val header = ByteArray(HEADER_LENGTH)
            input.readFully(header)
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val command = b.int
            val arg0 = b.int
            val arg1 = b.int
            val length = b.int
            val crc = b.int
            val magic = b.int
            if (magic != command xor -1) throw AdbException("bad magic for ${name(command)}")
            if (length < 0 || length > MAX_DATA) throw AdbException("bad length $length")
            val data = ByteArray(length)
            input.readFully(data)
            // Since protocol version 0x01000001 adbd may send 0 instead of a checksum.
            if (crc != 0 && crc != checksum(data)) throw AdbException("bad checksum for ${name(command)}")
            return AdbMessage(command, arg0, arg1, data)
        }

        fun name(command: Int): String = when (command) {
            AdbProtocol.A_SYNC -> "SYNC"
            AdbProtocol.A_CNXN -> "CNXN"
            AdbProtocol.A_AUTH -> "AUTH"
            AdbProtocol.A_OPEN -> "OPEN"
            AdbProtocol.A_OKAY -> "OKAY"
            AdbProtocol.A_CLSE -> "CLSE"
            AdbProtocol.A_WRTE -> "WRTE"
            AdbProtocol.A_STLS -> "STLS"
            else -> "0x" + Integer.toHexString(command)
        }
    }
}
