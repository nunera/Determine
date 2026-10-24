"""Phone-side helpers for bench.py and the tests: NTP-style clock sync (PROTOCOL.md PING/PONG),
latency percentiles, and FRAME reassembly (PROTOCOL.md FRAME, 150 ms completion bound)."""
from __future__ import annotations

import math
from collections import deque
from dataclasses import dataclass, field


def percentile(values, p: float) -> float | None:
    """Linear-interpolated percentile (p in 0..100) of the values; None when empty."""
    xs = sorted(values)
    if not xs:
        return None
    if not 0 <= p <= 100:
        raise ValueError("p must be within 0..100")
    k = (len(xs) - 1) * p / 100.0
    lo, hi = math.floor(k), math.ceil(k)
    return xs[lo] + (xs[hi] - xs[lo]) * (k - lo)


class LatencyStats:
    def __init__(self, maxlen: int = 1000):
        self.samples: deque[float] = deque(maxlen=maxlen)

    def add(self, ms: float):
        if ms < 0:
            raise ValueError("negative latency")
        self.samples.append(ms)

    @property
    def count(self) -> int:
        return len(self.samples)

    def p(self, pct: float) -> float | None:
        return percentile(self.samples, pct)

    @property
    def p50(self) -> float | None:
        return self.p(50)

    @property
    def p95(self) -> float | None:
        return self.p(95)

    @property
    def max(self) -> float | None:
        return max(self.samples) if self.samples else None


@dataclass
class SyncSample:
    t4: int
    offset: int
    rtt: int


class ClockSync:
    """Keeps the offset from the lowest-RTT sample in a sliding window (PROTOCOL.md)."""

    def __init__(self, window: int = 20):
        self.samples: deque[SyncSample] = deque(maxlen=window)
        self.history: deque[tuple[int, int]] = deque(maxlen=600)  # (t4, chosen offset) for drift
        self.last: SyncSample | None = None

    @staticmethod
    def compute(t_phone: int, rx: int, tx: int, t4: int) -> tuple[int, int]:
        offset = ((rx - t_phone) + (tx - t4)) // 2
        rtt = (t4 - t_phone) - (tx - rx)
        return offset, rtt

    def add(self, t_phone: int, rx: int, tx: int, t4: int) -> tuple[int, int]:
        offset, rtt = self.compute(t_phone, rx, tx, t4)
        if rtt < 0 or tx < rx:
            return offset, rtt  # impossible sample (clock glitch / forged): ignore
        self.last = SyncSample(t4, offset, rtt)
        self.samples.append(self.last)
        self.history.append((t4, self.offset))
        return offset, rtt

    @property
    def synced(self) -> bool:
        return bool(self.samples)

    def _best(self) -> SyncSample | None:
        return min(self.samples, key=lambda s: s.rtt) if self.samples else None

    @property
    def offset(self) -> int | None:
        b = self._best()
        return b.offset if b else None

    @property
    def rtt(self) -> int | None:
        b = self._best()
        return b.rtt if b else None

    def board_time(self, phone_us: int) -> int | None:
        o = self.offset
        return None if o is None else phone_us + o

    def drift_ppm(self, min_span_s: float = 3.0) -> float | None:
        """Least-squares slope of the chosen offset over time, in ppm."""
        pts = list(self.history)
        if len(pts) < 5 or (pts[-1][0] - pts[0][0]) < min_span_s * 1e6:
            return None
        n = len(pts)
        mx = sum(p[0] for p in pts) / n
        my = sum(p[1] for p in pts) / n
        sxx = sum((p[0] - mx) ** 2 for p in pts)
        if sxx == 0:
            return None
        sxy = sum((p[0] - mx) * (p[1] - my) for p in pts)
        return sxy / sxx * 1e6


@dataclass
class _Partial:
    first_rx_us: int
    total_len: int
    count: int
    w: int
    h: int
    t_cap_us: int
    chunks: dict[int, tuple[int, bytes]] = field(default_factory=dict)


@dataclass
class AssembledFrame:
    frame_id: int
    t_cap_board_us: int
    w: int
    h: int
    jpeg: bytes
    first_rx_us: int
    done_rx_us: int


class FrameAssembler:
    """Reassembles FRAME chunks. A frame not complete within `timeout_us` of its first chunk is
    dropped (PROTOCOL.md: 150 ms). Inconsistent chunks discard the frame."""

    def __init__(self, timeout_us: int = 150_000):
        self.timeout_us = timeout_us
        self.partial: dict[int, _Partial] = {}
        self.completed = 0
        self.dropped_timeout = 0
        self.dropped_bad = 0

    def add(self, f, rx_us: int) -> AssembledFrame | None:
        self.expire(rx_us)
        if f.chunk_count == 0 or f.chunk_idx >= f.chunk_count or f.offset + len(f.data) > f.total_len:
            self.dropped_bad += 1
            return None
        p = self.partial.get(f.frame_id)
        if p is None:
            p = self.partial[f.frame_id] = _Partial(rx_us, f.total_len, f.chunk_count, f.w, f.h, f.t_cap_us)
        elif (p.total_len, p.count, p.t_cap_us) != (f.total_len, f.chunk_count, f.t_cap_us):
            del self.partial[f.frame_id]
            self.dropped_bad += 1
            return None
        p.chunks[f.chunk_idx] = (f.offset, f.data)
        if len(p.chunks) < p.count:
            return None
        del self.partial[f.frame_id]
        buf = bytearray(p.total_len)
        filled = 0
        for off, data in p.chunks.values():
            buf[off:off + len(data)] = data
            filled += len(data)
        if filled != p.total_len:
            self.dropped_bad += 1
            return None
        self.completed += 1
        return AssembledFrame(f.frame_id, p.t_cap_us, p.w, p.h, bytes(buf), p.first_rx_us, rx_us)

    def expire(self, now_us: int):
        for fid in [k for k, p in self.partial.items() if now_us - p.first_rx_us > self.timeout_us]:
            del self.partial[fid]
            self.dropped_timeout += 1
