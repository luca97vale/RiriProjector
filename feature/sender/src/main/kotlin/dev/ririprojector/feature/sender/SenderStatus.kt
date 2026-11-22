package dev.ririprojector.feature.sender

import dev.ririprojector.net.ReceiverEndpoint
import dev.ririprojector.protocol.model.StreamParams
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Per-second sender metrics, merged with the receiver's STATS. */
data class SenderStats(
    val encodedFps: Double = 0.0,
    val bitrateBps: Long = 0,
    val rttMs: Double? = null,
    val sendDropped: Long = 0,
    val receiverFps: Double = 0.0,
    val receiverDecodeMs: Double = 0.0,
    val receiverDecodeP95Ms: Double = 0.0,
    val receiverDropped: Int = 0,
    val encoderName: String = "",
    val audio: AudioStatus = AudioStatus.OFF,
    val receiverUnderruns: Int = 0,
    /** Audio minus video at the receiver; positive = audio late. */
    val avOffsetMs: Int = 0,
    /** Lip-sync video delay the receiver applies (0 in low-latency mode). */
    val receiverVideoDelayMs: Int = 0,
    /** Capture here to shown on the receiver, mean and p95; null until the receiver knows it. */
    val latencyMs: Int? = null,
    val latencyP95Ms: Int? = null,
    /** Single-app sharing: the shared app is not on screen, so the receiver shows nothing. */
    val contentHidden: Boolean = false,
)

sealed interface SenderState {
    data object Idle : SenderState

    data class Connecting(val target: ReceiverEndpoint) : SenderState

    data class Streaming(val target: ReceiverEndpoint, val params: StreamParams, val stats: SenderStats) : SenderState

    /** The link dropped; the projection is kept and a new connection is being tried (ADR-010). */
    data class Reconnecting(val target: ReceiverEndpoint, val attempt: Int) : SenderState

    data class Ended(val message: String?) : SenderState
}

/** The session runs over the tablet's own Wi-Fi Direct group on [frequencyMhz] (ADR-011). */
data class DirectLink(val frequencyMhz: Int)

/** Process-wide sender state shared by [ProjectionService] (writer) and the UI (reader). */
object SenderStatus {
    private val _state = MutableStateFlow<SenderState>(SenderState.Idle)
    val state: StateFlow<SenderState> = _state.asStateFlow()

    private val _direct = MutableStateFlow<DirectLink?>(null)

    /** Non-null while the session's link is a Wi-Fi Direct group. */
    val direct: StateFlow<DirectLink?> = _direct.asStateFlow()

    internal fun set(value: SenderState) {
        _state.value = value
    }

    internal fun setDirect(value: DirectLink?) {
        _direct.value = value
    }
}

/** What the sender does with audio in the current session. */
enum class AudioStatus {
    /** Captured and streamed. */
    ON,

    /** Turned off in the settings. */
    OFF,

    /** Wanted but not possible: permission denied, API < 29, or capture failed. */
    UNAVAILABLE,

    /** Sharing one app with no app chosen as the audio source: other apps must not be heard. */
    NO_APP,
}
