package org.determine.core

import org.determine.core.cue.CueScheduler
import org.determine.core.hearing.BandProfile
import org.determine.core.hearing.CalBand
import org.determine.core.hearing.HearingMode
import org.determine.core.hearing.HearingProfile
import org.determine.core.range.ForwardRange
import org.determine.core.policy.HazardPolicy
import org.determine.core.wire.CueId
import org.determine.core.wire.Haptic
import org.determine.core.wire.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private val NOTHING = ForwardRange.NothingSeen
private val UNKNOWN = ForwardRange.Unknown
private fun at(mm: Int) = ForwardRange.Target(mm)
private fun HazardPolicy.at(ms: Int, r: ForwardRange, scanning: Boolean = false): Alert? = step(r, scanning, ms * 1000L)

/** Feeds [r] every [dtMs] from [fromMs] to [toMs] inclusive; returns the last alert. */
private fun HazardPolicy.feed(r: ForwardRange, fromMs: Int, toMs: Int, dtMs: Int = 50, scanning: Boolean = false): Alert? {
    var last: Alert? = null
    var t = fromMs
    while (t <= toMs) { last = at(t, r, scanning); t += dtMs }
    return last
}

class HazardPolicyTest {
    @Test
    fun `band boundaries are the conservative near-field edges`() {
        val p = HazardPolicy()
        assertEquals(Band.NEAR, p.band(0)); assertEquals(Band.NEAR, p.band(499))
        assertEquals(Band.MID, p.band(500)); assertEquals(Band.MID, p.band(899))
        assertEquals(Band.FAR, p.band(900)); assertEquals(Band.FAR, p.band(1200))
        assertEquals(Band.NONE, p.band(1201)) // nothing beyond 1.2 m: the sensor cannot honestly vouch for it
        assertEquals(Band.NONE, p.band(4000))
    }

    @Test
    fun `default tuning never exceeds 1200 mm and uses the contract defaults`() {
        val t = Tuning()
        assertEquals(500, t.nearMm); assertEquals(900, t.midMm); assertEquals(1200, t.farMm)
        assertEquals(150, t.bandEnterMs); assertEquals(600, t.bandExitMs); assertEquals(50, t.bandHysteresisMm)
        assertEquals(1000, t.farPeriodMs); assertEquals(400, t.midPeriodMs); assertEquals(200, t.nearPeriodMs)
        assertEquals(1500, t.announceHoldMs); assertEquals(10_000, t.announceRepeatMs)
        assertEquals(-70, t.weakLinkRssiDbm); assertEquals(2000, t.weakLinkHoldMs); assertEquals(30_000, t.weakLinkRepeatMs)
        assertFalse(t.approachEnabled); assertEquals(300, t.approachWindowMs)
        assertEquals(1500, t.approachTtcMs); assertEquals(300, t.approachMinSpeedMmPerS)
        assertFalse(t.exploreEnabled); assertEquals(120, t.explorePeriodMs)
        assertEquals(1200, t.exploreMaxMm); assertEquals(30, t.exploreMinIntensity)
    }

    @Test
    fun `a band is entered only after 150 ms of consistent readings`() {
        val p = HazardPolicy()
        assertNull(p.at(0, at(700)))
        assertNull(p.at(100, at(700)))
        assertNull(p.at(149, at(700)))
        assertEquals(Alert(Band.MID, 700), p.at(150, at(700)))
        assertTrue(p.takeOnset()); assertFalse(p.takeOnset())
    }

    @Test
    fun `leaving to nothing to report takes 600 ms for no-target and for valid-beyond-far`() {
        for (gone in listOf(NOTHING, at(2500))) {
            val p = HazardPolicy()
            p.feed(at(700), 0, 200)
            assertNotNull(p.at(1000, gone))
            assertNotNull(p.at(1599, gone))
            assertNull(p.at(1600, gone))
        }
    }

    @Test
    fun `an interrupting sample restarts the exit and the enter run`() {
        val p = HazardPolicy()
        p.feed(at(700), 0, 200)                  // MID active
        p.feed(NOTHING, 1000, 1500)              // exit run started at 1000
        assertNotNull(p.at(1550, at(700)))       // back in band: run reset
        p.feed(NOTHING, 1600, 2100)              // new run from 1600
        assertNotNull(p.at(2150, NOTHING))       // only 550 ms
        assertNull(p.at(2200, NOTHING))
        // enter side: a gap of NO_TARGET restarts the enter run
        val q = HazardPolicy()
        q.at(0, at(700)); q.at(100, at(700)); q.at(140, NOTHING)
        assertNull(q.at(200, at(700)))
        assertNull(q.at(340, at(700)))
        assertNotNull(q.at(350, at(700)))
    }

    @Test
    fun `hysteresis keeps the band until the distance exceeds its upper bound by 50 mm`() {
        // NEAR (< 500): readings up to 550 never leave, however long
        val p = HazardPolicy()
        p.feed(at(300), 0, 200)
        assertEquals(Band.NEAR, p.feed(at(550), 300, 5000)!!.band)
        // 551 mm held for 600 ms leaves NEAR → MID
        assertEquals(Band.NEAR, p.feed(at(551), 5000, 5550)!!.band)
        assertEquals(Band.MID, p.at(5600, at(551))!!.band)
        // MID (< 900): 950 holds, 951 leaves → FAR
        assertEquals(Band.MID, p.feed(at(950), 6000, 9000)!!.band)
        assertEquals(Band.MID, p.feed(at(951), 9050, 9600)!!.band)
        assertEquals(Band.FAR, p.at(9650, at(951))!!.band)
        // FAR (≤ 1200): 1250 holds, 1251 leaves → nothing
        assertEquals(Band.FAR, p.feed(at(1250), 10_000, 13_000)!!.band)
        assertEquals(Band.FAR, p.feed(at(1251), 13_050, 13_600)!!.band)
        assertNull(p.at(13_650, at(1251)))
    }

    @Test
    fun `hysteresis is exit only - entering uses the raw band`() {
        val p = HazardPolicy()
        assertEquals(Band.FAR, p.feed(at(1200), 0, 200)!!.band)   // 1200 is FAR, no margin on the way in
        assertNull(HazardPolicy().feed(at(1201), 0, 1000))
    }

    @Test
    fun `a more urgent band is entered after 150 ms and the run keeps the least urgent reading`() {
        val p = HazardPolicy()
        p.feed(at(1000), 0, 200)                  // FAR
        assertEquals(Band.FAR, p.at(300, at(700))!!.band)
        assertEquals(Band.FAR, p.at(400, at(300))!!.band)   // alternating MID/NEAR: only MID is consistent so far
        assertEquals(Band.MID, p.at(450, at(700))!!.band)    // 150 ms after the first more-urgent sample (300)
        assertEquals(Band.NEAR, p.feed(at(300), 500, 700)!!.band)
    }

    @Test
    fun `unknown drops the active alert at once and presents nothing until valid readings re-enter`() {
        val p = HazardPolicy()
        p.feed(at(700), 0, 200) // MID alert active
        assertTrue(p.takeOnset())
        // The measurement no longer exists: no stale MID, no distance, no onset, no all-clear.
        assertNull(p.at(250, UNKNOWN))
        assertNull(p.feed(UNKNOWN, 300, 2000))
        assertFalse(p.takeOnset())
        // VALID readings resume through the normal enter rules: 150 ms, then a fresh onset.
        assertNull(p.at(2050, at(700))); assertNull(p.at(2150, at(700)))
        assertEquals(Alert(Band.MID, 700), p.at(2200, at(700)))
        assertTrue(p.takeOnset())
    }

    @Test
    fun `unknown never produces an alert from nothing and an unknown sample restarts the enter run`() {
        assertNull(HazardPolicy().feed(UNKNOWN, 0, 2000))
        val p = HazardPolicy()
        p.at(0, at(700)); p.at(100, at(700))
        assertNull(p.at(120, UNKNOWN))
        assertNull(p.at(200, at(700)))
        assertNull(p.at(349, at(700)))
        assertNotNull(p.at(350, at(700)))
    }

    @Test
    fun `onset is rate limited to 700 ms`() {
        val p = HazardPolicy(Tuning(bandExitMs = 0))
        p.feed(at(700), 0, 100)
        assertEquals(Band.MID, p.at(150, at(700))!!.band)
        assertTrue(p.takeOnset())
        assertNull(p.at(200, NOTHING))                      // exit (no hold configured)
        assertNull(p.feed(at(700), 250, 800))               // active again but within 700 ms of the last onset: hidden
        assertFalse(p.takeOnset())
        assertNotNull(p.at(850, at(700)))                   // 700 ms after the onset at 150
        assertTrue(p.takeOnset())
    }

    @Test
    fun `band changes within an active alert have no new onset`() {
        val p = HazardPolicy()
        p.feed(at(1100), 0, 200)
        p.takeOnset()
        assertEquals(Band.NEAR, p.feed(at(300), 250, 500)!!.band)
        assertFalse(p.takeOnset())
    }

    @Test
    fun `scanning holds FAR only`() {
        val p = HazardPolicy()
        assertNull(p.feed(at(1100), 0, 500, scanning = true))
        assertFalse(p.takeOnset())
        val q = HazardPolicy()
        assertEquals(Band.NEAR, q.feed(at(300), 0, 200, scanning = true)!!.band)
        // FAR resumes once scanning stops, with its onset
        assertNotNull(p.at(550, at(1100)))
        assertTrue(p.takeOnset())
    }

    @Test
    fun `the alert carries only band and forward distance - no side or label`() {
        // Alert has exactly (band, distanceMm): nothing to lateralize and no object label to infer.
        val a = Alert(Band.NEAR, 320)
        assertEquals(setOf("band", "distanceMm"), Alert::class.java.declaredFields.map { it.name }.toSet())
        assertEquals(320, a.distanceMm)
    }

    @Test
    fun `a sample time going backwards drops the old session`() {
        val p = HazardPolicy()
        p.feed(at(700), 10_000, 10_300)
        assertNull(p.at(100, at(700)))
    }

    @Test
    fun `closing speed is the least squares slope and needs 3 samples in the window`() {
        val p = HazardPolicy()
        p.at(0, at(1000)); p.at(50, at(960))
        assertNull(p.closingSpeedMmPerS)
        p.at(100, at(920))
        assertEquals(800.0, p.closingSpeedMmPerS!!, 1e-6)       // 40 mm / 50 ms, approaching
        p.at(150, at(960)); p.at(200, at(1000)); p.at(250, at(1040)); p.at(300, at(1080))
        assertTrue(p.closingSpeedMmPerS!! < 0, "receding is negative: ${p.closingSpeedMmPerS}") // 0..300 inclusive window
        // beyond the window the old samples are gone
        p.at(1000, at(500)); p.at(1050, at(500)); p.at(1100, at(500))
        assertEquals(0.0, p.closingSpeedMmPerS!!, 1e-6)
        // an unknown sample or no target erases the history
        p.at(1150, NOTHING)
        assertNull(p.closingSpeedMmPerS)
    }

    @Test
    fun `approach off never escalates, approach on escalates by one when fast and about to collide`() {
        val closing = listOf(880, 840, 800, 760, 720, 680, 640, 600)
        val off = HazardPolicy()
        closing.forEachIndexed { i, mm -> off.at(i * 50, at(mm)) }
        assertFalse(off.approachActive)
        assertEquals(Band.MID, off.at(400, at(560))!!.band)

        val on = HazardPolicy(Tuning(approachEnabled = true))
        assertNull(on.at(0, at(880))); assertNull(on.at(50, at(840)))
        // 3 samples at 800 mm/s, 800 mm away: TTC 1.0 s < 1.5 s. NONE within farMm escalates to FAR at once.
        assertEquals(Alert(Band.FAR, 800), on.at(100, at(800)))
        assertTrue(on.approachActive); assertTrue(on.takeOnset())
        // MID has been entered by now (150 ms): escalated to NEAR
        assertEquals(Band.NEAR, on.at(150, at(760))!!.band)
        // NEAR stays NEAR; no second onset
        assertEquals(Band.NEAR, on.at(200, at(300))!!.band)
        assertFalse(on.takeOnset())
    }

    @Test
    fun `approach needs the minimum speed and a short time to contact`() {
        val slow = HazardPolicy(Tuning(approachEnabled = true))
        slow.feed(at(800), 0, 100)
        assertFalse(slow.approachActive)                 // 0 mm/s
        val far = HazardPolicy(Tuning(approachEnabled = true))
        far.at(0, at(1200)); far.at(50, at(1180)); far.at(100, at(1160)) // 400 mm/s, TTC 2.9 s
        assertEquals(400.0, far.closingSpeedMmPerS!!, 1e-6)
        assertFalse(far.approachActive)
        val fast = HazardPolicy(Tuning(approachEnabled = true, approachTtcMs = 3000))
        fast.at(0, at(1200)); fast.at(50, at(1180)); fast.at(100, at(1160))
        assertTrue(fast.approachActive)
        // closing speed is always computed, even with the feature disabled
        val off = HazardPolicy()
        off.at(0, at(1200)); off.at(50, at(1180)); off.at(100, at(1160))
        assertEquals(400.0, off.closingSpeedMmPerS!!, 1e-6); assertFalse(off.approachActive)
    }
}

class CueSchedulerTest {
    private val profile = HearingProfile(
        "t", 0, HearingMode.STEREO, ceilingDb = -14,
        bands = CalBand.HZ.map { BandProfile(it, -40, -30, 15, 0, 0) },
        hapticGainL = 200, hapticGainR = 100, hapticMax = 180, verifiedTrials = 5, verifiedCorrect = 5,
    )

    @Test
    fun `cadence per band and onset interrupts`() {
        val s = CueScheduler()
        val far = Alert(Band.FAR, 1100)
        val first = s.update(far, true, profile, 0)!!
        assertEquals(1, first.flags)
        assertNull(s.update(far, false, profile, 999_999))
        assertEquals(0, s.update(far, false, profile, 1_000_000)!!.flags)
        val mid = Alert(Band.MID, 700)
        assertEquals(1, s.update(mid, false, profile, 1_010_000)!!.flags) // band change plays now
        assertNull(s.update(mid, false, profile, 1_409_999))
        assertNotNull(s.update(mid, false, profile, 1_410_000))
        val near = Alert(Band.NEAR, 300)
        var n = 0
        for (t in 2_000_000L until 3_000_000L step 10_000) if (s.update(near, false, profile, t) != null) n++
        assertEquals(5, n) // 5/s
        assertNull(s.update(null, false, profile, 3_000_000))
    }

    @Test
    fun `cue family, both sides always, and haptic patterns`() {
        val s = CueScheduler()
        val far = s.build(Alert(Band.FAR, 1100), profile, false)
        assertEquals(CueId.OBSTACLE_FAR, far.cueId); assertEquals(Side.BOTH, far.side)
        assertEquals(-25, far.gainLDb); assertEquals(-15, far.gainRDb)
        assertEquals(Haptic.PULSE40, far.hapticPattern); assertEquals(120, far.hapticL); assertEquals(60, far.hapticR)
        assertEquals(150, far.ttlMs)
        val mid = s.build(Alert(Band.MID, 700), profile, false)
        assertEquals(CueId.OBSTACLE_MID, mid.cueId); assertEquals(Side.BOTH, mid.side)
        assertEquals(Haptic.PULSE60, mid.hapticPattern); assertEquals(160, mid.hapticL); assertEquals(80, mid.hapticR)
        val near = s.build(Alert(Band.NEAR, 300), profile, false)
        assertEquals(CueId.OBSTACLE_NEAR, near.cueId); assertEquals(Side.BOTH, near.side)
        assertEquals(Haptic.BUZZ150, near.hapticPattern)
        assertEquals(180, near.hapticL) // capped at haptic_max
        assertEquals(100, near.hapticR)
    }

    @Test
    fun `no person cue is ever selected from the forward zone`() {
        val s = CueScheduler()
        for (b in listOf(Band.FAR, Band.MID, Band.NEAR)) {
            val id = s.build(Alert(b, 800), profile, false).cueId
            assertTrue(id in CueId.OBSTACLE_FAR..CueId.OBSTACLE_NEAR, "band $b → cue $id")
        }
    }

    @Test
    fun `near adds 6 dB but never exceeds ceiling`() {
        val s = CueScheduler()
        val c = s.build(Alert(Band.NEAR, 300), profile, false)
        assertEquals(-19, c.gainLDb)       // -40+15+6
        assertEquals(-14, c.gainRDb)       // -30+15+6 = -9 → ceiling -14
        val loud = profile.copy(ceilingDb = 0)
        val c2 = s.build(Alert(Band.NEAR, 300), loud, false)
        assertEquals(-10, c2.gainRDb)      // hard ceiling −10 applies on the phone too
    }

    @Test
    fun `per-side ceiling, ssd with an unusable side, and haptic only mutes the phone audio`() {
        val s = CueScheduler()
        val ssd = profile.copy(mode = HearingMode.SSD, bands = CalBand.HZ.map { BandProfile(it, -40, null, 15, 0, 0) })
        val c = s.build(Alert(Band.MID, 700), ssd, false)
        assertEquals(Side.BOTH, c.side); assertEquals(-25, c.gainLDb); assertEquals(GAIN_MUTE, c.gainRDb) // unusable side stays muted
        assertTrue(c.hapticL > 0 && c.hapticR > 0) // board haptics unaffected, both temples
        val h = s.build(Alert(Band.NEAR, 300), profile.copy(mode = HearingMode.HAPTIC_ONLY), false)
        assertEquals(GAIN_MUTE, h.gainLDb); assertEquals(GAIN_MUTE, h.gainRDb)
        assertTrue(h.hapticL > 0 && h.hapticR > 0) // haptics still go to the board
    }

    @Test
    fun `spearcon never states distance without tof and never names an object`() {
        assertEquals("obstacle ahead, 0.7 metres", CueScheduler.spearcon(Alert(Band.MID, 700)))
        assertEquals("obstacle ahead", CueScheduler.spearcon(Alert(Band.FAR, null)))
    }

    @Test
    fun `repetition periods come from the tuning and follow a runtime change`() {
        val s = CueScheduler(Tuning(farPeriodMs = 500, midPeriodMs = 250, nearPeriodMs = 100))
        assertEquals(500_000L, s.period(Band.FAR)); assertEquals(250_000L, s.period(Band.MID)); assertEquals(100_000L, s.period(Band.NEAR))
        val near = Alert(Band.NEAR, 300)
        var n = 0
        for (t in 0L until 1_000_000L step 10_000) if (s.update(near, false, profile, t) != null) n++
        assertEquals(10, n)
        s.tuning = Tuning()
        assertEquals(200_000L, s.period(Band.NEAR))
    }
}
