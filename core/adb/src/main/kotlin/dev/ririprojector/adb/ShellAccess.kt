package dev.ririprojector.adb

/**
 * Where the second-screen setup stands (#35, ADR-014). Pure: the setup screen renders it and the
 * restore flow after a reboot or Wi-Fi change follows from it.
 */
sealed interface ShellAccessState {
    /** Android 10 or older: Wireless debugging pairing does not exist. */
    data object Unsupported : ShellAccessState

    /** No key paired yet: guide through Developer options → Wireless debugging → pairing code. */
    data object NotPaired : ShellAccessState

    /** Looking for the pairing service, or waiting for the code. */
    data class Pairing(val portFound: Boolean) : ShellAccessState

    /** Paired, helper not running; [wirelessDebuggingOn] false = the user must turn it on again. */
    data class Paired(val wirelessDebuggingOn: Boolean) : ShellAccessState

    /** Connecting to adbd and starting the helper. */
    data object Starting : ShellAccessState

    /** The helper's binder is alive. */
    data object Ready : ShellAccessState

    data class Failed(val reason: Reason, val paired: Boolean) : ShellAccessState

    enum class Reason { WRONG_CODE, PAIRING_FAILED, CONNECT_FAILED, HELPER_DID_NOT_START }
}

sealed interface ShellAccessEvent {
    data object PairingRequested : ShellAccessEvent

    data class PairingPort(val port: Int) : ShellAccessEvent

    data object PairingSucceeded : ShellAccessEvent

    data class PairingFailed(val wrongCode: Boolean) : ShellAccessEvent

    /** The `_adb-tls-connect` service of this device appeared (port > 0) or went away (-1). */
    data class ConnectPort(val port: Int) : ShellAccessEvent

    data object StartRequested : ShellAccessEvent

    /** adbd refused our key: the pairing was removed in Settings. */
    data object KeyRefused : ShellAccessEvent

    data object ConnectFailed : ShellAccessEvent

    data object HelperStarted : ShellAccessEvent

    data object HelperTimedOut : ShellAccessEvent

    data object HelperDied : ShellAccessEvent

    data object Forget : ShellAccessEvent
}

object ShellAccess {
    /** Wireless debugging pairing needs Android 11 (API 30). */
    const val MIN_SDK = 30

    fun initial(sdkInt: Int, paired: Boolean): ShellAccessState = when {
        sdkInt < MIN_SDK -> ShellAccessState.Unsupported
        paired -> ShellAccessState.Paired(wirelessDebuggingOn = false)
        else -> ShellAccessState.NotPaired
    }

    fun reduce(state: ShellAccessState, event: ShellAccessEvent): ShellAccessState {
        if (state == ShellAccessState.Unsupported) return state
        val paired = isPaired(state)
        return when (event) {
            ShellAccessEvent.PairingRequested -> ShellAccessState.Pairing(portFound = false)

            is ShellAccessEvent.PairingPort ->
                if (state is ShellAccessState.Pairing) state.copy(portFound = event.port > 0) else state

            ShellAccessEvent.PairingSucceeded -> ShellAccessState.Paired(wirelessDebuggingOn = true)

            is ShellAccessEvent.PairingFailed -> ShellAccessState.Failed(
                if (event.wrongCode) ShellAccessState.Reason.WRONG_CODE else ShellAccessState.Reason.PAIRING_FAILED,
                paired = false,
            )

            is ShellAccessEvent.ConnectPort -> when (state) {
                is ShellAccessState.Paired -> state.copy(wirelessDebuggingOn = event.port > 0)

                is ShellAccessState.Failed ->
                    if (state.paired) ShellAccessState.Paired(wirelessDebuggingOn = event.port > 0) else state

                // A running helper does not need adbd any more.
                else -> state
            }

            ShellAccessEvent.StartRequested -> if (paired) ShellAccessState.Starting else state

            ShellAccessEvent.KeyRefused -> ShellAccessState.NotPaired

            ShellAccessEvent.ConnectFailed ->
                ShellAccessState.Failed(ShellAccessState.Reason.CONNECT_FAILED, paired = paired)

            ShellAccessEvent.HelperStarted -> ShellAccessState.Ready

            ShellAccessEvent.HelperTimedOut ->
                ShellAccessState.Failed(ShellAccessState.Reason.HELPER_DID_NOT_START, paired = paired)

            // Reboot or app update: still paired, Wireless debugging state unknown until NSD says.
            ShellAccessEvent.HelperDied ->
                if (state == ShellAccessState.Ready) ShellAccessState.Paired(wirelessDebuggingOn = false) else state

            ShellAccessEvent.Forget -> ShellAccessState.NotPaired
        }
    }

    fun isPaired(state: ShellAccessState): Boolean = when (state) {
        is ShellAccessState.Paired, ShellAccessState.Starting, ShellAccessState.Ready -> true
        is ShellAccessState.Failed -> state.paired
        else -> false
    }
}
