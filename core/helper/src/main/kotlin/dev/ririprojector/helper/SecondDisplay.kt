package dev.ririprojector.helper

/** Pure rules of the second-screen virtual display (ADR-014). */
object SecondDisplay {
    // DisplayManager.VIRTUAL_DISPLAY_FLAG_* (several are @hide; values are stable platform constants).
    const val FLAG_PUBLIC = 1 shl 0
    const val FLAG_PRESENTATION = 1 shl 1
    const val FLAG_SECURE = 1 shl 2
    const val FLAG_OWN_CONTENT_ONLY = 1 shl 3
    const val FLAG_SUPPORTS_TOUCH = 1 shl 6
    const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
    const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
    const val FLAG_TRUSTED = 1 shl 10
    const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
    const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
    const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
    const val FLAG_OWN_FOCUS = 1 shl 14
    const val FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15

    /** Short side in dp: the owner's "maximum possible" layout (Android 16 large-screen rules apply). */
    const val TARGET_SHORT_SIDE_DP = 600
    const val MIN_DPI = 160

    /**
     * Flags per API level. Never ROTATES_WITH_CONTENT (fixed landscape) or SECURE (FLAG_SECURE content
     * stays black by design).
     */
    fun flags(sdkInt: Int): Int {
        var f = FLAG_PUBLIC or FLAG_PRESENTATION or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or
            FLAG_DESTROY_CONTENT_ON_REMOVAL
        if (sdkInt >= 33) {
            f = f or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
        }
        if (sdkInt >= 34) f = f or FLAG_OWN_FOCUS or FLAG_DEVICE_DISPLAY_GROUP
        return f
    }

    /** `max(160, shortSide × 160 / 600)`: 192 dpi at 720p, 288 at 1080p, 576 at 2160p. */
    fun densityDpi(width: Int, height: Int): Int = maxOf(MIN_DPI, minOf(width, height) * 160 / TARGET_SHORT_SIDE_DP)

    /** The display is always landscape: (long, short) of the receiver's screen. */
    fun landscape(width: Int, height: Int): Pair<Int, Int> = maxOf(width, height) to minOf(width, height)

    // KeyEvent.KEYCODE_* values (stable platform constants), as ranges so this stays pure.
    private val ALLOWED_KEYS: List<IntRange> = listOf(
        4..4, // BACK
        7..23, // 0-9, STAR, POUND, D-pad and center
        29..63, // A-Z, punctuation, ALT/SHIFT, TAB, SPACE, SYM
        66..77, // ENTER, DEL, punctuation (not 64 EXPLORER / 65 ENVELOPE: they launch apps)
        81..82, // PLUS, MENU
        85..90, // media play/pause, stop, next, previous, rewind, fast forward
        92..93, // PAGE_UP, PAGE_DOWN
        96..110, // gamepad buttons
        111..115, // ESCAPE, FORWARD_DEL, CTRL, CAPS_LOCK
        122..124, // MOVE_HOME, MOVE_END, INSERT
        126..127, // MEDIA_PLAY, MEDIA_PAUSE
        131..163, // F1-F12, NUM_LOCK, numpad
        268..271, // D-pad diagonals
    )

    /**
     * Keys a peer may inject into the second screen: an allowlist of what typing, D-pad navigation,
     * gamepads and media need. Everything that acts on the whole device (home, power, sleep, assist,
     * screenshot, volume, brightness, app switch, META shortcuts, newer keycodes) is dropped.
     */
    fun isAllowedKey(keyCode: Int): Boolean = ALLOWED_KEYS.any { keyCode in it }
}

/** Builds the shell command that starts the helper (ADR-014). Pure. */
object HelperCommand {
    const val PROCESS_NAME = "riri_helper"
    const val MAIN_CLASS = "dev.ririprojector.helper.HelperMain"
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    /**
     * `CLASSPATH='<apk>' setsid app_process /system/bin --nice-name=riri_helper <main> <pkg> <userId>`,
     * detached with its standard streams closed, so it outlives the `shell:` stream.
     */
    fun start(apkPath: String, packageName: String, userId: Int): String {
        require(PACKAGE.matches(packageName)) { "bad package name" }
        require(userId >= 0) { "bad user id" }
        return "CLASSPATH=${quote(apkPath)} setsid app_process /system/bin --nice-name=$PROCESS_NAME " +
            "$MAIN_CLASS $packageName $userId </dev/null >/dev/null 2>&1 &"
    }

    /** Stops a helper left from an older build before starting a new one. */
    fun stopOld(): String = "pkill -f '^$PROCESS_NAME' ; true"

    /** POSIX single quoting: `'` becomes `'\''`. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/** 1-ms buckets up to 100 ms; aggregated per stats() call (no per-event logging). */
class LatencyHistogram {
    private val buckets = LongArray(BUCKETS)
    private var count = 0L
    private var maxUs = 0L

    @Synchronized
    fun add(latencyUs: Long) {
        val us = latencyUs.coerceAtLeast(0)
        buckets[(us / 1000).toInt().coerceAtMost(BUCKETS - 1)]++
        count++
        if (us > maxUs) maxUs = us
    }

    /** Returns `[count, p95 µs (bucket upper bound), max µs]` and resets. */
    @Synchronized
    fun drain(): LongArray {
        val out = longArrayOf(count, percentileUs(0.95), maxUs)
        buckets.fill(0)
        count = 0
        maxUs = 0
        return out
    }

    private fun percentileUs(p: Double): Long {
        if (count == 0L) return 0
        val target = kotlin.math.ceil(count * p).toLong()
        var seen = 0L
        for (i in buckets.indices) {
            seen += buckets[i]
            if (seen >= target) return (i + 1) * 1000L
        }
        return BUCKETS * 1000L
    }

    private companion object {
        const val BUCKETS = 100
    }
}
