package dev.ririprojector.net

/** Connection lifecycle of a sender session, free of I/O so it can be tested on the JVM. */
sealed interface SessionState {
    data object Idle : SessionState

    data class Connecting(val attempt: Int) : SessionState

    data class Handshaking(val attempt: Int) : SessionState

    data object Streaming : SessionState

    data class Reconnecting(val attempt: Int, val retryAtMs: Long) : SessionState

    data class Closed(val reason: CloseReason) : SessionState
}

enum class CloseReason { STOPPED, FATAL_ERROR, GAVE_UP }

sealed interface SessionEvent {
    data class Start(val nowMs: Long) : SessionEvent

    data object Connected : SessionEvent

    data object HandshakeCompleted : SessionEvent

    /** I/O failure; [fatal] for errors a retry cannot fix (incompatible, projection revoked). */
    data class Failed(val nowMs: Long, val fatal: Boolean = false) : SessionEvent

    data class Tick(val nowMs: Long) : SessionEvent

    data object Stop : SessionEvent
}

sealed interface SessionAction {
    data object OpenConnection : SessionAction

    data object CloseConnection : SessionAction

    /** Ask the encoder for an IDR: the receiver lost its reference frames. */
    data object RequestKeyframe : SessionAction
}

data class Transition(val state: SessionState, val actions: List<SessionAction> = emptyList())

/**
 * Pure transition function. Reconnection backoff 250 ms, then 500 ms (capped), so a lost receiver
 * that comes back is found within 3 s (ADR-010). Gives up when an outage lasts [giveUpAfterMs]
 * (measured from the first failure since the last successful handshake), or after [maxAttempts]
 * consecutive failures.
 */
class SessionStateMachine(
    private val maxAttempts: Int = Int.MAX_VALUE,
    private val backoffMs: LongArray = longArrayOf(250, 500),
    private val giveUpAfterMs: Long = 30_000,
) {
    var state: SessionState = SessionState.Idle
        private set

    /** First failure of the current outage; null while streaming. */
    private var outageStartMs: Long? = null

    fun backoffFor(attempt: Int): Long = backoffMs[(attempt - 1).coerceIn(0, backoffMs.size - 1)]

    fun on(event: SessionEvent): Transition {
        val t = transition(state, event)
        state = t.state
        return t
    }

    private fun transition(s: SessionState, e: SessionEvent): Transition = when {
        e is SessionEvent.Stop ->
            if (s is SessionState.Closed) {
                Transition(s)
            } else {
                Transition(SessionState.Closed(CloseReason.STOPPED), listOf(SessionAction.CloseConnection))
            }

        s is SessionState.Idle && e is SessionEvent.Start ->
            Transition(SessionState.Connecting(1), listOf(SessionAction.OpenConnection))

        s is SessionState.Connecting && e is SessionEvent.Connected -> Transition(SessionState.Handshaking(s.attempt))

        s is SessionState.Handshaking && e is SessionEvent.HandshakeCompleted -> {
            outageStartMs = null
            Transition(SessionState.Streaming, listOf(SessionAction.RequestKeyframe))
        }

        e is SessionEvent.Failed && s.isActive() -> fail(s, e)

        s is SessionState.Reconnecting && e is SessionEvent.Tick && e.nowMs >= s.retryAtMs ->
            Transition(SessionState.Connecting(s.attempt), listOf(SessionAction.OpenConnection))

        else -> Transition(s) // ignored event
    }

    private fun fail(s: SessionState, e: SessionEvent.Failed): Transition {
        if (e.fatal) {
            return Transition(
                SessionState.Closed(CloseReason.FATAL_ERROR),
                listOf(SessionAction.CloseConnection),
            )
        }
        val nextAttempt =
            when (s) {
                is SessionState.Streaming -> 1
                is SessionState.Connecting -> s.attempt + 1
                is SessionState.Handshaking -> s.attempt + 1
                else -> 1
            }
        val outageStart = outageStartMs ?: e.nowMs.also { outageStartMs = it }
        if (nextAttempt > maxAttempts || e.nowMs - outageStart >= giveUpAfterMs) {
            return Transition(SessionState.Closed(CloseReason.GAVE_UP), listOf(SessionAction.CloseConnection))
        }
        return Transition(
            SessionState.Reconnecting(nextAttempt, e.nowMs + backoffFor(nextAttempt)),
            listOf(SessionAction.CloseConnection),
        )
    }

    private fun SessionState.isActive() =
        this is SessionState.Connecting || this is SessionState.Handshaking || this is SessionState.Streaming
}
