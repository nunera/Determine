"""Imports the reference codec (protocol/python/determine_wire.py) so every sim tool speaks
exactly the frozen wire contract v2 (protocol/PROTOCOL.md). Never re-implement byte layouts here."""
from __future__ import annotations

import pathlib
import sys

_CODEC_DIR = pathlib.Path(__file__).resolve().parents[2] / "protocol" / "python"
if str(_CODEC_DIR) not in sys.path:
    sys.path.insert(0, str(_CODEC_DIR))

import determine_wire as dw  # noqa: E402

CUE_NAMES = {v: k for k, v in dw.CUE_IDS.items()}
HAPTIC_NAMES = {v: k for k, v in dw.HAPTIC.items()}
SIDE_NAMES = {dw.SIDE_L: "L", dw.SIDE_R: "R", dw.SIDE_BOTH: "BOTH"}
SIDE_BY_NAME = {"L": dw.SIDE_L, "LEFT": dw.SIDE_L, "R": dw.SIDE_R, "RIGHT": dw.SIDE_R,
                "BOTH": dw.SIDE_BOTH, "B": dw.SIDE_BOTH, "C": dw.SIDE_BOTH, "CENTRE": dw.SIDE_BOTH,
                "CENTER": dw.SIDE_BOTH}
MODE_NAMES = {dw.MODE_STEREO: "STEREO", dw.MODE_SSD: "SSD", dw.MODE_HAPTIC_ONLY: "HAPTIC_ONLY"}
MODE_BY_NAME = {v: k for k, v in MODE_NAMES.items()}
ACK_NAMES = {dw.ACK_APPLIED: "APPLIED", dw.ACK_EXPIRED: "EXPIRED", dw.ACK_APPLIED_CLAMPED: "APPLIED_CLAMPED",
             dw.ACK_NO_SYNC: "NO_SYNC", dw.ACK_BAD_CUE: "BAD_CUE", dw.ACK_BUSY_DROPPED: "BUSY_DROPPED"}
RANGE_STATUS_NAMES = {dw.RANGE_VALID: "VALID", dw.RANGE_NO_TARGET: "NO_TARGET", dw.RANGE_UNKNOWN: "UNKNOWN"}

# Fault bits (PROTOCOL.md "BEAT"). Bit 2 is reserved in v2 (was I2S_ERR; no board audio).
FAULT_NAMES = ["SENSOR_INIT_FAIL", "SENSOR_READ_ERR", "RESERVED2", "HAPTIC_L_ERR", "HAPTIC_R_ERR",
               "BROWNOUT_SEEN", "NO_CLOCK_SYNC", "WATCHDOG_ACTIVE", "LOW_HEAP", "CAPTURE_FAIL"]
FAULT_NO_CLOCK_SYNC = 1 << 6
FAULT_WATCHDOG_ACTIVE = 1 << 7

# Watchdog (PROTOCOL.md "Watchdog"): haptic LSS only — the board makes no sound in v2.
WATCHDOG_NO_PING_S = 0.8
WATCHDOG_BOOT_GRACE_S = 5.0
WATCHDOG_REPEAT_S = 5.0

# Near-field hazard bands (PROTOCOL.md "Hazard bands"; measured/tunable, conservative ≤ 1200 mm).
BAND_NEAR_MM = 500      # NEAR: strictly below
BAND_MID_MM = 900       # MID: 500–899
BAND_FAR_MM = 1200      # FAR: 900–1200; beyond → no obstacle claim (and the VL53L4CD envelope)


def band_of(mm: int, status: int = dw.RANGE_VALID) -> str | None:
    """Band name for a VALID reading, else None (NO_TARGET/UNKNOWN/>1200 make no claim)."""
    if status != dw.RANGE_VALID or not 0 < mm <= BAND_FAR_MM:
        return None
    if mm < BAND_NEAR_MM:
        return "NEAR"
    return "MID" if mm < BAND_MID_MM else "FAR"


def fault_names(faults: int) -> list[str]:
    return [n for i, n in enumerate(FAULT_NAMES) if faults & (1 << i)] + \
           [f"BIT{i}" for i in range(len(FAULT_NAMES), 16) if faults & (1 << i)]


__all__ = ["dw", "CUE_NAMES", "HAPTIC_NAMES", "SIDE_NAMES", "SIDE_BY_NAME", "MODE_NAMES", "MODE_BY_NAME",
           "ACK_NAMES", "RANGE_STATUS_NAMES", "FAULT_NAMES", "FAULT_NO_CLOCK_SYNC", "FAULT_WATCHDOG_ACTIVE",
           "WATCHDOG_NO_PING_S", "WATCHDOG_BOOT_GRACE_S", "WATCHDOG_REPEAT_S", "BAND_NEAR_MM", "BAND_MID_MM",
           "BAND_FAR_MM", "band_of", "fault_names"]
