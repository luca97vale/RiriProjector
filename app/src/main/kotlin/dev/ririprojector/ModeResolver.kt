package dev.ririprojector

/** Which side of the mirroring session this device runs. */
enum class AppMode {
    SENDER,
    RECEIVER,
    ;

    companion object {
        /** Parses the `mode` launch extra or a stored choice (case-insensitive); unknown values yield null. */
        fun fromExtra(value: String?): AppMode? =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * Picks the app mode at launch (ADR-001): an explicit `mode` extra wins, then the choice the user
 * made in the app ("Use this device as a receiver/sender"), otherwise TV-like devices become
 * receivers and everything else (phones, tablets) a sender.
 */
object ModeResolver {
    const val EXTRA_MODE = "mode"

    /**
     * Fire TV feature flag. Some Fire OS builds report neither leanback nor the TV UI mode, so it is
     * one more TV signal; any other device just does not have it.
     */
    const val FEATURE_FIRE_TV = "amazon.hardware.fire_tv"

    fun isTv(hasFireTvFeature: Boolean, hasLeanbackFeature: Boolean, isTelevisionUiMode: Boolean): Boolean =
        hasFireTvFeature || hasLeanbackFeature || isTelevisionUiMode

    /**
     * [senderSessionActive]: a mirroring session runs on this device (e.g. its notification was
     * tapped); it always reopens the sender, whatever was stored or asked for.
     */
    fun resolve(
        modeExtra: String?,
        isTv: Boolean,
        remembered: AppMode? = null,
        senderSessionActive: Boolean = false,
    ): AppMode = if (senderSessionActive) {
        AppMode.SENDER
    } else {
        AppMode.fromExtra(modeExtra) ?: remembered ?: if (isTv) AppMode.RECEIVER else AppMode.SENDER
    }

    /**
     * The mode a switch request asks for, from the actions the feature screens send; null for any
     * other action (a normal launch).
     */
    fun switchTarget(action: String?, useAsReceiverAction: String, useAsSenderAction: String): AppMode? =
        when (action) {
            useAsReceiverAction -> AppMode.RECEIVER
            useAsSenderAction -> AppMode.SENDER
            else -> null
        }
}
