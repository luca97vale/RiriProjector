package dev.ririprojector.net.devlog

import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide developer-mode log (#33, ADR-013). Off by default: every helper then costs one
 * volatile read and never builds its message. Each device shows only its own log; nothing is sent
 * over the wire. Control path only, never per frame.
 */
object DevLog {
    /** Where every line goes; [PublishedApi] so the inline helpers can reach it. */
    @PublishedApi
    internal val log = DevEventLog()

    @Volatile private var on = false

    /** Turning it off clears the log; writing the current value again changes nothing. */
    var enabled: Boolean
        get() = on
        set(value) {
            synchronized(this) {
                if (on == value) return
                on = value
                if (!value) log.clear()
            }
        }

    val events: StateFlow<List<DevEvent>> get() = log.events

    inline fun info(source: String, message: () -> String) {
        if (enabled) log.add(DevLevel.INFO, source, message())
    }

    inline fun warn(source: String, message: () -> String) {
        if (enabled) log.add(DevLevel.WARN, source, message())
    }

    inline fun error(source: String, message: () -> String) {
        if (enabled) log.add(DevLevel.ERROR, source, message())
    }

    /** A line stamped at [atMs] (this log's clock, see [nowMs]) instead of now. */
    inline fun infoAt(atMs: Long, source: String, message: () -> String) {
        if (enabled) log.add(DevLevel.INFO, source, message(), atMs)
    }

    /** Starts a new attempt (the sender's pick): clears the log. */
    fun begin(title: String) {
        if (enabled) log.begin(title)
    }

    /** Receiver: new attempt unless the last milestone is recent (see [DevEventLog.beginUnlessRecent]). */
    fun beginUnlessRecent(title: String, windowMs: Long) {
        if (enabled) log.beginUnlessRecent(title, windowMs)
    }

    /** Receiver: attempt milestone (see [DevEventLog.touch]). */
    fun touch() {
        if (enabled) log.touch()
    }

    /** The log's clock: `CLOCK_MONOTONIC` ms. */
    fun nowMs(): Long = log.nowMs()

    fun format(): String = log.format()

    const val SOURCE_P2P = "P2P"
}
