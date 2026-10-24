"""Cue board decision logic vs PROTOCOL.md (CUE/ACK/PROFILE/Watchdog) and the firmware's
cue_logic.c, plus the golden vectors through the codec the simulator imports."""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from boards import (CUE_FLAG_INTERRUPT, ProfileState, Watchdog, clamp_gain, cue_time_check, decide_cue,  # noqa: E402
                    watchdog_gain_db)
from dtwire import dw  # noqa: E402

VECTORS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "protocol", "vectors",
                       "vectors.json")


def cue(cue_id=1, side=dw.SIDE_BOTH, gl=-20, gr=-20, pattern=1, hl=200, hr=200, flags=0, deadline=10_000_000):
    return dw.Cue(0, deadline, cue_id, side, gl, gr, pattern, hl, hr, flags, 150)


class ClampTest(unittest.TestCase):
    def test_clamp_table(self):
        cases = [
            # requested, profile ceiling, applied
            (-20, -10, -20),
            (-10, -10, -10),
            (-9, -10, -10),
            (0, -10, -10),
            (127, -10, -10),         # forged positive gain (Gate G4)
            (-20, -25, -25),         # profile ceiling below the hard cap wins
            (-30, -25, -30),
            (-5, 0, -10),            # a ceiling above the hard cap cannot raise the cap
            (-128, -10, -128),       # mute stays mute
            (-127, -10, -127),
        ]
        for req, ceil, want in cases:
            self.assertEqual(clamp_gain(req, ceil), want, (req, ceil))

    def test_watchdog_gain(self):
        self.assertEqual(watchdog_gain_db(-10), -20)
        self.assertEqual(watchdog_gain_db(-15), -20)
        self.assertEqual(watchdog_gain_db(-30), -30)  # never above the profile ceiling


class TimeCheckTest(unittest.TestCase):
    def test_deadline(self):
        self.assertEqual(cue_time_check(0, 5), dw.ACK_NO_SYNC)
        self.assertEqual(cue_time_check(1000, 999), dw.ACK_PLAYED)
        self.assertEqual(cue_time_check(1000, 1000), dw.ACK_PLAYED)   # "now > deadline" drops
        self.assertEqual(cue_time_check(1000, 1001), dw.ACK_EXPIRED)


class DecideTest(unittest.TestCase):
    def setUp(self):
        self.p = ProfileState()

    def test_played_and_clamped(self):
        d = decide_cue(cue(gl=-20, gr=-20), 1, self.p, False)
        self.assertEqual((d.status, d.play, d.gain_l_db, d.gain_r_db), (dw.ACK_PLAYED, True, -20, -20))
        d = decide_cue(cue(gl=0, gr=-20), 1, self.p, False)
        self.assertEqual((d.status, d.gain_l_db, d.gain_r_db), (dw.ACK_PLAYED_CLAMPED, -10, -20))

    def test_expired_and_nosync_never_play(self):
        d = decide_cue(cue(deadline=100), 101, self.p, False)
        self.assertEqual((d.status, d.play), (dw.ACK_EXPIRED, False))
        d = decide_cue(cue(deadline=0), 1, self.p, False)
        self.assertEqual((d.status, d.play), (dw.ACK_NO_SYNC, False))

    def test_bad_cue_takes_precedence(self):
        for c in (cue(cue_id=12), cue(side=3), cue(pattern=5), cue(cue_id=200, deadline=0)):
            d = decide_cue(c, 1, self.p, False)
            self.assertEqual((d.status, d.play), (dw.ACK_BAD_CUE, False))

    def test_busy_unless_interrupt(self):
        d = decide_cue(cue(), 1, self.p, True)
        self.assertEqual((d.status, d.play), (dw.ACK_BUSY_DROPPED, False))
        d = decide_cue(cue(flags=CUE_FLAG_INTERRUPT), 1, self.p, True)
        self.assertEqual((d.status, d.play), (dw.ACK_PLAYED, True))
        # an expired cue is EXPIRED even when busy (time check comes first, as in firmware)
        self.assertEqual(decide_cue(cue(deadline=1), 2, self.p, True).status, dw.ACK_EXPIRED)

    def test_side_silences_other_channel_and_motor(self):
        d = decide_cue(cue(side=dw.SIDE_L), 1, self.p, False)
        self.assertEqual((d.gain_l_db, d.gain_r_db, d.haptic_l, d.haptic_r), (-20, dw.GAIN_MUTE, 200, 0))
        d = decide_cue(cue(side=dw.SIDE_R), 1, self.p, False)
        self.assertEqual((d.gain_l_db, d.gain_r_db, d.haptic_l, d.haptic_r), (dw.GAIN_MUTE, -20, 0, 200))

    def test_haptic_only_and_haptic_max(self):
        self.assertTrue(self.p.apply(dw.Profile(-25, dw.MODE_HAPTIC_ONLY, 120)))
        d = decide_cue(cue(gl=-30, gr=-30), 1, self.p, False)
        self.assertEqual((d.status, d.gain_l_db, d.gain_r_db, d.haptic_l, d.haptic_r),
                         (dw.ACK_PLAYED, dw.GAIN_MUTE, dw.GAIN_MUTE, 120, 120))
        d = decide_cue(cue(pattern=0), 1, self.p, False)
        self.assertEqual((d.haptic_l, d.haptic_r), (0, 0))

    def test_profile_apply(self):
        self.assertTrue(self.p.apply(dw.Profile(-5, dw.MODE_SSD, 200)))
        self.assertEqual((self.p.ceiling_db, self.p.mode, self.p.haptic_max), (-10, dw.MODE_SSD, 200))
        self.assertTrue(self.p.apply(dw.Profile(-30, dw.MODE_STEREO, 255)))
        self.assertEqual(self.p.ceiling_db, -30)
        self.assertEqual(decide_cue(cue(gl=-20, gr=-35), 1, self.p, False).gain_l_db, -30)
        self.assertFalse(self.p.apply(dw.Profile(-12, 3, 1)))
        self.assertEqual((self.p.ceiling_db, self.p.mode), (-30, dw.MODE_STEREO))  # unchanged on reject


class WatchdogTest(unittest.TestCase):
    S = 1_000_000

    def test_boot_grace_then_period(self):
        w = Watchdog()
        self.assertFalse(w.update(4_999_999))
        self.assertTrue(w.update(5 * self.S))         # none within 5 s of boot → alarm on entry
        self.assertTrue(w.active)
        self.assertFalse(w.update(9_999_999))
        self.assertTrue(w.update(10 * self.S))        # every 5 s
        self.assertFalse(w.update(10 * self.S + 1))

    def test_ping_timeout_800ms_and_exit_on_ping(self):
        w = Watchdog()
        w.on_ping(1 * self.S)
        self.assertFalse(w.update(1 * self.S + 799_999))
        self.assertTrue(w.update(1 * self.S + 800_000))
        self.assertTrue(w.active)
        w.on_ping(2 * self.S)
        self.assertFalse(w.active)
        self.assertFalse(w.update(2 * self.S + 100))
        # a fresh starvation re-enters and alarms immediately, not 5 s after the previous alarm
        self.assertTrue(w.update(2 * self.S + 800_000))

    def test_regular_pings_never_alarm(self):
        w = Watchdog()
        t = 0
        for _ in range(200):
            w.on_ping(t)
            t += 100_000
            self.assertFalse(w.update(t))

    def test_next_check(self):
        w = Watchdog()
        self.assertEqual(w.next_check_us(1), 5 * self.S)
        w.on_ping(7 * self.S)
        self.assertEqual(w.next_check_us(7 * self.S), 7_800_000)
        w.update(7_800_000)
        self.assertEqual(w.next_check_us(7_800_000), 12_800_000)


class VectorsTest(unittest.TestCase):
    def test_golden_vectors_round_trip(self):
        with open(VECTORS) as f:
            items = json.load(f)
        self.assertGreater(len(items), 0)
        for v in items:
            pkt = bytes.fromhex(v["hex"])
            seq, msg = dw.decode(pkt)
            self.assertEqual(seq, v["seq"], v["name"])
            self.assertEqual(type(msg).__name__.upper(), v["type"], v["name"])
            self.assertEqual(dw.as_dict(msg), v["fields"], v["name"])
            self.assertEqual(dw.encode(msg, seq), pkt, v["name"])


if __name__ == "__main__":
    unittest.main()
