package dev.ririprojector.net.devlog

import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Severity of a [DevEvent]; WARN and ERROR are highlighted on screen. */
enum class DevLevel(val letter: Char) {
    INFO('I'),
    WARN('W'),
    ERROR('E'),
}

/**
 * One line of the developer-mode log (#33). [atMs] is relative to the start of the current
 * attempt; [repeat] > 1 when consecutive identical lines were collapsed, the last one at [lastAtMs].
 */
data class DevEvent(
    val atMs: Long,
    val level: DevLevel,
    val source: String,
    val message: String,
    val repeat: Int = 1,
    val lastAtMs: Long = atMs,
)

/**
 * Bounded, thread-safe log of connection steps for developer mode (#33). Pure: [clockMs] is
 * injectable. Every change publishes an immutable snapshot to [events]. Control path only (a few
 * events per second at most): never call it per frame.
 */
class DevEventLog(private val capacity: Int = DEFAULT_CAPACITY, private val clockMs: () -> Long = ::monotonicMs) {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val buffer = ArrayDeque<DevEvent>(capacity)
    private var startMs = clockMs()

    /** Clock reading of the last [begin] or accepted [beginUnlessRecent] mark, null before any. */
    private var lastMarkMs: Long? = null

    private val _events = MutableStateFlow<List<DevEvent>>(emptyList())
    val events: StateFlow<List<DevEvent>> = _events.asStateFlow()

    /** Starts a new attempt: clears the log, restarts the relative clock and logs [title]. */
    @Synchronized
    fun begin(title: String) {
        val now = clockMs()
        buffer.clear()
        startMs = now
        lastMarkMs = now
        append(DevLevel.INFO, SOURCE_SESSION, title, now)
    }

    /**
     * Starts a new attempt unless the previous mark is less than [windowMs] old; then [title] is
     * only appended, so the steps of one attempt (offer, join, connection) stay in one log.
     * Returns true when a new attempt began.
     */
    @Synchronized
    fun beginUnlessRecent(title: String, windowMs: Long): Boolean {
        val now = clockMs()
        val last = lastMarkMs
        if (last != null && now - last < windowMs) {
            lastMarkMs = now
            append(DevLevel.INFO, SOURCE_SESSION, title, now)
            return false
        }
        begin(title)
        return true
    }

    /**
     * Marks attempt activity (handshake, session end, offer accepted) without logging, so a
     * reconnect shortly after a long session keeps the lines about how it ended. Ordinary lines
     * never refresh the mark: repeating background lines must not keep an old log alive.
     */
    @Synchronized
    fun touch() {
        lastMarkMs = clockMs()
    }

    /** Appends a line; [atMs] is an absolute reading of this log's clock (default: now). */
    @Synchronized
    fun add(level: DevLevel, source: String, message: String, atMs: Long = clockMs()) {
        append(level, source, message, atMs)
    }

    /** Empties the log (developer mode turned off). */
    @Synchronized
    fun clear() {
        buffer.clear()
        lastMarkMs = null
        startMs = clockMs()
        _events.value = emptyList()
    }

    /** The current clock reading, for callers that stamp an event before logging it. */
    fun nowMs(): Long = clockMs()

    /** The whole log as plain text, one line per event (for "Copy log"). */
    fun format(): String = events.value.joinToString("\n", transform = ::formatLine)

    private fun append(level: DevLevel, source: String, message: String, atMs: Long) {
        val rel = (atMs - startMs).coerceAtLeast(0)
        val last = buffer.lastOrNull()
        if (last != null && last.level == level && last.source == source && last.message == message) {
            buffer[buffer.size - 1] = last.copy(repeat = last.repeat + 1, lastAtMs = maxOf(rel, last.lastAtMs))
        } else {
            if (buffer.size == capacity) buffer.removeFirst()
            // A line stamped in the past (e.g. a frame's render time logged by a later tick) goes to
            // its place in time, so the log always reads in order.
            var at = buffer.size
            while (at > 0 && buffer[at - 1].atMs > rel) at--
            buffer.add(at, DevEvent(rel, level, source, message))
        }
        _events.value = buffer.toList()
    }

    companion object {
        const val DEFAULT_CAPACITY = 300

        /** Source of attempt titles. */
        const val SOURCE_SESSION = "Session"

        /** `CLOCK_MONOTONIC` in ms: the clock of `System.nanoTime()`-based timestamps (frame render times). */
        fun monotonicMs(): Long = System.nanoTime() / 1_000_000

        /** "+1.234 s  W  [P2P] message  (×3, last +5.678 s)". */
        fun formatLine(e: DevEvent): String {
            val base = "${seconds(e.atMs)}  ${e.level.letter}  [${e.source}] ${e.message}"
            return if (e.repeat > 1) "$base  (×${e.repeat}, last ${seconds(e.lastAtMs)})" else base
        }

        private fun seconds(ms: Long) = String.format(Locale.ROOT, "+%d.%03d s", ms / 1000, ms % 1000)
    }
}
