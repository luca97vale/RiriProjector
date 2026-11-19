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
 * unit, which is copied straight into a codec input buffer. Every output is rendered immediately.
 *
 * [DecoderBacklog] caps the frames in flight; frames are dropped until the next keyframe when the
 * decoder falls behind, and [onKeyframeNeeded] is invoked (from the calling or the codec thread) so
 * the session can send REQUEST_KEYFRAME. Recoverable codec errors reset the codec in place.
 */
class VideoDecoder(
    private val codecName: String,
    private val config: VideoConfig,
    private val surface: Surface,
    private val lowLatencySupported: Boolean,
    private val onKeyframeNeeded: () -> Unit,
    private val onFatalError: (Throwable) -> Unit = {},
) : Closeable {
    private val thread = HandlerThread("Riri/Decoder", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = ReentrantLock()
    private val inputAvailable = lock.newCondition()

    // Guarded by lock.
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
    private var generation = 0
    private var closed = false

    @Volatile private var codec: MediaCodec? = null

    private val callback =
        object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(mc: MediaCodec, index: Int) = lock.withLock {
                if (mc !== codec || freeCount == MAX_INPUTS) return
                freeInputs[(freeHead + freeCount) % MAX_INPUTS] = index
                freeCount++
                inputAvailable.signal()
            }

            override fun onOutputBufferAvailable(mc: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                if (mc !== codec) return
                try {
                    mc.releaseOutputBuffer(index, true)
                } catch (e: IllegalStateException) {
                    return // codec is being reset or released
                }
                val nowUs = nowUs()
                lock.withLock {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
                    backlog.onFrameOutput()
                    decodedFrames++
                    val queuedAt = lookupQueuedAt(info.presentationTimeUs)
                    if (queuedAt > 0) latency.add(nowUs - queuedAt)
                }
            }

            override fun onError(mc: MediaCodec, e: MediaCodec.CodecException) {
                if (mc !== codec) return
                Log.w(TAG, "codec error recoverable=${e.isRecoverable} transient=${e.isTransient}: ${e.diagnosticInfo}")
                if (e.isRecoverable || e.isTransient) {
                    resetCodec()
                } else {
                    onFatalError(e)
                }
            }

            override fun onOutputFormatChanged(mc: MediaCodec, format: MediaFormat) {
                Log.i(TAG, "output format $format")
            }
        }

    /** Creates and starts the codec. Throws if the codec cannot be configured at all. */
    fun start(): VideoDecoder {
        val mc = MediaCodec.createByCodecName(codecName)
        try {
            configure(mc)
        } catch (e: Exception) {
            mc.release()
            throw e
        }
        Log.i(TAG, "started $codecName ${config.width}x${config.height}@${config.fps}")
        return this
    }

    private fun configure(mc: MediaCodec) {
        lock.withLock {
            freeCount = 0
            freeHead = 0
            backlog.reset()
            generation++
        }
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
     * Waits at most [inputWaitMs] for a free input buffer; returns false if the unit was dropped.
     */
    fun submit(src: ByteArray, length: Int, ptsUs: Long, isConfig: Boolean, isKeyframe: Boolean): Boolean {
        val mc = codec ?: return false
        var requestKeyframe = false
        val index: Int
        val gen: Int
        lock.withLock {
            if (closed) return false
            when (backlog.onAccessUnit(isConfig, isKeyframe)) {
                DecoderBacklog.Decision.DECODE -> Unit
                DecoderBacklog.Decision.DROP -> return false
                DecoderBacklog.Decision.DROP_AND_REQUEST_KEYFRAME -> requestKeyframe = true
            }
            if (requestKeyframe) {
                index = -1
            } else {
                var nanos = TimeUnit.MILLISECONDS.toNanos(INPUT_WAIT_MS)
                while (freeCount == 0 && nanos > 0 && !closed) nanos = inputAvailable.awaitNanos(nanos)
                if (freeCount == 0) {
                    // Decoder stalled: treat like a backlog overflow.
                    backlog.onSubmitFailed(isConfig)
                    index = -1
                    requestKeyframe = true
                } else {
                    index = freeInputs[freeHead]
                    freeHead = (freeHead + 1) % MAX_INPUTS
                    freeCount--
                }
            }
            gen = generation
            if (index >= 0 && !isConfig) rememberQueued(ptsUs, nowUs())
        }
        if (requestKeyframe) {
            onKeyframeNeeded()
            return false
        }
        return try {
            val buffer = mc.getInputBuffer(index) ?: return false
            if (length > buffer.capacity()) {
                Log.w(TAG, "access unit $length B exceeds input buffer ${buffer.capacity()} B")
                mc.queueInputBuffer(index, 0, 0, ptsUs, 0)
                lock.withLock { backlog.onSubmitFailed(isConfig) }
                onKeyframeNeeded()
                return false
            }
            buffer.clear()
            buffer.put(src, 0, length)
            val flags = if (isConfig) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
            mc.queueInputBuffer(index, 0, length, ptsUs, flags)
            true
        } catch (e: IllegalStateException) {
            // Codec was reset concurrently (index belongs to an older generation).
            if (lock.withLock { gen == generation }) Log.w(TAG, "queueInputBuffer failed: ${e.message}")
            false
        }
    }

    fun stats(): DecoderStats = lock.withLock {
        DecoderStats(decodedFrames, backlog.dropped, latency.meanUs(), latency.p95Us(), resets)
    }

    private fun resetCodec() {
        val mc = codec ?: return
        lock.withLock { resets++ }
        try {
            mc.reset()
            configure(mc)
        } catch (e: Exception) {
            onFatalError(e)
            return
        }
        onKeyframeNeeded()
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

    override fun close() {
        val mc =
            lock.withLock {
                if (closed) return
                closed = true
                inputAvailable.signalAll()
                codec
            }
        codec = null
        // Stop/release on the codec thread so no callback runs concurrently with release.
        handler.post {
            runCatching { mc?.stop() }
            runCatching { mc?.release() }
        }
        thread.quitSafely()
        thread.join(1_000)
    }

    private companion object {
        const val TAG = "Riri/Decoder"
        const val MAX_INPUTS = 64
        const val PTS_SLOTS = 32
        const val INPUT_WAIT_MS = 50L

        fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1_000
    }
}
