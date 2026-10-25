package org.determine.android.audio

import org.determine.app.audio.CueBank
import org.determine.core.CueCommand
import org.determine.core.GAIN_MUTE
import org.determine.core.wire.AckStatus
import org.determine.core.wire.Haptic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CueBankTest {
    private fun cmd(
        id: Int = CueBank.OBSTACLE_MID, side: Int = CueBank.SIDE_BOTH, gl: Int = -30, gr: Int = -30, flags: Int = 0,
    ) = CueCommand(id, side, gl, gr, Haptic.NONE, 0, 0, flags, 150)

    @Test
    fun `every cue obeys the Arch 8-1 timing rules`() {
        for ((id, c) in CueBank.CUES.withIndex()) assertNull("cue $id ${c.name}", CueBank.timingViolation(c))
        assertEquals(12, CueBank.CUES.size) // ids 0..11 keep their phone sound semantics
    }

    @Test
    fun `gains clamp to the lower of the profile ceiling and the hard ceiling, mute stays mute`() {
        assertEquals(-10, CueBank.clampGain(0, 0))
        assertEquals(-20, CueBank.clampGain(-5, -20))
        assertEquals(-25, CueBank.clampGain(-25, -20))
        assertEquals(GAIN_MUTE, CueBank.clampGain(GAIN_MUTE, -20))
        assertEquals(0, CueBank.dbToQ15(GAIN_MUTE))
        assertEquals(32768, CueBank.dbToQ15(5)) // never boosts
    }

    @Test
    fun `clamping a requested gain is reported as APPLIED_CLAMPED, otherwise APPLIED`() {
        assertEquals(AckStatus.APPLIED, CueBank.decide(cmd(gl = -30, gr = -30), -20, false).status)
        assertEquals(AckStatus.APPLIED_CLAMPED, CueBank.decide(cmd(gl = -5, gr = -30), -20, false).status)
    }

    @Test
    fun `per-side ceiling and side routing, calibration tones included`() {
        val both = CueBank.decide(cmd(gl = -40, gr = -22), -30, false)
        assertEquals(-40, both.gainLDb); assertEquals(-30, both.gainRDb) // each side clamped on its own
        val leftOnly = CueBank.decide(cmd(id = CueBank.CAL_TONE_1K, side = CueBank.SIDE_L, gl = -40, gr = -40), -20, false)
        assertEquals(-40, leftOnly.gainLDb); assertEquals(GAIN_MUTE, leftOnly.gainRDb)
        val rightOnly = CueBank.decide(cmd(id = CueBank.CAL_TONE_1K, side = CueBank.SIDE_R, gl = -40, gr = -40), -20, false)
        assertEquals(GAIN_MUTE, rightOnly.gainLDb); assertEquals(-40, rightOnly.gainRDb)
    }

    @Test
    fun `commanded gains are authoritative - a muted command is not audible`() {
        val muted = CueBank.decide(cmd(gl = GAIN_MUTE, gr = GAIN_MUTE), -20, false)
        assertTrue(muted.play); assertFalse(muted.audible)
        assertTrue(CueBank.decide(cmd(gl = -30, gr = GAIN_MUTE), -20, false).audible)
    }

    @Test
    fun `bad ids and sides are rejected, busy drops unless interrupting`() {
        assertEquals(AckStatus.BAD_CUE, CueBank.decide(cmd(id = 12), -20, false).status)
        assertEquals(AckStatus.BAD_CUE, CueBank.decide(cmd(side = 3), -20, false).status)
        assertEquals(AckStatus.BUSY_DROPPED, CueBank.decide(cmd(), -20, true).status)
        val forced = CueBank.decide(cmd(flags = CueBank.FLAG_INTERRUPT), -20, true)
        assertTrue(forced.play)
    }

    @Test
    fun `the player renders a note window and then finishes`() {
        val rate = 48_000
        val p = CueBank.Player(CueBank.cue(CueBank.OBSTACLE_NEAR)!!, rate, CueBank.dbToQ15(-30), CueBank.dbToQ15(-30))
        val buf = ShortArray(240 * 2)
        var frames = 0
        var peak = 0
        while (!p.done) {
            val n = p.render(buf, 240)
            frames += n
            for (i in 0 until n * 2) peak = maxOf(peak, kotlin.math.abs(buf[i].toInt()))
        }
        assertEquals(CueBank.windowSamples(90, rate), frames)
        assertTrue("audible", peak > 100)
        assertTrue("under -30 dBFS + margin", peak < 32768 * 0.05)
    }
}
