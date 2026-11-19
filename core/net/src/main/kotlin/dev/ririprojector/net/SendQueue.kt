package dev.ririprojector.net

import dev.ririprojector.protocol.ControlMessage
import dev.ririprojector.protocol.HeaderFlags
import dev.ririprojector.protocol.MessageType
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A packet waiting to be written. Pooled: return it with [SendQueue.recycle] after writing. */
class OutboundPacket internal constructor() {
    var type: MessageType = MessageType.FRAME
        internal set
    var flags: Int = 0
        internal set
    var ptsUs: Long = 0
        internal set
    var data: ByteArray = EMPTY
        internal set
    var length: Int = 0
        internal set
    var control: ControlMessage? = null
        internal set

    val isKeyframe: Boolean get() = flags and HeaderFlags.KEYFRAME != 0

    /** View over [data] for the writer; allocation-free after the first call per buffer. */
    internal var view: ByteBuffer = ByteBuffer.wrap(EMPTY)

    fun payload(): ByteBuffer {
        if (view.array() !== data) view = ByteBuffer.wrap(data)
        view.clear().limit(length)
        return view
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}

/**
 * Bounded, prioritized hand-off from capture threads to the single socket writer thread (ADR-002):
 * control > audio > video.
 *
 * Video is bounded by packet count and bytes. On overflow it drops queued non-key frames and,
 * because later P-frames reference the dropped ones, every further non-key frame until a keyframe
 * arrives; [onKeyframeNeeded] fires once per such episode so the encoder can produce one.
 * Audio is bounded by packet count and drops the oldest packet on overflow.
 *
 * Payloads are copied into pooled arrays so codec buffers can be released immediately.
 */
class SendQueue(
    private val maxVideoPackets: Int = 8,
    private val maxVideoBytes: Int = 4 * 1024 * 1024,
    private val maxAudioPackets: Int = 50,
    private val onKeyframeNeeded: () -> Unit = {},
) {
    private val lock = ReentrantLock()
    private val notEmpty = lock.newCondition()
    private val control = ArrayDeque<OutboundPacket>()
    private val audio = ArrayDeque<OutboundPacket>()
    private val video = ArrayDeque<OutboundPacket>()
    private val freePackets = ArrayDeque<OutboundPacket>()
    private val freeBuffers = ArrayDeque<ByteArray>()
    private var videoBytes = 0
    private var waitingForKeyframe = false
    private var closed = false

    var droppedVideoFrames: Long = 0
        private set
    var droppedAudioPackets: Long = 0
        private set

    val queuedVideoPackets: Int get() = lock.withLock { video.size }

    fun offerControl(message: ControlMessage) = lock.withLock {
        if (closed) return
        control.addLast(
            obtain().also {
                it.type = message.type
                it.control = message
                it.length = 0
            },
        )
        notEmpty.signal()
    }

    fun offerAudio(ptsUs: Long, src: ByteBuffer) = lock.withLock {
        if (closed) return
        if (audio.size >= maxAudioPackets) {
            recycleLocked(audio.removeFirst())
            droppedAudioPackets++
        }
        audio.addLast(copy(MessageType.AUDIO_FRAME, 0, ptsUs, src))
        notEmpty.signal()
    }

    /**
     * @param codecConfig true for CSD (always kept: the decoder cannot start without it)
     * @return false if the frame was dropped
     */
    fun offerVideo(ptsUs: Long, keyframe: Boolean, codecConfig: Boolean, src: ByteBuffer): Boolean = lock.withLock {
        if (closed) return false
        val size = src.remaining()
        if (codecConfig) {
            video.addLast(copy(MessageType.CONFIG, HeaderFlags.CODEC_CONFIG, ptsUs, src))
            videoBytes += size
            notEmpty.signal()
            return true
        }
        if (keyframe) {
            waitingForKeyframe = false
        } else if (waitingForKeyframe) {
            droppedVideoFrames++
            return false
        }
        if (video.size >= maxVideoPackets || videoBytes + size > maxVideoBytes) {
            dropQueuedVideo(keepConfig = true)
            if (!keyframe) {
                droppedVideoFrames++
                enterWaitForKeyframe()
                return false
            }
        }
        video.addLast(copy(MessageType.FRAME, if (keyframe) HeaderFlags.KEYFRAME else 0, ptsUs, src))
        videoBytes += size
        notEmpty.signal()
        true
    }

    /** Blocks up to [timeoutMs] for the next packet by priority; null on timeout or close. */
    fun take(timeoutMs: Long): OutboundPacket? = lock.withLock {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!closed && control.isEmpty() && audio.isEmpty() && video.isEmpty()) {
            if (nanos <= 0) return null
            nanos = notEmpty.awaitNanos(nanos)
        }
        if (closed) return null
        val packet = control.pollFirst() ?: audio.pollFirst() ?: video.pollFirst()!!
        if (packet.type == MessageType.FRAME || packet.type == MessageType.CONFIG) videoBytes -= packet.length
        packet
    }

    fun recycle(packet: OutboundPacket) = lock.withLock { recycleLocked(packet) }

    /** Drops pending video (e.g. after a reconnection) and waits for the next keyframe. */
    fun resetVideo() = lock.withLock {
        dropQueuedVideo(keepConfig = false)
        enterWaitForKeyframe()
    }

    fun close() = lock.withLock {
        closed = true
        notEmpty.signalAll()
    }

    private fun enterWaitForKeyframe() {
        if (!waitingForKeyframe) {
            waitingForKeyframe = true
            onKeyframeNeeded()
        }
    }

    private fun dropQueuedVideo(keepConfig: Boolean) {
        val it = video.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (keepConfig && p.type == MessageType.CONFIG) continue
            it.remove()
            videoBytes -= p.length
            if (p.type == MessageType.FRAME) droppedVideoFrames++
            recycleLocked(p)
        }
    }

    private fun copy(type: MessageType, flags: Int, ptsUs: Long, src: ByteBuffer): OutboundPacket {
        val length = src.remaining()
        val p = obtain()
        p.type = type
        p.flags = flags
        p.ptsUs = ptsUs
        p.control = null
        p.data = buffer(length)
        val position = src.position()
        src.get(p.data, 0, length)
        src.position(position)
        p.length = length
        return p
    }

    private fun obtain(): OutboundPacket = freePackets.pollFirst() ?: OutboundPacket()

    private fun buffer(minSize: Int): ByteArray {
        val it = freeBuffers.iterator()
        while (it.hasNext()) {
            val b = it.next()
            if (b.size >= minSize) {
                it.remove()
                return b
            }
        }
        // Round up so buffers are reusable across slightly larger frames.
        return ByteArray(maxOf(minSize + minSize / 4, 4 * 1024))
    }

    private fun recycleLocked(p: OutboundPacket) {
        p.control = null
        if (p.data.isNotEmpty() && freeBuffers.size < MAX_POOLED_BUFFERS) freeBuffers.addLast(p.data)
        if (freePackets.size < MAX_POOLED_BUFFERS * 2) freePackets.addLast(p)
    }

    private companion object {
        const val MAX_POOLED_BUFFERS = 32
    }
}
