package dev.ririprojector.feature.sender

import android.annotation.SuppressLint
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import dev.ririprojector.media.AndroidCodecs
import dev.ririprojector.media.AudioCapture
import dev.ririprojector.media.CodecDescriptor
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
import dev.ririprojector.net.devlog.DevLog
import dev.ririprojector.net.p2p.P2pPolicy
import dev.ririprojector.protocol.ByeReason
import dev.ririprojector.protocol.CodecNegotiator
import dev.ririprojector.protocol.ControlMessage
import dev.ririprojector.protocol.MessageType
import dev.ririprojector.protocol.ProtocolException
import dev.ririprojector.protocol.model.AudioCodec
import dev.ririprojector.protocol.model.AudioCodecCapability
import dev.ririprojector.protocol.model.AudioConfig
import dev.ririprojector.protocol.model.DisplayInfo
import dev.ririprojector.protocol.model.SenderCapabilities
import dev.ririprojector.protocol.model.StreamParams
import dev.ririprojector.protocol.model.StreamPreferences
import dev.ririprojector.protocol.model.VideoConfig
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
    /** True once the handshake succeeded and the encoder started. */
    val streamed: Boolean = false,
    val encoderFailed: Boolean = false,
    /**
     * True once the receiver reported decoded frames: only a healthy link restarts the 30 s outage
     * clock, so a receiver that fails right after START cannot keep the session retrying forever.
     */
    val healthy: Boolean = false,
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
 * socket writer, PINGs and samples the bitrate policy every second), HostCtl (resize debounce,
 * encoder swaps and bitrate changes, so `MediaCodec.setParameters` never runs on the writer), the
 * encoder drain thread and the audio capture thread. `videoLock` serializes everything that
 * touches the VirtualDisplay and the link's encoder: link start, link teardown and reconfigure.
 * The host thread releases everything in `finally`, in reverse order of creation.
 */
internal class ProjectionHost(
    private val target: ReceiverEndpoint,
    private val preferences: StreamPreferences,
    private val projection: MediaProjection,
    initialSource: DisplayInfo,
    private val densityDpi: Int,
    /** True when RECORD_AUDIO is granted and the platform supports playback capture (API 29+). */
    private val audioPermitted: Boolean,
    /** Blocking (host thread): what the user shared in the system dialog (ADR-009). */
    private val detectShare: () -> ShareScope,
    /** Shown when the receiver runs a different protocol version (localized by the caller). */
    private val versionMismatchText: String,
    private val onEnded: (message: String?) -> Unit,
    /** How long one outage may last before giving up: 30 s over the router, 1.5 s over Wi-Fi Direct. */
    private val outageLimitMs: Long = P2pPolicy.ROUTER_OUTAGE_LIMIT_MS,
    /**
     * How long the first link keeps retrying a failed connect. Zero over the router (a receiver that
     * does not answer is reported at once); over Wi-Fi Direct the receiver appears in the group before
     * its own network is up, and the first connect can time out.
     */
    private val firstConnectWindowMs: Long = 0,
) {
    /** [target] with the name the receiver gave in its HELLO, once a link has one. */
    @Volatile private var shownTarget: ReceiverEndpoint = target

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

    /** Format the running capture was started with; every link announces this one. */
    @Volatile private var audioConfig: AudioConfig? = null
    private val sessionId = Random.nextLong() and Long.MAX_VALUE
    private val catalog by lazy { AndroidCodecs.catalog() }

    /** Size of what is captured now (rotation, one-app resize); negotiated from by each link. */
    @Volatile private var currentSource: DisplayInfo = initialSource

    /** HostCtl: debounces size changes and runs encoder swaps off the callers' threads. */
    private val ctlThread = HandlerThread("Riri/HostCtl", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
    private val ctl = Handler(ctlThread.looper)
    private val applyResize = Runnable { link?.reconfigure(currentSource) }

    fun start() {
        Thread({ run() }, "Riri/SenderHost").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /**
     * The captured content changed size (rotation, or the shared app's window resized). Any thread.
     * Debounced: a rotation reports several sizes in a row, and each change costs a keyframe.
     */
    fun onSourceResized(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val old = currentSource
        if (old.width == width && old.height == height) return // e.g. the first callback after start
        currentSource = old.copy(width = width, height = height)
        Log.i(TAG, "source resized ${old.width}x${old.height} -> ${width}x$height")
        ctl.removeCallbacks(applyResize)
        ctl.postDelayed(applyResize, RESIZE_DEBOUNCE_MS)
    }

    private fun capabilities(source: DisplayInfo) = SenderCapabilities(
        source = source,
        videoEncoders = catalog.capabilities(encoder = true),
        audioEncoders = listOf(AudioCodecCapability(AudioCodec.PCM_S16LE, 2, listOf(48_000))),
    )

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
                    stopRequested = true // nothing interrupts this thread but a shutdown
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
        val machine = SessionStateMachine(giveUpAfterMs = outageLimitMs)
        machine.on(SessionEvent.Start(SystemClock.elapsedRealtime()))
        SenderStatus.set(SenderState.Connecting(target)) // later links keep showing "Reconnecting"
        var everStarted = false
        var encoderFailures = 0
        try {
            while (!stopRequested) {
                val l = StreamLink()
                link = l
                if (stopRequested) break // stop() raced with the link creation
                val end =
                    if (everStarted) {
                        l.run(RETRY_CONNECT_TIMEOUT_MS, RETRY_HANDSHAKE_TIMEOUT_MS)
                    } else {
                        l.run(
                            SenderClient.DEFAULT_CONNECT_TIMEOUT_MS,
                            Handshake.DEFAULT_TIMEOUT_MS,
                            firstConnectWindowMs,
                        )
                    }
                link = null
                // A link that got through the handshake is worth retrying even if it dropped before
                // the first decoded frame; only a healthy one restarts the 30 s outage window.
                if (end.streamed) everStarted = true
                if (end.healthy) {
                    machine.on(SessionEvent.Connected)
                    machine.on(SessionEvent.HandshakeCompleted)
                }
                if (stopRequested) break
                if (end.encoderFailed) encoderFailures++
                val incompatible = (end.cause as? HandshakeException)?.bye == ByeReason.INCOMPATIBLE
                val decision = ReconnectPolicy.classify(end.peerBye, incompatible, end.encoderFailed, encoderFailures)
                val fatal = !everStarted || decision == ReconnectPolicy.Decision.FINAL
                val state = machine.on(SessionEvent.Failed(SystemClock.elapsedRealtime(), fatal)).state
                if (state !is SessionState.Reconnecting) {
                    failure = end.failure
                    if (state == SessionState.Closed(CloseReason.GAVE_UP)) failure = failure ?: GAVE_UP_TEXT
                    break
                }
                Log.i(TAG, "link lost (${end.failure ?: end.peerBye}): reconnecting, attempt ${state.attempt}")
                DevLog.warn(DEV_SOURCE) {
                    "Link lost (${end.failure ?: end.peerBye ?: "closed"}): reconnecting, attempt ${state.attempt}"
                }
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
            val reason = failure
            if (reason != null) {
                DevLog.error(DEV_SOURCE) { "Session ended: $reason" }
            } else {
                DevLog.info(DEV_SOURCE) { "Session ended normally" }
            }
            audioQueue = null
            runCatching { audio?.close() }
            audio = null
            synchronized(videoLock) {
                runCatching { virtualDisplay?.release() }
                virtualDisplay = null
            }
            ctl.removeCallbacksAndMessages(null)
            ctlThread.quitSafely()
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
            audioConfig = params.audio
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

        /** The receiver reported decoded frames (see [LinkEnd.healthy]). */
        @Volatile private var healthy = false

        /** Cumulative receiver drops from the latest STATS, sampled by the bitrate tick. */
        @Volatile private var receiverDropped = 0

        // Diagnostics (writer thread, logged and reset with the audio line): oldest audio at send
        // time (now − pts, includes the capture age), longest single write + flush and its size.
        @Volatile private var maxAudioSendAgeUs = 0L

        @Volatile private var maxWriteUs = 0L

        @Volatile private var maxWriteBytes = 0

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

        /** Host thread: connects, retrying failed attempts until [windowMs] has passed or the link ended. */
        private fun connectWithin(timeoutMs: Int, windowMs: Long): Connection {
            val deadline = SystemClock.elapsedRealtime() + windowMs
            while (true) {
                try {
                    return SenderClient.connect(target, timeoutMs)
                } catch (e: IOException) {
                    if (ended || SystemClock.elapsedRealtime() + CONNECT_RETRY_PAUSE_MS >= deadline) throw e
                    Log.i(TAG, "connect failed (${e.message}): retrying")
                    DevLog.warn(DEV_SOURCE) { "TCP connect failed (${e.message}): retrying" }
                    Thread.sleep(CONNECT_RETRY_PAUSE_MS)
                }
            }
        }

        fun run(connectTimeoutMs: Int, handshakeTimeoutMs: Int, connectWindowMs: Long = 0): LinkEnd {
            var writer: Thread? = null
            var failure: String? = null
            var cause: Throwable? = null
            var streamed = false
            try {
                val linkStartAt = SystemClock.elapsedRealtime()
                DevLog.info(DEV_SOURCE) { "Connecting to ${target.host}:${target.port} (timeout $connectTimeoutMs ms)" }
                val conn = connectWithin(connectTimeoutMs, connectWindowMs)
                connection = conn
                DevLog.info(DEV_SOURCE) { "TCP connected in ${SystemClock.elapsedRealtime() - linkStartAt} ms" }
                if (ended) return LinkEnd(null)
                val negotiatedSource = currentSource
                val (hello, params) =
                    Handshake.asSender(
                        conn,
                        capabilities(negotiatedSource),
                        preferences,
                        sessionId,
                        handshakeTimeoutMs,
                        catalog.encoderPredicate(),
                    )
                Log.i(TAG, "connected and handshaken in ${SystemClock.elapsedRealtime() - linkStartAt} ms")
                DevLog.info(DEV_SOURCE) {
                    "Handshake OK with ${hello.deviceName} in ${SystemClock.elapsedRealtime() - linkStartAt} ms"
                }
                DevLog.info(DEV_SOURCE) {
                    val v = params.video
                    "Negotiated ${v.codec} ${v.width}x${v.height} @${v.fps} fps, ${v.bitrateBps / 1000} kbps, " +
                        "sync ${params.syncMode}"
                }
                Log.i(TAG, "streaming to ${hello.deviceName} (${target.host}): ${params.video}")
                // Show who actually answered: over Wi-Fi Direct the tablet streams to whichever device
                // joined its group, so a wrong receiver must be visible (ADR-011).
                shownTarget = target.copy(name = hello.deviceName.ifBlank { target.name })
                val codec = catalog.best(params.video.codec, encoder = true) ?: throw IOException("no encoder")
                val policy =
                    bitrate ?: BitratePolicy(params.video.bitrateBps, nowMs = SystemClock.elapsedRealtime()).also {
                        bitrate = it
                    }
                policy.onNewLink()
                policy.refit(params.video.bitrateBps)
                writer = startWriter(conn)
                synchronized(videoLock) {
                    val enc =
                        try {
                            newEncoder(codec, params.video.copy(bitrateBps = policy.currentBps))
                        } catch (e: Exception) {
                            encoderError = e // counts toward the encoder retry limit (ADR-010)
                            throw e
                        }
                    attachSurface(enc.inputSurface, params.video.width, params.video.height)
                    // Published only now: reconfigure() treats them as "the encoder is running".
                    this.hello = hello
                    this.codec = codec
                    this.params = params
                }
                val status = startAudioOnce(params)
                if (status == AudioStatus.ON) {
                    // The capture keeps the first link's format; announce that one.
                    queue.offerControl(ControlMessage.AudioSetup(audioConfig ?: params.audio))
                    audioQueue = queue
                }
                streamed = true
                DevLog.info(DEV_SOURCE) { "Encoder $encoderName started" }
                DevLog.info(DEV_SOURCE) { "Audio: ${status.name.lowercase()}" }
                SenderStatus.set(
                    SenderState.Streaming(shownTarget, params, SenderStats(encoderName = encoderName, audio = status)),
                )
                // Rotated while connecting: the size negotiated above is already stale.
                if (currentSource != negotiatedSource) ctl.post(applyResize)
                // The receiver sends PONG and STATS every second: silence means it is gone.
                conn.setReadTimeout(RECEIVER_SILENCE_MS)
                readLoop(conn, status)
            } catch (e: HandshakeException) {
                cause = e
                failure =
                    if (e.cause is ProtocolException && e.bye == ByeReason.INCOMPATIBLE) {
                        versionMismatchText
                    } else {
                        e.message
                    }
                DevLog.warn(DEV_SOURCE) { "Handshake failed: ${e.message}" }
            } catch (e: SocketTimeoutException) {
                cause = e
                if (!ended) failure = "receiver not responding"
                if (!ended) DevLog.warn(DEV_SOURCE) { "Timed out (${e.message}): receiver not responding" }
            } catch (e: Exception) {
                cause = e
                if (!ended) failure = e.message ?: e.javaClass.simpleName
                if (!ended) DevLog.warn(DEV_SOURCE) { "Link failed: ${e.message ?: e.javaClass.simpleName}" }
            } finally {
                encoderError?.let { failure = failure ?: "encoder error: ${it.message}" }
                if (audioQueue === queue) audioQueue = null
                stop(if (failure == null) ByeReason.NORMAL else ByeReason.ERROR)
                synchronized(videoLock) {
                    detachSurface() // before the encoder (and its input surface) goes away
                    encoder?.close()
                    encoder = null
                }
                writer?.join(WRITER_DRAIN_MS)
                queue.close()
                writer?.interrupt()
                connection?.close()
            }
            return LinkEnd(failure, cause, peerBye, streamed, encoderError != null, healthy)
        }

        // Set after the handshake; read by reconfigure (HostCtl) under videoLock.
        @Volatile private var hello: ControlMessage.Hello? = null

        @Volatile private var codec: CodecDescriptor? = null

        @Volatile private var params: StreamParams? = null

        @Volatile private var encoderName = ""

        private var generation = 0

        /** Caller holds videoLock. Creates and starts the encoder feeding this link's queue. */
        private fun newEncoder(codec: CodecDescriptor, config: VideoConfig): VideoEncoder {
            val enc =
                VideoEncoder(
                    codec = codec,
                    config = config,
                    sink = { data, pts, key, cfg -> queue.offerVideo(pts, key, cfg, data) },
                    onError = {
                        encoderError = it
                        stop(ByeReason.ERROR)
                    },
                )
            encoder = enc
            enc.start()
            encoderName = "${codec.name} · ${enc.tier.label}"
            // A bitrate tick may have moved the policy while the encoder was being configured.
            bitrate?.currentBps?.takeIf { it != config.bitrateBps }?.let { enc.setBitrate(it) }
            return enc
        }

        /**
         * Rebuilds the encoder for a new source size (HostCtl thread). Ordered with video: frames
         * of the old size already queued go first, then VIDEO_RECONFIG, then CONFIG and a keyframe
         * of the new size (ADR-010). Android 14+ keeps one VirtualDisplay, so it is resized.
         */
        fun reconfigure(source: DisplayInfo) = synchronized(videoLock) {
            if (ended) return
            val p = params ?: return // handshake still running: run() re-checks the size after it
            val h = hello ?: return
            val c = codec ?: return
            val video = CodecNegotiator.refit(p.video, capabilities(source), h, preferences, catalog.encoderPredicate())
            if (video == null) {
                Log.w(TAG, "no usable size for ${source.width}x${source.height}: keeping ${p.video}")
                return
            }
            if (video.width == p.video.width && video.height == p.video.height) return
            val policy = bitrate ?: return
            val startBps = policy.refit(video.bitrateBps)
            detachSurface()
            encoder?.close()
            encoder = null
            // The new encoder starts with CONFIG + IDR; the flush is not a congestion drop.
            queue.resetVideo(requestKeyframe = false, countDropped = false)
            generation = (generation + 1) and 0xFFFF
            queue.offerVideoControl(ControlMessage.VideoReconfig(generation, video))
            params = p.copy(video = video)
            Log.i(TAG, "reconfig #$generation: ${p.video.width}x${p.video.height} -> $video at ${startBps / 1000} kbps")
            try {
                val enc = newEncoder(c, video.copy(bitrateBps = startBps))
                attachSurface(enc.inputSurface, video.width, video.height)
                // Belt and braces: an encoder whose first IDR is not flagged as a keyframe would
                // otherwise leave the queue dropping frames until the next periodic IDR (10 s).
                enc.requestKeyframe()
            } catch (e: Exception) {
                Log.w(TAG, "encoder rebuild failed: ${e.message}")
                encoderError = e
                stop(ByeReason.ERROR)
            }
        }

        private fun applyBitrate(bps: Int, why: String) {
            encoder?.setBitrate(bps)
            Log.i(TAG, "bitrate ${bps / 1000} kbps ($why)")
        }

        /**
         * Once per second from the writer thread, which only samples: `MediaCodec.setParameters`
         * runs on HostCtl so it never delays a socket write.
         */
        private fun tickBitrate(nowMs: Long) {
            val policy = bitrate ?: return
            val signals =
                BitratePolicy.Signals(
                    oldestQueuedVideoAgeUs = queue.oldestQueuedVideoAgeUs(nowUs()),
                    receiverDropped = receiverDropped,
                    lastRttUs = clock.lastRttUs,
                )
            policy.tick(nowMs, signals)?.let { bps -> ctl.post { applyBitrate(bps, "tick $signals") } }
        }

        private fun readLoop(conn: Connection, audioStatus: AudioStatus) {
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
                        if (!healthy && s.decodedFpsX100 > 0) {
                            DevLog.info(DEV_SOURCE) { "Receiver is showing frames (${s.decodedFpsX100 / 100} fps)" }
                        }
                        if (s.decodedFpsX100 > 0) healthy = true
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
                                        "queue drops ${queue.droppedAudioPackets}; ${it.takeTiming()}; " +
                                        "send age max ${maxAudioSendAgeUs / 1000} ms, " +
                                        "write max ${maxWriteUs / 1000} ms (${maxWriteBytes / 1024} KiB)",
                                )
                                maxAudioSendAgeUs = 0
                                maxWriteUs = 0
                            }
                        }
                        lastFrames = frames
                        lastBytes = bytes
                        lastAt = now
                        params?.let { SenderStatus.set(SenderState.Streaming(shownTarget, it, stats)) }
                    }

                    MessageType.BYE -> {
                        peerBye = (p.decodeControl() as ControlMessage.Bye).reason
                        Log.i(TAG, "receiver said goodbye: $peerBye")
                        DevLog.warn(DEV_SOURCE) { "Receiver said goodbye: $peerBye" }
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
                        val startUs = nowUs()
                        if (packet.type == MessageType.AUDIO_FRAME && startUs - packet.ptsUs > maxAudioSendAgeUs) {
                            maxAudioSendAgeUs = startUs - packet.ptsUs
                        }
                        if (control != null) {
                            conn.writer.writeControl(control)
                        } else {
                            conn.writer.writeMedia(packet.type, packet.flags, packet.ptsUs, packet.payload())
                        }
                        conn.writer.flush()
                        val writeUs = nowUs() - startUs
                        if (writeUs > maxWriteUs) {
                            maxWriteUs = writeUs
                            maxWriteBytes = packet.length
                        }
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
        const val DEV_SOURCE = "Sender"
        const val PING_INTERVAL_MS = 1_000L
        const val WRITER_DRAIN_MS = 300L
        const val BYE_GRACE_MS = 300L
        const val RECEIVER_SILENCE_MS = 3 * PING_INTERVAL_MS.toInt()
        const val DIAG_EVERY_STATS = 5

        /**
         * Reconnecting: fail fast on connect. The receiver now handshakes before waiting for a preempted
         * session's decoder, so that wait counts against [RECEIVER_SILENCE_MS], not this timeout.
         */
        const val RETRY_CONNECT_TIMEOUT_MS = 1_000
        const val CONNECT_RETRY_PAUSE_MS = 300L
        const val RETRY_HANDSHAKE_TIMEOUT_MS = 2_000
        const val RESIZE_DEBOUNCE_MS = 300L
        const val GAVE_UP_TEXT = "the receiver did not come back within 30 s"

        /** `CLOCK_MONOTONIC` µs: the clock of video pts and of PING/PONG (protocol v3). */
        fun nowUs() = System.nanoTime() / 1_000
    }
}
