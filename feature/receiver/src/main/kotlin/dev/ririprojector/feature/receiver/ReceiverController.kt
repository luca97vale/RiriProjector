package dev.ririprojector.feature.receiver

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import dev.ririprojector.media.AndroidCodecs
import dev.ririprojector.media.CodecCatalog
import dev.ririprojector.media.DecoderStats
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
 * session at a time on its own thread: HELLO → START → decoder → read loop (ADR-002, ADR-003).
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
        session?.end(ByeReason.NORMAL)
        server?.close()
        server = null
    }

    /** Called from the SurfaceHolder callbacks. Losing the surface ends the current session. */
    fun setSurface(value: Surface?) {
        surfaceLock.withLock {
            surface = value
            if (value != null) surfaceReady.signalAll()
        }
        if (value == null) session?.end(ByeReason.NORMAL)
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
        private val writeLock = ReentrantLock()
        private var decoder: VideoDecoder? = null

        @Volatile private var ended = false

        @Volatile private var bytesReceived = 0L

        fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            var statsThread: Thread? = null
            try {
                val params = Handshake.asReceiver(connection, hello())
                Log.i(TAG, "session ${params.sessionId} from ${connection.remoteAddress}: ${params.video}")
                val target = awaitSurface(SURFACE_WAIT_MS) ?: throw IOException("no surface")
                onStreamStarted(params)
                val best = catalog.best(params.video.codec, encoder = false) ?: throw IOException("no decoder")
                if (android.os.Build.VERSION.SDK_INT >= 30) {
                    runCatching {
                        target.setFrameRate(params.video.fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
                    }
                }
                decoder =
                    VideoDecoder(
                        codecName = best.name,
                        config = params.video,
                        surface = target,
                        lowLatencySupported = best.lowLatency,
                        onKeyframeNeeded = { send(ControlMessage.RequestKeyframe) },
                        onFatalError = { end(ByeReason.ERROR) },
                    ).start()
                _state.value =
                    ReceiverState.Streaming(params, connection.remoteAddress, ReceiverStats(decoderName = best.name))
                statsThread = startStatsLoop(params, best.name)
                readLoop()
            } catch (e: HandshakeException) {
                Log.w(TAG, "handshake failed: ${e.message}")
            } catch (e: Exception) {
                if (!ended) Log.w(TAG, "session ended: ${e.message}")
                if (!ended) runCatching { send(ControlMessage.Bye(ByeReason.ERROR)) }
            } finally {
                ended = true
                statsThread?.interrupt()
                decoder?.close()
                decoder = null
                server?.sessionEnded(connection)
                if (session === this) session = null
                showWaiting()
            }
        }

        private fun readLoop() {
            val reader = connection.reader
            while (!ended) {
                val p = reader.read() ?: return
                bytesReceived += p.payloadLength + HEADER_BYTES
                when (p.type) {
                    MessageType.CONFIG, MessageType.FRAME ->
                        decoder?.submit(
                            p.payload,
                            p.payloadLength,
                            p.ptsOrSeq,
                            p.isCodecConfig || p.type == MessageType.CONFIG,
                            p.isKeyframe,
                        )

                    MessageType.AUDIO_FRAME -> Unit

                    // M5

                    MessageType.PING -> {
                        val ping = p.decodeControl() as ControlMessage.Ping
                        send(ControlMessage.Pong(ping.senderTimeUs, nowUs()))
                    }

                    MessageType.BYE -> return

                    else -> Unit // AUDIO_SETUP, BITRATE_HINT, unknown types: ignore for now
                }
            }
        }

        private fun startStatsLoop(params: StreamParams, decoderName: String): Thread = Thread({
            var lastFrames = 0L
            var lastBytes = 0L
            var lastAt = SystemClock.elapsedRealtime()
            try {
                while (!ended) {
                    Thread.sleep(1_000)
                    val d: DecoderStats = decoder?.stats() ?: continue
                    val now = SystemClock.elapsedRealtime()
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
                    _state.value = ReceiverState.Streaming(params, connection.remoteAddress, stats)
                    send(
                        ControlMessage.Stats(
                            decodedFpsX100 = (stats.fps * 100).toInt(),
                            droppedFrames = d.droppedFrames.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                            decodeLatencyMeanUs = d.latencyMeanUs.toInt(),
                            decodeLatencyP95Us = d.latencyP95Us.toInt(),
                            audioUnderruns = 0,
                            avOffsetUs = 0,
                        ),
                    )
                }
            } catch (_: InterruptedException) {
                // session ended
            } catch (e: IOException) {
                end(ByeReason.ERROR)
            }
        }, "Riri/Stats").apply {
            isDaemon = true
            start()
        }

        fun send(message: ControlMessage) {
            if (connection.isClosed) return
            try {
                writeLock.withLock {
                    connection.writer.writeControl(message)
                    connection.writer.flush()
                }
            } catch (e: IOException) {
                Log.w(TAG, "send ${message.type} failed: ${e.message}")
                end(ByeReason.ERROR)
            }
        }

        /** Ends the session from any thread; the session thread cleans up. */
        fun end(reason: ByeReason) {
            if (ended) return
            ended = true
            if (reason != ByeReason.ERROR) runCatching { send(ControlMessage.Bye(reason)) }
            connection.close() // unblocks the reader
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

        fun nowUs() = SystemClock.elapsedRealtimeNanos() / 1_000
    }
}
