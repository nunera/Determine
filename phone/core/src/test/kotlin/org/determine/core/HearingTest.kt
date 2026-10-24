package org.determine.core

import org.determine.core.hearing.CalStep
import org.determine.core.hearing.CalibrationSession
import org.determine.core.hearing.Ear
import org.determine.core.hearing.HearingMode
import org.determine.core.hearing.HearingProfile
import org.determine.core.hearing.Staircase
import org.determine.core.wire.CueId
import org.determine.core.wire.GAIN_MUTE
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.math.abs
import kotlin.math.exp
import kotlin.random.Random

class StaircaseTest {
    @Test
    fun `converges on a deterministic listener and stops after 8 reversals`() {
        val s = Staircase(startDb = -60, ceilingDb = -10)
        while (!s.done) s.answer(s.levelDb >= -37)
        assertEquals(8, s.reversalLevels.size)
        val t = s.thresholdDb!!
        assertTrue(abs(t - (-37)) <= 2, "threshold $t")
    }

    @Test
    fun `converges near the 70_7 percent point of a noisy listener`() {
        // logistic psychometric function, 50 % at -40 dB, slope 1 dB; 70.7 % point ≈ -39.1 dB
        val errs = (1..40).map { seed ->
            val rnd = Random(seed)
            val s = Staircase(startDb = -60, ceilingDb = -10, targetReversals = 8)
            while (!s.done) s.answer(rnd.nextDouble() < 1 / (1 + exp(-(s.levelDb + 40.0))))
            s.thresholdDb!! - (-39.1)
        }
        assertTrue(abs(errs.average()) < 2.0, "mean error ${errs.average()}")
    }

    @Test
    fun `never steps above the ceiling - not heard at ceiling is unusable`() {
        val s = Staircase(startDb = -30, ceilingDb = -20)
        var maxLevel = s.levelDb
        while (!s.done) { s.answer(false); maxLevel = maxOf(maxLevel, s.levelDb) }
        assertEquals(-20, maxLevel)
        assertTrue(s.unusable)
        assertNull(s.thresholdDb)
    }

    @Test
    fun `reversal count must be 6 to 8`() {
        assertThrows<IllegalArgumentException> { Staircase(ceilingDb = -10, targetReversals = 5) }
        val s = Staircase(startDb = -60, ceilingDb = -10, targetReversals = 6)
        while (!s.done) s.answer(s.levelDb >= -45)
        assertEquals(6, s.reversalLevels.size)
    }

    @Test
    fun `listener hearing everything ends at floor, usable`() {
        val s = Staircase(startDb = -60, ceilingDb = -10, floorDb = -90)
        while (!s.done) s.answer(true)
        assertEquals(-90, s.thresholdDb)
    }
}

class CalibrationSessionTest {
    /** Simulated listener: deterministic per-ear thresholds; null = deaf on that side. */
    private fun run(thrL: Int?, thrR: Int?, sideAnswer: (Ear) -> Ear = { it }, ceiling: Int = -14): Pair<CalibrationSession, List<CueCommand>> {
        val sent = mutableListOf<CueCommand>()
        val cal = CalibrationSession(ceiling, { sent += it }, Random(7))
        cal.setFit("glasses-1", Ear.RIGHT)
        while (cal.step == CalStep.THRESHOLD) {
            val thr = if (cal.ear == Ear.LEFT) thrL else thrR
            cal.answerHeard(thr != null && cal.currentLevelDb!! >= thr)
        }
        cal.setSensationLevel(12)
        if (cal.step == CalStep.BALANCE) { cal.nudgeBalance(2); cal.confirmBalance(); cal.nudgeBalance(2); cal.confirmBalance() }
        while (cal.step == CalStep.VERIFY) {
            val last = sent.last()
            cal.answerSide(sideAnswer(if (last.side == 0) Ear.LEFT else Ear.RIGHT))
        }
        return cal to sent
    }

    @Test
    fun `stereo when both sides heard and sides named`() {
        val (cal, sent) = run(-50, -40)
        val p = cal.result()!!
        assertEquals(HearingMode.STEREO, p.mode)
        assertEquals(5, p.verifiedCorrect)
        assertTrue(abs(p.bands[0].thrDbL!! - (-50)) <= 2)
        assertTrue(abs(p.bands[2].thrDbR!! - (-40)) <= 2)
        assertEquals(12, p.bands[0].slDb)
        assertEquals(-1, p.bands[0].offsetDbL); assertEquals(2, p.bands[0].offsetDbR) // balance (2+4)/2=3 → R−L = 3
        assertTrue(sent.all { it.ttlMs == 1000 })
        assertTrue(sent.all { maxOf(it.gainLDb, it.gainRDb) <= -14 })
        assertTrue(sent.any { it.cueId == CueId.CAL_TONE_500 } && sent.any { it.cueId == CueId.CAL_TONE_2K })
        // one side at a time during thresholds
        assertTrue(sent.filter { it.cueId in 8..10 }.all { (it.gainLDb == GAIN_MUTE) != (it.gainRDb == GAIN_MUTE) })
    }

    @Test
    fun `ssd when one side cannot hear at the ceiling`() {
        val (cal, sent) = run(-50, null)
        val p = cal.result()!!
        assertEquals(HearingMode.SSD, p.mode)
        assertTrue(p.bands.all { it.thrDbR == null })
        assertNull(p.levelDb(0, Ear.RIGHT))
        assertTrue(sent.all { maxOf(it.gainLDb, it.gainRDb) <= -14 })
    }

    @Test
    fun `ssd when side naming is at chance`() {
        val (cal, _) = run(-50, -45, sideAnswer = { Ear.LEFT })
        assertEquals(HearingMode.SSD, cal.result()!!.mode)
    }

    @Test
    fun `ssd when a side needs the ceiling even if named correctly`() {
        // threshold ≈ -24 + sl 12 ≈ -12 ≥ ceiling -14
        val (cal, _) = run(-50, -24)
        assertEquals(HearingMode.SSD, cal.result()!!.mode)
    }

    @Test
    fun `haptic only when deaf both sides or chosen`() {
        assertEquals(HearingMode.HAPTIC_ONLY, run(null, null).first.result()!!.mode)
        val cal = CalibrationSession(-14, {})
        cal.chooseHapticOnly()
        assertEquals(HearingMode.HAPTIC_ONLY, cal.result()!!.mode)
    }

    @Test
    fun `sensation level bounded and levels clamp to ceiling`() {
        val cal = CalibrationSession(-14, {})
        cal.setFit("x", null)
        while (cal.step == CalStep.THRESHOLD) cal.answerHeard(cal.currentLevelDb!! >= -30)
        assertThrows<IllegalArgumentException> { cal.setSensationLevel(21) }
        assertThrows<IllegalArgumentException> { cal.setSensationLevel(9) }
        val p = HearingProfile.uncalibrated(-14)
        assertTrue(p.levelDb(1, Ear.LEFT)!! <= -14)
        assertEquals(-10, HearingProfile.uncalibrated(0).effectiveCeilingDb)
    }
}
