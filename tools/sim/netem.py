"""Wi-Fi impairment model applied by the simulator to each datagram (both directions)."""
from __future__ import annotations

import random
from dataclasses import dataclass


@dataclass(frozen=True)
class NetProfile:
    loss: float = 0.0             # independent drop probability per datagram
    delay_min_ms: float = 0.0     # uniform base delay
    delay_max_ms: float = 0.0
    spike_prob: float = 0.0       # probability of an extra latency spike (airtime contention, retries)
    spike_min_ms: float = 0.0
    spike_max_ms: float = 0.0

    @property
    def active(self) -> bool:
        return self.loss > 0 or self.delay_max_ms > 0 or self.spike_prob > 0


CLEAN = NetProfile()


class Impairment:
    def __init__(self, profile: NetProfile, rng: random.Random):
        self.p = profile
        self.rng = rng
        self.dropped = 0
        self.passed = 0

    def decide(self) -> float | None:
        """None = drop; else delay in seconds."""
        p = self.p
        if not p.active:
            self.passed += 1
            return 0.0
        if p.loss > 0 and self.rng.random() < p.loss:
            self.dropped += 1
            return None
        d = self.rng.uniform(p.delay_min_ms, p.delay_max_ms) if p.delay_max_ms > 0 else 0.0
        if p.spike_prob > 0 and self.rng.random() < p.spike_prob:
            d += self.rng.uniform(p.spike_min_ms, p.spike_max_ms)
        self.passed += 1
        return d / 1000.0
