package dev.ririprojector.media

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.Surface
import dev.ririprojector.protocol.model.VideoConfig
import java.io.Closeable
import java.nio.ByteBuffer

/** Receives encoder output. [data] is only valid during the call: copy, do not keep. */
fun interface EncodedSink {
    fun onEncoded(data: ByteBuffer, ptsUs: Long, keyframe: Boolean, codecConfig: Boolean)
}

/**
 * Surface-input hardware encoder (ADR-004). The drain loop runs on its own urgent-display thread
 * and hands every output buffer to [sink] without copying; the buffer is released right after.
 * [requestKeyframe] and [setBitrate] may be called from any thread.
 */
class VideoEncoder(
    private val codec: CodecDescriptor,
    private val config: VideoConfig,
    private val sink: EncodedSink,
    private val onError: (Throwable) -> Unit = {},
) : Closeable {
    private lateinit var mc: MediaCodec
    private var drain: Thread? = null

    @Volatile private var running = false

    /** The surface to render into (e.g. by a VirtualDisplay); valid after [start]. */
    lateinit var inputSurface: Surface
        private set

    @Volatile var encodedFrames: Long = 0
        private set

    @Volatile var encodedBytes: Long = 0
        private set

    fun start(): VideoEncoder {
        mc = MediaCodec.createByCodecName(codec.name)
        try {
            try {
                mc.configure(format(lowLatencyKeys = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (e: Exception) {
                Log.w(TAG, "configure with low-latency keys failed (${e.message}); retrying plain")
                mc.reset()
                mc.configure(format(lowLatencyKeys = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }
            inputSurface = mc.createInputSurface()
            mc.start()
        } catch (e: Exception) {
            if (::inputSurface.isInitialized) inputSurface.release()
            mc.release()
            throw e
        }
        running = true
        drain =
            Thread({ drainLoop() }, "Riri/Encoder").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        Log.i(
            TAG,
            "started ${codec.name} ${config.width}x${config.height}@${config.fps} ${config.bitrateBps / 1000} kbps",
        )
        return this
    }

    private fun format(lowLatencyKeys: Boolean): MediaFormat =
        MediaFormat.createVideoFormat(config.codec.mime, config.width, config.height).also {
            EncoderSettings.apply(it, EncoderSettings.keys(config, codec, Build.VERSION.SDK_INT, lowLatencyKeys))
        }

    private fun drainLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        val info = MediaCodec.BufferInfo()
        try {
            while (running) {
                val index = mc.dequeueOutputBuffer(info, DRAIN_TIMEOUT_US)
                if (index < 0) continue // try again later / format changed
                val buffer = mc.getOutputBuffer(index)
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset).limit(info.offset + info.size)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                    sink.onEncoded(buffer, info.presentationTimeUs, key, config)
                    if (!config) encodedFrames++
                    encodedBytes += info.size
                }
                mc.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } catch (e: IllegalStateException) {
            if (running) {
                Log.w(TAG, "encoder failed: ${e.message}")
                onError(e)
            }
        }
    }

    fun requestKeyframe() {
        if (!running) return
        runCatching { mc.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
    }

    fun setBitrate(bps: Int) {
        if (!running) return
        runCatching { mc.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps) }) }
    }

    override fun close() {
        if (!running) return
        running = false
        drain?.join(500)
        runCatching { mc.stop() }
        runCatching { mc.release() }
        runCatching { inputSurface.release() }
    }

    private companion object {
        const val TAG = "Riri/Encoder"
        const val DRAIN_TIMEOUT_US = 10_000L
    }
}
