"""Scene model → one forward ToF reading, as the cue board would send it in RANGE (protocol v2).

The cue board wears ONE Arduino Modulino Distance (VL53L4ED): a single forward-looking zone,
~18° diagonal FOV, vendor maximum range up to 1.2 m; usable warning distance unvalidated (near-field demo envelope — NOT a 3 m grid, NOT a
lateral sensor: one zone cannot tell left from right or resolve two objects).

Coordinates (metres): the sensor sits at the origin, x forward, y to the wearer's right, z up
from the floor at SENSOR_HEIGHT_M. Objects are axis-aligned boxes. A box is visible when it
cuts the beam height and its lateral extent reaches into the forward cone (half-angle 9°).
The reading is the nearest forward distance among visible boxes plus Gaussian noise.

Status mapping mirrors the firmware's VL53L4ED mapping (PROTOCOL.md RANGE,
lib/cue_logic dt_range_status_from_tof): a detection is VALID (raw status 0); no return from
open space or beyond the measurement envelope is NO_TARGET (raw status 2/4: the sensor saw no
target within range, which is NOT an all-clear). An explicit glass/saturation fixture override
is UNKNOWN (any other nonzero raw status). mm is 0 unless the status is VALID.
"""
from __future__ import annotations

import math
import random
from dataclasses import dataclass

from dtwire import dw

FOV_DIAG_DEG = 18.0                     # VL53L4CD optical field of view (datasheet)
HALF_ANGLE_RAD = math.radians(FOV_DIAG_DEG / 2)
RANGE_LIMIT_MM = 1200                   # vendor maximum range (unvalidated as usable); also the FAR band ceiling
SENSOR_HEIGHT_M = 1.6


@dataclass(frozen=True)
class Box:
    x_m: float          # forward distance to the near face
    y_m: float          # lateral centre, + = wearer's right
    z_bottom_m: float   # above floor
    z_top_m: float
    width_m: float
    depth_m: float = 0.1
    kind: str = "obstacle"


def in_cone(b: Box) -> bool:
    """True when the box cuts the beam height and reaches into the forward cone."""
    if not (b.z_bottom_m <= SENSOR_HEIGHT_M <= b.z_top_m):
        return False
    lateral = max(0.0, abs(b.y_m) - b.width_m / 2)  # nearest lateral edge to the axis
    return math.atan2(lateral, b.x_m) <= HALF_ANGLE_RAD


def measure(objects: list[Box], rng: random.Random | None = None, *, noise: bool = True,
            range_limit_mm: int = RANGE_LIMIT_MM,
            invalid_status: int | None = None) -> tuple[int, int]:
    """Returns (mm, status) in wire semantics: canonical status, mm 0 unless VALID.

    invalid_status explicitly overrides the whole reading for a fixture (e.g. a
    glass/saturation fault → RANGE_UNKNOWN, or a scripted RANGE_NO_TARGET).
    """
    rng = rng or random.Random(0)
    if invalid_status is not None:
        return 0, invalid_status
    visible = [b.x_m for b in objects if b.x_m > 0 and in_cone(b)]
    if not visible:
        return 0, dw.RANGE_NO_TARGET  # no return in view
    dist_mm = min(visible) * 1000.0
    if noise:
        dist_mm += rng.gauss(0.0, 4.0 + 0.005 * dist_mm)  # ±7 mm short-range class sensor noise
    dist_mm = max(1, int(round(dist_mm)))
    if dist_mm > range_limit_mm:
        return 0, dw.RANGE_NO_TARGET  # no return beyond the vendor maximum range
    return dist_mm, dw.RANGE_VALID
