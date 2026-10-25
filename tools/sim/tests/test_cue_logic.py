"""Cue board decision logic vs PROTOCOL.md v2 (CUE/ACK/PROFILE/Watchdog) and the firmware's
cue_logic.c, plus the golden vectors through the codec the simulator imports. The board is
haptic-only: there is no gain, ceiling or audio logic to test."""
import json
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from boards import (CUE_FLAG_INTERRUPT, ProfileState, Watchdog, clamp_haptic, cue_time_check,  # noqa: E402
                    decide_cue)
from dtwire import dw  # noqa: E402

VECTORS = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "..", "protocol", "vectors",
                       "vectors.json")


def cue(cue_id=1, side=dw.SIDE_BOTH, pattern=1, hl=200, hr=200, flags=0, deadline=10_000_000):
    return dw.Cue(0, deadline, cue_id, side, pattern, hl, hr, flags, 150)


class ClampTest(unittest.TestCase):
    def test_clamp_table(self):
        for req, mx, want in [(200, 255, 200), (255, 255, 255), (255, 180, 180), (180, 180, 180),
                              (181, 180, 180), (0, 180, 0), (255, 1, 1)]:
            self.assertEqual(clamp_haptic(req, mx), want, (req, mx))


class TimeCheckTest(unittest.TestCase):
    def test_deadline(self):
        self.assertEqual(cue_time_check(0, 5), dw.ACK_NO_SYNC)
        self.assertEqual(cue_time_check(1000, 999), dw.ACK_APPLIED)
        self.assertEqual(cue_time_check(1000, 1000), dw.ACK_APPLIED)   # "now > deadline" drops
        self.assertEqual(cue_time_check(1000, 1001), dw.ACK_EXPIRED)


class DecideTest(unittest.TestCase):
    def setUp(self):
        self.p = ProfileState()

    def test_applied_and_clamped(self):
        d = decide_cue(cue(hl=200, hr=200), 1, self.p, False)
        self.assertEqual((d.status, d.fire, d.haptic_l, d.haptic_r), (dw.ACK_APPLIED, True, 200, 200))
        self.assertTrue(self.p.apply(dw.Profile(dw.MODE_STEREO, 120)))
        d = decide_cue(cue(hl=255, hr=100), 1, self.p, False)
        self.assertEqual((d.status, d.haptic_l, d.haptic_r), (dw.ACK_APPLIED_CLAMPED, 120, 100))
        d = decide_cue(cue(hl=120, hr=50), 1, self.p, False)
        self.assertEqual(d.status, dw.ACK_APPLIED)  # at the ceiling is not clamped

    def test_expired_and_nosync_never_fire(self):
        d = decide_cue(cue(deadline=100), 101, self.p, False)
        self.assertEqual((d.status, d.fire), (dw.ACK_EXPIRED, False))
        d = decide_cue(cue(deadline=0), 1, self.p, False)
        self.assertEqual((d.status, d.fire), (dw.ACK_NO_SYNC, False))

    def test_bad_cue_takes_precedence(self):
        for c in (cue(cue_id=12), cue(side=3), cue(pattern=5), cue(cue_id=200, deadline=0)):
            d = decide_cue(c, 1, self.p, False)
            self.assertEqual((d.status, d.fire), (dw.ACK_BAD_CUE, False))

    def test_busy_unless_interrupt(self):
        d = decide_cue(cue(), 1, self.p, True)
        self.assertEqual((d.status, d.fire), (dw.ACK_BUSY_DROPPED, False))
        d = decide_cue(cue(flags=CUE_FLAG_INTERRUPT), 1, self.p, True)
        self.assertEqual((d.status, d.fire), (dw.ACK_APPLIED, True))
        # an expired cue is EXPIRED even when busy (time check comes first, as in firmware)
        self.assertEqual(decide_cue(cue(deadline=1), 2, self.p, True).status, dw.ACK_EXPIRED)

    def test_side_silences_the_other_motor(self):
        d = decide_cue(cue(side=dw.SIDE_L), 1, self.p, False)
        self.assertEqual((d.haptic_l, d.haptic_r), (200, 0))
        d = decide_cue(cue(side=dw.SIDE_R), 1, self.p, False)
        self.assertEqual((d.haptic_l, d.haptic_r), (0, 200))

    def test_pattern_none_is_silent_and_not_clamped(self):
        self.assertTrue(self.p.apply(dw.Profile(dw.MODE_STEREO, 50)))
        d = decide_cue(cue(pattern=0, hl=255, hr=255), 1, self.p, False)
        self.assertEqual((d.status, d.haptic_l, d.haptic_r), (dw.ACK_APPLIED, 0, 0))

    def test_mode_never_changes_the_board_output(self):
        # The board is haptic-only whatever the phone's audio profile says.
        outs = []
        for mode in (dw.MODE_STEREO, dw.MODE_SSD, dw.MODE_HAPTIC_ONLY):
            p = ProfileState()
            self.assertTrue(p.apply(dw.Profile(mode, 200)))
            d = decide_cue(cue(), 1, p, False)
            outs.append((d.status, d.haptic_l, d.haptic_r))
        self.assertEqual(len(set(outs)), 1)

    def test_profile_apply_and_reject(self):
        self.assertTrue(self.p.apply(dw.Profile(dw.MODE_SSD, 200)))
        self.assertEqual((self.p.mode, self.p.haptic_max), (dw.MODE_SSD, 200))
        self.assertFalse(self.p.apply(dw.Profile(3, 1)))          # unknown mode
        self.assertEqual((self.p.mode, self.p.haptic_max), (dw.MODE_SSD, 200))  # unchanged on reject

    def test_haptic_max_zero_is_rejected_and_keeps_the_previous_profile(self):
        # With no board audio the haptics are the last board-local failure channel: they cannot be
        # configured away (PROTOCOL.md PROFILE).
        self.assertTrue(self.p.apply(dw.Profile(dw.MODE_STEREO, 90)))
        self.assertFalse(self.p.apply(dw.Profile(dw.MODE_STEREO, 0)))
        self.assertEqual(self.p.haptic_max, 90)
        self.assertTrue(self.p.apply(dw.Profile(dw.MODE_STEREO, 1)))   # 1 is the lowest legal ceiling
        self.assertEqual(self.p.haptic_max, 1)
        self.assertFalse(dw.Profile(dw.MODE_STEREO, 0).valid())
        self.assertTrue(dw.Profile(dw.MODE_HAPTIC_ONLY, 255).valid())


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

    def test_vectors_are_v2_and_carry_no_gain_or_grid_fields(self):
        with open(VECTORS) as f:
            items = json.load(f)
        names = {k for v in items for k in v["fields"]}
        self.assertFalse({k for k in names if "gain" in k or k in ("res", "ceiling_db")}, names)
        for v in items:
            self.assertEqual(bytes.fromhex(v["hex"])[2], 2, v["name"])  # header ver = 2
        want_len = {"RANGE": 19, "CUE": 32, "ACK": 22, "PROFILE": 10}
        for v in items:
            if v["type"] in want_len:
                self.assertEqual(len(bytes.fromhex(v["hex"])), want_len[v["type"]], v["name"])
            if v["type"] == "RANGE":
                self.assertIsInstance(v["fields"]["mm"], int)
        statuses = {v["fields"]["status"] for v in items if v["type"] == "RANGE"}
        self.assertEqual(statuses, {dw.RANGE_VALID, dw.RANGE_NO_TARGET, dw.RANGE_UNKNOWN})
        for v in items:
            if v["type"] == "PROFILE":
                self.assertGreaterEqual(v["fields"]["haptic_max"], dw.HAPTIC_MAX_MIN, v["name"])


if __name__ == "__main__":
    unittest.main()
