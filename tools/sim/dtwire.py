"""Imports the reference codec (protocol/python/determine_wire.py) so every sim tool speaks
exactly the frozen wire contract v1 (protocol/PROTOCOL.md). Never re-implement byte layouts here."""
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
ACK_NAMES = {dw.ACK_PLAYED: "PLAYED", dw.ACK_EXPIRED: "EXPIRED", dw.ACK_PLAYED_CLAMPED: "PLAYED_CLAMPED",
             dw.ACK_NO_SYNC: "NO_SYNC", dw.ACK_BAD_CUE: "BAD_CUE", dw.ACK_BUSY_DROPPED: "BUSY_DROPPED"}

# Fault bits (PROTOCOL.md "BEAT").
FAULT_NAMES = ["SENSOR_INIT_FAIL", "SENSOR_READ_ERR", "I2S_ERR", "HAPTIC_L_ERR", "HAPTIC_R_ERR",
               "BROWNOUT_SEEN", "NO_CLOCK_SYNC", "WATCHDOG_ACTIVE", "LOW_HEAP", "CAPTURE_FAIL"]
FAULT_NO_CLOCK_SYNC = 1 << 6
FAULT_WATCHDOG_ACTIVE = 1 << 7

# Watchdog (PROTOCOL.md "Watchdog"; firmware/common/include/determine_wire.h DT_WATCHDOG_GAIN_DB).
WATCHDOG_GAIN_DB = -20
WATCHDOG_NO_PING_S = 0.8
WATCHDOG_BOOT_GRACE_S = 5.0
WATCHDOG_REPEAT_S = 5.0


def fault_names(faults: int) -> list[str]:
    return [n for i, n in enumerate(FAULT_NAMES) if faults & (1 << i)] + \
           [f"BIT{i}" for i in range(len(FAULT_NAMES), 16) if faults & (1 << i)]


__all__ = ["dw", "CUE_NAMES", "HAPTIC_NAMES", "SIDE_NAMES", "SIDE_BY_NAME", "MODE_NAMES", "MODE_BY_NAME",
           "ACK_NAMES", "FAULT_NAMES", "FAULT_NO_CLOCK_SYNC", "FAULT_WATCHDOG_ACTIVE", "WATCHDOG_GAIN_DB",
           "WATCHDOG_NO_PING_S", "WATCHDOG_BOOT_GRACE_S", "WATCHDOG_REPEAT_S", "fault_names"]
