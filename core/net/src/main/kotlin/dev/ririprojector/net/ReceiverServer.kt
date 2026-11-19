package dev.ririprojector.net

import dev.ririprojector.protocol.ByeReason
import dev.ririprojector.protocol.ControlMessage
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference

/**
 * Accepts sender connections on a dedicated thread, one active session at a time (ADR-002).
 * While a session is active, extra clients receive `BYE(BUSY)` and are closed.
 *
 * [onConnection] runs on the accept thread's handoff and must return quickly: it should start the
 * session on its own thread and call [sessionEnded] when that connection is finished.
 */
class ReceiverServer(
    private val port: Int = ReceiverEndpoint.DEFAULT_PORT,
    private val sendBufferBytes: Int = SocketTuning.DEFAULT_SEND_BUFFER_BYTES,
    private val onConnection: (Connection) -> Unit,
) : Closeable {
    private val serverSocket = ServerSocket()
    private val active = AtomicReference<Connection?>(null)
    private var thread: Thread? = null

    /** Port actually bound (useful with port 0 in tests). */
    val boundPort: Int get() = serverSocket.localPort

    fun start(): ReceiverServer {
        serverSocket.reuseAddress = true
        serverSocket.bind(InetSocketAddress(port))
        thread =
            Thread({ acceptLoop() }, "Riri/Net-accept").apply {
                isDaemon = true
                start()
            }
        return this
    }

    /** Frees the slot so the next client can become the active session. */
    fun sessionEnded(connection: Connection) {
        connection.close()
        active.compareAndSet(connection, null)
    }

    private fun acceptLoop() {
        while (!serverSocket.isClosed) {
            val socket: Socket =
                try {
                    serverSocket.accept()
                } catch (_: SocketException) {
                    return // closed
                }
            try {
                SocketTuning.apply(socket, sendBufferBytes)
                val connection = Connection(socket)
                if (active.compareAndSet(null, connection)) {
                    onConnection(connection)
                } else {
                    rejectBusy(connection)
                }
            } catch (e: Exception) {
                runCatching { socket.close() }
            }
        }
    }

    private fun rejectBusy(connection: Connection) {
        runCatching {
            connection.writer.writeControl(ControlMessage.Bye(ByeReason.BUSY))
            connection.writer.flush()
        }
        connection.close()
    }

    override fun close() {
        runCatching { serverSocket.close() }
        active.getAndSet(null)?.close()
        thread?.join(1_000)
    }
}

/** Opens tuned connections to a receiver. */
object SenderClient {
    const val DEFAULT_CONNECT_TIMEOUT_MS = 3_000

    fun connect(
        endpoint: ReceiverEndpoint,
        timeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        sendBufferBytes: Int = SocketTuning.DEFAULT_SEND_BUFFER_BYTES,
    ): Connection {
        val socket = Socket()
        try {
            SocketTuning.apply(socket, sendBufferBytes)
            socket.connect(InetSocketAddress(endpoint.host, endpoint.port), timeoutMs)
            return Connection(socket)
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }
}
