package dev.ririprojector.protocol

import java.io.IOException

/** A peer violated wire format v1; the session must be torn down. */
class ProtocolException(val reason: Reason, message: String) : IOException("$reason: $message") {
    enum class Reason {
        BAD_MAGIC,
        UNSUPPORTED_VERSION,
        PAYLOAD_TOO_LARGE,
        TRUNCATED,
        MALFORMED_PAYLOAD,
        UNEXPECTED_TYPE,
    }
}
