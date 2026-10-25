import math
import os
import random
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import scenarios  # noqa: E402
import scene  # noqa: E402
from dtwire import BAND_FAR_MM, BAND_MID_MM, BAND_NEAR_MM, band_of, dw  # noqa: E402
from scene import Box, in_cone, measure  # noqa: E402

H = scene.SENSOR_HEIGHT_M


def wall(x, y=0.0, w=0.4, zb=H - 0.2, zt=H + 0.2):
    return Box(x_m=x, y_m=y, z_bottom_m=zb, z_top_m=zt, width_m=w, depth_m=0.05)


class ConeTest(unittest.TestCase):
    def test_fov_is_the_vl53l4cd_18_degrees(self):
        self.assertAlmostEqual(scene.FOV_DIAG_DEG, 18.0)
        self.assertEqual(scene.RANGE_LIMIT_MM, 1200)

    def test_box_straight_ahead_is_in_the_cone(self):
        self.assertTrue(in_cone(wall(1.0)))

    def test_box_beside_the_beam_is_invisible(self):
        # 0.5 m wide at 1 m: its inner edge is 0.4 m off-axis (21.8°) — outside the 9° half-angle.
        self.assertFalse(in_cone(wall(1.0, y=0.65, w=0.5)))
        self.assertFalse(in_cone(wall(1.0, y=-0.65, w=0.5)))

    def test_box_edge_just_inside_the_half_angle_is_seen(self):
        edge = math.tan(math.radians(9.0)) * 1.0
        self.assertTrue(in_cone(wall(1.0, y=edge - 0.01 + 0.25, w=0.5)))
        self.assertFalse(in_cone(wall(1.0, y=edge + 0.01 + 0.25, w=0.5)))

    def test_box_outside_beam_height_is_invisible(self):
        self.assertFalse(in_cone(wall(1.0, zb=0.0, zt=1.0)))   # below the sensor
        self.assertFalse(in_cone(wall(1.0, zb=2.0, zt=3.0)))   # above the sensor


class MeasureTest(unittest.TestCase):
    def test_valid_reading_is_the_nearest_forward_distance(self):
        mm, st = measure([wall(0.9), wall(0.6)], noise=False)
        self.assertEqual((mm, st), (600, dw.RANGE_VALID))

    def test_nothing_in_the_beam_is_no_target_with_mm_zero(self):
        self.assertEqual(measure([], noise=False), (0, dw.RANGE_NO_TARGET))
        self.assertEqual(measure([wall(1.0, y=1.0)], noise=False), (0, dw.RANGE_NO_TARGET))
        self.assertEqual(measure([wall(1.0, zb=0.0, zt=1.0)], noise=False), (0, dw.RANGE_NO_TARGET))

    def test_beyond_the_envelope_is_no_target(self):
        self.assertEqual(measure([wall(1.5)], noise=False), (0, dw.RANGE_NO_TARGET))
        self.assertEqual(measure([wall(1.2)], noise=False), (1200, dw.RANGE_VALID))

    def test_explicit_status_override_wins(self):
        self.assertEqual(measure([wall(0.5)], noise=False, invalid_status=dw.RANGE_NO_TARGET),
                         (0, dw.RANGE_NO_TARGET))
        for objs in ([], [wall(0.5)]):
            self.assertEqual(measure(objs, noise=False, invalid_status=dw.RANGE_UNKNOWN), (0, dw.RANGE_UNKNOWN))

    def test_noise_is_seeded_and_small(self):
        a = measure([wall(0.8)], random.Random(7))
        b = measure([wall(0.8)], random.Random(7))
        self.assertEqual(a, b)
        self.assertLess(abs(a[0] - 800), 40)
        self.assertEqual(a[1], dw.RANGE_VALID)

    def test_range_message_is_the_19_byte_scalar(self):
        mm, st = measure([wall(0.8)], random.Random(1))
        pkt = dw.encode(dw.Range(123, mm, st), 5)
        self.assertEqual(len(pkt), 19)
        self.assertEqual(dw.decode(pkt)[1], dw.Range(123, mm, st))


class BandTest(unittest.TestCase):
    def test_edges(self):
        self.assertEqual((BAND_NEAR_MM, BAND_MID_MM, BAND_FAR_MM), (500, 900, 1200))
        cases = [(1, "NEAR"), (499, "NEAR"), (500, "MID"), (899, "MID"), (900, "FAR"), (1200, "FAR"),
                 (1201, None), (0, None)]
        for mm, want in cases:
            self.assertEqual(band_of(mm), want, mm)

    def test_only_valid_readings_have_a_band(self):
        self.assertIsNone(band_of(700, dw.RANGE_NO_TARGET))
        self.assertIsNone(band_of(700, dw.RANGE_UNKNOWN))


class ScenarioTest(unittest.TestCase):
    def read(self, s, t):
        return measure(s.objects(t), noise=False, invalid_status=s.invalid_status(t, random.Random(0)))

    def test_looped_invalid_range_recovers_and_repeats(self):
        s = scenarios.looped(scenarios.get("approach_then_unknown"))
        for lap in (0, 1):
            for t, expected in ((s.marks["glass_from"] - 0.01, dw.RANGE_VALID),
                                (s.marks["glass_from"], dw.RANGE_UNKNOWN),
                                (s.marks["glass_to"], dw.RANGE_VALID)):
                with self.subTest(lap=lap, time=t):
                    self.assertEqual(self.read(s, lap * s.duration_s + t)[1], expected)

    def test_all_scenarios_deterministic(self):
        for name in scenarios.SCENARIOS:
            a, b = scenarios.get(name, 3), scenarios.get(name, 3)
            for t in (0.0, 3.5, 5.0, 7.0):
                self.assertEqual(measure(a.objects(t), random.Random(2), invalid_status=a.invalid_status(t, random.Random(1))),
                                 measure(b.objects(t), random.Random(2), invalid_status=b.invalid_status(t, random.Random(1))),
                                 name)


    def test_approach_centre_crosses_the_bands_in_order(self):
        s = scenarios.get("approach_centre")
        m = s.marks
        self.assertEqual(self.read(s, 1.0), (0, dw.RANGE_NO_TARGET))  # 1.8 m: beyond the envelope
        mm, st = self.read(s, m["far_at"] + 0.02)
        self.assertEqual((band_of(mm, st)), "FAR")
        mm, st = self.read(s, m["mid_at"] + 0.02)
        self.assertEqual((band_of(mm, st)), "MID")
        mm, st = self.read(s, m["near_at"] + 0.02)
        self.assertEqual((band_of(mm, st)), "NEAR")
        mm, st = self.read(s, m["motion_end"] + 0.5)
        self.assertEqual((mm, st), (350, dw.RANGE_VALID))
        self.assertEqual(self.read(s, m["hold_end"] + 0.1), (0, dw.RANGE_NO_TARGET))
        self.assertAlmostEqual(m["motion_end"] - m["motion_start"], (1.8 - 0.35) / scenarios.APPROACH_MPS, places=6)

    def test_idle_open_space_is_always_no_target(self):
        s = scenarios.get("idle_open_space")
        for t in (0, 2, 4, 6, 8):
            self.assertEqual(self.read(s, t), (0, dw.RANGE_NO_TARGET))

    def test_hover_boundary_oscillates_around_900_mm(self):
        s = scenarios.get("hover_boundary")
        readings = [self.read(s, 1.0 + i * 0.01) for i in range(200)]
        self.assertTrue(all(st == dw.RANGE_VALID for _, st in readings))
        mms = [mm for mm, _ in readings]
        self.assertTrue(870 <= min(mms) <= 872 and 928 <= max(mms) <= 930, (min(mms), max(mms)))
        # 3 Hz: the target crosses 900 mm about six times a second (2 s sampled here)
        crossings = sum((a - 900) * (b - 900) < 0 for a, b in zip(mms, mms[1:]))
        self.assertGreaterEqual(crossings, 8)

    def test_glass_unknown_transitions_from_valid_distance_to_unknown(self):
        s = scenarios.get("glass_unknown")
        g = s.marks["glass_from"]
        self.assertEqual(self.read(s, g - 0.5), (1000, dw.RANGE_VALID))
        self.assertEqual(self.read(s, g + 0.5), (0, dw.RANGE_UNKNOWN))

    def test_approach_then_unknown_window(self):
        s = scenarios.get("approach_then_unknown")
        g, g2 = s.marks["glass_from"], s.marks["glass_to"]
        mm, st = self.read(s, g - 0.05)
        self.assertEqual(band_of(mm, st), "MID")
        self.assertEqual(self.read(s, g + 0.1), (0, dw.RANGE_UNKNOWN))
        self.assertEqual(self.read(s, g2 - 0.1), (0, dw.RANGE_UNKNOWN))
        mm, st = self.read(s, g2 + 0.1)
        self.assertEqual((mm, st), (700, dw.RANGE_VALID))

    def test_person_steps_out_of_the_beam(self):
        s = scenarios.get("person_ahead")
        m = s.marks
        mm, st = self.read(s, m["stop"] + 0.2)
        self.assertEqual((mm, st), (400, dw.RANGE_VALID))
        self.assertEqual(self.read(s, m["gone"] + 0.2), (0, dw.RANGE_NO_TARGET))

    def test_dropout_windows(self):
        s = scenarios.get("cue_board_dropout")
        a, b = s.marks["drop_start"], s.marks["drop_end"]
        self.assertAlmostEqual(b - a, 3.0)
        self.assertFalse(s.cue_down(a - 0.01))
        self.assertTrue(s.cue_down(a))
        self.assertFalse(s.cue_down(b))
        self.assertFalse(s.cam_down(a + 1))
        self.assertTrue(scenarios.get("cam_dropout").cam_down(a + 1))

    def test_unknown_scenario(self):
        with self.assertRaises(KeyError):
            scenarios.get("nope")


if __name__ == "__main__":
    unittest.main()
