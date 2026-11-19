package dev.ririprojector.feature.receiver

import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import dev.ririprojector.media.AndroidCodecs
import dev.ririprojector.media.CodecCatalog
import dev.ririprojector.media.KeyframeRequestLimiter
import dev.ririprojector.media.VideoDecoder
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
import dev.ririprojector.protocol.model.DisplayInfo
import dev.ririprojector.protocol.model.StreamParams
import java.io.Closeable
import java.io.IOException
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

    fun start() {
        if (server != null) return
        try {
            val s = ReceiverServer(port = ReceiverEndpoint.DEFAULT_PORT) { onConnection(it) }.start()
            server = s
            nsd = NsdPublisher(context).publish(deviceName, s.boundPort)
            showWaiting()
        } catch (e: IOException) {
            Log.e(TAG, "cannot start server", e)
            _state.value = ReceiverState.Failed(e.message ?: "server error")
        }
    }

    fun stop() {
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
                decoder =
                    VideoDecoder(
                        codecName = best.name,
                        config = p.video,
                        surface = target,
                        lowLatencySupported = best.lowLatency,
                        onKeyframeNeeded = ::requestKeyframe,
                        onFatalError = { end(ByeReason.ERROR) },
                    ).start()
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
                decoder?.close()
                decoder = null
                server?.sessionEnded(connection)
                if (session === this) session = null
                showWaiting()
                done.countDown()
            }
        }

        private fun readLoop() {
            val reader = connection.reader
            while (!ended) {
                val pk = reader.read() ?: return
                bytesReceived += pk.payloadLength + HEADER_BYTES
                when (pk.type) {
                    MessageType.CONFIG, MessageType.FRAME -> {
                        val isConfig = pk.type == MessageType.CONFIG || pk.isCodecConfig
                        decoder?.submit(pk.payload, pk.payloadLength, pk.ptsOrSeq, isConfig, pk.isKeyframe)
                    }

                    MessageType.AUDIO_FRAME -> Unit

                    // M5

                    MessageType.PING -> {
                        val ping = pk.decodeControl() as ControlMessage.Ping
                        outbox.offer(ControlMessage.Pong(ping.senderTimeUs, nowUs()))
                    }

                    MessageType.BYE -> return

                    else -> Unit // AUDIO_SETUP, BITRATE_HINT, unknown types: ignore for now
                }
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
            try {
                while (true) {
                    val wait = (nextStatsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                    val msg = outbox.poll(wait, TimeUnit.MILLISECONDS)
                    if (msg != null) {
                        connection.writer.writeControl(msg)
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
                    val stats =
                        ReceiverStats(
                            fps = (d.decodedFrames - lastFrames) / secs,
                            bitrateBps = ((bytes - lastBytes) * 8 / secs).toLong(),
                            dropped = d.droppedFrames,
                            decodeMeanUs = d.latencyMeanUs,
                            decodeP95Us = d.latencyP95Us,
                            resets = d.resets,
                            decoderName = decoderName,
                        )
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
                            audioUnderruns = 0,
                            avOffsetUs = 0,
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
        // PCM is always playable via AudioTrack; playback lands in M5.
        audioDecoders = listOf(AudioCodecCapability(AudioCodec.PCM_S16LE, 2, listOf(48_000))),
    )

    private companion object {
        const val TAG = "Riri/Receiver"
        const val SURFACE_WAIT_MS = 2_000L
        const val HEADER_BYTES = 16
        const val OUTBOX_CAPACITY = 32
        const val WRITER_DRAIN_MS = 300L
        const val GRACE_MS = 500L

        fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1_000
    }
}
