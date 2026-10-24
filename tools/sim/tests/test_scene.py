import math
import os
import random
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import scenarios  # noqa: E402
import scene  # noqa: E402
from dtwire import dw  # noqa: E402
from scene import Box, project, zone_index  # noqa: E402

H = scene.SENSOR_HEIGHT_M


def wall(x, y=0.0, w=0.4, zb=H - 0.2, zt=H + 0.2):
    return Box(x_m=x, y_m=y, z_bottom_m=zb, z_top_m=zt, width_m=w, depth_m=0.05)


def hit_zones(status):
    return {i for i, s in enumerate(status) if s in dw.VALID_STATUS}


class FovTest(unittest.TestCase):
    def test_fov_geometry(self):
        diag = 2 * math.degrees(math.atan(scene.HALF_TAN * math.sqrt(2)))
        self.assertAlmostEqual(diag, 63.0, places=6)
        self.assertAlmostEqual(scene.AXIS_FOV_DEG, 46.8, delta=0.2)

    def test_object_just_outside_fov_is_invisible(self):
        half = math.atan(scene.HALF_TAN)
        y = 2.0 * math.tan(half + math.radians(2)) + 0.15  # beyond the right edge
        mm, st = project([wall(2.0, y, 0.2, 0.0, 3.0)], noise=False)
        self.assertEqual(hit_zones(st), set())


class ProjectionTest(unittest.TestCase):
    def test_centre_object_hits_centre_zones_only(self):
        mm, st = project([wall(2.0)], noise=False)
        hits = hit_zones(st)
        centre = {zone_index(r, c) for r in (3, 4) for c in (3, 4)}
        self.assertTrue(centre <= hits, f"centre zones missing: {centre - hits}")
        for z in hits:
            r, c = divmod(z, 8)
            self.assertIn(r, range(2, 6))
            self.assertIn(c, range(2, 6))
        # corners see nothing: NO_TARGET with mm 0 (PROTOCOL.md RANGE)
        for z in (0, 7, 56, 63):
            self.assertEqual(st[z], dw.STATUS_NO_TARGET)
            self.assertEqual(mm[z], 0)
        for z in centre:
            self.assertTrue(2000 <= mm[z] <= 2010, mm[z])  # radial distance, just off-axis

    def test_beyond_4m_is_no_target(self):
        mm, st = project([wall(4.2, w=3.0, zb=0.0, zt=3.0)], noise=False)
        self.assertEqual(st, [dw.STATUS_NO_TARGET] * 64)
        self.assertEqual(mm, [0] * 64)

    def test_just_inside_4m_is_valid(self):
        mm, st = project([wall(3.9, w=1.0, zb=H - 0.5, zt=H + 0.5)], noise=False)
        z = zone_index(3, 3)
        self.assertIn(st[z], dw.VALID_STATUS)
        self.assertTrue(3900 <= mm[z] <= 4000)

    def test_left_right_and_up_orientation(self):
        _, st = project([wall(1.5, y=-0.5, w=0.3, zb=0.0, zt=3.0)], noise=False)
        cols = {z % 8 for z in hit_zones(st)}
        self.assertTrue(cols and max(cols) <= 2, f"left object hit cols {cols}")  # col 0 = wearer's left
        _, st = project([wall(1.5, y=+0.5, w=0.3, zb=0.0, zt=3.0)], noise=False)
        cols = {z % 8 for z in hit_zones(st)}
        self.assertTrue(cols and min(cols) >= 5, f"right object hit cols {cols}")
        _, st = project([wall(1.5, w=2.0, zb=H + 0.35, zt=H + 1.0)], noise=False)
        rows = {z // 8 for z in hit_zones(st)}
        self.assertTrue(rows and max(rows) <= 2, f"overhead object hit rows {rows}")  # row 0 = top

    def test_nearest_object_wins(self):
        mm, _ = project([wall(3.0, w=2.0), wall(1.0, w=0.2)], noise=False)
        self.assertLess(mm[zone_index(3, 3)], 1100)
        self.assertGreater(mm[zone_index(3, 1)], 2900)

    def test_invalid_zones_override(self):
        inv = {zone_index(3, 3): 4, zone_index(0, 0): dw.STATUS_NO_TARGET, zone_index(4, 4): 12}
        mm, st = project([wall(2.0)], invalid_zones=inv, rng=random.Random(1))
        self.assertEqual(st[zone_index(3, 3)], 4)
        self.assertEqual(st[zone_index(4, 4)], 12)
        self.assertGreater(mm[zone_index(3, 3)], 0)  # junk distance on an UNKNOWN zone
        self.assertEqual((st[0], mm[0]), (dw.STATUS_NO_TARGET, 0))

    def test_noise_is_seeded_and_small(self):
        a = project([wall(2.0)], rng=random.Random(7))
        b = project([wall(2.0)], rng=random.Random(7))
        self.assertEqual(a, b)
        z = zone_index(3, 3)
        self.assertLess(abs(a[0][z] - 2002), 60)

    def test_range_message_round_trip(self):
        mm, st = project([wall(2.0)], rng=random.Random(1))
        pkt = dw.encode(dw.Range(123, mm, st), 5)
        self.assertEqual(len(pkt), 212)
        self.assertEqual(dw.decode(pkt)[1].mm, mm)


class ScenarioTest(unittest.TestCase):
    def grid(self, s, t):
        return project(s.objects(t), s.invalid_zones(t, random.Random(0)), noise=False)

    def test_all_scenarios_deterministic(self):
        for name in scenarios.SCENARIOS:
            a, b = scenarios.get(name, 3), scenarios.get(name, 3)
            for t in (0.0, 3.5, 5.0, 7.0):
                self.assertEqual(project(a.objects(t), a.invalid_zones(t, random.Random(1)), random.Random(2)),
                                 project(b.objects(t), b.invalid_zones(t, random.Random(1)), random.Random(2)), name)

    def test_approach_centre_distances(self):
        s = scenarios.get("approach_centre")
        m = s.marks
        corridor = [zone_index(r, c) for r in (3, 4) for c in (3, 4)]
        mm, st = self.grid(s, 1.0)
        self.assertTrue(all(st[z] in dw.VALID_STATUS for z in corridor))
        self.assertAlmostEqual(min(mm[z] for z in corridor), 3500, delta=40)
        mm, _ = self.grid(s, m["mid_at"] + 0.01)
        self.assertLess(min(mm[z] for z in corridor if mm[z]), 2000)
        mm, _ = self.grid(s, m["motion_end"] + 0.5)
        self.assertAlmostEqual(min(mm[z] for z in corridor if mm[z]), 500, delta=15)
        self.assertAlmostEqual(m["motion_end"] - m["motion_start"], 3.0 / 1.4, places=6)
        _, st = self.grid(s, m["hold_end"] + 0.1)
        self.assertEqual(hit_zones(st), set())

    def test_idle_open_space_is_empty(self):
        s = scenarios.get("idle_open_space")
        for t in (0, 2, 4, 6, 8):
            _, st = self.grid(s, t)
            self.assertEqual(st, [dw.STATUS_NO_TARGET] * 64)

    def test_glass_corridor_unknown(self):
        s = scenarios.get("glass_unknown")
        _, st = self.grid(s, s.marks["glass_from"] + 0.5)
        for r in range(1, 7):
            for c in range(2, 6):
                v = st[zone_index(r, c)]
                self.assertNotIn(v, dw.VALID_STATUS)
                self.assertNotEqual(v, dw.STATUS_NO_TARGET)
        mm, st = self.grid(s, s.marks["glass_from"] - 0.5)
        # walls are outside the FAR band and the corridor reads NO_TARGET before the glass
        self.assertTrue(all(mm[z] == 0 or mm[z] >= 3000 for z in range(64)))

    def test_left_then_right_flanks(self):
        s = scenarios.get("left_then_right")
        m = s.marks
        for t in (m["left_start"] + 0.5, m["left_end"] - 0.1):
            mm, st = self.grid(s, t)
            near = {z % 8 for z in hit_zones(st) if mm[z] < 3000}
            self.assertTrue(near and max(near) <= 1, (t, near))
        for t in (m["right_start"] + 0.5, m["right_end"] - 0.1):
            mm, st = self.grid(s, t)
            near = {z % 8 for z in hit_zones(st) if mm[z] < 3000}
            self.assertTrue(near and min(near) >= 6, (t, near))

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
