package org.determine.core

import org.determine.core.hearing.HearingProfile
import org.determine.core.wire.CueId
import org.determine.core.wire.Haptic
import org.determine.core.wire.RangeStatus
import org.determine.core.wire.Side
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

private fun readyRig(cfg: EngineConfig = EngineConfig()) =
    Rig(cfg).also { it.run(1_000_000); assertEquals(SystemState.READY, it.engine.state, it.states.toString()) }

/** Feature contract §3: less chatter. */
class AnnouncementTest {
    @Test
    fun `a degraded state that does not hold 1_5 s is never spoken and the visual state is immediate`() {
        val r = readyRig()
        r.status = RangeStatus.UNKNOWN
        r.run(600_000)
        assertEquals(SystemState.DEGRADED, r.states.last().first) // visual / onState: immediate
        r.status = RangeStatus.NO_TARGET
        r.run(3_000_000)
        assertEquals(SystemState.READY, r.engine.state)
        assertTrue(r.spoken.none { it == "distance sensor limited" }, r.spoken.toString())
    }

    @Test
    fun `the same phrase is not repeated within 10 s, but is after`() {
        val r = readyRig()
        fun flapDegraded() {
            r.status = RangeStatus.UNKNOWN; r.run(2_000_000)
            r.status = RangeStatus.NO_TARGET; r.run(1_000_000)
        }
        flapDegraded()
        assertEquals(1, r.spoken.count { it == "distance sensor limited" })
        flapDegraded() // ~3 s after the first announcement
        assertEquals(1, r.spoken.count { it == "distance sensor limited" }, "repeat window 10 s")
        r.run(10_000_000)
        flapDegraded()
        assertEquals(2, r.spoken.count { it == "distance sensor limited" })
    }

    @Test
    fun `hold and repeat follow the tuning, set at runtime`() {
        val r = readyRig(EngineConfig(tuning = Tuning(announceHoldMs = 0)))
        r.status = RangeStatus.UNKNOWN
        r.run(300_000)
        assertEquals(1, r.spoken.count { it == "distance sensor limited" }, "hold 0 speaks at once")
        r.engine.setTuning(r.engine.tuning.copy(announceHoldMs = 3000, announceRepeatMs = 0))
        r.status = RangeStatus.NO_TARGET; r.run(500_000)
        r.status = RangeStatus.UNKNOWN; r.run(2_000_000)
        assertEquals(1, r.spoken.count { it == "distance sensor limited" })
        r.run(1_500_000)
        assertEquals(2, r.spoken.count { it == "distance sensor limited" })
    }

    @Test
    fun `paused and resumed stay immediate`() {
        val r = readyRig()
        r.engine.setPaused(true)
        r.run(50_000)
        assertEquals(listOf("paused"), r.spoken)
        r.engine.setPaused(false)
        r.run(50_000)
        assertTrue(r.spoken.contains("resumed"))
    }

    @Test
    fun `system off is never delayed`() {
        val r = readyRig()
        r.camAlive = false
        r.run(700_000)
        assertEquals(SystemState.UNAVAILABLE, r.engine.state)
        assertEquals(1, r.systemOffs)
    }
}

/** Feature contract §2: time-based smoothing at the engine level. */
class SmoothingEngineTest {
    @Test
    fun `a target hovering across the FAR - MID boundary does not flap`() {
        val r = readyRig()
        // ±30 mm around 900 mm at ~3 Hz: raw band alternates MID/FAR every 167 ms, shorter than any enter/exit run
        val t0 = r.now
        var up = false
        repeat(30) { r.obstacle(if (up) 930 else 870); up = !up; r.run(167_000) }
        val bands = r.alertsOut.filterNotNull().map { it.band }
        assertTrue(bands.isNotEmpty())
        assertEquals(1, bands.toSet().size, "one band for the whole hover: $bands")
        val ids = r.obstacleCues(t0).map { it.second.cueId }
        assertEquals(1, ids.toSet().size, "no FAR/MID alternation in the cues: $ids")
    }

    @Test
    fun `a reordered RANGE is dropped and does not reset the smoother`() {
        val r = readyRig()
        r.obstacle(700)
        r.run(1_000_000)
        val before = r.engine.diagnostics(r.now).staleDropped
        val cuesBefore = r.obstacleCues().size
        val late = org.determine.core.wire.Wire.encode(
            org.determine.core.wire.RangePkt(r.now + r.cueOffset - 120_000, 700, RangeStatus.VALID), 9999,
        )
        r.engine.onPacket(BoardId.CUE, late, late.size, r.now)
        assertEquals(before + 1, r.engine.diagnostics(r.now).staleDropped)
        assertEquals(cuesBefore, r.obstacleCues().size)
        assertEquals(Band.MID, r.alertsOut.last()!!.band)
    }

    @Test
    fun `a brief unknown cancels the warning at once and a fresh onset needs the enter time`() {
        val r = readyRig()
        r.obstacle(700)
        r.run(1_000_000)
        assertEquals(Band.MID, r.alertsOut.last()!!.band)
        r.status = RangeStatus.UNKNOWN
        r.run(150_000)
        assertNull(r.alertsOut.last())
    }
}

/** Feature contract §4: link quality warning. */
class WeakLinkTest {
    @Test
    fun `weak after 2 s below -70 dBm, spoken once, informational, recovers 5 dB above held 2 s`() {
        val r = readyRig()
        r.cueRssi = -80
        r.run(1_500_000)
        assertFalse(r.engine.diagnostics(r.now).linkWeak)
        r.run(1_000_000)
        assertTrue(r.engine.diagnostics(r.now).linkWeak)
        assertEquals(1, r.spoken.count { it == "weak connection" })
        assertEquals(SystemState.READY, r.engine.state, "purely informational")
        // -66 is above the threshold but not 5 dB above it: stays weak
        r.cueRssi = -66
        r.run(4_000_000)
        assertTrue(r.engine.diagnostics(r.now).linkWeak)
        // -65 = threshold + 5 held for 2 s recovers
        r.cueRssi = -65
        r.run(1_500_000)
        assertTrue(r.engine.diagnostics(r.now).linkWeak)
        r.run(1_000_000)
        assertFalse(r.engine.diagnostics(r.now).linkWeak)
        // weak again inside the 30 s repeat window: flag set, no second announcement
        r.cueRssi = -90
        r.run(3_000_000)
        assertTrue(r.engine.diagnostics(r.now).linkWeak)
        assertEquals(1, r.spoken.count { it == "weak connection" })
        // after the window a new episode is announced again
        r.cueRssi = -50
        r.run(3_000_000)
        r.run(25_000_000)
        r.cueRssi = -90
        r.run(3_000_000)
        assertEquals(2, r.spoken.count { it == "weak connection" })
    }

    @Test
    fun `a dip shorter than the hold never counts`() {
        val r = readyRig()
        repeat(5) {
            r.cueRssi = -85; r.run(1_000_000)
            r.cueRssi = -50; r.run(300_000)
        }
        assertFalse(r.engine.diagnostics(r.now).linkWeak)
        assertTrue(r.spoken.none { it == "weak connection" })
    }

    @Test
    fun `threshold and hold come from the tuning`() {
        val r = readyRig(EngineConfig(tuning = Tuning(weakLinkRssiDbm = -45, weakLinkHoldMs = 500)))
        r.run(1_000_000)
        assertTrue(r.engine.diagnostics(r.now).linkWeak, "default test RSSI -50 is below -45")
    }
}

/** Feature contract §5: approach-speed escalation. */
class ApproachEngineTest {
    private fun approach(r: Rig) {
        var mm = 1150
        repeat(8) { r.obstacle(mm); r.run(66_667); mm -= 80 }
    }

    @Test
    fun `off by default - closing speed is still reported`() {
        val r = readyRig()
        approach(r)
        val d = r.engine.diagnostics(r.now)
        assertFalse(d.approachActive)
        assertNotNull(d.closingSpeedMmPerS)
        assertTrue(d.closingSpeedMmPerS!! > 900.0, "${d.closingSpeedMmPerS}")
        assertEquals(Band.MID, r.alertsOut.last()!!.band)
    }

    @Test
    fun `on - a fast approach escalates the reported band by one`() {
        val r = readyRig(EngineConfig(tuning = Tuning(approachEnabled = true)))
        approach(r)
        val d = r.engine.diagnostics(r.now)
        assertTrue(d.approachActive)
        assertEquals(Band.NEAR, r.alertsOut.last()!!.band)
        assertTrue(r.obstacleCues().any { it.second.cueId == CueId.OBSTACLE_NEAR })
    }

    @Test
    fun `can be switched at runtime and a stalled range clears the speed`() {
        val r = readyRig()
        r.engine.setTuning(r.engine.tuning.copy(approachEnabled = true))
        approach(r)
        assertTrue(r.engine.diagnostics(r.now).approachActive)
        r.engine.setTuning(r.engine.tuning.copy(approachEnabled = false))
        assertFalse(r.engine.diagnostics(r.now).approachActive)
        r.rangeAlive = false
        r.run(1_000_000)
        assertNull(r.engine.diagnostics(r.now).closingSpeedMmPerS)
    }
}

/** Feature contract §6: exploration mode. */
class ExploreEngineTest {
    private fun exploring(profile: HearingProfile = HearingProfile.uncalibrated(), tuning: Tuning = Tuning(exploreEnabled = true)) =
        readyRig(EngineConfig(initialProfile = profile, tuning = tuning))

    @Test
    fun `pulses every 120 ms with linear intensity, no band cues, no spearcons, no phone audio`() {
        val r = exploring()
        r.obstacle(600) // 30 + (225 * 600 / 1200) = 142.5 → 143 (round half up)
        val t0 = r.now
        r.run(1_000_000)
        val cues = r.cues.filter { it.first >= t0 }
        assertTrue(cues.size in 7..9, "one per 120 ms: ${cues.size}")
        assertTrue(cues.all {
            it.second.hapticPattern == Haptic.PULSE40 && it.second.side == Side.BOTH &&
                it.second.hapticL == 143 && it.second.hapticR == 143
        }, cues.map { it.second }.toString())
        assertTrue(r.sink.audible().none { it.second.cueId in CueId.OBSTACLE_FAR..CueId.OBSTACLE_NEAR })
        assertEquals(emptyList<String>(), r.spoken.filter { it.startsWith("obstacle") })
        assertTrue(r.engine.diagnostics(r.now).exploring)
    }

    @Test
    fun `intensity runs from the minimum at exploreMaxMm to 255 at 0 mm`() {
        val r = exploring()
        r.obstacle(1200)
        val a = r.now; r.run(300_000)
        assertTrue(r.cues.filter { it.first >= a }.all { it.second.hapticL == 30 })
        r.obstacle(0)
        val b = r.now; r.run(300_000)
        assertTrue(r.cues.filter { it.first >= b + 100_000 }.all { it.second.hapticL == 255 })
    }

    @Test
    fun `intensity is clamped to haptic_max`() {
        val r = exploring(HearingProfile.uncalibrated().copy(hapticMax = 100))
        r.obstacle(0)
        val t0 = r.now
        r.run(500_000)
        val cues = r.cues.filter { it.first >= t0 }
        assertTrue(cues.isNotEmpty() && cues.all { it.second.hapticL == 100 && it.second.hapticR == 100 })
    }

    @Test
    fun `no target and beyond exploreMaxMm are silent`() {
        val r = exploring()
        r.obstacle(null)
        r.run(300_000)
        val t0 = r.now
        r.run(1_000_000)
        assertTrue(r.cues.none { it.first >= t0 }, "NO_TARGET is not a reason to buzz")
        assertFalse(r.engine.diagnostics(r.now).exploring)
        r.obstacle(1500)
        val t1 = r.now
        r.run(1_000_000)
        assertTrue(r.cues.none { it.first >= t1 })
        // tighter reach set at runtime
        r.engine.setTuning(r.engine.tuning.copy(exploreMaxMm = 600))
        r.obstacle(700); val t2 = r.now; r.run(500_000)
        assertTrue(r.cues.none { it.first >= t2 })
        r.obstacle(500); val t3 = r.now; r.run(500_000)
        assertTrue(r.cues.any { it.first >= t3 })
    }

    @Test
    fun `unknown stays degraded and silent, and switching explore off restores band cues`() {
        val r = exploring()
        r.obstacle(500); r.run(500_000)
        r.status = RangeStatus.UNKNOWN
        r.run(300_000)
        assertEquals(SystemState.DEGRADED, r.engine.state)
        val t0 = r.now
        r.run(500_000)
        assertTrue(r.cues.none { it.first >= t0 })
        r.status = RangeStatus.VALID
        r.engine.setTuning(r.engine.tuning.copy(exploreEnabled = false))
        r.run(1_000_000)
        assertTrue(r.obstacleCues(t0).isNotEmpty())
        assertFalse(r.engine.diagnostics(r.now).exploring)
    }
}

/** Feature contract §7: runtime tuning API. */
class TuningApiTest {
    @Test
    fun `validation rejects every out of range value and keeps the previous tuning`() {
        val r = readyRig()
        val base = r.engine.tuning
        val bad = listOf(
            base.copy(nearMm = 0), base.copy(nearMm = 900), base.copy(midMm = 1200), base.copy(farMm = 8001),
            base.copy(bandEnterMs = -1), base.copy(bandExitMs = -1), base.copy(bandHysteresisMm = -1),
            base.copy(farPeriodMs = 0), base.copy(midPeriodMs = 0), base.copy(nearPeriodMs = 0),
            base.copy(announceHoldMs = -1), base.copy(announceRepeatMs = -1),
            base.copy(weakLinkRssiDbm = 1), base.copy(weakLinkRssiDbm = -128), base.copy(weakLinkHoldMs = -1), base.copy(weakLinkRepeatMs = -1),
            base.copy(approachWindowMs = 0), base.copy(approachTtcMs = 0), base.copy(approachMinSpeedMmPerS = 0),
            base.copy(explorePeriodMs = 0), base.copy(exploreMaxMm = 0), base.copy(exploreMaxMm = 8001),
            base.copy(exploreMinIntensity = -1), base.copy(exploreMinIntensity = 256),
        )
        for (t in bad) {
            assertThrows(IllegalArgumentException::class.java, { r.engine.setTuning(t) }, t.toString())
            assertEquals(base, r.engine.tuning)
        }
        r.engine.setTuning(base.copy(farMm = 8000)) // the upper limit itself is fine
        assertEquals(8000, r.engine.tuning.farMm)
    }

    @Test
    fun `an invalid initial tuning is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { Rig(EngineConfig(tuning = Tuning(nearMm = 1000))) }
    }

    @Test
    fun `new thresholds and cadence apply immediately without a restart`() {
        val r = readyRig()
        r.obstacle(700)
        r.run(1_000_000)
        assertEquals(Band.MID, r.alertsOut.last()!!.band)
        r.engine.setTuning(r.engine.tuning.copy(nearMm = 800, midMm = 1000, farMm = 1500, nearPeriodMs = 100))
        r.run(1_500_000) // the 700 mm reading is NEAR now; leaving MID upwards needs no hysteresis wait
        assertEquals(Band.NEAR, r.alertsOut.last()!!.band)
        val t0 = r.now
        r.run(1_000_000)
        val n = r.obstacleCues(t0).size
        assertTrue(n in 9..11, "NEAR every 100 ms: $n")
        assertEquals(SystemState.READY, r.engine.state)
    }

    @Test
    fun `band enter and exit times are tunable`() {
        val r = readyRig(EngineConfig(tuning = Tuning(bandEnterMs = 1000)))
        r.obstacle(700)
        r.run(800_000)
        assertNull(r.alertsOut.lastOrNull())
        r.run(500_000)
        assertEquals(Band.MID, r.alertsOut.last()!!.band)
    }

    @Test
    fun `test haptic needs a synced cue board and goes through the normal cue path`() {
        val cold = Rig()
        assertFalse(cold.engine.sendTestHaptic(Haptic.PULSE40, 100, cold.now), "no cue board known yet")
        val r = readyRig(EngineConfig(initialProfile = HearingProfile.uncalibrated().copy(hapticMax = 120)))
        val sent = r.cues.size
        assertTrue(r.engine.sendTestHaptic(Haptic.BUZZ150, 255, r.now))
        r.run(100_000)
        assertEquals(sent + 1, r.cues.size)
        val c = r.cues.last().second
        assertEquals(Haptic.BUZZ150, c.hapticPattern)
        assertEquals(120, c.hapticL); assertEquals(120, c.hapticR) // haptic_max clamp
        assertEquals(Side.BOTH, c.side)
        assertTrue(c.deadlineBoardUs > 0)
        assertTrue(r.sink.plays.none { it.second.hapticPattern == Haptic.BUZZ150 }, "no phone audio")
        assertFalse(r.engine.sendTestHaptic(Haptic.NONE, 100, r.now))
        assertFalse(r.engine.sendTestHaptic(9, 100, r.now))
        assertTrue(r.engine.sendTestHaptic(Haptic.PULSE40, 900, r.now), "intensity clamps instead of failing")
    }

    @Test
    fun `test haptic is refused when the cue clock is not synced`() {
        val r = readyRig()
        r.cueAlive = false
        r.run(3_000_000)
        assertFalse(r.engine.sendTestHaptic(Haptic.PULSE40, 100, r.now), "clock sync expired with the silent board")
    }
}
