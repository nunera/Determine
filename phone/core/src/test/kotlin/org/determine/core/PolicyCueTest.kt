package org.determine.core

import org.determine.core.cue.CueScheduler
import org.determine.core.hearing.BandProfile
import org.determine.core.hearing.CalBand
import org.determine.core.hearing.HearingMode
import org.determine.core.hearing.HearingProfile
import org.determine.core.policy.HazardPolicy
import org.determine.core.policy.RegionObservation
import org.determine.core.wire.CueId
import org.determine.core.wire.GAIN_MUTE
import org.determine.core.wire.Haptic
import org.determine.core.wire.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun obs(c: Int? = null, l: Int? = null, r: Int? = null, unknownC: Boolean = false): Map<Region, RegionObservation> {
    val p = HazardPolicy()
    fun o(mm: Int?, unknown: Boolean = false) = if (unknown) RegionObservation(Band.NONE, null, false)
    else if (mm == null) RegionObservation(Band.NONE, null, true)
    else p.band(mm).let { RegionObservation(it, if (it == Band.NONE) null else mm, true) }
    return mapOf(Region.CENTER to o(c, unknownC), Region.LEFT to o(l), Region.RIGHT to o(r))
}

class HazardPolicyTest {
    private val dt = 66_667L // 15 Hz

    @Test
    fun `band boundaries`() {
        val p = HazardPolicy()
        assertEquals(Band.NEAR, p.band(999)); assertEquals(Band.MID, p.band(1000))
        assertEquals(Band.MID, p.band(1999)); assertEquals(Band.FAR, p.band(2000))
        assertEquals(Band.FAR, p.band(2999)); assertEquals(Band.NONE, p.band(3000))
    }

    @Test
    fun `enter needs 3 consecutive samples, exit needs 5 none samples`() {
        val p = HazardPolicy()
        var t = 0L
        assertNull(p.step(obs(c = 1500), emptyList(), false, t)); t += dt
        assertNull(p.step(obs(c = 1500), emptyList(), false, t)); t += dt
        val a = p.step(obs(c = 1500), emptyList(), false, t); t += dt
        assertEquals(Alert(Region.CENTER, Band.MID, "obstacle", 1500), a)
        assertTrue(p.takeOnset()); assertFalse(p.takeOnset())
        repeat(4) { assertNotNull(p.step(obs(), emptyList(), false, t)); t += dt }
        assertNull(p.step(obs(), emptyList(), false, t))
    }

    @Test
    fun `interrupted run restarts the count and a single none sample does not exit`() {
        val p = HazardPolicy()
        var t = 0L
        p.step(obs(c = 1500), emptyList(), false, t); t += dt
        p.step(obs(c = 1500), emptyList(), false, t); t += dt
        p.step(obs(), emptyList(), false, t); t += dt
        p.step(obs(c = 1500), emptyList(), false, t); t += dt
        assertNull(p.step(obs(c = 1500), emptyList(), false, t)); t += dt
        assertNotNull(p.step(obs(c = 1500), emptyList(), false, t)); t += dt
        p.step(obs(), emptyList(), false, t); t += dt
        assertNotNull(p.step(obs(c = 1500), emptyList(), false, t))
    }

    @Test
    fun `unknown is never clear - unknown samples do not count toward exit`() {
        val p = HazardPolicy()
        var t = 0L
        repeat(3) { p.step(obs(c = 800), emptyList(), false, t); t += dt }
        repeat(20) { assertNotNull(p.step(obs(unknownC = true), emptyList(), false, t)); t += dt }
        // and unknown never produces an alert from nothing
        val q = HazardPolicy()
        repeat(20) { assertNull(q.step(obs(unknownC = true), emptyList(), false, it * dt)) }
    }

    @Test
    fun `priority order`() {
        fun pick(o: Map<Region, RegionObservation>): Alert? {
            val p = HazardPolicy(); var a: Alert? = null
            repeat(3) { a = p.step(o, emptyList(), false, it * dt) }
            return a
        }
        assertEquals(Region.CENTER, pick(obs(c = 900, l = 500))!!.region)          // NEAR corridor > NEAR flank
        assertEquals(Region.LEFT, pick(obs(c = 1500, l = 900))!!.region)           // NEAR flank > MID corridor
        assertEquals(Region.CENTER, pick(obs(c = 1500, r = 1200))!!.region)        // MID corridor > MID flank
        assertEquals(Region.RIGHT, pick(obs(c = 2500, r = 1900))!!.region)         // MID flank > FAR corridor
        assertEquals(Region.RIGHT, pick(obs(l = 1800, r = 1100))!!.region)        // nearer flank wins a tie in band
        assertNull(pick(obs(l = 2500, r = 2500)))                                  // FAR flank never alerts
    }

    @Test
    fun `region change exits and onset is rate limited to 700 ms`() {
        val p = HazardPolicy()
        var t = 0L
        repeat(3) { p.step(obs(c = 1500), emptyList(), false, t); t += dt }
        assertTrue(p.takeOnset())
        val onsetT = t - dt
        // obstacle moves to the left flank (NEAR): region change exits immediately
        assertNull(p.step(obs(l = 900), emptyList(), false, t)); t += dt
        p.step(obs(l = 900), emptyList(), false, t); t += dt
        // third consecutive sample is at onset+200 ms: blocked by rate limit
        assertNull(p.step(obs(l = 900), emptyList(), false, t)); t += dt
        while (t - onsetT < 700_000) { assertNull(p.step(obs(l = 900), emptyList(), false, t)); t += dt }
        assertEquals(Region.LEFT, p.step(obs(l = 900), emptyList(), false, t)!!.region)
    }

    @Test
    fun `band updates within an active alert without a new onset`() {
        val p = HazardPolicy()
        repeat(3) { p.step(obs(c = 2500), emptyList(), false, it * dt) }
        p.takeOnset()
        assertEquals(Band.NEAR, p.step(obs(c = 700), emptyList(), false, 4 * dt)!!.band)
        assertFalse(p.takeOnset())
    }

    @Test
    fun `scanning holds FAR only`() {
        val p = HazardPolicy()
        repeat(5) { assertNull(p.step(obs(c = 2500), emptyList(), true, it * dt)) }
        val q = HazardPolicy()
        var a: Alert? = null
        repeat(3) { a = q.step(obs(c = 700), emptyList(), true, it * dt) }
        assertEquals(Band.NEAR, a!!.band)
        // FAR resumes once scanning stops; counts were kept
        assertNotNull(p.step(obs(c = 2500), emptyList(), false, 6 * dt))
    }

    @Test
    fun `label from matching confident detection by bearing`() {
        val p = HazardPolicy()
        val person = Detection("Person", 0.8f, 0.4f, 0.1f, 0.6f, 0.9f)       // centre
        val weakDoor = Detection("door", 0.3f, 0.4f, 0.1f, 0.6f, 0.9f)       // centre, below threshold
        val leftChair = Detection("chair", 0.9f, 0.0f, 0.1f, 0.2f, 0.9f)     // left
        var a: Alert? = null
        repeat(3) { a = p.step(obs(c = 1500), listOf(weakDoor, leftChair), false, it * dt) }
        assertEquals("obstacle", a!!.label)
        assertEquals("person", p.step(obs(c = 1500), listOf(person, leftChair), false, 4 * dt)!!.label)
        assertEquals(Region.LEFT, p.regionOf(leftChair))
        assertEquals(Region.RIGHT, p.regionOf(Detection("x", 1f, 0.7f, 0f, 0.9f, 1f)))
    }

    @Test
    fun `relative cues without tof never carry distance`() {
        val p = HazardPolicy()
        val small = Detection("person", 0.9f, 0.45f, 0.4f, 0.55f, 0.6f)      // area 0.02
        val big = Detection("person", 0.9f, 0.4f, 0.3f, 0.6f, 0.7f)          // area 0.08
        val o0 = p.relativeObservations(listOf(small), 0)
        assertEquals(Band.NONE, o0.getValue(Region.CENTER).band)
        val o1 = p.relativeObservations(listOf(big), 500_000)
        assertEquals(RegionObservation(Band.FAR, null, true), o1.getValue(Region.CENTER))
        assertEquals(Band.NONE, o1.getValue(Region.LEFT).band)
        // no growth (static object) → nothing
        val q = HazardPolicy()
        q.relativeObservations(listOf(big), 0)
        assertEquals(Band.NONE, q.relativeObservations(listOf(big), 500_000).getValue(Region.CENTER).band)
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
        val far = Alert(Region.CENTER, Band.FAR, "obstacle", 2500)
        val first = s.update(far, true, profile, 0)!!
        assertEquals(1, first.flags)
        assertNull(s.update(far, false, profile, 999_999))
        assertEquals(0, s.update(far, false, profile, 1_000_000)!!.flags)
        val mid = far.copy(band = Band.MID, distanceMm = 1500)
        assertEquals(1, s.update(mid, false, profile, 1_010_000)!!.flags) // band change plays now
        assertNull(s.update(mid, false, profile, 1_409_999))
        assertNotNull(s.update(mid, false, profile, 1_410_000))
        val near = far.copy(band = Band.NEAR, distanceMm = 800)
        var n = 0
        for (t in 2_000_000L until 3_000_000L step 10_000) if (s.update(near, false, profile, t) != null) n++
        assertEquals(5, n) // 5/s
        assertNull(s.update(null, false, profile, 3_000_000))
    }

    @Test
    fun `stereo side mapping, cue family and haptic patterns`() {
        val s = CueScheduler()
        val l = s.build(Alert(Region.LEFT, Band.FAR, "obstacle", 2500), profile, false)
        assertEquals(CueId.OBSTACLE_FAR, l.cueId); assertEquals(Side.L, l.side)
        assertEquals(-25, l.gainLDb); assertEquals(GAIN_MUTE, l.gainRDb)
        assertEquals(Haptic.PULSE40, l.hapticPattern); assertEquals(120, l.hapticL); assertEquals(0, l.hapticR)
        assertEquals(150, l.ttlMs)
        val r = s.build(Alert(Region.RIGHT, Band.MID, "person", 1500), profile, false)
        assertEquals(CueId.PERSON_MID, r.cueId); assertEquals(Side.R, r.side)
        assertEquals(GAIN_MUTE, r.gainLDb); assertEquals(-15, r.gainRDb)
        assertEquals(Haptic.PULSE60, r.hapticPattern); assertEquals(0, r.hapticL); assertEquals(80, r.hapticR)
        val c = s.build(Alert(Region.CENTER, Band.NEAR, "person", 800), profile, false)
        assertEquals(CueId.PERSON_NEAR, c.cueId); assertEquals(Side.BOTH, c.side)
        assertEquals(Haptic.BUZZ150, c.hapticPattern)
        assertEquals(180, c.hapticL) // capped at haptic_max
        assertEquals(100, c.hapticR)
    }

    @Test
    fun `near adds 6 dB but never exceeds ceiling`() {
        val s = CueScheduler()
        val c = s.build(Alert(Region.CENTER, Band.NEAR, "obstacle", 800), profile, false)
        assertEquals(-19, c.gainLDb)       // -40+15+6
        assertEquals(-14, c.gainRDb)       // -30+15+6 = -9 → ceiling -14
        val loud = profile.copy(ceilingDb = 0)
        val c2 = s.build(Alert(Region.CENTER, Band.NEAR, "obstacle", 800), loud, false)
        assertEquals(-10, c2.gainRDb)      // firmware hard ceiling −10 applies on the phone too
    }

    @Test
    fun `ssd plays identical audio both sides, direction on haptics, haptic only mutes`() {
        val s = CueScheduler()
        val ssd = profile.copy(mode = HearingMode.SSD, bands = CalBand.HZ.map { BandProfile(it, -40, null, 15, 0, 0) })
        val c = s.build(Alert(Region.LEFT, Band.MID, "obstacle", 1500), ssd, false)
        assertEquals(Side.BOTH, c.side); assertEquals(-25, c.gainLDb); assertEquals(GAIN_MUTE, c.gainRDb)
        assertTrue(c.hapticL > 0); assertEquals(0, c.hapticR)
        val h = s.build(Alert(Region.RIGHT, Band.NEAR, "obstacle", 800), profile.copy(mode = HearingMode.HAPTIC_ONLY), false)
        assertEquals(GAIN_MUTE, h.gainLDb); assertEquals(GAIN_MUTE, h.gainRDb)
        assertEquals(0, h.hapticL); assertTrue(h.hapticR > 0)
    }

    @Test
    fun `spearcon never states distance without tof`() {
        assertEquals("person left, 1.5 metres", CueScheduler.spearcon(Alert(Region.LEFT, Band.MID, "person", 1500)))
        assertEquals("obstacle ahead", CueScheduler.spearcon(Alert(Region.CENTER, Band.FAR, "obstacle", null)))
    }
}
