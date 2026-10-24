"""Scene model → VL53L5CX 8×8 grid, as the cue board would send it in RANGE (PROTOCOL.md).

Coordinates (metres): wearer's sensor at (0, 0, sensor_height); x forward, y to the wearer's
RIGHT, z up from the floor. Objects are axis-aligned boxes.

Sensor model (Arch §5, VL53L5CX: 4 m, 63° diagonal FOV, 8×8 @ 15 Hz): square field of view,
so each axis spans 2·atan(tan(31.5°)/√2) ≈ 46.8°. Zones are equal slices of the image plane.
Zone index = row*8 + col with row 0 = top of the wearer's view and col 0 = wearer's left
(the firmware's mounting transform is assumed already applied).

Each zone is sampled with sub×sub rays. A zone reports a target when at least `coverage` of its
rays hit an object; the reported distance is the nearest hit along the ray (radial distance —
[INFERENCE] about the real sensor's reporting convention), plus Gaussian noise.
Status 5 = valid with full coverage, 9 = valid with partial coverage (both VALID for the phone);
nothing within 4 m → 255 NO_TARGET with mm = 0; scripted invalid zones (glass, sunlight) report
their raw status (e.g. 4) with a meaningless distance, which the phone must treat as UNKNOWN.
"""
from __future__ import annotations

import math
import random
from dataclasses import dataclass

from dtwire import dw

ROWS = COLS = 8
ZONES = ROWS * COLS
DIAG_FOV_DEG = 63.0
HALF_TAN = math.tan(math.radians(DIAG_FOV_DEG / 2)) / math.sqrt(2)  # per-axis tan(half FOV)
AXIS_FOV_DEG = 2 * math.degrees(math.atan(HALF_TAN))
RANGE_LIMIT_MM = 4000
SENSOR_HEIGHT_M = 1.6
STATUS_VALID = 5
STATUS_VALID_PARTIAL = 9
STATUS_GLASS = 4  # a raw non-valid VL53L5CX target_status; the phone maps it to UNKNOWN


@dataclass(frozen=True)
class Box:
    x_m: float          # forward distance to the near face
    y_m: float          # lateral centre, + = wearer's right
    z_bottom_m: float   # above floor
    z_top_m: float
    width_m: float
    depth_m: float = 0.1
    kind: str = "obstacle"

    def hit(self, origin: tuple[float, float, float], d: tuple[float, float, float]) -> float | None:
        """Slab test; d must be normalised. Returns distance along the ray or None."""
        lo = (self.x_m, self.y_m - self.width_m / 2, self.z_bottom_m)
        hi = (self.x_m + self.depth_m, self.y_m + self.width_m / 2, self.z_top_m)
        tmin, tmax = 0.0, math.inf
        for a in range(3):
            if abs(d[a]) < 1e-12:
                if origin[a] < lo[a] or origin[a] > hi[a]:
                    return None
                continue
            t1 = (lo[a] - origin[a]) / d[a]
            t2 = (hi[a] - origin[a]) / d[a]
            if t1 > t2:
                t1, t2 = t2, t1
            tmin, tmax = max(tmin, t1), min(tmax, t2)
            if tmin > tmax:
                return None
        return tmin if tmin > 0 else None


def zone_index(row: int, col: int) -> int:
    return row * COLS + col


def zone_ray(row: int, col: int, su: float = 0.5, sv: float = 0.5) -> tuple[float, float, float]:
    """Normalised ray through image-plane point (su, sv) ∈ [0,1)² of zone (row, col)."""
    u = -HALF_TAN + (col + su) / COLS * 2 * HALF_TAN   # col 0 → left (−y)
    v = HALF_TAN - (row + sv) / ROWS * 2 * HALF_TAN    # row 0 → top (+z)
    n = math.sqrt(1 + u * u + v * v)
    return (1 / n, u / n, v / n)


_RAY_CACHE: dict[int, list[list[tuple[float, float, float]]]] = {}


def _zone_rays(sub: int) -> list[list[tuple[float, float, float]]]:
    rays = _RAY_CACHE.get(sub)
    if rays is None:
        rays = [[zone_ray(r, c, (j + 0.5) / sub, (i + 0.5) / sub) for i in range(sub) for j in range(sub)]
                for r in range(ROWS) for c in range(COLS)]
        _RAY_CACHE[sub] = rays
    return rays


def project(objects: list[Box], invalid_zones: dict[int, int] | None = None,
            rng: random.Random | None = None, *, noise: bool = True, sub: int = 5,
            coverage: float = 0.2, range_limit_mm: int = RANGE_LIMIT_MM,
            sensor_height_m: float = SENSOR_HEIGHT_M) -> tuple[list[int], list[int]]:
    """Returns (mm[64], status[64]) in wire order."""
    rng = rng or random.Random(0)
    origin = (0.0, 0.0, sensor_height_m)
    mm = [0] * ZONES
    status = [dw.STATUS_NO_TARGET] * ZONES
    rays = _zone_rays(sub)
    for z in range(ZONES):
        hits = []
        if objects:
            for d in rays[z]:
                best = None
                for ob in objects:
                    t = ob.hit(origin, d)
                    if t is not None and (best is None or t < best):
                        best = t
                if best is not None:
                    hits.append(best)
        frac = len(hits) / len(rays[z])
        if hits and frac >= coverage:
            dist_mm = min(hits) * 1000.0
            if noise:
                dist_mm += rng.gauss(0.0, 3.0 + 0.005 * dist_mm)
            dist_mm = max(1, int(round(dist_mm)))
            if dist_mm <= range_limit_mm:
                mm[z] = dist_mm
                status[z] = STATUS_VALID if frac >= 0.5 else STATUS_VALID_PARTIAL
    for z, st in (invalid_zones or {}).items():
        status[z] = st
        if st == dw.STATUS_NO_TARGET:
            mm[z] = 0
        elif st not in dw.VALID_STATUS:
            mm[z] = rng.randint(150, range_limit_mm)  # meaningless reading on an invalid status
    return mm, status


def region_zones(rows: range, cols: range) -> list[int]:
    return [zone_index(r, c) for r in rows for c in cols]
