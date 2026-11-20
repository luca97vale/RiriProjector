package dev.ririprojector.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.util.Log
import dev.ririprojector.protocol.model.AudioConfig
import java.io.Closeable

/**
 * Low-latency PCM playback (ADR-007). One urgent-audio thread pulls a packet at a time from
 * [buffer] (audio or silence, never blocking on the network) and writes it to a minimal
 * `AudioTrack`; the blocking write paces the loop. For each audible packet it estimates when the
 * first sample reaches the output (via `AudioTrack.getTimestamp`) and reports it to [sync].
 */
class AudioPlayer(
    private val config: AudioConfig,
    private val buffer: JitterBuffer,
    private val sync: AvSync,
    private val onError: (Throwable) -> Unit = {},
) : Closeable {
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    @Volatile private var running = false
    private val validator = TimestampValidator()

    /** Diagnostics: `getTimestamp` samples rejected by the [TimestampValidator]. */
    val rejectedTimestamps: Long get() = validator.rejected

    /** Diagnostics: audio queued in the track (written but not yet played), in ms. */
    @Volatile var trackQueuedMs: Int = 0
        private set

    fun start(): AudioPlayer {
        val channelMask = if (config.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val packetBytes = config.pcmBytesPerFrame
        val minBuffer = AudioTrack.getMinBufferSize(config.sampleRateHz, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        val t =
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build(),
                ).setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(config.sampleRateHz)
                        .setChannelMask(channelMask)
                        .build(),
                ).setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setBufferSizeInBytes(maxOf(minBuffer, packetBytes * 2))
                .build()
        try {
            check(t.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack not initialized" }
            // The builder rounds the buffer up (60 ms on a Pixel); queue only what pacing needs.
            t.setBufferSizeInFrames(packetBytes / (config.channels * 2) * TRACK_PACKETS)
            t.play()
        } catch (e: Exception) {
            t.release()
            throw e
        }
        track = t
        running = true
        thread =
            Thread({ loop(t, packetBytes) }, "Riri/AudioPlayer").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
        Log.i(
            TAG,
            "started: track buffer ${t.bufferSizeInFrames} of ${t.bufferCapacityInFrames} frames, min $minBuffer B",
        )
        return this
    }

    private fun loop(t: AudioTrack, packetBytes: Int) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val chunk = ByteArray(packetBytes)
        val ts = AudioTimestamp()
        val frameBytes = config.channels * 2
        val rate = config.sampleRateHz.toLong()
        var framesWritten = 0L
        try {
            while (running) {
                val pts = buffer.pull(chunk, packetBytes)
                val firstFrame = framesWritten
                var off = 0
                while (off < packetBytes && running) {
                    val n = t.write(chunk, off, packetBytes - off, AudioTrack.WRITE_BLOCKING)
                    if (n < 0) throw IllegalStateException("AudioTrack.write failed: $n")
                    off += n
                }
                framesWritten += packetBytes / frameBytes
                val head = t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
                trackQueuedMs = ((framesWritten - head) * 1000 / rate).toInt()
                if (pts == JitterBuffer.NO_PTS) continue
                // Only validated timestamps feed lip-sync: a guessed play time (no timestamp yet,
                // frozen or stale position) would steer the video delay with garbage.
                val got = t.getTimestamp(ts)
                if (!validator.accept(got, ts.framePosition, ts.nanoTime, System.nanoTime())) continue
                val playNs = ts.nanoTime + (firstFrame - ts.framePosition) * NANOS_PER_SECOND / rate
                sync.onAudioPlayed(pts, playNs / 1000)
            }
        } catch (e: Exception) {
            if (running) {
                Log.w(TAG, "playback failed: ${e.message}")
                onError(e)
            }
        }
    }

    override fun close() {
        if (!running) return
        running = false
        val t = track ?: return
        runCatching { t.pause() } // unblocks a pending write
        runCatching { t.flush() }
        thread?.join(500)
        runCatching { t.stop() }
        t.release()
        track = null
    }

    private companion object {
        const val TAG = "Riri/AudioPlayer"
        const val TRACK_PACKETS = 2
        const val NANOS_PER_SECOND = 1_000_000_000L
    }
}
