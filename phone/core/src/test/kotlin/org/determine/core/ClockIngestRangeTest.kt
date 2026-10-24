package org.determine.core

import org.determine.core.clock.ClockSync
import org.determine.core.ingest.FrameAssembler
import org.determine.core.ingest.FreshnessGate
import org.determine.core.range.RangeCorridor
import org.determine.core.range.RegionMasks
import org.determine.core.range.RegionRange
import org.determine.core.wire.FramePkt
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClockSyncTest {
    @Test
    fun `offset and rtt follow the NTP formulas`() {
        val c = ClockSync()
        c.onPingSent(1_000_000)
        // board = phone + 5 s; 10 ms each way; board holds 1 ms.
        val s = c.onPong(t1 = 1_000_000, rx = 6_010_000, tx = 6_011_000, t4 = 1_021_000)!!
        assertEquals(5_000_000, s.offsetUs)
        assertEquals(20_000, s.rttUs)
        assertEquals(6_000_000L, c.toBoard(1_000_000))
        assertEquals(1_000_000L, c.toPhone(6_000_000))
        assertTrue(c.synced(1_021_000))
    }

    @Test
    fun `asymmetric delay biases offset by half the asymmetry, min rtt sample wins`() {
        val c = ClockSync()
        for (t in listOf(0L, 100_000L, 200_000L)) c.onPingSent(t)
        // queued sample: 80 ms outbound, 2 ms back → offset biased +39 ms, rtt 82 ms
        c.onPong(0, 5_080_000, 5_080_000, 82_000)
        assertEquals(5_039_000L, c.offsetUs)
        // clean sample: 2 ms each way
        c.onPong(100_000, 5_102_000, 5_102_000, 104_000)
        assertEquals(5_000_000L, c.offsetUs)
        assertEquals(4_000L, c.rttUs)
        // another queued sample later does not displace the min-RTT one
        c.onPong(200_000, 5_250_000, 5_250_000, 252_000)
        assertEquals(5_000_000L, c.offsetUs)
    }

    @Test
    fun `outliers are rejected`() {
        val c = ClockSync(maxRttUs = 200_000)
        assertNull(c.onPong(0, 10, 10, 100)) // no ping sent yet
        c.onPingSent(1_000)
        assertNull(c.onPong(2_000, 10, 10, 3_000))       // echo of a time we never sent
        assertNull(c.onPong(1_000, 10, 10, 500))         // receive before send
        assertNull(c.onPong(1_000, 10, 5, 2_000))        // tx before rx
        assertNull(c.onPong(1_000, 10, 10, 1_000 + 200_001)) // rtt too large
        assertFalse(c.synced(2_000))
        assertEquals(5L, c.rejected)
        assertNotNull(c.onPong(1_000, 10, 10, 1_000 + 200_000))
    }

    @Test
    fun `window slides and staleness unsyncs`() {
        val c = ClockSync(windowSize = 2, staleAfterUs = 1_000_000)
        for (t in 0L..2L) c.onPingSent(t * 100_000)
        c.onPong(0, 1_000, 1_000, 2_000)                 // rtt 2 ms, offset -0
        c.onPong(100_000, 200_000, 200_000, 140_000)     // rtt 40 ms
        c.onPong(200_000, 300_000, 300_000, 240_000)     // rtt 40 ms — first sample now out of window
        assertEquals(40_000L, c.rttUs)
        assertTrue(c.synced(1_240_000))
        assertFalse(c.synced(1_240_001))
        c.reset()
        assertNull(c.toBoard(0))
    }
}

class FrameAssemblerTest {
    private fun chunks(id: Long, data: ByteArray, cap: Long = 1_000, size: Int = 1400): List<FramePkt> {
        val n = (data.size + size - 1) / size
        return (0 until n).map { i ->
            val off = i * size
            FramePkt(id, cap, 320, 240, data.size.toLong(), off.toLong(), i, n, data.copyOfRange(off, minOf(off + size, data.size)))
        }
    }

    private val jpeg = ByteArray(3000) { (it * 7).toByte() }

    @Test
    fun `reassembles out-of-order chunks and ignores duplicates`() {
        val a = FrameAssembler()
        val c = chunks(1, jpeg)
        assertNull(a.onChunk(c[2], 0))
        assertNull(a.onChunk(c[0], 1))
        assertNull(a.onChunk(c[0], 2))
        val f = a.onChunk(c[1], 3)!!
        assertArrayEquals(jpeg, f.jpeg)
        assertEquals(0L, f.firstRxPhoneUs)
        assertEquals(0, a.inFlightCount)
    }

    @Test
    fun `incomplete frame is dropped exactly after 150 ms`() {
        val a = FrameAssembler()
        val c = chunks(1, jpeg)
        a.onChunk(c[0], 0); a.onChunk(c[1], 0)
        a.expire(150_000)
        assertEquals(1, a.inFlightCount) // at the bound: still allowed
        assertNull(a.onChunk(c[2], 150_001)) // late chunk: frame expired, and id 1 is now old
        assertEquals(0, a.inFlightCount)
    }

    @Test
    fun `old frame ids are dropped after a newer frame completes`() {
        val a = FrameAssembler()
        val old = chunks(4, jpeg)
        a.onChunk(old[0], 0)
        chunks(5, jpeg).forEach { a.onChunk(it, 10) }
        assertEquals(0, a.inFlightCount) // partial 4 discarded
        assertNull(a.onChunk(old[1], 20)); assertNull(a.onChunk(old[2], 20))
        assertEquals(0, a.inFlightCount)
    }

    @Test
    fun `camera reboot restarts frame ids`() {
        val a = FrameAssembler(rebootJump = 1000)
        chunks(5000, jpeg).forEach { a.onChunk(it, 0) }
        var got: Any? = null
        chunks(0, jpeg).forEach { got = a.onChunk(it, 10) ?: got }
        assertNotNull(got)
    }

    @Test
    fun `memory bound and inconsistent chunks`() {
        val a = FrameAssembler(maxInFlight = 2, maxFrameBytes = 10_000)
        a.onChunk(chunks(1, jpeg)[0], 0); a.onChunk(chunks(2, jpeg)[0], 0); a.onChunk(chunks(3, jpeg)[0], 0)
        assertEquals(2, a.inFlightCount)
        assertNull(a.onChunk(FramePkt(9, 1, 1, 1, 20_000, 0, 0, 15, ByteArray(10)), 0)) // too big
        assertNull(a.onChunk(FramePkt(10, 1, 1, 1, 100, 95, 0, 1, ByteArray(10)), 0))   // offset past end
        assertNull(a.onChunk(FramePkt(11, 1, 1, 1, 0, 0, 0, 1, ByteArray(0)), 0))         // empty frame
        // chunk_count lies (claims 1 chunk of 3000 bytes) — impossible with ≤1400-byte chunks
        assertNull(a.onChunk(FramePkt(12, 1, 1, 1, 3000, 0, 0, 1, ByteArray(1400)), 0))
    }

    @Test
    fun `freshness gate bounds both ages and needs sync`() {
        val g = FreshnessGate(500_000)
        assertTrue(g.isFresh(capturePhoneUs = 0, rxPhoneUs = 0, nowPhoneUs = 500_000))
        assertFalse(g.isFresh(0, 400_000, 500_001))
        assertFalse(g.isFresh(400_000, 0, 500_001))
        assertFalse(g.isFresh(null, 0, 0))
    }
}

class RangeCorridorTest {
    private fun grid(fill: Int, status: Int) = IntArray(64) { fill } to IntArray(64) { status }

    @Test
    fun `regions take min valid distance`() {
        val (mm, st) = grid(0, 255)
        mm[2 * 8 + 3] = 1500; st[2 * 8 + 3] = 5
        mm[3 * 8 + 4] = 900; st[3 * 8 + 4] = 9
        mm[1 * 8 + 0] = 2500; st[1 * 8 + 0] = 5
        mm[1 * 8 + 1] = 100; st[1 * 8 + 1] = 4 // invalid status ignored even if near
        val r = RangeCorridor().evaluate(mm, st)
        assertEquals(RegionRange.Target(900), r.of(Region.CENTER))
        assertEquals(RegionRange.Target(2500), r.of(Region.LEFT))
        assertEquals(RegionRange.NothingSeen, r.of(Region.RIGHT))
    }

    @Test
    fun `all invalid is unknown, never clear, and floor rows are excluded`() {
        val (mm, st) = grid(500, 3)
        val r = RangeCorridor().evaluate(mm, st)
        Region.entries.forEach { assertEquals(RegionRange.Unknown, r.of(it)) }
        assertEquals(1f, r.corridorUnknownFraction)
        val (mm2, st2) = grid(0, 255)
        mm2[7 * 8 + 3] = 300; st2[7 * 8 + 3] = 5 // bottom row sees the floor: not in default corridor
        assertEquals(RegionRange.NothingSeen, RangeCorridor().evaluate(mm2, st2).of(Region.CENTER))
        val tilted = RangeCorridor(RegionMasks.of(rows = 2..7))
        assertEquals(RegionRange.Target(300), tilted.evaluate(mm2, st2).of(Region.CENTER))
    }

    @Test
    fun `mixed unknown and no target counts as nothing seen with confidence fraction`() {
        val (mm, st) = grid(0, 255)
        for (z in RegionMasks.DEFAULT.zones(Region.CENTER).take(18)) st[z] = 0
        val r = RangeCorridor().evaluate(mm, st)
        assertEquals(RegionRange.NothingSeen, r.of(Region.CENTER))
        assertEquals(18f / 24f, r.corridorUnknownFraction)
    }
}
