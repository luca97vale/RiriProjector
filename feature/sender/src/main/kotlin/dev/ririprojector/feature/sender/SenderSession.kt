package dev.ririprojector.feature.sender

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.ririprojector.media.AndroidCodecs
import dev.ririprojector.media.AudioCapture
import dev.ririprojector.media.VideoEncoder
import dev.ririprojector.net.ClockSync
import dev.ririprojector.net.Connection
import dev.ririprojector.net.Handshake
import dev.ririprojector.net.HandshakeException
import dev.ririprojector.net.ReceiverEndpoint
import dev.ririprojector.net.SendQueue
import dev.ririprojector.net.SenderClient
import dev.ririprojector.protocol.ByeReason
import dev.ririprojector.protocol.ControlMessage
import dev.ririprojector.protocol.MessageType
import dev.ririprojector.protocol.ProtocolException
import dev.ririprojector.protocol.model.AudioCodec
import dev.ririprojector.protocol.model.AudioCodecCapability
import dev.ririprojector.protocol.model.DisplayInfo
import dev.ririprojector.protocol.model.SenderCapabilities
import dev.ririprojector.protocol.model.StreamParams
import dev.ririprojector.protocol.model.StreamPreferences
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * One mirroring session (ADR-002, ADR-004): connect → HELLO/START → encoder → VirtualDisplay →
 * stream until stopped or failed. Threads: session (connect, then socket reader), writer (the only
 * socket writer: drains the [SendQueue], sends PING every second) and the encoder drain thread.
 * Everything is released in [run]'s `finally`, in reverse order of creation.
 */
class SenderSession(
    private val target: ReceiverEndpoint,
    private val preferences: StreamPreferences,
    private val projection: MediaProjection,
    private val source: DisplayInfo,
    private val densityDpi: Int,
    /** True when RECORD_AUDIO is granted and the platform supports playback capture (API 29+). */
    private val audioPermitted: Boolean,
    /** Blocking (session thread): what the user shared in the system dialog (ADR-009). */
    private val detectShare: () -> ShareScope,
    /** Shown when the receiver runs a different protocol version (localized by the caller). */
    private val versionMismatchText: String,
    private val onEnded: (message: String?) -> Unit,
) {
    @Volatile private var ended = false

    @Volatile private var connection: Connection? = null

    @Volatile private var encoder: VideoEncoder? = null

    @Volatile private var writerStarted = false

    @Volatile private var audio: AudioCapture? = null

    @Volatile private var audioFailed = false

    @Volatile private var endMessage: String? = null

    @Volatile private var contentHidden = false
    private val queue = SendQueue(onKeyframeNeeded = { encoder?.requestKeyframe() })
    private val clock = ClockSync()

    fun start() {
        Thread({ run() }, "Riri/SenderSession").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * Ends the session from any thread. Never blocks on I/O: a BYE is queued (best effort) and the
     * socket is force-closed after [BYE_GRACE_MS] even if the writer is stuck on a dead peer.
     * [message] is shown to the user (null for a normal stop).
     */
    fun stop(reason: ByeReason, message: String? = null) {
        if (ended) return
        endMessage = message
        ended = true
        if (writerStarted) queue.offerControl(ControlMessage.Bye(reason)) // the writer sends it, then closes
        Thread({
            try {
                if (writerStarted) Thread.sleep(BYE_GRACE_MS)
            } catch (_: InterruptedException) {
            }
            queue.close()
            connection?.close() // unblocks the reader and a writer stuck in write()
        }, "Riri/SenderStop").apply {
            isDaemon = true
            start()
        }
    }

    /** Single-app sharing: the shared app left/returned to the screen (shown with the next STATS). */
    fun onContentVisibilityChanged(visible: Boolean) {
        contentHidden = !visible
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        var virtualDisplay: VirtualDisplay? = null
        var writer: Thread? = null
        var failure: String? = null
        SenderStatus.set(SenderState.Connecting(target))
        try {
            val conn = SenderClient.connect(target)
            connection = conn
            if (ended) return
            val catalog = AndroidCodecs.catalog()
            val caps =
                SenderCapabilities(
                    source = source,
                    videoEncoders = catalog.capabilities(encoder = true),
                    audioEncoders = listOf(AudioCodecCapability(AudioCodec.PCM_S16LE, 2, listOf(48_000))),
                )
            val (hello, params) = Handshake.asSender(conn, caps, preferences, Random.nextLong() and Long.MAX_VALUE)
            Log.i(TAG, "streaming to ${hello.deviceName} (${target.host}): ${params.video}")
            val codec = catalog.best(params.video.codec, encoder = true) ?: throw IOException("no encoder")
            val enc =
                VideoEncoder(
                    codec = codec,
                    config = params.video,
                    sink = { data, pts, key, cfg -> queue.offerVideo(pts, key, cfg, data) },
                    onError = { stop(ByeReason.ERROR, "encoder error: ${it.message}") },
                )
            encoder = enc
            enc.start()
            writer = startWriter(conn)
            virtualDisplay =
                projection.createVirtualDisplay(
                    "RiriProjector",
                    params.video.width,
                    params.video.height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    enc.inputSurface,
                    null,
                    null,
                )
            val audioStatus = startAudio(params)
            SenderStatus.set(
                SenderState.Streaming(target, params, SenderStats(encoderName = codec.name, audio = audioStatus)),
            )
            // The receiver sends PONG and STATS every second: silence means it is gone.
            conn.setReadTimeout(RECEIVER_SILENCE_MS)
            readLoop(conn, params, codec.name, audioStatus)
        } catch (e: HandshakeException) {
            failure =
                if (e.cause is ProtocolException && e.bye == ByeReason.INCOMPATIBLE) versionMismatchText else e.message
        } catch (e: SocketTimeoutException) {
            if (!ended) failure = "receiver not responding"
        } catch (e: Exception) {
            if (!ended) failure = e.message ?: e.javaClass.simpleName
        } finally {
            failure = failure ?: endMessage
            if (failure != null) Log.w(TAG, "session ended: $failure")
            stop(if (failure == null) ByeReason.NORMAL else ByeReason.ERROR, failure)
            writer?.join(WRITER_DRAIN_MS)
            queue.close()
            writer?.interrupt()
            runCatching { audio?.close() }
            audio = null
            runCatching { virtualDisplay?.release() }
            encoder?.close()
            encoder = null
            connection?.close()
            SenderStatus.set(SenderState.Ended(failure))
            onEnded(failure)
        }
    }

    /**
     * Announces the audio format and starts playback capture (ADR-007). Audio failures never end
     * the session: mirroring continues video-only and the UI says so.
     */
    @SuppressLint("MissingPermission") // checked by the caller: audioPermitted
    private fun startAudio(params: StreamParams): AudioStatus {
        if (!preferences.audioEnabled) return AudioStatus.OFF
        if (!audioPermitted || Build.VERSION.SDK_INT < 29) {
            Log.w(TAG, "audio capture unavailable (permission or API level): video only")
            return AudioStatus.UNAVAILABLE
        }
        // One app shared: only that app may be heard; if it cannot be resolved, mirror video
        // only rather than leak every app's sound (ADR-009).
        val scope = detectShare()
        val audioUid = (scope as? ShareScope.SingleApp)?.uid
        if (scope is ShareScope.SingleApp && audioUid == null) {
            Log.i(TAG, "shared app could not be resolved: video only")
            return AudioStatus.NO_APP
        }
        return try {
            val capture =
                AudioCapture(
                    projection = projection,
                    config = params.audio,
                    sink = { data, pts -> queue.offerAudio(pts, data) },
                    onlyUid = audioUid,
                    onError = {
                        Log.w(TAG, "audio capture stopped: ${it.message}")
                        audioFailed = true // the next STATS update shows audio as off
                    },
                )
            audio = capture.start()
            queue.offerControl(ControlMessage.AudioSetup(params.audio))
            AudioStatus.ON
        } catch (e: Exception) {
            Log.w(TAG, "cannot start audio capture: ${e.message}")
            AudioStatus.UNAVAILABLE
        }
    }

    private fun readLoop(conn: Connection, params: StreamParams, encoderName: String, audioStatus: AudioStatus) {
        var lastFrames = 0L
        var lastBytes = 0L
        var lastAt = SystemClock.elapsedRealtime()
        var statsCount = 0
        while (!ended) {
            val p = conn.reader.read() ?: return
            when (p.type) {
                MessageType.REQUEST_KEYFRAME -> encoder?.requestKeyframe()

                MessageType.BITRATE_HINT -> {
                    val hint = p.decodeControl() as ControlMessage.BitrateHint
                    encoder?.setBitrate(hint.bitrateBps.coerceIn(MIN_BITRATE_BPS, MAX_BITRATE_BPS))
                }

                MessageType.PONG -> {
                    val pong = p.decodeControl() as ControlMessage.Pong
                    clock.addSample(pong.senderTimeUs, pong.receiverRxUs, pong.receiverTxUs, nowUs())
                }

                MessageType.STATS -> {
                    val s = p.decodeControl() as ControlMessage.Stats
                    val enc = encoder ?: continue
                    val now = SystemClock.elapsedRealtime()
                    val secs = (now - lastAt).coerceAtLeast(1) / 1000.0
                    val frames = enc.encodedFrames
                    val bytes = enc.encodedBytes
                    val stats =
                        SenderStats(
                            encodedFps = (frames - lastFrames) / secs,
                            bitrateBps = ((bytes - lastBytes) * 8 / secs).toLong(),
                            rttMs = clock.rttUs?.let { it / 1000.0 },
                            sendDropped = queue.droppedVideoFrames,
                            receiverFps = s.decodedFpsX100 / 100.0,
                            receiverDecodeMs = s.decodeLatencyMeanUs / 1000.0,
                            receiverDecodeP95Ms = s.decodeLatencyP95Us / 1000.0,
                            receiverDropped = s.droppedFrames,
                            encoderName = encoderName,
                            audio = if (audioFailed) AudioStatus.UNAVAILABLE else audioStatus,
                            receiverUnderruns = s.audioUnderruns,
                            avOffsetMs = s.avOffsetUs / 1000,
                            receiverVideoDelayMs = s.videoDelayUs / 1000,
                            contentHidden = contentHidden,
                        )
                    if (++statsCount % DIAG_EVERY_STATS == 0) {
                        audio?.let {
                            Log.d(
                                TAG,
                                "audio: capture age ${it.lastAgeUs / 1000} ms, peak ${it.takePeak()}, " +
                                    "queue drops ${queue.droppedAudioPackets}",
                            )
                        }
                    }
                    lastFrames = frames
                    lastBytes = bytes
                    lastAt = now
                    SenderStatus.set(SenderState.Streaming(target, params, stats))
                }

                MessageType.BYE -> return

                else -> Unit
            }
        }
    }

    /** The only socket writer: drains the queue by priority and sends a PING every second. */
    private fun startWriter(conn: Connection): Thread = Thread({
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        var nextPingAt = 0L
        try {
            while (true) {
                val now = SystemClock.elapsedRealtime()
                if (now >= nextPingAt && !ended) {
                    queue.offerControl(
                        ControlMessage.Ping(
                            nowUs(),
                            clock.offsetUs ?: ControlMessage.NO_OFFSET,
                            clock.rttUs?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0,
                        ),
                    )
                    nextPingAt = now + PING_INTERVAL_MS
                }
                val packet = queue.take(PING_INTERVAL_MS) ?: if (ended) break else continue
                try {
                    val control = packet.control
                    if (control != null) {
                        conn.writer.writeControl(control)
                    } else {
                        conn.writer.writeMedia(packet.type, packet.flags, packet.ptsUs, packet.payload())
                    }
                    conn.writer.flush()
                    if (control is ControlMessage.Bye) break
                } finally {
                    queue.recycle(packet)
                }
            }
        } catch (e: IOException) {
            if (!ended) Log.w(TAG, "write failed: ${e.message}")
        } finally {
            ended = true
            queue.close()
            conn.close() // unblocks the reader
        }
    }, "Riri/SenderWriter").apply {
        start()
        writerStarted = true
    }

    private companion object {
        const val TAG = "Riri/Sender"
        const val PING_INTERVAL_MS = 1_000L
        const val WRITER_DRAIN_MS = 300L
        const val BYE_GRACE_MS = 300L
        const val RECEIVER_SILENCE_MS = 3 * PING_INTERVAL_MS.toInt()
        const val MIN_BITRATE_BPS = 1_000_000
        const val MAX_BITRATE_BPS = 40_000_000
        const val DIAG_EVERY_STATS = 5

        /** `CLOCK_MONOTONIC` µs: the clock of video pts and of PING/PONG (protocol v3). */
        fun nowUs() = System.nanoTime() / 1_000
    }
}
