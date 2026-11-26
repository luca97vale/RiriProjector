package dev.ririprojector.net

import dev.ririprojector.protocol.ByeReason
import dev.ririprojector.protocol.CodecNegotiator
import dev.ririprojector.protocol.ControlMessage
import dev.ririprojector.protocol.EncoderPredicate
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

    /** What a sender asked for after the receiver's HELLO. */
    sealed interface Opening {
        data class Stream(val params: StreamParams) : Opening

        /** A Wi-Fi Direct offer over the router (v4, ADR-011): no stream follows on this connection. */
        data class Offer(val offer: ControlMessage.P2pOffer) : Opening
    }

    /** Receiver side: send HELLO, wait for START or P2P_OFFER. Restores a blocking read timeout afterwards. */
    fun receive(connection: Connection, hello: ControlMessage.Hello, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Opening {
        connection.writer.writeControl(hello)
        connection.writer.flush()
        val reply = readControl(connection, timeoutMs, "START")
        connection.setReadTimeout(0)
        return when (reply) {
            is ControlMessage.Start -> Opening.Stream(reply.params)
            is ControlMessage.P2pOffer -> Opening.Offer(reply)
            is ControlMessage.Bye -> throw HandshakeException("sender refused: ${reply.reason}", reply.reason)
            else -> throw HandshakeException("expected START, got ${reply.type}")
        }
    }

    /** Receiver side, stream only: send HELLO, wait for START. */
    fun asReceiver(
        connection: Connection,
        hello: ControlMessage.Hello,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): StreamParams = when (val opening = receive(connection, hello, timeoutMs)) {
        is Opening.Stream -> opening.params
        is Opening.Offer -> throw HandshakeException("expected START, got P2P_OFFER")
    }

    /**
     * Sender side, Wi-Fi Direct fast path (v4, ADR-011): wait for HELLO, then hand over the group's
     * credentials instead of START. Returns the receiver's HELLO; the caller closes the connection.
     */
    fun offerP2p(
        connection: Connection,
        offer: ControlMessage.P2pOffer,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    ): ControlMessage.Hello {
        val hello =
            when (val first = readControl(connection, timeoutMs, "HELLO")) {
                is ControlMessage.Hello -> first
                is ControlMessage.Bye -> throw HandshakeException("receiver refused: ${first.reason}", first.reason)
                else -> throw HandshakeException("expected HELLO, got ${first.type}")
            }
        connection.writer.writeControl(offer)
        connection.writer.flush()
        return hello
    }

    /** Sender side: wait for HELLO, negotiate, send START (or BYE(INCOMPATIBLE)). */
    fun asSender(
        connection: Connection,
        capabilities: SenderCapabilities,
        preferences: StreamPreferences,
        sessionId: Long,
        timeoutMs: Int = DEFAULT_TIMEOUT_MS,
        /** Exact local encoder check (#34): START never carries a size the encoder rejects. */
        encoderAccepts: EncoderPredicate = CodecNegotiator.ACCEPT_ALL,
    ): Pair<ControlMessage.Hello, StreamParams> {
        val hello =
            when (val first = readControl(connection, timeoutMs, "HELLO")) {
                is ControlMessage.Hello -> first
                is ControlMessage.Bye -> throw HandshakeException("receiver refused: ${first.reason}", first.reason)
                else -> throw HandshakeException("expected HELLO, got ${first.type}")
            }
        connection.setReadTimeout(0)
        return when (
            val result = CodecNegotiator.negotiate(
                capabilities,
                hello,
                preferences,
                sessionId,
                encoderAccepts,
            )
        ) {
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
        // One deadline for the whole exchange, so a peer spamming PINGs cannot hold it open.
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (true) {
            val remainingMs = (deadline - System.nanoTime()) / 1_000_000L
            if (remainingMs <= 0) throw HandshakeException("timed out waiting for $expected")
            connection.setReadTimeout(remainingMs.toInt())
            val packet =
                try {
                    connection.reader.read()
                } catch (e: SocketTimeoutException) {
                    throw HandshakeException("timed out waiting for $expected", cause = e)
                } catch (e: ProtocolException) {
                    if (e.reason == ProtocolException.Reason.UNSUPPORTED_VERSION) {
                        // The two devices run different app builds (no cross-version compatibility).
                        throw HandshakeException("protocol version mismatch: ${e.message}", ByeReason.INCOMPATIBLE, e)
                    }
                    throw HandshakeException("protocol error waiting for $expected: ${e.message}", cause = e)
                } ?: throw HandshakeException("peer closed before $expected")
            val type = packet.type ?: continue // unknown type from a newer peer: skip
            if (type == MessageType.PING || type == MessageType.PONG) continue
            if (type.isMedia) throw HandshakeException("media before handshake completed")
            return packet.decodeControl()
        }
    }
}
