package dev.ririprojector.net

import dev.ririprojector.protocol.FrameHeader
import dev.ririprojector.protocol.MessageReader
import dev.ririprojector.protocol.MessageWriter
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean

/** Where a receiver can be reached: from NSD or typed in manually. */
data class ReceiverEndpoint(val name: String, val host: String, val port: Int) {
    init {
        require(port in 1..65535) { "invalid port $port" }
        require(host.isNotBlank()) { "blank host" }
    }

    companion object {
        const val DEFAULT_PORT = 47_800

        /** Parses `host` or `host:port` typed by the user; null if invalid. */
        fun parse(text: String, name: String = text.trim()): ReceiverEndpoint? {
            val t = text.trim()
            if (t.isEmpty()) return null
            val colon = t.lastIndexOf(':')
            val (host, port) =
                if (colon > 0 && t.indexOf(':') == colon) {
                    t.substring(0, colon) to (t.substring(colon + 1).toIntOrNull() ?: return null)
                } else {
                    t to DEFAULT_PORT
                }
            return runCatching { ReceiverEndpoint(name, host, port) }.getOrNull()
        }
    }
}

/** Low-latency socket options (ADR-002). */
object SocketTuning {
    /** DSCP AF41 (interactive video) in the IP TOS byte. */
    const val TRAFFIC_CLASS_VIDEO = 0x88
    const val DEFAULT_SEND_BUFFER_BYTES = 256 * 1024

    fun apply(socket: Socket, sendBufferBytes: Int = DEFAULT_SEND_BUFFER_BYTES) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.sendBufferSize = sendBufferBytes
        try {
            socket.trafficClass = TRAFFIC_CLASS_VIDEO
        } catch (_: SocketException) {
            // Not every network stack lets apps set QoS bits; best effort only.
        }
    }
}

/**
 * One TCP connection with its protocol reader/writer. Owns the socket.
 * [reader] must be used by a single thread and [writer] by a single (other) thread;
 * [close] is safe from any thread and unblocks a pending read.
 */
class Connection(val socket: Socket, maxPayloadBytes: Int = FrameHeader.DEFAULT_MAX_PAYLOAD_BYTES) : Closeable {
    private val closed = AtomicBoolean(false)

    val reader = MessageReader(BufferedInputStream(socket.getInputStream(), 64 * 1024), maxPayloadBytes)

    /** Buffered: call `writer.flush()` after each packet (or batch) to put it on the wire. */
    val writer = MessageWriter(BufferedOutputStream(socket.getOutputStream(), 64 * 1024), maxPayloadBytes)

    val remoteAddress: String get() = (socket.remoteSocketAddress as? InetSocketAddress)?.address?.hostAddress ?: "?"

    val isClosed: Boolean get() = closed.get()

    /** Sets the read timeout; 0 means block forever. */
    fun setReadTimeout(millis: Int) {
        socket.soTimeout = millis
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.shutdownInput() }
            runCatching { socket.close() }
        }
    }
}
