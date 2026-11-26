package dev.ririprojector.adb

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Opens a TCP connection to [host]:[port]. A plain function on purpose: inside `Socket().apply { }`
 * the names `port` and `localPort` resolve to the unconnected socket's own (0), not the caller's.
 * The socket is closed if the connect fails.
 */
internal fun openSocket(host: String, port: Int, timeoutMs: Int): Socket {
    val socket = Socket()
    try {
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = timeoutMs
        return socket
    } catch (e: Exception) {
        runCatching { socket.close() }
        throw e
    }
}
