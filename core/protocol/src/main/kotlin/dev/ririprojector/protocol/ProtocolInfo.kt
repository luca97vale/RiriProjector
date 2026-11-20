package dev.ririprojector.protocol

/** Wire protocol constants shared by sender and receiver (see ADR-003). */
object ProtocolInfo {
    /** Bumped on every wire format change. */
    const val PROTOCOL_VERSION: Int = 3

    /** NSD service type advertised by the receiver (ADR-006). */
    const val SERVICE_TYPE: String = "_ririproj._tcp"
}
