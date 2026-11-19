package dev.ririprojector.media

/**
 * Lip-sync delay storage (ADR-007): a FIFO of video access units, each held until its due time.
 * Pure logic, allocation-free after construction: one byte ring of [capacityBytes] plus primitive
 * descriptor arrays for at most [maxEntries] units. Each unit is stored contiguously so it can be
 * handed to the decoder as `(buffer, offset, length)`. Not thread-safe: [VideoDelayLine] locks.
 *
 * Scheduling: `due = max(arrival + delay, previous due)`, so order is kept and a shrinking delay
 * releases the backlog back-to-back instead of reordering it.
 *
 * Overflow (bytes or descriptors): the unit is dropped and so is every following non-keyframe
 * until a keyframe fits; [offer] returns [Offer.OVERFLOW] once per episode so the caller can ask
 * the sender for a keyframe. Codec config always gets a chance to queue (it precedes keyframes).
 */
class DelayRing(capacityBytes: Int, private val maxEntries: Int = 64) {
    enum class Offer { QUEUED, DROPPED, OVERFLOW }

    /** Backing storage; a unit's bytes are `buffer[headOffset, headOffset + headLength)`. */
    val buffer = ByteArray(capacityBytes)
    private val offsets = IntArray(maxEntries)
    private val lengths = IntArray(maxEntries)
    private val ptsUs = LongArray(maxEntries)
    private val dueUs = LongArray(maxEntries)
    private val flags = IntArray(maxEntries)
    private var head = 0
    private var count = 0
    private var lastDueUs = Long.MIN_VALUE
    private var dropping = false

    /** Units dropped because of overflow (diagnostics). */
    var dropped = 0L
        private set

    val size: Int get() = count
    val isEmpty: Boolean get() = count == 0

    val headDueUs: Long get() = check(count > 0).let { dueUs[head] }
    val headOffset: Int get() = check(count > 0).let { offsets[head] }
    val headLength: Int get() = check(count > 0).let { lengths[head] }
    val headPtsUs: Long get() = check(count > 0).let { ptsUs[head] }
    val headIsConfig: Boolean get() = check(count > 0).let { flags[head] and FLAG_CONFIG != 0 }
    val headIsKeyframe: Boolean get() = check(count > 0).let { flags[head] and FLAG_KEY != 0 }

    fun offer(
        src: ByteArray,
        offset: Int,
        length: Int,
        pts: Long,
        isConfig: Boolean,
        isKeyframe: Boolean,
        arrivalUs: Long,
        delayUs: Long,
    ): Offer {
        // Empty units carry nothing to decode, and a zero-length slot at the head's offset would
        // make a wrapped ring look unwrapped (place() would then overwrite the head).
        if (length <= 0) return Offer.DROPPED
        if (dropping && !isKeyframe && !isConfig) {
            dropped++
            return Offer.DROPPED
        }
        val at = if (count < maxEntries) place(length) else -1
        if (at < 0) {
            dropped++
            // Report again when a keyframe cannot fit either: the previous keyframe request may
            // have been rate-limited away, and recovery needs a fresh one.
            val report = !dropping || isKeyframe
            dropping = true
            return if (report) Offer.OVERFLOW else Offer.DROPPED
        }
        if (isKeyframe) dropping = false
        System.arraycopy(src, offset, buffer, at, length)
        val slot = (head + count) % maxEntries
        offsets[slot] = at
        lengths[slot] = length
        ptsUs[slot] = pts
        flags[slot] = (if (isConfig) FLAG_CONFIG else 0) or (if (isKeyframe) FLAG_KEY else 0)
        val due = maxOf(arrivalUs + delayUs, lastDueUs)
        dueUs[slot] = due
        lastDueUs = due
        count++
        return Offer.QUEUED
    }

    /** Frees the head unit once the decoder has copied it. */
    fun releaseHead() {
        check(count > 0)
        head = (head + 1) % maxEntries
        count--
    }

    fun clear() {
        head = 0
        count = 0
        lastDueUs = Long.MIN_VALUE
        dropping = false
    }

    /** Where a contiguous run of [length] bytes fits, or -1. */
    private fun place(length: Int): Int {
        if (length > buffer.size) return -1
        if (count == 0) return 0
        val readPos = offsets[head]
        val last = (head + count - 1) % maxEntries
        val tailEnd = offsets[last] + lengths[last]
        val wrapped = offsets[last] < readPos
        return when {
            !wrapped && buffer.size - tailEnd >= length -> tailEnd
            !wrapped && readPos >= length -> 0
            wrapped && readPos - tailEnd >= length -> tailEnd
            else -> -1
        }
    }

    private companion object {
        const val FLAG_CONFIG = 1
        const val FLAG_KEY = 2
    }
}
