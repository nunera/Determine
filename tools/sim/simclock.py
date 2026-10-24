"""Clock models.

Host time: CLOCK_MONOTONIC in µs. On Linux this is the same clock as the phone cli's
System.nanoTime(), so the simulator's host timestamps line up with "phone time" when both run
on one machine.

BoardClock: an ESP32 `esp_timer_get_time()` simulation — µs since the simulated boot, with a
crystal drift in ppm. Board − phone offset therefore is large (host uptime vs. seconds since
boot), distinct per board, and changes slowly, exactly what the phone's PING/PONG sync must
track (PROTOCOL.md "PING/PONG").
"""
from __future__ import annotations

import time
from typing import Callable


def host_us() -> int:
    return time.monotonic_ns() // 1000


class BoardClock:
    def __init__(self, boot_age_s: float, drift_ppm: float, host: Callable[[], int] = host_us,
                 ref_host_us: int | None = None):
        """boot_age_s: how long ago (host time) the board booted, i.e. board time at the
        reference instant. drift_ppm: board clock rate error (+ = board runs fast)."""
        if boot_age_s < 0:
            raise ValueError("boot_age_s must be >= 0")
        self._host = host
        self.ref_host_us = host() if ref_host_us is None else ref_host_us
        self.board_at_ref_us = int(round(boot_age_s * 1e6))
        self.drift_ppm = drift_ppm

    def at(self, host_t_us: int) -> int:
        """Board time (µs since boot) at the given host time."""
        dt = host_t_us - self.ref_host_us
        return self.board_at_ref_us + dt + int(round(dt * self.drift_ppm * 1e-6))

    def now(self) -> int:
        return self.at(self._host())

    def host_at(self, board_t_us: int) -> int:
        """Inverse of at(): host time at which the board clock reads board_t_us."""
        db = board_t_us - self.board_at_ref_us
        return self.ref_host_us + int(round(db / (1.0 + self.drift_ppm * 1e-6)))

    def offset_at(self, host_t_us: int) -> int:
        """True board − phone offset (phone clock == host clock), what PING/PONG must recover."""
        return self.at(host_t_us) - host_t_us
