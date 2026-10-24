import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from phonelib import ClockSync  # noqa: E402
from simclock import BoardClock  # noqa: E402


class FakeHost:
    def __init__(self, t=1_000_000_000):
        self.t = t

    def __call__(self):
        return self.t


class BoardClockTest(unittest.TestCase):
    def test_boot_age_and_offset(self):
        host = FakeHost(50_000_000_000)
        c = BoardClock(2.5, 0.0, host)
        self.assertEqual(c.now(), 2_500_000)
        self.assertEqual(c.offset_at(host.t), 2_500_000 - 50_000_000_000)
        host.t += 1_000_000
        self.assertEqual(c.now(), 3_500_000)

    def test_drift_accumulates_at_ppm_rate(self):
        host = FakeHost()
        c = BoardClock(1.0, 40.0, host)
        o0 = c.offset_at(host.t)
        self.assertEqual(c.offset_at(host.t + 10_000_000) - o0, 400)    # 40 ppm × 10 s
        self.assertEqual(c.offset_at(host.t + 100_000_000) - o0, 4000)
        neg = BoardClock(1.0, -25.0, host)
        self.assertEqual(neg.offset_at(host.t + 10_000_000) - neg.offset_at(host.t), -250)

    def test_inverse(self):
        host = FakeHost()
        c = BoardClock(3.0, 35.0, host)
        for dt in (0, 1, 123_456, 9_876_543_210):
            h = host.t + dt
            self.assertLessEqual(abs(c.host_at(c.at(h)) - h), 1)

    def test_distinct_boards(self):
        host = FakeHost()
        a, b = BoardClock(2.5, 35, host), BoardClock(4.2, -22, host)
        self.assertNotEqual(a.offset_at(host.t), b.offset_at(host.t))

    def test_negative_boot_age_rejected(self):
        with self.assertRaises(ValueError):
            BoardClock(-1, 0, FakeHost())


class ClockSyncTest(unittest.TestCase):
    def exchange(self, clock: BoardClock, t_phone: int, up_us: int, proc_us: int, down_us: int):
        rx = clock.at(t_phone + up_us)
        tx = clock.at(t_phone + up_us + proc_us)
        t4 = t_phone + up_us + proc_us + down_us
        return t_phone, rx, tx, t4

    def test_symmetric_path_recovers_offset_exactly(self):
        host = FakeHost()
        c = BoardClock(2.5, 0, host)
        s = ClockSync()
        off, rtt = s.add(*self.exchange(c, host.t, 3000, 400, 3000))
        self.assertEqual(off, c.offset_at(host.t))
        self.assertEqual(rtt, 6000)

    def test_asymmetry_error_is_half_the_difference(self):
        host = FakeHost()
        c = BoardClock(2.5, 0, host)
        off, _ = ClockSync().add(*self.exchange(c, host.t, 9000, 0, 1000))
        self.assertEqual(off - c.offset_at(host.t), 4000)

    def test_lowest_rtt_sample_wins_and_window_slides(self):
        host = FakeHost()
        c = BoardClock(2.5, 0, host)
        s = ClockSync(window=4)
        t = host.t
        s.add(*self.exchange(c, t, 500, 100, 500))            # good sample
        for k in range(3):
            s.add(*self.exchange(c, t + (k + 1) * 100_000, 20_000, 100, 2_000))  # congested, asymmetric
        self.assertEqual(s.offset, c.offset_at(t))
        self.assertEqual(s.rtt, 1000)
        s.add(*self.exchange(c, t + 500_000, 20_000, 100, 2_000))  # pushes the good sample out
        self.assertNotEqual(s.offset, c.offset_at(t))
        self.assertEqual(s.rtt, 22_000)

    def test_impossible_samples_ignored(self):
        s = ClockSync()
        s.add(1000, 50, 40, 2000)  # tx < rx
        self.assertFalse(s.synced)
        s.add(1000, 50, 60, 900)   # t4 < t_phone → negative rtt
        self.assertFalse(s.synced)

    def test_drift_estimate(self):
        host = FakeHost()
        c = BoardClock(2.5, 50.0, host)
        s = ClockSync(window=1)  # every sample is "best": history follows the true offset
        self.assertIsNone(s.drift_ppm())
        for k in range(60):
            s.add(*self.exchange(c, host.t + k * 100_000, 1000, 100, 1000))
        self.assertAlmostEqual(s.drift_ppm(), 50.0, delta=2.0)

    def test_board_time(self):
        s = ClockSync()
        self.assertIsNone(s.board_time(5))
        s.add(1_000, 501_000, 501_000, 2_000)
        self.assertEqual(s.board_time(10_000), 10_000 + s.offset)


if __name__ == "__main__":
    unittest.main()
