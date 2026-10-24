package org.determine.android.detect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacerTest {
    private val pacer = FramePacer(maxAgeUs = 500_000, stuckTimeoutUs = 500_000)

    @Test
    fun submitsFreshFrameOnceAndSkipsWhileBusy() {
        val s = pacer.offer(frameId = 1, captureBoardUs = 10, capturePhoneUs = 1_000_000, nowUs = 1_050_000)
        assertNotNull(s)
        assertTrue(pacer.busy)
        // Same frame offered on the next tick: nothing new to do.
        assertNull(pacer.offer(1, 10, 1_000_000, 1_060_000))
        // Newer frame while busy: skipped, not queued.
        assertNull(pacer.offer(2, 20, 1_066_000, 1_070_000))
        assertNull(pacer.offer(2, 20, 1_066_000, 1_080_000))
        assertEquals("a frame skipped on several ticks counts once", 1, pacer.skippedBusy)
        assertEquals(1L, pacer.onResult(s!!.timestampMs)!!.frameId)
        assertFalse(pacer.busy)
    }

    @Test
    fun afterBusyTakesNewestNotQueuedBacklog() {
        val s1 = pacer.offer(1, 10, 1_000_000, 1_000_000)!!
        assertNull(pacer.offer(2, 20, 1_066_000, 1_070_000))
        assertNull(pacer.offer(3, 30, 1_133_000, 1_140_000))
        pacer.onResult(s1.timestampMs)
        // Frame 2 is never revisited; the caller only ever offers the newest (3).
        val s3 = pacer.offer(3, 30, 1_133_000, 1_150_000)
        assertEquals(3L, s3!!.frameId)
    }

    @Test
    fun staleFrameIsNeverSubmitted() {
        assertNull(pacer.offer(1, 10, capturePhoneUs = 1_000_000, nowUs = 1_500_001))
        assertEquals(1, pacer.skippedStale)
        assertFalse(pacer.busy)
        // Exactly at the bound is still fresh.
        assertNotNull(pacer.offer(2, 20, capturePhoneUs = 2_000_000, nowUs = 2_500_000))
    }

    @Test
    fun timestampsStrictlyIncreaseEvenForSameMillisecond() {
        val a = pacer.offer(1, 10, 1_000_100, 1_001_000)!!
        pacer.onResult(a.timestampMs)
        val b = pacer.offer(2, 20, 1_000_900, 1_002_000)!! // same ms as a
        assertTrue(b.timestampMs > a.timestampMs)
        pacer.onResult(b.timestampMs)
        val c = pacer.offer(3, 30, 999_000, 1_003_000)!! // capture clock stepped back (re-sync)
        assertTrue(c.timestampMs > b.timestampMs)
    }

    @Test
    fun stuckDetectorIsAbandonedAndLateResultRejected() {
        val s1 = pacer.offer(1, 10, 1_000_000, 1_000_000)!!
        assertNull(pacer.offer(2, 20, 1_400_000, 1_400_000)) // still within stuck timeout
        val s3 = pacer.offer(3, 30, 1_500_000, 1_500_001) // 500.001 ms in flight → abandoned
        assertNotNull(s3)
        assertEquals(1, pacer.abandoned)
        assertNull("late result of the abandoned frame must be discarded", pacer.onResult(s1.timestampMs))
        assertEquals(3L, pacer.onResult(s3!!.timestampMs)!!.frameId)
    }

    @Test
    fun errorFreesDetectorOnlyForMatchingSubmission() {
        val s = pacer.offer(1, 10, 1_000_000, 1_000_000)!!
        pacer.onError(s.timestampMs + 1)
        assertTrue(pacer.busy)
        pacer.onError(s.timestampMs)
        assertFalse(pacer.busy)
    }

    @Test
    fun cameraRebootRestartingFrameIdsIsStillDetected() {
        val s = pacer.offer(500, 9_000_000, 1_000_000, 1_000_000)!!
        pacer.onResult(s.timestampMs)
        // Board rebooted: frame_id restarts at 0, board clock restarts.
        val r = pacer.offer(0, 50_000, 2_000_000, 2_010_000)
        assertNotNull(r)
        assertEquals(0L, r!!.frameId)
        assertEquals(50_000L, r.captureBoardUs)
    }
}
