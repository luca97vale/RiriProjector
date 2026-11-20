package dev.ririprojector.media

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.projection.MediaProjection
import android.os.Process
import android.util.Log
import dev.ririprojector.protocol.model.AudioConfig
import java.io.Closeable
import java.nio.ByteBuffer

/** Receives captured PCM. [data] is only valid during the call: copy, do not keep. */
fun interface AudioSink {
    fun onAudio(data: ByteBuffer, ptsUs: Long)
}

/**
 * Captures what other apps play (ADR-007) from the session's [MediaProjection] as PCM s16 in
 * packets of [AudioConfig.frameDurationUs]. Pts are `CLOCK_MONOTONIC` µs, the video timebase,
 * derived from `AudioRecord.getTimestamp` when available. Apps that opt out of playback capture
 * are simply not heard. With [onlyUid] only that app's audio is captured (single-app sharing,
 * ADR-009): playback capture is device-wide otherwise, whatever the projection shows.
 */
@TargetApi(29) // callers check API 29+
class AudioCapture(
    private val projection: MediaProjection,
    private val config: AudioConfig,
    private val sink: AudioSink,
    private val onlyUid: Int? = null,
    private val onError: (Throwable) -> Unit = {},
) : Closeable {
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    @Volatile var capturedPackets: Long = 0
        private set

    @Volatile private var peak = 0

    /** Diagnostics: highest absolute s16 sample since the last call (0 = silence), then resets. */
    fun takePeak(): Int = peak.also { peak = 0 }

    // Diagnostics, read and reset by [takeTiming]: largest interval between two packets read, longest
    // hand-off to the sink, and audio missing between packets (pts jumps: AudioRecord overruns).
    @Volatile private var maxReadGapUs = 0L

    @Volatile private var maxSinkUs = 0L

    @Volatile private var lostUs = 0L

    /** Diagnostics: "read gap max X ms, sink max Y ms, lost Z ms" since the last call, then resets. */
    fun takeTiming(): String =
        "read gap max ${maxReadGapUs / 1000} ms, sink max ${maxSinkUs / 1000} ms, lost ${lostUs / 1000} ms"
            .also {
                maxReadGapUs = 0
                maxSinkUs = 0
                lostUs = 0
            }

    /** Diagnostics: age of the last packet (now minus its pts) when handed to the sink, in µs. */
    @Volatile var lastAgeUs: Long = 0
        private set

    /** The caller must hold RECORD_AUDIO. */
    @SuppressLint("MissingPermission")
    fun start(): AudioCapture {
        val channelMask = if (config.channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val packetBytes = config.pcmBytesPerFrame
        val minBuffer = AudioRecord.getMinBufferSize(config.sampleRateHz, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        // Rules of different kinds are ANDed: (media | game | unknown usage) and, if set, that uid.
        val capture =
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .apply { onlyUid?.let { addMatchingUid(it) } }
                .build()
        val rec =
            AudioRecord.Builder()
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(config.sampleRateHz)
                        .setChannelMask(channelMask)
                        .build(),
                ).setBufferSizeInBytes(maxOf(minBuffer, packetBytes * BUFFER_PACKETS))
                .setAudioPlaybackCaptureConfig(capture)
                .build()
        try {
            check(rec.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord not initialized" }
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            throw e
        }
        record = rec
        running = true
        thread =
            Thread({ loop(rec, packetBytes) }, "Riri/AudioCapture").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        Log.i(
            TAG,
            "started ${config.sampleRateHz} Hz x${config.channels}, $packetBytes B packets, min buffer $minBuffer B",
        )
        return this
    }

    private fun loop(rec: AudioRecord, packetBytes: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val bytes = ByteArray(packetBytes)
        val buffer = ByteBuffer.wrap(bytes)
        val ts = AudioTimestamp()
        val frameBytes = config.channels * 2
        val packetFrames = packetBytes / frameBytes
        var framesRead = 0L
        var lastReadAtUs = 0L
        var lastPtsUs = 0L
        try {
            while (running) {
                var filled = 0
                while (filled < packetBytes && running) {
                    val n = rec.read(bytes, filled, packetBytes - filled, AudioRecord.READ_BLOCKING)
                    if (n < 0) throw IllegalStateException("AudioRecord.read failed: $n")
                    filled += n
                }
                if (!running) break
                val firstFrame = framesRead
                framesRead += packetFrames
                val ptsUs =
                    if (rec.getTimestamp(ts, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                        (ts.nanoTime + (firstFrame - ts.framePosition) * NANOS_PER_SECOND / config.sampleRateHz) / 1000
                    } else {
                        System.nanoTime() / 1000 - config.frameDurationUs
                    }
                val readAtUs = System.nanoTime() / 1000
                lastAgeUs = readAtUs - ptsUs
                if (lastReadAtUs != 0L && readAtUs - lastReadAtUs > maxReadGapUs) maxReadGapUs = readAtUs - lastReadAtUs
                lastReadAtUs = readAtUs
                val hole = ptsUs - lastPtsUs - config.frameDurationUs
                if (lastPtsUs != 0L && hole > HOLE_TOLERANCE_US) lostUs += hole
                lastPtsUs = ptsUs
                peak = maxOf(peak, peakOf(bytes, packetBytes))
                buffer.position(0).limit(packetBytes)
                sink.onAudio(buffer, ptsUs)
                val sinkUs = System.nanoTime() / 1000 - readAtUs
                if (sinkUs > maxSinkUs) maxSinkUs = sinkUs
                capturedPackets++
            }
        } catch (e: Exception) {
            if (running) {
                Log.w(TAG, "capture failed: ${e.message}")
                onError(e)
            }
        }
    }

    private fun peakOf(pcm: ByteArray, length: Int): Int {
        var max = 0
        var i = 0
        while (i + 1 < length) {
            val s = (pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8) // little-endian s16
            val a = if (s < 0) -s else s
            if (a > max) max = a
            i += 2
        }
        return max
    }

    override fun close() {
        if (!running) return
        running = false
        val rec = record ?: return
        runCatching { rec.stop() } // unblocks a pending read
        thread?.join(500)
        rec.release()
        record = null
    }

    private companion object {
        const val TAG = "Riri/AudioCapture"
        const val BUFFER_PACKETS = 4
        const val HOLE_TOLERANCE_US = 2_000L
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
