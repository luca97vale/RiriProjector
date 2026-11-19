package dev.ririprojector.net

import dev.ririprojector.protocol.ByeReason
import dev.ririprojector.protocol.CodecNegotiator
import dev.ririprojector.protocol.ControlMessage
import dev.ririprojector.protocol.MessageType
import dev.ririprojector.protocol.Negotiation
import dev.ririprojector.protocol.ProtocolException
import dev.ririprojector.protocol.model.SenderCapabilities
import dev.ririprojector.protocol.model.StreamParams
import dev.ririprojector.protocol.model.StreamPreferences
import java.io.IOException
import java.net.SocketTimeoutException

/** Why a handshake did not produce a stream. */
class HandshakeException(message: String, val bye: ByeReason? = null, cause: Throwable? = null) :
    IOException(message, cause)

/**
 * HELLO → START exchange (ADR-003). The receiver speaks first so the sender can negotiate with
 * full knowledge of the receiver's decoders.
 */
object Handshake {
    const val DEFAULT_TIMEOUT_MS = 3_000

    /** Receiver side: send HELLO, wait for START. Restores a blocking read timeout afterwards. */
    fun asReceiver(
        connection: Connection,
        hello: ControlMessage.Hello,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): StreamParams {
        connection.writer.writeControl(hello)
        connection.writer.flush()
        val reply = readControl(connection, timeoutMs, "START")
        connection.setReadTimeout(0)
        return when (reply) {
            is ControlMessage.Start -> reply.params
            is ControlMessage.Bye -> throw HandshakeException("sender refused: ${reply.reason}", reply.reason)
            else -> throw HandshakeException("expected START, got ${reply.type}")
        }
    }

    /** Sender side: wait for HELLO, negotiate, send START (or BYE(INCOMPATIBLE)). */
    fun asSender(
        connection: Connection,
        capabilities: SenderCapabilities,
        preferences: StreamPreferences,
        sessionId: Long,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): Pair<ControlMessage.Hello, StreamParams> {
        val hello =
            when (val first = readControl(connection, timeoutMs, "HELLO")) {
                is ControlMessage.Hello -> first
                is ControlMessage.Bye -> throw HandshakeException("receiver refused: ${first.reason}", first.reason)
                else -> throw HandshakeException("expected HELLO, got ${first.type}")
            }
        connection.setReadTimeout(0)
        return when (val result = CodecNegotiator.negotiate(capabilities, hello, preferences, sessionId)) {
            is Negotiation.Success -> {
                connection.writer.writeControl(ControlMessage.Start(result.params))
                connection.writer.flush()
                hello to result.params
            }

            is Negotiation.Failure -> {
                runCatching {
                    connection.writer.writeControl(ControlMessage.Bye(ByeReason.INCOMPATIBLE))
                    connection.writer.flush()
                }
                throw HandshakeException("incompatible receiver: ${result.reason}", ByeReason.INCOMPATIBLE)
            }
        }
    }

    private fun readControl(connection: Connection, timeoutMs: Int, expected: String): ControlMessage {
        connection.setReadTimeout(timeoutMs)
        while (true) {
            val packet =
                try {
                    connection.reader.read()
                } catch (e: SocketTimeoutException) {
                    throw HandshakeException("timed out waiting for $expected", cause = e)
                } catch (e: ProtocolException) {
                    throw HandshakeException("protocol error waiting for $expected: ${e.message}", cause = e)
                } ?: throw HandshakeException("peer closed before $expected")
            val type = packet.type ?: continue // unknown type from a newer peer: skip
            if (type == MessageType.PING || type == MessageType.PONG) continue
            if (type.isMedia) throw HandshakeException("media before handshake completed")
            return packet.decodeControl()
        }
    }
}
