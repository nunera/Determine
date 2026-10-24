"""Named, deterministic scenarios for sim_boards.py and e2e.py.

Scenario time t (s) starts when the simulator starts. Every scenario begins with LEAD_S of
lead-in so the phone can learn both boards and sync clocks before anything happens.
Hazard bands for reference (Arch §7.2, PROPOSED): NEAR < 1 m, MID < 2 m, FAR < 3 m.
Geometry notes: the sensor is at 1.6 m; per-axis FOV ≈ 46.8° (63° diagonal), so at 1.7 m an
object 0.75 m to the side sits in the outermost column only (flank), and at 3 m it is still
outside the central four columns.
"""
from __future__ import annotations

import random
from dataclasses import dataclass, field
from typing import Callable

from netem import CLEAN, NetProfile
from scene import STATUS_GLASS, Box, region_zones

LEAD_S = 3.0
WALK_MPS = 1.4  # Arch §6.3


@dataclass
class Scenario:
    name: str
    description: str
    duration_s: float
    seed: int
    objects_fn: Callable[[float], list[Box]] = lambda t: []
    invalid_fn: Callable[[float, random.Random], dict[int, int]] = lambda t, rng: {}
    cue_dropouts: list[tuple[float, float]] = field(default_factory=list)
    cam_dropouts: list[tuple[float, float]] = field(default_factory=list)
    net: NetProfile = CLEAN
    marks: dict[str, float] = field(default_factory=dict)  # named instants used by e2e.py

    def objects(self, t: float) -> list[Box]:
        return self.objects_fn(t)

    def invalid_zones(self, t: float, rng: random.Random) -> dict[int, int]:
        return self.invalid_fn(t, rng)

    def cue_down(self, t: float) -> bool:
        return any(a <= t < b for a, b in self.cue_dropouts)

    def cam_down(self, t: float) -> bool:
        return any(a <= t < b for a, b in self.cam_dropouts)


def _lerp_dist(t: float, t0: float, d0: float, d1: float, speed: float) -> float:
    """Distance at time t when closing from d0 toward d1 at `speed` from t0, then holding d1."""
    if t <= t0:
        return d0
    return max(d1, d0 - (t - t0) * speed) if d1 < d0 else min(d1, d0 + (t - t0) * speed)


def _head_bar(x: float) -> Box:
    # A head-height bar/sign: 1.2 m wide, 1.40–1.80 m above the floor (sensor at 1.6 m).
    return Box(x_m=x, y_m=0.0, z_bottom_m=1.40, z_top_m=1.80, width_m=1.2, depth_m=0.05, kind="bar")


def approach_centre(seed: int = 1, net: NetProfile = CLEAN, name: str = "approach_centre") -> Scenario:
    d0, d1 = 3.5, 0.5
    t_end_motion = LEAD_S + (d0 - d1) / WALK_MPS
    hold_end = t_end_motion + 2.0

    def objs(t: float) -> list[Box]:
        if t >= hold_end:
            return []  # wearer stops and turns away: bar leaves the field of view
        return [_head_bar(_lerp_dist(t, LEAD_S, d0, d1, WALK_MPS))]

    marks = {"motion_start": LEAD_S, "motion_end": t_end_motion, "hold_end": hold_end,
             "far_at": LEAD_S + (d0 - 3.0) / WALK_MPS, "mid_at": LEAD_S + (d0 - 2.0) / WALK_MPS,
             "near_at": LEAD_S + (d0 - 1.0) / WALK_MPS}
    return Scenario(name, "head-height bar, centre, 3.5 m → 0.5 m at 1.4 m/s, hold 2 s, then gone",
                    hold_end + 2.0, seed, objs, net=net, marks=marks)


def left_then_right(seed: int = 1) -> Scenario:
    speed, d0, d1 = 1.0, 3.2, 1.7
    l0, l_gone = LEAD_S, LEAD_S + 3.5
    r0 = l_gone + 2.0
    r_gone = r0 + 3.5

    def pole(y: float, x: float) -> Box:
        return Box(x_m=x, y_m=y, z_bottom_m=0.0, z_top_m=2.0, width_m=0.25, depth_m=0.25, kind="pole")

    def objs(t: float) -> list[Box]:
        if l0 <= t < l_gone:
            return [pole(-0.75, _lerp_dist(t, l0, d0, d1, speed))]
        if r0 <= t < r_gone:
            return [pole(+0.75, _lerp_dist(t, r0, d0, d1, speed))]
        return []

    return Scenario("left_then_right", "pole on the LEFT flank 3.2 → 1.7 m, gone, then on the RIGHT flank",
                    r_gone + 2.0, seed, objs,
                    marks={"left_start": l0, "left_end": l_gone, "right_start": r0, "right_end": r_gone})


def person_ahead(seed: int = 1) -> Scenario:
    speed, d0, d1 = 1.0, 4.5, 1.2
    stop = LEAD_S + (d0 - d1) / speed
    aside0, aside1 = stop + 1.5, stop + 2.5

    def objs(t: float) -> list[Box]:
        x = _lerp_dist(t, LEAD_S, d0, d1, speed)
        y = 0.0
        if t >= aside0:
            y = min(1.6, (t - aside0) / (aside1 - aside0) * 1.6)  # steps aside out of the FOV
        return [Box(x_m=x, y_m=y, z_bottom_m=0.0, z_top_m=1.75, width_m=0.5, depth_m=0.3, kind="person")]

    return Scenario("person_ahead", "person walking toward the wearer 4.5 → 1.2 m, pauses, steps aside",
                    aside1 + 2.5, seed, objs, marks={"motion_start": LEAD_S, "stop": stop,
                                                     "aside_start": aside0, "gone": aside1})


def glass_unknown(seed: int = 1) -> Scenario:
    glass_from = LEAD_S
    corridor = region_zones(range(1, 7), range(2, 6))
    junk = [0, 2, 4, 6, 12, 13]  # raw non-valid target_status codes; glass/clutter

    def walls(t: float) -> list[Box]:
        return [Box(0.3, -1.6, 0.0, 2.5, 0.1, 12.0, "wall"), Box(0.3, 1.6, 0.0, 2.5, 0.1, 12.0, "wall")]

    def invalid(t: float, rng: random.Random) -> dict[int, int]:
        if t < glass_from:
            return {}
        return {z: (STATUS_GLASS if rng.random() < 0.6 else rng.choice(junk)) for z in corridor}

    return Scenario("glass_unknown", "corridor walls 1.6 m each side; from 3 s the central zones read invalid (glass)",
                    glass_from + 6.0, seed, walls, invalid, marks={"glass_from": glass_from})


def cue_board_dropout(seed: int = 1) -> Scenario:
    a = LEAD_S + 1.0
    return Scenario("cue_board_dropout", "open space; cue board silent on the network (no tx, no rx) for 3 s",
                    a + 3.0 + 3.0, seed, cue_dropouts=[(a, a + 3.0)], marks={"drop_start": a, "drop_end": a + 3.0})


def cam_dropout(seed: int = 1) -> Scenario:
    a = LEAD_S + 1.0
    return Scenario("cam_dropout", "open space; camera board silent on the network for 3 s",
                    a + 3.0 + 3.0, seed, cam_dropouts=[(a, a + 3.0)], marks={"drop_start": a, "drop_end": a + 3.0})


WIFI_JITTER_NET = NetProfile(loss=0.03, delay_min_ms=2.0, delay_max_ms=30.0,
                             spike_prob=0.05, spike_min_ms=60.0, spike_max_ms=140.0)


def wifi_jitter(seed: int = 1) -> Scenario:
    s = approach_centre(seed, WIFI_JITTER_NET, "wifi_jitter")
    s.description = "approach_centre under 3% loss, 2–30 ms jitter, 5% spikes of 60–140 ms (both directions)"
    return s


def idle_open_space(seed: int = 1) -> Scenario:
    return Scenario("idle_open_space", "nothing within 4 m; the system must stay silent", LEAD_S + 6.0, seed)


SCENARIOS: dict[str, Callable[[int], Scenario]] = {
    "approach_centre": approach_centre,
    "left_then_right": left_then_right,
    "person_ahead": person_ahead,
    "glass_unknown": glass_unknown,
    "cue_board_dropout": cue_board_dropout,
    "cam_dropout": cam_dropout,
    "wifi_jitter": wifi_jitter,
    "idle_open_space": idle_open_space,
}


def get(name: str, seed: int = 1) -> Scenario:
    try:
        return SCENARIOS[name](seed)
    except KeyError:
        raise KeyError(f"unknown scenario {name!r}; choose from {', '.join(SCENARIOS)}") from None
