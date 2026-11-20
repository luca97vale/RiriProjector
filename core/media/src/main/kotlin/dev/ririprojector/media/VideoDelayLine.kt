package dev.ririprojector.media

import android.os.Process
import java.io.Closeable
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Where delayed access units go (the decoder). Called from the delay thread only. */
fun interface DelaySink {
    fun submit(src: ByteArray, offset: Int, length: Int, ptsUs: Long, isConfig: Boolean, isKeyframe: Boolean)
}

/**
 * Lip-sync video delay line (ADR-007): holds every video unit for the current delay before the
 * decoder sees it. The network thread [offer]s (copy into a [DelayRing], never blocks); the
 * "Riri/VideoDelay" thread waits for the head's due time and submits it to [sink]. The ring region
 * being decoded is released only after [DelaySink.submit] returns, so offers never overwrite it.
 * [onOverflow] runs (network thread) when units start being dropped until the next keyframe;
 * [onError] runs (delay thread, which then stops) if feeding the decoder throws.
 */
class VideoDelayLine(
    capacityBytes: Int,
    private val sink: DelaySink,
    private val onOverflow: () -> Unit,
    private val onError: (Exception) -> Unit,
) : Closeable {
    private val ring = DelayRing(capacityBytes)
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var thread: Thread? = null

    @Volatile private var closed = false

    /** Units dropped because the ring overflowed (diagnostics). */
    val dropped: Long get() = lock.withLock { ring.dropped }

    fun start(): VideoDelayLine {
        thread =
            Thread({ loop() }, "Riri/VideoDelay").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        return this
    }

    /** Network thread. Copies [src]`[0 until length]`; due at now + [delayUs] (kept in order). */
    fun offer(src: ByteArray, length: Int, ptsUs: Long, isConfig: Boolean, isKeyframe: Boolean, delayUs: Long) {
        val result =
            lock.withLock {
                if (closed) return
                val r = ring.offer(src, 0, length, ptsUs, isConfig, isKeyframe, nowUs(), delayUs)
                if (r == DelayRing.Offer.QUEUED) changed.signal()
                r
            }
        if (result == DelayRing.Offer.OVERFLOW) onOverflow()
    }

    private fun loop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        try {
            while (true) {
                var offset: Int
                var length: Int
                var pts: Long
                var isConfig: Boolean
                var isKey: Boolean
                lock.withLock {
                    while (true) {
                        if (closed) return
                        if (ring.isEmpty) {
                            changed.await()
                            continue
                        }
                        val waitUs = ring.headDueUs - nowUs()
                        if (waitUs <= 0) break
                        changed.awaitNanos(waitUs * 1_000)
                    }
                    offset = ring.headOffset
                    length = ring.headLength
                    pts = ring.headPtsUs
                    isConfig = ring.headIsConfig
                    isKey = ring.headIsKeyframe
                }
                try {
                    sink.submit(ring.buffer, offset, length, pts, isConfig, isKey)
                } finally {
                    lock.withLock { if (!closed) ring.releaseHead() }
                }
            }
        } catch (_: InterruptedException) {
            // closing
        } catch (e: Exception) {
            // Never crash the process from this thread: the owner ends the session cleanly.
            if (!closed) onError(e)
        }
    }

    /** Stops the thread (bounded wait: a submit can block briefly on the decoder) and drops the queue. */
    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            ring.clear()
            changed.signalAll()
        }
        thread?.let {
            it.join(JOIN_MS)
            if (it.isAlive) it.interrupt()
        }
    }

    companion object {
        private const val JOIN_MS = 200L

        /** Room for the maximum delay plus margin at twice the stream bitrate, at least 2 MiB. */
        fun capacityFor(bitrateBps: Int, maxDelayMs: Int): Int {
            val bytes = bitrateBps.toLong() / 8 * (maxDelayMs + 200) / 1000 * 2
            return maxOf(2L * 1024 * 1024, bytes).coerceAtMost(64L * 1024 * 1024).toInt()
        }

        private fun nowUs() = System.nanoTime() / 1_000
    }
}
