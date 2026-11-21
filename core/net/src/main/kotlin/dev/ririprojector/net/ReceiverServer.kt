package dev.ririprojector.net

import android.util.Log
import dev.ririprojector.protocol.ByeReason
import dev.ririprojector.protocol.ControlMessage
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * Accepts sender connections on a dedicated thread, one active session at a time (ADR-002).
 * While a session is active, extra clients receive `BYE(BUSY)` and are closed, except the active
 * sender itself reconnecting, which replaces its session (preemption, ADR-010).
 *
 * [onConnection] runs on the accept thread's handoff and must return quickly: it should start the
 * session on its own thread and call [sessionEnded] when that connection is finished.
 */
class ReceiverServer(
    private val port: Int = ReceiverEndpoint.DEFAULT_PORT,
    private val sendBufferBytes: Int = SocketTuning.DEFAULT_SEND_BUFFER_BYTES,
    /** Whether a new client is the active session's own sender reconnecting (default: same IP). */
    private val samePeer: (active: Connection, new: Connection) -> Boolean = { a, b ->
        a.remoteAddress ==
            b.remoteAddress
    },
    private val onConnection: (Connection) -> Unit,
) : Closeable {
    private val serverSocket = ServerSocket()
    private val active = AtomicReference<Connection?>(null)
    private var thread: Thread? = null

    /** Port actually bound (useful with port 0 in tests). */
    val boundPort: Int get() = serverSocket.localPort

    fun start(): ReceiverServer {
        try {
            serverSocket.reuseAddress = true
            serverSocket.bind(InetSocketAddress(port))
        } catch (e: IOException) {
            runCatching { serverSocket.close() }
            throw e
        }
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
                } catch (e: IOException) {
                    if (serverSocket.isClosed) return
                    // Transient (e.g. EMFILE): keep the server alive.
                    Log.w(TAG, "accept failed: ${e.message}")
                    runCatching { Thread.sleep(ACCEPT_RETRY_MS) }
                    continue
                }
            var connection: Connection? = null
            try {
                SocketTuning.apply(socket, sendBufferBytes)
                connection = Connection(socket)
                val current = active.get()
                when {
                    current == null && active.compareAndSet(null, connection) -> onConnection(connection)

                    // The same sender reconnecting (its old link may look alive here for up to 3 s):
                    // it replaces its own session instead of being refused as BUSY (ADR-010).
                    current != null && samePeer(current, connection) &&
                        active.compareAndSet(current, connection) -> {
                        Log.i(TAG, "${connection.remoteAddress} reconnected: replacing its previous session")
                        current.close()
                        onConnection(connection)
                    }

                    else -> rejectBusy(connection)
                }
            } catch (e: Exception) {
                Log.w(TAG, "connection setup failed: ${e.message}")
                runCatching { socket.close() }
                if (connection != null) active.compareAndSet(connection, null)
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

    private companion object {
        const val TAG = "Riri/Server"
        const val ACCEPT_RETRY_MS = 100L
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
