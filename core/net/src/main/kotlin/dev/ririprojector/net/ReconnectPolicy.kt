package dev.ririprojector.net

import dev.ririprojector.protocol.ByeReason

/** Whether a link that ended is worth reconnecting (ADR-010). Pure. */
object ReconnectPolicy {
    enum class Decision { FINAL, RETRY }

    /** Encoder failures tolerated per session before giving up (each one rebuilds the encoder). */
    const val MAX_ENCODER_FAILURES = 3

    /**
     * @param peerBye BYE reason the receiver sent, if any
     * @param incompatible the handshake failed on version or codecs: a retry would fail the same way
     * @param encoderFailed this link ended because the encoder failed
     * @param encoderFailures encoder failures so far in the session, this one included
     *
     * A local stop (user, projection revoked) is final and decided by the caller before this.
     */
    fun classify(peerBye: ByeReason?, incompatible: Boolean, encoderFailed: Boolean, encoderFailures: Int): Decision =
        when {
            incompatible -> Decision.FINAL

            // The receiver's user left the app, or it cannot ever play this stream.
            peerBye == ByeReason.USER_STOPPED || peerBye == ByeReason.INCOMPATIBLE ||
                peerBye == ByeReason.PROJECTION_REVOKED -> Decision.FINAL

            encoderFailed -> if (encoderFailures > MAX_ENCODER_FAILURES) Decision.FINAL else Decision.RETRY

            // NORMAL (receiver backgrounded), BUSY, ERROR, I/O errors and timeouts.
            else -> Decision.RETRY
        }
}
