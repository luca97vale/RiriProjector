package dev.ririprojector.feature.receiver

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import dev.ririprojector.media.AndroidCodecs
import dev.ririprojector.media.AudioPlayer
import dev.ririprojector.media.AvSync
import dev.ririprojector.media.CaptureLatency
import dev.ririprojector.media.CodecCatalog
import dev.ririprojector.media.JitterBuffer
import dev.ririprojector.media.KeyframeRequestLimiter
import dev.ririprojector.media.VideoDecoder
import dev.ririprojector.media.VideoDelayController
import dev.ririprojector.media.VideoDelayLine
import dev.ririprojector.net.Connection
import dev.ririprojector.net.Handshake
import dev.ririprojector.net.HandshakeException
import dev.ririprojector.net.ReceiverEndpoint
import dev.ririprojector.net.ReceiverServer
import dev.ririprojector.net.nsd.NsdPublisher
import dev.ririprojector.protocol.ByeReason
import dev.ririprojector.protocol.ControlMessage
import dev.ririprojector.protocol.MessageType
import dev.ririprojector.protocol.ProtocolInfo
import dev.ririprojector.protocol.model.AudioCodec
import dev.ririprojector.protocol.model.AudioCodecCapability
import dev.ririprojector.protocol.model.AudioConfig
import dev.ririprojector.protocol.model.DisplayInfo
import dev.ririprojector.protocol.model.StreamParams
import dev.ririprojector.protocol.model.SyncMode
import java.io.Closeable
import java.io.IOException
import java.net.BindException
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the receiver screen shows. */
sealed interface ReceiverState {
    data class Waiting(val name: String, val address: String?, val port: Int) : ReceiverState

    data class Streaming(val params: StreamParams, val peer: String, val stats: ReceiverStats) : ReceiverState

    data class Failed(val message: String) : ReceiverState
}

/** Per-second metrics for the overlay (and STATS to the sender). */
data class ReceiverStats(
    val fps: Double = 0.0,
    val bitrateBps: Long = 0,
    val dropped: Long = 0,
    val decodeMeanUs: Long = 0,
    val decodeP95Us: Long = 0,
    val resets: Int = 0,
    val decoderName: String = "",
    val audioBufferedMs: Int = 0,
    val audioUnderruns: Int = 0,
    /** Audio minus video, positive = audio late; null until both play. */
    val avOffsetMs: Int? = null,
    /** Lip-sync video delay currently applied (0 in low-latency mode). */
    val videoDelayMs: Int = 0,
    /** Capture on the tablet to shown on this screen (includes the lip-sync delay); null until known. */
    val latency: CaptureLatency.Snapshot? = null,
)

/**
 * Owns the TCP server and the NSD registration while the receiver screen is started, and runs one
 * session at a time: HELLO → START → decoder → read loop (ADR-002, ADR-003).
 *
 * Threads per session: the session thread reads and feeds the decoder; the control thread is the
 * only writer after the handshake (PONG, STATS, REQUEST_KEYFRAME, BYE), so no other thread ever
 * blocks on the socket. Public methods are called from the main thread and never do network I/O.
 */
class ReceiverController(
    private val context: Context,
    private val deviceName: String,
    private val display: DisplayInfo,
    private val localAddress: () -> String?,
) {
    private val _state = MutableStateFlow<ReceiverState>(ReceiverState.Waiting(deviceName, null, 0))
    val state: StateFlow<ReceiverState> = _state.asStateFlow()

    /** Emits the stream size when a session starts, so the UI can size the surface. */
    var onStreamStarted: (StreamParams) -> Unit = {}

    private val catalog: CodecCatalog by lazy { AndroidCodecs.catalog() }
    private var server: ReceiverServer? = null
    private var nsd: Closeable? = null

    @Volatile private var session: Session? = null

    private val surfaceLock = ReentrantLock()
    private val surfaceReady = surfaceLock.newCondition()
    private var surface: Surface? = null

    private val main = Handler(Looper.getMainLooper())
    private var bindAttempts = 0
    private val retryStart = Runnable { start() }

    fun start() {
        if (server != null) return
        try {
            val s = ReceiverServer(port = ReceiverEndpoint.DEFAULT_PORT) { onConnection(it) }.start()
            server = s
            bindAttempts = 0
            nsd = NsdPublisher(context).publish(deviceName, s.boundPort)
            showWaiting()
        } catch (e: BindException) {
            // Another instance (or a previous session) may still be releasing the port.
            if (++bindAttempts <= MAX_BIND_ATTEMPTS) {
                Log.w(TAG, "port busy, retrying (${e.message})")
                main.postDelayed(retryStart, BIND_RETRY_MS)
            } else {
                Log.e(TAG, "cannot bind the server port", e)
                _state.value = ReceiverState.Failed(e.message ?: "port in use")
            }
        } catch (e: IOException) {
            Log.e(TAG, "cannot start server", e)
            _state.value = ReceiverState.Failed(e.message ?: "server error")
        }
    }

    fun stop() {
        main.removeCallbacks(retryStart)
        bindAttempts = 0
        runCatching { nsd?.close() }
        nsd = null
        session?.endAndAwait(ByeReason.NORMAL)
        server?.close()
        server = null
    }

    /**
     * Called from the SurfaceHolder callbacks. Losing the surface ends the current session and
     * waits (bounded) until its decoder is released, so the codec never renders into a dead surface.
     */
    fun setSurface(value: Surface?) {
        surfaceLock.withLock {
            surface = value
            if (value != null) surfaceReady.signalAll()
        }
        if (value == null) session?.endAndAwait(ByeReason.NORMAL)
    }

    private fun awaitSurface(timeoutMs: Long): Surface? = surfaceLock.withLock {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (surface == null && nanos > 0) nanos = surfaceReady.awaitNanos(nanos)
        surface
    }

    private fun showWaiting() {
        _state.value = ReceiverState.Waiting(deviceName, localAddress(), server?.boundPort ?: 0)
    }

    private fun onConnection(connection: Connection) {
        val s = Session(connection)
        session = s
        Thread({ s.run() }, "Riri/Session").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    private inner class Session(private val connection: Connection) {
        private val outbox = LinkedBlockingQueue<ControlMessage>(OUTBOX_CAPACITY)
        private val keyframes = KeyframeRequestLimiter()
        private val done = CountDownLatch(1)

        @Volatile private var decoder: VideoDecoder? = null

        private val avSync = AvSync()

        /** Capture→shown latency; fed by the codec thread, clock set by PINGs. */
        private val captureLatency = CaptureLatency()

        /** Lip-sync only (ADR-007): video waits here for its audio. Null in low-latency mode. */
        @Volatile private var delayLine: VideoDelayLine? = null

        /** Ticked by the control thread only. */
        private val delayController = VideoDelayController(maxDelayMs = MAX_VIDEO_DELAY_MS)

        @Volatile private var videoDelayUs = 0L

        /** Set on a new audio stream; the control thread resets the controller on its next tick. */
        @Volatile private var delayResetPending = false

        @Volatile private var jitter: JitterBuffer? = null

        @Volatile private var player: AudioPlayer? = null

        @Volatile private var params: StreamParams? = null

        @Volatile private var decoderName = ""

        @Volatile private var ended = false

        @Volatile private var writerStarted = false

        @Volatile private var bytesReceived = 0L

        fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            var writer: Thread? = null
            try {
                val p = Handshake.asReceiver(connection, hello())
                params = p
                Log.i(TAG, "session ${p.sessionId} from ${connection.remoteAddress}: ${p.video}")
                writer = startWriter()
                val target = awaitSurface(SURFACE_WAIT_MS)
                if (target == null || ended) throw IOException("no surface")
                onStreamStarted(p)
                val best = catalog.best(p.video.codec, encoder = false) ?: throw IOException("no decoder")
                decoderName = best.name
                if (Build.VERSION.SDK_INT >= 30) {
                    runCatching {
                        target.setFrameRate(p.video.fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                    }
                }
                val dec =
                    VideoDecoder(
                        codecName = best.name,
                        config = p.video,
                        surface = target,
                        lowLatencySupported = best.lowLatency,
                        onKeyframeNeeded = ::requestKeyframe,
                        onFatalError = { end(ByeReason.ERROR) },
                        onFrameRendered = { pts, shownAt ->
                            avSync.onVideoShown(pts, shownAt)
                            captureLatency.onShown(pts, shownAt)
                        },
                    ).start()
                decoder = dec
                if (p.syncMode == SyncMode.LIP_SYNC) {
                    delayLine =
                        VideoDelayLine(
                            capacityBytes = VideoDelayLine.capacityFor(p.video.bitrateBps, MAX_VIDEO_DELAY_MS),
                            sink = { src, off, len, pts, cfg, key -> dec.submit(src, off, len, pts, cfg, key) },
                            onOverflow = {
                                Log.w(TAG, "video delay line overflow: dropping to the next keyframe")
                                requestKeyframe()
                            },
                            onError = {
                                Log.w(TAG, "video delay line failed: ${it.message}")
                                end(ByeReason.ERROR)
                            },
                        ).start()
                }
                Log.i(TAG, "sync mode ${p.syncMode}")
                _state.value =
                    ReceiverState.Streaming(p, connection.remoteAddress, ReceiverStats(decoderName = best.name))
                readLoop()
            } catch (e: HandshakeException) {
                Log.w(TAG, "handshake failed: ${e.message}")
            } catch (e: Exception) {
                if (!ended) {
                    Log.w(TAG, "session failed: ${e.message}")
                    end(ByeReason.ERROR)
                }
            } finally {
                ended = true
                if (writer != null) {
                    writer.join(WRITER_DRAIN_MS) // lets a queued BYE go out
                    writer.interrupt()
                }
                connection.close()
                delayLine?.close() // before the decoder: it is the decoder's feeding thread
                delayLine = null
                decoder?.close()
                decoder = null
                player?.close()
                player = null
                jitter = null
                server?.sessionEnded(connection)
                if (session === this) session = null
                showWaiting()
                done.countDown()
            }
        }

        private fun readLoop() {
            val reader = connection.reader
            connection.setReadTimeout(READ_TIMEOUT_MS)
            while (!ended) {
                val pk =
                    try {
                        reader.read() ?: return
                    } catch (e: SocketTimeoutException) {
                        Log.w(TAG, "sender silent for $READ_TIMEOUT_MS ms: ending the session")
                        throw e
                    }
                bytesReceived += pk.payloadLength + HEADER_BYTES
                when (pk.type) {
                    MessageType.CONFIG, MessageType.FRAME -> {
                        val isConfig = pk.type == MessageType.CONFIG || pk.isCodecConfig
                        if (!isConfig) avSync.onVideoArrived(pk.ptsOrSeq, System.nanoTime() / 1000)
                        val line = delayLine
                        if (line != null) {
                            line.offer(pk.payload, pk.payloadLength, pk.ptsOrSeq, isConfig, pk.isKeyframe, videoDelayUs)
                        } else {
                            decoder?.submit(pk.payload, 0, pk.payloadLength, pk.ptsOrSeq, isConfig, pk.isKeyframe)
                        }
                    }

                    MessageType.AUDIO_CONFIG -> {
                        val setup = pk.decodeControl() as ControlMessage.AudioSetup
                        startAudio(setup.config)
                    }

                    MessageType.AUDIO_FRAME -> {
                        avSync.onAudioArrived(pk.ptsOrSeq, System.nanoTime() / 1000)
                        jitter?.push(pk.payload, 0, pk.payloadLength, pk.ptsOrSeq)
                    }

                    MessageType.PING -> {
                        val ping = pk.decodeControl() as ControlMessage.Ping
                        captureLatency.setClock(
                            ping.offsetUs.takeIf { it != ControlMessage.NO_OFFSET },
                            ping.rttUs,
                        )
                        val now = nowUs()
                        outbox.offer(ControlMessage.Pong(ping.senderTimeUs, receiverRxUs = now, receiverTxUs = now))
                    }

                    MessageType.BYE -> return

                    else -> Unit // unknown types are skipped
                }
            }
        }

        /** Session thread only. Audio failures leave video running (logged, shown as no audio). */
        private fun startAudio(config: AudioConfig) {
            player?.close()
            player = null
            jitter = null
            if (config.codec != AudioCodec.PCM_S16LE) {
                Log.w(TAG, "unsupported audio codec ${config.codec}")
                return
            }
            val buffer = JitterBuffer(config.sampleRateHz, config.channels)
            avSync.reset()
            delayResetPending = true
            try {
                player = AudioPlayer(config, buffer, avSync) { Log.w(TAG, "audio stopped: ${it.message}") }.start()
                jitter = buffer
            } catch (e: Exception) {
                Log.w(TAG, "cannot start audio: ${e.message}")
            }
        }

        /** Non-blocking; safe from the codec thread. */
        private fun requestKeyframe() {
            if (keyframes.tryAcquire(SystemClock.elapsedRealtime())) outbox.offer(ControlMessage.RequestKeyframe)
        }

        /** The only socket writer after the handshake; also produces STATS once per second. */
        private fun startWriter(): Thread = Thread({
            var nextStatsAt = SystemClock.elapsedRealtime() + 1_000
            var lastFrames = 0L
            var lastBytes = 0L
            var lastAt = SystemClock.elapsedRealtime()
            var ticks = 0
            try {
                while (true) {
                    val wait = (nextStatsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                    val msg = outbox.poll(wait, TimeUnit.MILLISECONDS)
                    if (msg != null) {
                        // NTP t3: stamped right before writing, so outbox queuing is excluded.
                        val out = if (msg is ControlMessage.Pong) msg.copy(receiverTxUs = nowUs()) else msg
                        connection.writer.writeControl(out)
                        connection.writer.flush()
                        if (msg is ControlMessage.Bye) break
                        continue
                    }
                    if (ended) break
                    val now = SystemClock.elapsedRealtime()
                    nextStatsAt = now + 1_000
                    val d = decoder?.stats() ?: continue
                    val secs = (now - lastAt).coerceAtLeast(1) / 1000.0
                    val bytes = bytesReceived
                    val jb = jitter
                    val sync = avSync.snapshot()
                    val offsetUs = sync.offsetUs
                    if (delayLine != null) {
                        if (delayResetPending) {
                            delayResetPending = false
                            delayController.reset()
                        }
                        videoDelayUs = delayController.update(sync, System.nanoTime() / 1000)
                    }
                    val stats =
                        ReceiverStats(
                            fps = (d.decodedFrames - lastFrames) / secs,
                            bitrateBps = ((bytes - lastBytes) * 8 / secs).toLong(),
                            dropped = d.droppedFrames,
                            decodeMeanUs = d.latencyMeanUs,
                            decodeP95Us = d.latencyP95Us,
                            resets = d.resets,
                            decoderName = decoderName,
                            audioBufferedMs = jb?.bufferedMs ?: 0,
                            audioUnderruns = jb?.underruns ?: 0,
                            avOffsetMs = offsetUs?.let { (it / 1000).toInt() },
                            videoDelayMs = (videoDelayUs / 1000).toInt(),
                            latency = captureLatency.snapshot(),
                        )
                    if (++ticks % DIAG_EVERY_TICKS == 0 && jb != null) {
                        Log.d(
                            TAG,
                            "av: ${avSync.describe()}; buffer ${jb.bufferedMs}/${jb.targetMs} ms, " +
                                "track ${player?.trackQueuedMs} ms, underruns ${jb.underruns}, " +
                                "dropped ${jb.droppedMs} ms; " +
                                "video delay ${videoDelayUs / 1000} ms (line drops ${delayLine?.dropped}), " +
                                "ts rejected ${player?.rejectedTimestamps}",
                        )
                    }
                    lastFrames = d.decodedFrames
                    lastBytes = bytes
                    lastAt = now
                    params?.let { _state.value = ReceiverState.Streaming(it, connection.remoteAddress, stats) }
                    connection.writer.writeControl(
                        ControlMessage.Stats(
                            decodedFpsX100 = (stats.fps * 100).toInt(),
                            droppedFrames = d.droppedFrames.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            decodeLatencyMeanUs = d.latencyMeanUs.toInt(),
                            decodeLatencyP95Us = d.latencyP95Us.toInt(),
                            audioUnderruns = stats.audioUnderruns,
                            avOffsetUs = offsetUs?.let { clampInt(it, Int.MIN_VALUE + 1L) }
                                ?: ControlMessage.UNKNOWN_SIGNED,
                            videoDelayUs = videoDelayUs.toInt(),
                            captureToShownUs = stats.latency?.let { clampInt(it.meanUs, 0) }
                                ?: ControlMessage.UNKNOWN,
                            captureToShownP95Us = stats.latency?.let { clampInt(it.p95Us, 0) }
                                ?: ControlMessage.UNKNOWN,
                        ),
                    )
                    connection.writer.flush()
                }
            } catch (_: InterruptedException) {
                // session ended
            } catch (e: IOException) {
                if (!ended) Log.w(TAG, "control write failed: ${e.message}")
            } finally {
                ended = true
                connection.close() // unblocks the reader
            }
        }, "Riri/Control").apply {
            isDaemon = true
            start()
            writerStarted = true
        }

        /** Ends the session from any thread without touching the socket's output. */
        fun end(reason: ByeReason) {
            if (ended) return
            ended = true
            if (writerStarted && reason != ByeReason.ERROR && outbox.offer(ControlMessage.Bye(reason))) return
            connection.close()
        }

        /** [end], then wait (bounded) for the decoder to be released. Main-thread safe. */
        fun endAndAwait(reason: ByeReason) {
            end(reason)
            if (!done.await(GRACE_MS, TimeUnit.MILLISECONDS)) {
                connection.close() // BYE could not be written in time; force the reader out
                done.await(GRACE_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun hello() = ControlMessage.Hello(
        protocolVersion = ProtocolInfo.PROTOCOL_VERSION,
        deviceName = deviceName,
        display = display,
        videoDecoders = catalog.capabilities(encoder = false),
        // PCM is always playable via AudioTrack.
        audioDecoders = listOf(AudioCodecCapability(AudioCodec.PCM_S16LE, 2, listOf(48_000))),
    )

    private companion object {
        const val TAG = "Riri/Receiver"
        const val SURFACE_WAIT_MS = 2_000L
        const val HEADER_BYTES = 16
        const val OUTBOX_CAPACITY = 32
        const val WRITER_DRAIN_MS = 300L
        const val GRACE_MS = 500L
        const val MAX_BIND_ATTEMPTS = 10
        const val BIND_RETRY_MS = 300L
        const val DIAG_EVERY_TICKS = 5
        const val MAX_VIDEO_DELAY_MS = 300

        /** The sender PINGs every second: 3 s of silence means it is gone (ADR-010). */
        const val READ_TIMEOUT_MS = 3_000

        /** Keeps [min] off the wire's sentinel values. */
        fun clampInt(value: Long, min: Long): Int = value.coerceIn(min, Int.MAX_VALUE.toLong()).toInt()

        /** `CLOCK_MONOTONIC` µs: the clock of video pts and of PING/PONG (protocol v3). */
        fun nowUs() = System.nanoTime() / 1_000
    }
}
