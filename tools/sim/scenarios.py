"""Named, deterministic scenarios for sim_boards.py and e2e.py (protocol v2, near-field).

Scenario time t (s) starts when the simulator starts. Every scenario begins with LEAD_S of
lead-in so the phone can learn both boards and sync clocks before anything happens.

Hazard bands (PROTOCOL.md "Hazard bands", measured/tunable, conservative for the VL53L4CD's
~1.2 m envelope): NEAR < 500 mm, MID 500–899 mm, FAR 900–1200 mm, beyond 1200 mm → no obstacle
claim (NO_TARGET when the sensor sees nothing within range), NO all-clear ever. The single forward zone (~18° FOV) cannot lateralize, so obstacle
cues are always centred (side BOTH); there is deliberately no left/right scenario.
"""
from __future__ import annotations

import math
import random
from dataclasses import dataclass, field, fields
from typing import Callable

from dtwire import dw
from netem import CLEAN, NetProfile
from scene import Box

LEAD_S = 3.0
APPROACH_MPS = 0.5  # staged near-field demo approach speed: keeps FAR/MID/NEAR each wide enough to cue
BAND_FAR_MM = 1200
BAND_MID_MM = 900
BAND_NEAR_MM = 500


@dataclass
class Scenario:
    name: str
    description: str
    duration_s: float
    seed: int
    objects_fn: Callable[[float], list[Box]] = lambda t: []
    invalid_fn: Callable[[float, random.Random], int | None] = lambda t, rng: None
    cue_dropouts: list[tuple[float, float]] = field(default_factory=list)
    cam_dropouts: list[tuple[float, float]] = field(default_factory=list)
    net: NetProfile = CLEAN
    marks: dict[str, float] = field(default_factory=dict)  # named instants used by e2e.py

    def objects(self, t: float) -> list[Box]:
        return self.objects_fn(t)

    def invalid_status(self, t: float, rng: random.Random) -> int | None:
        """Wire status override for the whole reading (glass/saturation → UNKNOWN), else None."""
        return self.invalid_fn(t, rng)

    def cue_down(self, t: float) -> bool:
        return any(a <= t < b for a, b in self.cue_dropouts)

    def cam_down(self, t: float) -> bool:
        return any(a <= t < b for a, b in self.cam_dropouts)


class LoopedScenario(Scenario):
    """The same scenario repeating every duration_s while the boards keep running (no reboot between laps)."""

    def objects(self, t: float) -> list[Box]:
        return super().objects(t % self.duration_s)

    def invalid_status(self, t: float, rng: random.Random) -> int | None:
        return super().invalid_status(t % self.duration_s, rng)

    def cue_down(self, t: float) -> bool:
        return super().cue_down(t % self.duration_s)

    def cam_down(self, t: float) -> bool:
        return super().cam_down(t % self.duration_s)


def looped(s: Scenario) -> Scenario:
    return LoopedScenario(**{f.name: getattr(s, f.name) for f in fields(s)})


def _lerp_dist(t: float, t0: float, d0: float, d1: float, speed: float) -> float:
    """Distance at time t when closing from d0 toward d1 at `speed` from t0, then holding d1."""
    if t <= t0:
        return d0
    return max(d1, d0 - (t - t0) * speed) if d1 < d0 else min(d1, d0 + (t - t0) * speed)


def _band_marks(d0: float, speed: float) -> dict[str, float]:
    """Instants at which the closing object crosses 1200/900/500 mm (band entry points)."""
    return {"far_at": LEAD_S + (d0 - BAND_FAR_MM / 1000) / speed,
            "mid_at": LEAD_S + (d0 - BAND_MID_MM / 1000) / speed,
            "near_at": LEAD_S + (d0 - BAND_NEAR_MM / 1000) / speed}


def _head_bar(x: float) -> Box:
    # A head-height bar/sign: 1.2 m wide, 1.40–1.80 m above the floor (sensor at 1.6 m).
    return Box(x_m=x, y_m=0.0, z_bottom_m=1.40, z_top_m=1.80, width_m=1.2, depth_m=0.05, kind="bar")


def approach_centre(seed: int = 1, net: NetProfile = CLEAN, name: str = "approach_centre") -> Scenario:
    d0, d1, speed = 1.8, 0.35, APPROACH_MPS  # starts beyond the 1.2 m envelope: silent until FAR
    t_end_motion = LEAD_S + (d0 - d1) / speed
    hold_end = t_end_motion + 2.0

    def objs(t: float) -> list[Box]:
        if t >= hold_end:
            return []  # wearer stops and turns away: bar leaves the beam
        return [_head_bar(_lerp_dist(t, LEAD_S, d0, d1, speed))]

    marks = {"motion_start": LEAD_S, "motion_end": t_end_motion, "hold_end": hold_end, **_band_marks(d0, speed)}
    return Scenario(name, "head-height bar, straight ahead, 1.8 m → 0.35 m at 0.5 m/s, hold 2 s, then gone",
                    hold_end + 2.0, seed, objs, net=net, marks=marks)


def person_ahead(seed: int = 1) -> Scenario:
    speed, d0, d1 = APPROACH_MPS, 1.8, 0.40
    stop = LEAD_S + (d0 - d1) / speed
    aside0, aside1 = stop + 1.5, stop + 2.5

    def objs(t: float) -> list[Box]:
        x = _lerp_dist(t, LEAD_S, d0, d1, speed)
        y = 0.0
        if t >= aside0:
            y = min(1.0, (t - aside0) / (aside1 - aside0) * 1.0)  # steps aside, out of the 18° beam
        return [Box(x_m=x, y_m=y, z_bottom_m=0.0, z_top_m=1.75, width_m=0.5, depth_m=0.3, kind="person")]

    return Scenario("person_ahead", "person walking toward the wearer 1.8 m → 0.4 m, pauses, steps aside",
                    aside1 + 2.5, seed, objs,
                    marks={"motion_start": LEAD_S, "stop": stop, "aside_start": aside0, "gone": aside1,
                           **_band_marks(d0, speed)})


def glass_unknown(seed: int = 1) -> Scenario:
    glass_from = LEAD_S

    def invalid(t: float, rng: random.Random) -> int | None:
        # A valid bar is seen before the sensor goes invalid: the phone must leave READY
        # for DEGRADED and stop cueing the last valid distance, not treat it as open space.
        return None if t < glass_from else dw.RANGE_UNKNOWN

    return Scenario("glass_unknown", "bar at 1.0 m; from 3 s the single reading goes invalid (glass) → UNKNOWN",
                    glass_from + 6.0, seed, objects_fn=lambda t: [_head_bar(1.0)],
                    invalid_fn=invalid, marks={"glass_from": glass_from})


def approach_then_unknown(seed: int = 1) -> Scenario:
    """Fault path mid-approach: the bar closes to MID, the sensor goes invalid for 3 s, then recovers."""
    d0, d1, speed = 1.8, 0.70, APPROACH_MPS
    glass_from = LEAD_S + 2.0   # bar is at 0.8 m (MID) when the reading goes invalid
    glass_to = glass_from + 3.0

    def objs(t: float) -> list[Box]:
        return [_head_bar(_lerp_dist(t, LEAD_S, d0, d1, speed))]

    def invalid(t: float, rng: random.Random) -> int | None:
        return dw.RANGE_UNKNOWN if glass_from <= t < glass_to else None

    return Scenario("approach_then_unknown",
                    "bar closes 1.8 m → 0.7 m at 0.5 m/s; at 0.8 m the reading goes invalid for 3 s, then recovers",
                    glass_to + 3.0, seed, objs, invalid,
                    marks={"glass_from": glass_from, "glass_to": glass_to, **_band_marks(d0, speed)})


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
    return Scenario("idle_open_space",
                    "no return within the 1.2 m envelope; NO_TARGET (not clear): quiet — no obstacle cue, alert, "
                    "DEGRADED, UNAVAILABLE or 'sensor limited' speech",
                    LEAD_S + 6.0, seed)


HOVER_CENTRE_M = 0.9
HOVER_AMPLITUDE_M = 0.03
HOVER_HZ = 3.0


def hover_boundary(seed: int = 1) -> Scenario:
    """Target jitters ±30 mm around the FAR/MID boundary (900 mm) at 3 Hz, on top of sensor noise."""
    def objs(t: float) -> list[Box]:
        return [_head_bar(HOVER_CENTRE_M + HOVER_AMPLITUDE_M * math.sin(2 * math.pi * HOVER_HZ * t))]

    return Scenario("hover_boundary",
                    "head-height bar oscillating ±30 mm around 900 mm (FAR/MID boundary) at 3 Hz with sensor noise; "
                    "bands must not chatter",
                    LEAD_S + 10.0, seed, objs, marks={"hover_start": LEAD_S})


SCENARIOS: dict[str, Callable[[int], Scenario]] = {
    "approach_centre": approach_centre,
    "person_ahead": person_ahead,
    "glass_unknown": glass_unknown,
    "approach_then_unknown": approach_then_unknown,
    "cue_board_dropout": cue_board_dropout,
    "cam_dropout": cam_dropout,
    "wifi_jitter": wifi_jitter,
    "idle_open_space": idle_open_space,
    "hover_boundary": hover_boundary,
}


def get(name: str, seed: int = 1) -> Scenario:
    try:
        return SCENARIOS[name](seed)
    except KeyError:
        raise KeyError(f"unknown scenario {name!r}; choose from {', '.join(SCENARIOS)}") from None
