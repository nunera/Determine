package org.determine.core.ingest

import org.determine.core.wire.FramePkt
import org.determine.core.wire.MAX_CHUNK
import java.util.BitSet

/** A JPEG fully reassembled from FRAME chunks (board capture time, phone receive time of the first chunk). */
data class RawFrame(val frameId: Long, val tCapBoardUs: Long, val w: Int, val h: Int, val firstRxPhoneUs: Long, val jpeg: ByteArray)

/**
 * Reassembles FRAME chunks (PROTOCOL.md FRAME). Rules:
 *  - a frame not complete within [timeoutUs] (150 ms) of its first chunk is dropped;
 *  - chunks of a frame_id ≤ the newest completed/abandoned one are dropped (late, out of order) unless the
 *    id jumped back by more than [rebootJump], which means the camera rebooted and restarted counting;
 *  - at most [maxInFlight] partial frames and [maxFrameBytes] per frame are held (memory bound);
 *  - inconsistent chunks (bad offset/count/length) drop the whole frame.
 */
class FrameAssembler(
    private val timeoutUs: Long = 150_000,
    private val maxInFlight: Int = 3,
    private val maxFrameBytes: Int = 512 * 1024,
    private val rebootJump: Long = 1000,
) {
    private class Partial(val first: FramePkt, val rxUs: Long) {
        val buf = ByteArray(first.totalLen.toInt())
        val got = BitSet(first.chunkCount)
        var bytes = 0L
    }

    private val inFlight = LinkedHashMap<Long, Partial>()
    private var newestDoneId = -1L
    var dropped = 0L; private set
    var completed = 0L; private set

    /** Returns the frame when this chunk completes it. */
    fun onChunk(c: FramePkt, rxPhoneUs: Long): RawFrame? {
        expire(rxPhoneUs)
        if (newestDoneId >= 0 && c.frameId <= newestDoneId) {
            if (newestDoneId - c.frameId > rebootJump) { newestDoneId = -1; inFlight.clear() } else { return null }
        }
        if (c.totalLen <= 0 || c.totalLen > maxFrameBytes || c.chunkCount == 0 || c.chunkIdx >= c.chunkCount ||
            c.chunkCount.toLong() * MAX_CHUNK < c.totalLen
        ) { drop(c.frameId); return null }
        var p = inFlight[c.frameId]
        if (p == null) {
            if (inFlight.size >= maxInFlight) {
                val oldest = inFlight.keys.first()
                drop(oldest)
            }
            p = Partial(c, rxPhoneUs)
            inFlight[c.frameId] = p
        }
        val f = p.first
        if (c.totalLen != f.totalLen || c.chunkCount != f.chunkCount || c.tCapUs != f.tCapUs ||
            c.offset + c.data.size > f.totalLen
        ) { drop(c.frameId); return null }
        if (p.got[c.chunkIdx]) return null // duplicate
        p.got.set(c.chunkIdx)
        c.data.copyInto(p.buf, c.offset.toInt())
        p.bytes += c.data.size
        if (p.got.cardinality() < f.chunkCount) return null
        inFlight.remove(c.frameId)
        if (p.bytes != f.totalLen) { dropped++; markDone(c.frameId); return null }
        markDone(c.frameId)
        completed++
        // Older partials can no longer be useful: a newer frame is complete.
        inFlight.keys.filter { it < c.frameId }.forEach { drop(it) }
        return RawFrame(f.frameId, f.tCapUs, f.w, f.h, p.rxUs, p.buf)
    }

    /** Drops partial frames older than the timeout. Call periodically too. */
    fun expire(nowPhoneUs: Long) {
        inFlight.entries.filter { nowPhoneUs - it.value.rxUs > timeoutUs }.map { it.key }.forEach { drop(it) }
    }

    val inFlightCount: Int get() = inFlight.size

    private fun drop(id: Long) {
        inFlight.remove(id)
        dropped++
        markDone(id)
    }

    private fun markDone(id: Long) { if (id > newestDoneId) newestDoneId = id }
}

/**
 * Arch §4.3: discard FRAME/RANGE whose offset-corrected capture age or receive age exceeds the bound.
 * Without clock sync the capture age cannot be known, so nothing is fresh (unknown is never "ok").
 */
class FreshnessGate(private val maxAgeUs: Long = 500_000) {
    /** @param capturePhoneUs capture time converted to phone time, or null when unsynced. */
    fun isFresh(capturePhoneUs: Long?, rxPhoneUs: Long, nowPhoneUs: Long): Boolean {
        if (capturePhoneUs == null) return false
        return nowPhoneUs - capturePhoneUs <= maxAgeUs && nowPhoneUs - rxPhoneUs <= maxAgeUs
    }
}
