package dev.ririprojector

/** Which side of the mirroring session this device runs. */
enum class AppMode {
    SENDER,
    RECEIVER,
    ;

    companion object {
        /** Parses the `mode` launch extra (case-insensitive); unknown values yield null. */
        fun fromExtra(value: String?): AppMode? =
            entries.firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) }
    }
}

/**
 * Picks the app mode at launch (ADR-001): an explicit `mode` extra wins, otherwise TV-like
 * devices become receivers and everything else a sender.
 */
object ModeResolver {
    const val EXTRA_MODE = "mode"

    /** Amazon's feature flag for Fire TV devices (some do not report leanback reliably). */
    const val FEATURE_FIRE_TV = "amazon.hardware.fire_tv"

    fun isTv(hasFireTvFeature: Boolean, hasLeanbackFeature: Boolean, isTelevisionUiMode: Boolean): Boolean =
        hasFireTvFeature || hasLeanbackFeature || isTelevisionUiMode

    fun resolve(modeExtra: String?, isTv: Boolean): AppMode =
        AppMode.fromExtra(modeExtra) ?: if (isTv) AppMode.RECEIVER else AppMode.SENDER
}
