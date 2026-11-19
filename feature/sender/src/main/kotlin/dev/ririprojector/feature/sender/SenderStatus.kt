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
)

sealed interface SenderState {
    data object Idle : SenderState

    data class Connecting(val target: ReceiverEndpoint) : SenderState

    data class Streaming(val target: ReceiverEndpoint, val params: StreamParams, val stats: SenderStats) : SenderState

    data class Ended(val message: String?) : SenderState
}

/** Process-wide sender state shared by [ProjectionService] (writer) and the UI (reader). */
object SenderStatus {
    private val _state = MutableStateFlow<SenderState>(SenderState.Idle)
    val state: StateFlow<SenderState> = _state.asStateFlow()

    internal fun set(value: SenderState) {
        _state.value = value
    }
}
