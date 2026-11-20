package dev.ririprojector.feature.sender

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import dev.ririprojector.media.AndroidCodecs
import dev.ririprojector.media.AudioCapture
import dev.ririprojector.media.VideoEncoder
import dev.ririprojector.net.BitratePolicy
import dev.ririprojector.net.ClockSync
import dev.ririprojector.net.CloseReason
import dev.ririprojector.net.Connection
import dev.ririprojector.net.Handshake
import dev.ririprojector.net.HandshakeException
import dev.ririprojector.net.ReceiverEndpoint
import dev.ririprojector.net.ReconnectPolicy
import dev.ririprojector.net.SendQueue
import dev.ririprojector.net.SenderClient
import dev.ririprojector.net.SessionEvent
import dev.ririprojector.net.SessionState
import dev.ririprojector.net.SessionStateMachine
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

/** How one [ProjectionHost.StreamLink] ended. */
internal data class LinkEnd(
    /** Shown to the user if the session ends here; null for a normal end. */
    val failure: String?,
    val cause: Throwable? = null,
    /** BYE reason sent by the receiver, if it said goodbye. */
    val peerBye: ByeReason? = null,
    /** True once media was flowing (the handshake succeeded). */
    val streamed: Boolean = false,
    val encoderFailed: Boolean = false,
)

/**
 * The sender side of one mirroring session (ADR-002, ADR-004, ADR-010), split in two lifetimes:
 *
 * - **ProjectionHost** (this class) lives as long as the [MediaProjection]: the single
 *   VirtualDisplay (Android 14+ allows one `createVirtualDisplay` per projection), audio capture,
 *   the share scope detected right after consent, and the [BitratePolicy].
 * - **[StreamLink]** is one TCP connection: socket, handshake, encoder, [SendQueue], writer,
 *   reader and [ClockSync]. Between links the VirtualDisplay is detached (`setSurface(null)`) and
 *   captured audio is dropped.
 *
 * Threads: the host thread (connect, handshake, then the link's reads), the link writer (only
 * socket writer), the encoder drain thread and the audio capture thread. The host thread
 * releases everything in `finally`, in reverse order of creation.
 */
class ProjectionHost(
    private val target: ReceiverEndpoint,
    private val preferences: StreamPreferences,
    private val projection: MediaProjection,
    private val source: DisplayInfo,
    private val densityDpi: Int,
    /** True when RECORD_AUDIO is granted and the platform supports playback capture (API 29+). */
    private val audioPermitted: Boolean,
    /** Blocking (host thread): what the user shared in the system dialog (ADR-009). */
    private val detectShare: () -> ShareScope,
    /** Shown when the receiver runs a different protocol version (localized by the caller). */
    private val versionMismatchText: String,
    private val onEnded: (message: String?) -> Unit,
) {
    @Volatile private var stopRequested = false

    @Volatile private var endMessage: String? = null

    @Volatile private var link: StreamLink? = null

    @Volatile private var contentHidden = false

    /** Where captured audio goes: the streaming link's queue, or nowhere between links. */
    @Volatile private var audioQueue: SendQueue? = null

    @Volatile private var audioFailed = false

    /** Created by the first successful handshake; its state survives links. */
    @Volatile private var bitrate: BitratePolicy? = null

    private val videoLock = Any()

    // Guarded by videoLock.
    private var virtualDisplay: VirtualDisplay? = null
    private var vdWidth = 0
    private var vdHeight = 0

    // Host thread only.
    private var audio: AudioCapture? = null
    private var audioStatus: AudioStatus? = null
    private val sessionId = Random.nextLong() and Long.MAX_VALUE
    private val catalog by lazy { AndroidCodecs.catalog() }

    fun start() {
        Thread({ run() }, "Riri/SenderHost").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * Ends the session from any thread. Never blocks on I/O: the current link queues a BYE and is
     * force-closed shortly after. [message] is shown to the user (null for a normal stop).
     */
    fun stop(reason: ByeReason, message: String? = null) {
        if (stopRequested) return
        endMessage = message
        stopRequested = true
        link?.stop(reason)
        synchronized(wake) { wake.notifyAll() } // ends a reconnect backoff early
    }

    private val wake = Object()

    /** Host thread: sleeps until [atMs] (elapsedRealtime) or a stop. */
    private fun waitUntil(atMs: Long) {
        synchronized(wake) {
            while (!stopRequested) {
                val left = atMs - SystemClock.elapsedRealtime()
                if (left <= 0) return
                try {
                    wake.wait(left)
                } catch (_: InterruptedException) {
                    return
                }
            }
        }
    }

    /** Single-app sharing: the shared app left/returned to the screen (shown with the next STATS). */
    fun onContentVisibilityChanged(visible: Boolean) {
        contentHidden = !visible
    }

    /**
     * Runs links until the user stops, a final cause ends the session or an outage lasts 30 s
     * (ADR-010). The first link must succeed: a receiver that never answered is not retried.
     */
    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        var failure: String? = null
        val machine = SessionStateMachine()
        machine.on(SessionEvent.Start(SystemClock.elapsedRealtime()))
        SenderStatus.set(SenderState.Connecting(target)) // later links keep showing "Reconnecting"
        var everStreamed = false
        var encoderFailures = 0
        try {
            while (!stopRequested) {
                val l = StreamLink()
                link = l
                if (stopRequested) break // stop() raced with the link creation
                val timeoutMs = if (everStreamed) RETRY_TIMEOUT_MS else SenderClient.DEFAULT_CONNECT_TIMEOUT_MS
                val end = l.run(timeoutMs, if (everStreamed) RETRY_TIMEOUT_MS else Handshake.DEFAULT_TIMEOUT_MS)
                link = null
                if (end.streamed) {
                    everStreamed = true
                    machine.on(SessionEvent.Connected)
                    machine.on(SessionEvent.HandshakeCompleted)
                }
                if (stopRequested) break
                if (end.encoderFailed) encoderFailures++
                val incompatible = (end.cause as? HandshakeException)?.bye == ByeReason.INCOMPATIBLE
                val decision = ReconnectPolicy.classify(end.peerBye, incompatible, end.encoderFailed, encoderFailures)
                val fatal = !everStreamed || decision == ReconnectPolicy.Decision.FINAL
                val state = machine.on(SessionEvent.Failed(SystemClock.elapsedRealtime(), fatal)).state
                if (state !is SessionState.Reconnecting) {
                    failure = end.failure
                    if (state == SessionState.Closed(CloseReason.GAVE_UP)) failure = failure ?: GAVE_UP_TEXT
                    break
                }
                Log.i(TAG, "link lost (${end.failure ?: end.peerBye}): reconnecting, attempt ${state.attempt}")
                SenderStatus.set(SenderState.Reconnecting(target, state.attempt))
                waitUntil(state.retryAtMs)
                machine.on(SessionEvent.Tick(SystemClock.elapsedRealtime()))
            }
        } catch (e: Exception) {
            failure = e.message ?: e.javaClass.simpleName
        } finally {
            link = null
            failure = failure ?: endMessage
            if (failure != null) Log.w(TAG, "session ended: $failure")
            audioQueue = null
            runCatching { audio?.close() }
            audio = null
            synchronized(videoLock) {
                runCatching { virtualDisplay?.release() }
                virtualDisplay = null
            }
            SenderStatus.set(SenderState.Ended(failure))
            onEnded(failure)
        }
    }

    /** Points the VirtualDisplay at [surface], creating it on the first link and resizing if needed. */
    private fun attachSurface(surface: Surface, width: Int, height: Int) = synchronized(videoLock) {
        val vd = virtualDisplay
        if (vd == null) {
            virtualDisplay =
                projection.createVirtualDisplay(
                    "RiriProjector",
                    width,
                    height,
                    densityDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface,
                    null,
                    null,
                )
        } else {
            if (width != vdWidth || height != vdHeight) vd.resize(width, height, densityDpi)
            vd.setSurface(surface)
        }
        vdWidth = width
        vdHeight = height
    }

    /** Stops composition into the encoder before it is closed; the display itself stays alive. */
    private fun detachSurface() = synchronized(videoLock) {
        runCatching { virtualDisplay?.setSurface(null) }
    }

    /**
     * Decides audio once per session and starts playback capture (ADR-007, ADR-009). Audio
     * failures never end the session: mirroring continues video-only and the UI says so.
     */
    @SuppressLint("MissingPermission") // checked by the caller: audioPermitted
    private fun startAudioOnce(params: StreamParams): AudioStatus {
        audioStatus?.let { return it }
        val status = startAudio(params)
        audioStatus = status
        return status
    }

    @SuppressLint("MissingPermission")
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
                    sink = { data, pts -> audioQueue?.offerAudio(pts, data) },
                    onlyUid = audioUid,
                    onError = {
                        Log.w(TAG, "audio capture stopped: ${it.message}")
                        audioFailed = true // the next STATS update shows audio as off
                    },
                )
            audio = capture.start()
            AudioStatus.ON
        } catch (e: Exception) {
            Log.w(TAG, "cannot start audio capture: ${e.message}")
            AudioStatus.UNAVAILABLE
        }
    }

    /** One TCP connection to the receiver. [run] blocks the host thread until the link ends. */
    private inner class StreamLink {
        @Volatile private var ended = false

        @Volatile private var connection: Connection? = null

        @Volatile private var encoder: VideoEncoder? = null

        @Volatile private var writerStarted = false

        @Volatile private var encoderError: Throwable? = null

        @Volatile private var peerBye: ByeReason? = null

        /** Cumulative receiver drops from the latest STATS, sampled by the bitrate tick. */
        @Volatile private var receiverDropped = 0

        private val clock = ClockSync()

        val queue =
            SendQueue(
                onKeyframeNeeded = {
                    // Lower the bitrate first, so the recovery keyframe is already smaller.
                    bitrate?.onVideoDrop(SystemClock.elapsedRealtime())?.let { applyBitrate(it, "send queue drop") }
                    encoder?.requestKeyframe()
                },
            )

        /**
         * Ends the link from any thread. Never blocks on I/O: a BYE is queued (best effort) and the
         * socket is force-closed after [BYE_GRACE_MS] even if the writer is stuck on a dead peer.
         */
        fun stop(reason: ByeReason) {
            if (ended) return
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

        fun run(connectTimeoutMs: Int, handshakeTimeoutMs: Int): LinkEnd {
            var writer: Thread? = null
            var failure: String? = null
            var cause: Throwable? = null
            var streamed = false
            try {
                val conn = SenderClient.connect(target, connectTimeoutMs)
                connection = conn
                if (ended) return LinkEnd(null)
                val caps =
                    SenderCapabilities(
                        source = source,
                        videoEncoders = catalog.capabilities(encoder = true),
                        audioEncoders = listOf(AudioCodecCapability(AudioCodec.PCM_S16LE, 2, listOf(48_000))),
                    )
                val (hello, params) = Handshake.asSender(conn, caps, preferences, sessionId, handshakeTimeoutMs)
                Log.i(TAG, "streaming to ${hello.deviceName} (${target.host}): ${params.video}")
                val codec = catalog.best(params.video.codec, encoder = true) ?: throw IOException("no encoder")
                val policy =
                    bitrate ?: BitratePolicy(params.video.bitrateBps, nowMs = SystemClock.elapsedRealtime()).also {
                        bitrate = it
                    }
                policy.onNewLink()
                val enc =
                    VideoEncoder(
                        codec = codec,
                        config = params.video.copy(bitrateBps = policy.currentBps),
                        sink = { data, pts, key, cfg -> queue.offerVideo(pts, key, cfg, data) },
                        onError = {
                            encoderError = it
                            stop(ByeReason.ERROR)
                        },
                    )
                encoder = enc
                enc.start()
                writer = startWriter(conn)
                attachSurface(enc.inputSurface, params.video.width, params.video.height)
                val status = startAudioOnce(params)
                if (status == AudioStatus.ON) {
                    queue.offerControl(ControlMessage.AudioSetup(params.audio))
                    audioQueue = queue
                }
                streamed = true
                val encoderName = "${codec.name} · ${enc.tier.label}"
                SenderStatus.set(
                    SenderState.Streaming(target, params, SenderStats(encoderName = encoderName, audio = status)),
                )
                // The receiver sends PONG and STATS every second: silence means it is gone.
                conn.setReadTimeout(RECEIVER_SILENCE_MS)
                readLoop(conn, params, encoderName, status)
            } catch (e: HandshakeException) {
                cause = e
                failure =
                    if (e.cause is ProtocolException && e.bye == ByeReason.INCOMPATIBLE) {
                        versionMismatchText
                    } else {
                        e.message
                    }
            } catch (e: SocketTimeoutException) {
                cause = e
                if (!ended) failure = "receiver not responding"
            } catch (e: Exception) {
                cause = e
                if (!ended) failure = e.message ?: e.javaClass.simpleName
            } finally {
                encoderError?.let { failure = failure ?: "encoder error: ${it.message}" }
                if (audioQueue === queue) audioQueue = null
                detachSurface() // before the encoder (and its input surface) goes away
                stop(if (failure == null) ByeReason.NORMAL else ByeReason.ERROR)
                writer?.join(WRITER_DRAIN_MS)
                queue.close()
                writer?.interrupt()
                encoder?.close()
                encoder = null
                connection?.close()
            }
            return LinkEnd(failure, cause, peerBye, streamed, encoderError != null)
        }

        private fun applyBitrate(bps: Int, why: String) {
            encoder?.setBitrate(bps)
            Log.i(TAG, "bitrate ${bps / 1000} kbps ($why)")
        }

        /** Once per second from the writer thread. */
        private fun tickBitrate(nowMs: Long) {
            val policy = bitrate ?: return
            val signals =
                BitratePolicy.Signals(
                    oldestQueuedVideoAgeUs = queue.oldestQueuedVideoAgeUs(nowUs()),
                    receiverDropped = receiverDropped,
                    lastRttUs = clock.lastRttUs,
                )
            policy.tick(nowMs, signals)?.let { applyBitrate(it, "tick $signals") }
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

                    // BITRATE_HINT is ignored since M6: the sender's BitratePolicy decides (ADR-004).

                    MessageType.PONG -> {
                        val pong = p.decodeControl() as ControlMessage.Pong
                        clock.addSample(pong.senderTimeUs, pong.receiverRxUs, pong.receiverTxUs, nowUs())
                    }

                    MessageType.STATS -> {
                        val s = p.decodeControl() as ControlMessage.Stats
                        receiverDropped = s.droppedFrames
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
                                avOffsetMs =
                                    if (s.avOffsetUs == ControlMessage.UNKNOWN_SIGNED) 0 else s.avOffsetUs / 1000,
                                receiverVideoDelayMs = s.videoDelayUs / 1000,
                                latencyMs = s.captureToShownUs.takeIf { it >= 0 }?.div(1000),
                                latencyP95Ms = s.captureToShownP95Us.takeIf { it >= 0 }?.div(1000),
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

                    MessageType.BYE -> {
                        peerBye = (p.decodeControl() as ControlMessage.Bye).reason
                        Log.i(TAG, "receiver said goodbye: $peerBye")
                        return
                    }

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
                        tickBitrate(now)
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
    }

    private companion object {
        const val TAG = "Riri/Sender"
        const val PING_INTERVAL_MS = 1_000L
        const val WRITER_DRAIN_MS = 300L
        const val BYE_GRACE_MS = 300L
        const val RECEIVER_SILENCE_MS = 3 * PING_INTERVAL_MS.toInt()
        const val DIAG_EVERY_STATS = 5

        /** Connect and handshake timeout when reconnecting: fail fast, retry soon. */
        const val RETRY_TIMEOUT_MS = 1_000
        const val GAVE_UP_TEXT = "the receiver did not come back within 30 s"

        /** `CLOCK_MONOTONIC` µs: the clock of video pts and of PING/PONG (protocol v3). */
        fun nowUs() = System.nanoTime() / 1_000
    }
}
