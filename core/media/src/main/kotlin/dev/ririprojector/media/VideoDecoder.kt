package dev.ririprojector.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import dev.ririprojector.protocol.model.VideoConfig
import java.io.Closeable
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Told when a frame is released for display; [shownAtUs] is receiver `CLOCK_MONOTONIC` µs. */
fun interface FrameRenderListener {
    fun onRendered(ptsUs: Long, shownAtUs: Long)
}

/** Snapshot of decoder counters, read once per second for STATS and the overlay. */
data class DecoderStats(
    val decodedFrames: Long,
    val droppedFrames: Long,
    val latencyMeanUs: Long,
    val latencyP95Us: Long,
    val resets: Int,
)

/**
 * Low-latency hardware video decoder (ADR-005). `MediaCodec` runs in async mode with callbacks on
 * a dedicated urgent-display-priority thread. The network thread calls [submit] for each access
 * unit, which is copied straight into a codec input buffer; every output is rendered immediately.
 *
 * Concurrency: one lock covers the input-index pool, the whole dequeue → copy → queue sequence and
 * codec resets, so an input index can never outlive the codec generation it came from.
 *
 * [DecoderBacklog] drops frames until the next keyframe when the decoder falls behind, and
 * [onKeyframeNeeded] is invoked so the session can send REQUEST_KEYFRAME. It must not block (the
 * caller rate-limits and hands the message to its writer thread). Recoverable codec errors reset
 * the codec in place and replay the cached codec config, since the encoder sends it only once.
 */
class VideoDecoder(
    private val codecName: String,
    private val config: VideoConfig,
    private val surface: Surface,
    private val lowLatencySupported: Boolean,
    private val onKeyframeNeeded: () -> Unit,
    private val onFatalError: (Throwable) -> Unit = {},
    private val onFrameRendered: FrameRenderListener? = null,
) : Closeable {
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val lock = ReentrantLock()
    private val inputAvailable = lock.newCondition()

    // Everything below is guarded by lock.
    private var codec: MediaCodec? = null
    private val freeInputs = IntArray(MAX_INPUTS)
    private var freeHead = 0
    private var freeCount = 0
    private val backlog = DecoderBacklog()
    private val latency = LatencyStats()
    private val queuedPts = LongArray(PTS_SLOTS)
    private val queuedAtUs = LongArray(PTS_SLOTS)
    private var queuedNext = 0
    private var decodedFrames = 0L
    private var resets = 0
    private var closed = false
    private var csd = ByteArray(0)
    private var csdLength = 0
    private var replayCsd = false

    private val callback =
        object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, index: Int) = lock.withLock {
                if (mc !== codec || freeCount == MAX_INPUTS) return
                freeInputs[(freeHead + freeCount) % MAX_INPUTS] = index
                freeCount++
                inputAvailable.signal()
            }

            override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                val nowUs = nowUs()
                lock.withLock {
                    if (mc !== codec) return
                    try {
                        mc.releaseOutputBuffer(index, true)
                    } catch (e: IllegalStateException) {
                        return
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
                    backlog.onFrameOutput()
                    decodedFrames++
                    val queuedAt = lookupQueuedAt(info.presentationTimeUs)
                    if (queuedAt > 0) latency.add(nowUs - queuedAt)
                }
                onFrameRendered?.onRendered(info.presentationTimeUs, System.nanoTime() / 1000)
            }

            override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                Log.w(TAG, "codec error recoverable=${e.isRecoverable} transient=${e.isTransient}: ${e.diagnosticInfo}")
                when {
                    e.isTransient -> Unit

                    // the failed call can simply be retried later
                    e.isRecoverable -> resetCodec(mc)

                    else -> onFatalError(e)
                }
            }

            override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "output format $format")
            }
        }

    /** Creates and starts the codec. Throws (leaking nothing) if it cannot be configured. */
    fun start(): VideoDecoder {
        val t = HandlerThread("Riri/Decoder", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        thread = t
        handler = Handler(t.looper)
        var mc: MediaCodec? = null
        try {
            mc = MediaCodec.createByCodecName(codecName)
            lock.withLock { configureLocked(mc) }
        } catch (e: Exception) {
            lock.withLock { codec = null }
            runCatching { mc?.release() }
            t.quitSafely()
            throw e
        }
        Log.i(TAG, "started $codecName ${config.width}x${config.height}@${config.fps}")
        return this
    }

    private fun configureLocked(mc: MediaCodec) {
        freeCount = 0
        freeHead = 0
        backlog.reset()
        codec = mc
        mc.setCallback(callback, handler)
        try {
            mc.configure(format(lowLatencyKeys = true), surface, null, 0)
        } catch (e: Exception) {
            // Some decoders reject the low-latency or vendor keys: retry with a plain format.
            Log.w(TAG, "configure with low-latency keys failed (${e.message}); retrying plain")
            mc.reset()
            mc.setCallback(callback, handler)
            mc.configure(format(lowLatencyKeys = false), surface, null, 0)
        }
        mc.start()
        replayCsd = csdLength > 0
    }

    private fun format(lowLatencyKeys: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(config.codec.mime, config.width, config.height).apply {
            // Keyframes can be large at high bitrates; the default input size is often too small.
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxOf(config.width * config.height, 1 shl 20))
            if (!lowLatencyKeys) return@apply
            setInteger(MediaFormat.KEY_PRIORITY, 0) // realtime
            setInteger(MediaFormat.KEY_OPERATING_RATE, config.fps)
            if (Build.VERSION.SDK_INT >= 30 && lowLatencySupported) {
                setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            // Vendor keys: ignored by codecs that do not know them.
            setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
            setInteger("vendor.low-latency.enable", 1)
        }

    /**
     * Feeds one access unit from [src]`[0 until length]`. Called from the network thread only.
     * Waits briefly for a free input buffer; returns false if the unit was dropped.
     */
    fun submit(src: ByteArray, length: Int, ptsUs: Long, isConfig: Boolean, isKeyframe: Boolean): Boolean {
        val receivedAtUs = nowUs()
        var requestKeyframe = false
        val queued =
            lock.withLock {
                if (closed || codec == null) return false
                if (isConfig) cacheCsd(src, length)
                if (replayCsd && !isConfig && !replayCsdLocked()) {
                    requestKeyframe = true
                    return@withLock false
                }
                when (backlog.onAccessUnit(isConfig, isKeyframe, receivedAtUs)) {
                    DecoderBacklog.Decision.DECODE -> Unit

                    DecoderBacklog.Decision.DROP -> return false

                    DecoderBacklog.Decision.DROP_AND_REQUEST_KEYFRAME -> {
                        requestKeyframe = true
                        return@withLock false
                    }
                }
                val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                if (queueLocked(src, length, ptsUs, flags)) {
                    if (!isConfig) rememberQueued(ptsUs, receivedAtUs)
                    true
                } else {
                    backlog.onSubmitFailed(isConfig)
                    requestKeyframe = true
                    false
                }
            }
        if (requestKeyframe) onKeyframeNeeded()
        return queued
    }

    /** Dequeues an input buffer, copies and queues. Returns false on stall or codec failure. */
    private fun queueLocked(src: ByteArray, length: Int, ptsUs: Long, flags: Int): Boolean {
        val mc = codec ?: return false
        var nanos = TimeUnit.MILLISECONDS.toNanos(INPUT_WAIT_MS)
        while (freeCount == 0 && nanos > 0 && !closed && codec === mc) nanos = inputAvailable.awaitNanos(nanos)
        if (freeCount == 0 || closed || codec !== mc) {
            Log.w(TAG, "no input buffer within $INPUT_WAIT_MS ms")
            return false
        }
        val index = freeInputs[freeHead]
        freeHead = (freeHead + 1) % MAX_INPUTS
        freeCount--
        return try {
            val buffer = mc.getInputBuffer(index) ?: return false
            if (length > buffer.capacity()) {
                Log.w(TAG, "access unit $length B exceeds input buffer ${buffer.capacity()} B")
                mc.queueInputBuffer(index, 0, 0, ptsUs, 0)
                return false
            }
            buffer.clear()
            buffer.put(src, 0, length)
            mc.queueInputBuffer(index, 0, length, ptsUs, flags)
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "queueInputBuffer failed: ${e.message}")
            false
        }
    }

    private fun cacheCsd(src: ByteArray, length: Int) {
        if (csd.size < length) csd = ByteArray(length) // rare: once per session/resolution
        System.arraycopy(src, 0, csd, 0, length)
        csdLength = length
        replayCsd = false // the codec gets this one directly
    }

    private fun replayCsdLocked(): Boolean {
        if (!queueLocked(csd, csdLength, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)) return false
        replayCsd = false
        backlog.onAccessUnit(isConfig = true, isKeyframe = false, nowUs = nowUs())
        return true
    }

    fun stats(): DecoderStats = lock.withLock {
        DecoderStats(decodedFrames, backlog.dropped, latency.meanUs(), latency.p95Us(), resets)
    }

    /** Runs on the codec thread. Under the lock, so no submit holds an index of the old codec. */
    private fun resetCodec(mc: MediaCodec) {
        val ok =
            lock.withLock {
                if (mc !== codec || closed) return
                resets++
                try {
                    mc.reset()
                    configureLocked(mc)
                    true
                } catch (e: Exception) {
                    codec = null
                    Log.w(TAG, "reset failed: ${e.message}")
                    false
                }
            }
        if (ok) onKeyframeNeeded() else onFatalError(IllegalStateException("decoder reset failed"))
    }

    private fun rememberQueued(ptsUs: Long, atUs: Long) {
        queuedPts[queuedNext] = ptsUs
        queuedAtUs[queuedNext] = atUs
        queuedNext = (queuedNext + 1) % PTS_SLOTS
    }

    private fun lookupQueuedAt(ptsUs: Long): Long {
        for (i in 0 until PTS_SLOTS) if (queuedPts[i] == ptsUs && queuedAtUs[i] > 0) return queuedAtUs[i]
        return 0
    }

    /** Releases the codec on its own thread and waits (bounded) so the surface is free afterwards. */
    override fun close() {
        val mc =
            lock.withLock {
                if (closed) return
                closed = true
                inputAvailable.signalAll()
                codec.also { codec = null }
            }
        val t = thread ?: return
        handler?.post {
            runCatching { mc?.stop() }
            runCatching { mc?.release() }
        }
        t.quitSafely()
        t.join(1_000)
    }

    private companion object {
        const val TAG = "Riri/Decoder"
        const val MAX_INPUTS = 64
        const val PTS_SLOTS = 32
        const val INPUT_WAIT_MS = 50L

        fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1_000
    }
}
