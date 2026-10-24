#!/usr/bin/env python3
"""Bench: act as the PHONE against the real boards (Gate G0 bring-up, Gate G3 cue path) or
against sim_boards.py for self-test.

Binds UDP 47000, learns each board's address from its first packet (or --cue-ip/--cam-ip),
pings both at 10 Hz, and shows per-board offset/RTT/drift/BEAT faults, the live 8×8 ToF grid
(mm + status), camera frame rate/sizes, and command→ACK latency. Sends test cues and PROFILE.

Examples
  bench.py                                   # live dashboard; type commands + Enter (h for help)
  bench.py --cue OBSTACLE_NEAR --side L --gain -20
  bench.py --profile -25 --mode STEREO --haptic-max 200
  bench.py --latency 50 --cue OBSTACLE_FAR    # command→ACK p50/p95 over 50 cues
  bench.py --fault-test                       # stop pinging; verify the cue-board watchdog
  bench.py --duration 10 --json --save-jpeg /tmp/latest.jpg

Latency here is command→ACK (CUE send → ACK receipt on this host). Command→sound for Gate G3
additionally needs the board's audio start, e.g. measured with a microphone.
"""
from __future__ import annotations

import argparse
import json
import os
import queue
import socket
import sys
import threading
import time
from collections import Counter, deque
from dataclasses import dataclass, field
from typing import Callable

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from dtwire import (ACK_NAMES, CUE_NAMES, FAULT_WATCHDOG_ACTIVE, MODE_BY_NAME, SIDE_BY_NAME, dw,  # noqa: E402
                    fault_names)
from phonelib import AssembledFrame, ClockSync, FrameAssembler, LatencyStats  # noqa: E402
from simclock import host_us  # noqa: E402

PING_PERIOD_US = 100_000  # 10 Hz per board (PROTOCOL.md rates)
DEFAULT_HAPTIC = {"OBSTACLE_FAR": "PULSE40", "OBSTACLE_MID": "PULSE60", "OBSTACLE_NEAR": "BUZZ150",
                  "PERSON_FAR": "PULSE40", "PERSON_MID": "PULSE60", "PERSON_NEAR": "BUZZ150", "SYSTEM_OFF": "LSS"}
CAL_CUES = {"CAL_TONE_500", "CAL_TONE_1K", "CAL_TONE_2K", "CAL_OBSTACLE"}
ACK_TIMEOUT_US = 1_000_000


@dataclass
class BoardState:
    name: str
    addr: tuple[str, int] | None = None
    sync: ClockSync = field(default_factory=ClockSync)
    last_beat: "dw.Beat | None" = None
    last_beat_rx: int | None = None
    beat_rx: deque = field(default_factory=lambda: deque(maxlen=50))
    pings: int = 0
    pongs: int = 0
    next_ping: int = 0
    last_ping_sent: int | None = None
    first_rx: int | None = None


@dataclass
class PendingCue:
    seq: int
    kind: str  # "CUE" | "PROFILE"
    name: str
    sent_us: int
    gains: tuple[int, int]


@dataclass
class AckResult:
    seq: int
    kind: str
    name: str
    status: str
    latency_ms: float | None
    applied: tuple[int, int]
    requested: tuple[int, int]


class Bench:
    def __init__(self, *, bind: str = "0.0.0.0", port: int = dw.PORT_PHONE, cue_addr: tuple[str, int] | None = None,
                 cam_addr: tuple[str, int] | None = None, host: Callable[[], int] = host_us,
                 log: Callable[[str], None] | None = None, save_jpeg: str | None = None):
        self.host = host
        self.log = log or (lambda s: None)
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 21)
        self.sock.bind((bind, port))
        self.sock.settimeout(0.005)
        self.port = self.sock.getsockname()[1]
        self.boards = {"cue": BoardState("cue", cue_addr), "cam": BoardState("cam", cam_addr)}
        self.pinging = True
        self.seq: Counter = Counter()
        self.last_range: "dw.Range | None" = None
        self.range_rx: deque = deque(maxlen=60)
        self.frames = FrameAssembler()
        self.frame_done_rx: deque = deque(maxlen=60)
        self.frame_sizes: deque = deque(maxlen=60)
        self.last_frame: AssembledFrame | None = None
        self.last_frame_age_ms: float | None = None
        self.save_jpeg = save_jpeg
        self._last_save = 0
        self.pending: dict[int, PendingCue] = {}
        self.ack_lat = LatencyStats()
        self.ack_status: Counter = Counter()
        self.results: deque[AckResult] = deque(maxlen=200)
        self.missed_acks = 0
        self.malformed = 0
        self.unknown_src = 0
        self.beat_watchers: list[Callable[[str, "dw.Beat", int], None]] = []
        self.lock = threading.RLock()

    # -------------------------------------------------------------------- I/O
    def _send(self, board: str, msg) -> int | None:
        b = self.boards[board]
        if b.addr is None:
            return None
        # ACK.cue_seq names "the CUE or PROFILE header seq" but seq is per-type, so CUE n and
        # PROFILE n would be indistinguishable. CUE and PROFILE therefore share one counter here
        # (each type's seq still increases, with gaps).
        key = dw.Cue.TYPE if msg.TYPE in (dw.Cue.TYPE, dw.Profile.TYPE) else msg.TYPE
        seq = self.seq[key]
        self.seq[key] = (seq + 1) & 0xFFFFFFFF
        try:
            self.sock.sendto(dw.encode(msg, seq), b.addr)
        except OSError as e:
            self.log(f"send error to {board}: {e}")
            return None
        return seq

    def poll(self, duration_s: float = 0.0):
        """Receive/handle packets and run pings/timeouts for duration_s (at least one pass)."""
        end = self.host() + int(duration_s * 1e6)
        while True:
            self._service()
            try:
                pkt, addr = self.sock.recvfrom(2048)
            except (socket.timeout, BlockingIOError):
                pkt = None
            if pkt is not None:
                self.handle(pkt, addr, self.host())
                # drain anything else already queued without blocking
                self.sock.setblocking(False)
                try:
                    while True:
                        try:
                            pkt, addr = self.sock.recvfrom(2048)
                        except (BlockingIOError, InterruptedError):
                            break
                        self.handle(pkt, addr, self.host())
                finally:
                    self.sock.settimeout(0.005)
            if self.host() >= end:
                return

    def _service(self):
        now = self.host()
        with self.lock:
            if self.pinging:
                for b in self.boards.values():
                    if b.addr is not None and now >= b.next_ping:
                        self._send(b.name, dw.Ping(now))
                        b.pings += 1
                        b.last_ping_sent = now
                        b.next_ping = max(b.next_ping + PING_PERIOD_US, now)
            for seq, p in list(self.pending.items()):
                if now - p.sent_us > ACK_TIMEOUT_US:
                    del self.pending[seq]
                    self.missed_acks += 1
                    self.ack_status["MISSING"] += 1
                    self.results.append(AckResult(seq, p.kind, p.name, "MISSING", None, (0, 0), p.gains))
            self.frames.expire(now)

    def _board_for(self, msg, addr) -> str | None:
        if isinstance(msg, dw.Beat):
            return {dw.BOARD_CUE: "cue", dw.BOARD_CAM: "cam"}.get(msg.board)
        if isinstance(msg, (dw.Range, dw.Ack)):
            return "cue"
        if isinstance(msg, dw.Frame):
            return "cam"
        for b in self.boards.values():
            if b.addr == addr:
                return b.name
        return None

    def handle(self, pkt: bytes, addr, rx: int):
        try:
            seq, msg = dw.decode(pkt)
        except dw.WireError as e:
            self.malformed += 1
            self.log(f"malformed packet from {addr}: {e}")
            return
        name = self._board_for(msg, addr)
        if name is None:
            self.unknown_src += 1
            return
        with self.lock:
            b = self.boards[name]
            if b.addr != addr:
                if b.addr is not None:
                    self.log(f"{name} board address changed {b.addr} → {addr}")
                b.addr = addr  # learn from the first packet (PROTOCOL.md Transport)
                b.next_ping = rx
            if b.first_rx is None:
                b.first_rx = rx
            if isinstance(msg, dw.Pong):
                b.sync.add(msg.t_phone_us, msg.t_board_rx_us, msg.t_board_tx_us, rx)
                b.pongs += 1
            elif isinstance(msg, dw.Beat):
                b.last_beat, b.last_beat_rx = msg, rx
                b.beat_rx.append(rx)
                for w in self.beat_watchers:
                    w(name, msg, rx)
            elif isinstance(msg, dw.Range):
                self.last_range = msg
                self.range_rx.append(rx)
            elif isinstance(msg, dw.Frame):
                done = self.frames.add(msg, rx)
                if done is not None:
                    self._on_frame(done, rx)
            elif isinstance(msg, dw.Ack):
                self._on_ack(msg, rx)

    def _on_frame(self, f: AssembledFrame, rx: int):
        ok = f.jpeg[:2] == b"\xFF\xD8" and f.jpeg[-2:] == b"\xFF\xD9"
        if not ok:
            self.log(f"frame {f.frame_id}: corrupt JPEG (no SOI/EOI)")
            self.frames.dropped_bad += 1
            return
        self.last_frame = f
        self.frame_done_rx.append(rx)
        self.frame_sizes.append(len(f.jpeg))
        off = self.boards["cam"].sync.offset
        self.last_frame_age_ms = None if off is None else (rx - (f.t_cap_board_us - off)) / 1000.0
        if self.save_jpeg and rx - self._last_save > 1_000_000:
            tmp = self.save_jpeg + ".tmp"
            with open(tmp, "wb") as fh:
                fh.write(f.jpeg)
            os.replace(tmp, self.save_jpeg)
            self._last_save = rx

    def _on_ack(self, a: "dw.Ack", rx: int):
        p = self.pending.pop(a.cue_seq, None)
        st = ACK_NAMES.get(a.status, str(a.status))
        if p is None:
            self.log(f"ACK for unknown seq {a.cue_seq} status={st}")
            return
        lat = (rx - p.sent_us) / 1000.0
        self.ack_lat.add(lat)
        self.ack_status[st] += 1
        r = AckResult(a.cue_seq, p.kind, p.name, st, lat, (a.applied_gain_l_db, a.applied_gain_r_db), p.gains)
        self.results.append(r)
        self.log(f"ACK {p.kind} {p.name} seq={a.cue_seq} status={st} applied=({a.applied_gain_l_db},"
                 f"{a.applied_gain_r_db}) requested={p.gains} latency={lat:.1f} ms")

    # -------------------------------------------------------------------- commands
    def synced(self, board: str) -> bool:
        return self.boards[board].sync.synced

    def wait_for(self, pred: Callable[[], bool], timeout_s: float) -> bool:
        end = time.monotonic() + timeout_s
        while time.monotonic() < end:
            if pred():
                return True
            self.poll(0.02)
        return pred()

    def send_cue(self, name: str, side: int = dw.SIDE_BOTH, gain_l: int = -20, gain_r: int | None = None,
                 haptic: str | None = None, hl: int = 180, hr: int | None = None, ttl_ms: int | None = None,
                 flags: int = 0, no_sync: bool = False) -> int | None:
        """Builds a CUE exactly as the phone would: deadline = board-time estimate at issue + ttl.
        Refuses without clock sync unless no_sync=True (sends deadline 0 → expect ACK NO_SYNC)."""
        cue_id = dw.CUE_IDS[name]
        gain_r = gain_l if gain_r is None else gain_r
        hr = hl if hr is None else hr
        if side == dw.SIDE_L:
            gain_r, hr = dw.GAIN_MUTE, 0
        elif side == dw.SIDE_R:
            gain_l, hl = dw.GAIN_MUTE, 0
        haptic = haptic or DEFAULT_HAPTIC.get(name, "NONE")
        ttl_ms = ttl_ms if ttl_ms is not None else (1000 if name in CAL_CUES else 150)  # PROTOCOL.md CUE ttl
        with self.lock:
            now = self.host()
            bt = self.boards["cue"].sync.board_time(now)
            if bt is None and not no_sync:
                self.log("no clock sync with the cue board yet: CUE not sent (use --no-sync-test to force)")
                return None
            deadline = 0 if no_sync else bt + ttl_ms * 1000
            seq = self._send("cue", dw.Cue(now, deadline, cue_id, side, gain_l, gain_r, dw.HAPTIC[haptic],
                                           hl, hr, flags, ttl_ms))
            if seq is not None:
                self.pending[seq] = PendingCue(seq, "CUE", name, now, (gain_l, gain_r))
            return seq

    def send_profile(self, ceiling_db: int, mode: int = dw.MODE_STEREO, haptic_max: int = 255) -> int | None:
        with self.lock:
            seq = self._send("cue", dw.Profile(ceiling_db, mode, haptic_max))
            if seq is not None:
                self.pending[seq] = PendingCue(seq, "PROFILE", f"ceiling={ceiling_db}", self.host(),
                                               (ceiling_db, ceiling_db))
            return seq

    def wait_ack(self, seq: int, timeout_s: float = 1.5) -> AckResult | None:
        self.wait_for(lambda: seq not in self.pending, timeout_s)
        for r in reversed(self.results):
            if r.seq == seq:
                return r
        return None

    # -------------------------------------------------------------------- reporting
    @staticmethod
    def _rate(times: deque, now: int, window_us: int = 2_000_000) -> float:
        recent = [t for t in times if now - t <= window_us]
        if len(recent) < 2:
            return 0.0
        return (len(recent) - 1) / ((recent[-1] - recent[0]) / 1e6) if recent[-1] > recent[0] else 0.0

    def summary(self) -> dict:
        now = self.host()
        out: dict = {"boards": {}}
        for b in self.boards.values():
            bt = b.last_beat
            out["boards"][b.name] = {
                "addr": f"{b.addr[0]}:{b.addr[1]}" if b.addr else None,
                "offset_us": b.sync.offset, "rtt_us": b.sync.rtt,
                "last_rtt_us": b.sync.last.rtt if b.sync.last else None,
                "drift_ppm": b.sync.drift_ppm(), "pings": b.pings, "pongs": b.pongs,
                "beat_hz": round(self._rate(b.beat_rx, now), 2),
                "beat_age_ms": None if b.last_beat_rx is None else (now - b.last_beat_rx) / 1000.0,
                "faults": fault_names(bt.faults) if bt else None,
                "rssi_dbm": bt.rssi_dbm if bt else None, "heap_kb": bt.heap_kb if bt else None,
                "temp_c": None if (bt is None or bt.temp_c == 127) else bt.temp_c,
                "reset_reason": bt.reset_reason if bt else None,
            }
        out["range_hz"] = round(self._rate(self.range_rx, now), 2)
        out["frame_fps"] = round(self._rate(self.frame_done_rx, now), 2)
        out["frame_bytes_avg"] = int(sum(self.frame_sizes) / len(self.frame_sizes)) if self.frame_sizes else None
        out["frame_wh"] = [self.last_frame.w, self.last_frame.h] if self.last_frame else None
        out["frame_age_ms"] = self.last_frame_age_ms
        out["frames_completed"] = self.frames.completed
        out["frames_dropped_timeout"] = self.frames.dropped_timeout
        out["frames_dropped_bad"] = self.frames.dropped_bad
        out["ack_p50_ms"] = self.ack_lat.p50
        out["ack_p95_ms"] = self.ack_lat.p95
        out["ack_count"] = self.ack_lat.count
        out["ack_status"] = dict(self.ack_status)
        out["missed_acks"] = self.missed_acks
        out["malformed"] = self.malformed
        return out

    def render(self, color: bool = True) -> str:
        s = self.summary()
        lines = []
        for name in ("cue", "cam"):
            b = s["boards"][name]
            def f(v, fmt="{}"):
                return "-" if v is None else fmt.format(v)
            lines.append(f"{name.upper():3s} {f(b['addr']):21s} offset={f(b['offset_us'])} us  rtt={f(b['rtt_us'])} us "
                         f"(last {f(b['last_rtt_us'])})  drift={f(b['drift_ppm'], '{:+.1f}')} ppm  beat={b['beat_hz']:.1f} Hz "
                         f"age={f(b['beat_age_ms'], '{:.0f}')} ms  rssi={f(b['rssi_dbm'])}  heap={f(b['heap_kb'])} KB "
                         f"temp={f(b['temp_c'])}  reset={f(b['reset_reason'])}")
            lines.append(f"    faults: {', '.join(b['faults']) if b['faults'] else ('none' if b['faults'] == [] else '-')}")
        lines.append(f"RANGE {s['range_hz']:.1f} Hz")
        lines.extend(render_grid(self.last_range, color))
        lines.append(f"FRAME {s['frame_fps']:.1f} fps  size={s['frame_wh']} avg={s['frame_bytes_avg']} B  "
                     f"age={'-' if s['frame_age_ms'] is None else f'{s['frame_age_ms']:.0f} ms'}  "
                     f"completed={s['frames_completed']} timeout={s['frames_dropped_timeout']} bad={s['frames_dropped_bad']}")
        p50, p95 = s["ack_p50_ms"], s["ack_p95_ms"]
        lines.append(f"ACK   n={s['ack_count']} p50={'-' if p50 is None else f'{p50:.1f}'} ms "
                     f"p95={'-' if p95 is None else f'{p95:.1f}'} ms  status={s['ack_status']}  missed={s['missed_acks']}  "
                     f"pinging={'yes' if self.pinging else 'NO (watchdog test)'}")
        for r in list(self.results)[-3:]:
            lines.append(f"  last: {r.kind} {r.name} seq={r.seq} {r.status} applied={r.applied} "
                         f"{'' if r.latency_ms is None else f'{r.latency_ms:.1f} ms'}")
        return "\n".join(lines)

    def close(self):
        self.sock.close()


def render_grid(r: "dw.Range | None", color: bool = True) -> list[str]:
    """8×8 ToF heatmap: row 0 = top, col 0 = wearer's left. Valid zones show mm, '----' = NO_TARGET,
    '?NNN' = any other raw status (UNKNOWN for the phone; never 'far')."""
    if r is None:
        return ["  (no RANGE yet)"]
    out = ["      L " + " ".join(f"  c{c} " for c in range(8)) + " R"]
    for row in range(8):
        cells = []
        for col in range(8):
            z = row * 8 + col
            st, mm = r.status[z], r.mm[z]
            if st in dw.VALID_STATUS:
                txt = f"{mm:5d}"
                code = 196 if mm < 1000 else 208 if mm < 2000 else 226 if mm < 3000 else 46
            elif st == dw.STATUS_NO_TARGET:
                txt, code = " ----", 240
            else:
                txt, code = f" ?{st:3d}", 201
            cells.append(f"\x1b[38;5;{code}m{txt}\x1b[0m" if color else txt)
        out.append(f"  r{row}   " + " ".join(cells))
    out.append("  mm: <1000 NEAR  <2000 MID  <3000 FAR (Arch §7.2)   ---- NO_TARGET   ?s raw status = UNKNOWN")
    return out


# ------------------------------------------------------------------------ modes

def run_fault_test(bench: Bench, hold_s: float, out: Callable[[str], None]) -> bool:
    """Gate check of the board watchdog (PROTOCOL.md Watchdog): stop PINGs, expect BEAT fault bit 7
    within 800 ms + one BEAT period (+ margin), expect it to clear after PINGs resume."""
    if not bench.wait_for(lambda: bench.synced("cue") and bench.boards["cue"].last_beat is not None, 8.0):
        out("FAIL: cue board not synced / no BEAT within 8 s")
        return False
    bench.poll(1.5)
    b = bench.boards["cue"]
    if b.last_beat.faults & FAULT_WATCHDOG_ACTIVE:
        out("FAIL: watchdog already active while pinging")
        return False
    events: list[tuple[int, bool]] = []
    bench.beat_watchers.append(lambda name, beat, rx: events.append((rx, bool(beat.faults & FAULT_WATCHDOG_ACTIVE)))
                               if name == "cue" else None)
    with bench.lock:
        bench.pinging = False
        last_ping = b.last_ping_sent  # pings are sent only from _service; none after this point
    out(f"stopped pinging; holding {hold_s:.1f} s (listen for SYSTEM_OFF + LSS now and again after 5 s)")
    bench.poll(hold_s)
    on = next((rx for rx, a in events if a), None)
    with bench.lock:
        bench.pinging = True
        for bs in bench.boards.values():
            bs.next_ping = 0
        resume = bench.host()
    bench.poll(1.0)
    off = next((rx for rx, a in events if rx > resume and not a), None)
    ok = True
    if on is None:
        out("FAIL: BEAT never reported WATCHDOG_ACTIVE")
        ok = False
    else:
        dt = (on - last_ping) / 1000.0
        # Board enters ≥ 800 ms after the last PING it received; the flag shows on the next BEAT
        # (≤ 100 ms later) plus Wi-Fi delay. Well under 800 ms means the board's timeout is too short.
        good = 780.0 <= dt <= 1100.0
        out(f"{'PASS' if good else 'FAIL'}: WATCHDOG_ACTIVE seen {dt:.0f} ms after the last PING (expect 800–1000)")
        ok &= good
    if off is None:
        out("FAIL: WATCHDOG_ACTIVE did not clear within 1 s of resuming PINGs")
        ok = False
    else:
        out(f"PASS: watchdog cleared {(off - resume) / 1000.0:.0f} ms after PINGs resumed")
    return ok


def run_latency(bench: Bench, n: int, name: str, side: int, gain: int, interval_s: float,
                out: Callable[[str], None]) -> dict:
    if not bench.wait_for(lambda: bench.synced("cue"), 8.0):
        out("FAIL: no clock sync with the cue board within 8 s")
        return {}
    lat = LatencyStats()
    statuses: Counter = Counter()
    for _ in range(n):
        seq = bench.send_cue(name, side, gain)
        r = bench.wait_ack(seq, 1.2) if seq is not None else None
        if r is None or r.latency_ms is None:
            statuses["MISSING"] += 1
        else:
            statuses[r.status] += 1
            lat.add(r.latency_ms)
        bench.poll(max(0.0, interval_s - (r.latency_ms or 0) / 1000.0 if r else interval_s))
    res = {"n": n, "acked": lat.count, "p50_ms": lat.p50, "p95_ms": lat.p95, "max_ms": lat.max,
           "status": dict(statuses)}
    out(f"command→ACK over {n} × {name}: p50={_fmt(lat.p50)} ms p95={_fmt(lat.p95)} ms max={_fmt(lat.max)} ms "
        f"status={dict(statuses)}")
    return res


def _fmt(v):
    return "-" if v is None else f"{v:.2f}"


HELP = """commands (then Enter):
  f|m|n [L|R|B]        OBSTACLE_FAR / MID / NEAR at --gain (default side BOTH)
  s                    SYSTEM_OFF        r  READY
  c NAME [L|R|B] [gain] [haptic] [intensity] [ttl_ms]   any cue, e.g. c CAL_TONE_1K L -30 NONE 0 1000
  x                    forged CUE at 0 dBFS (Gate G4: expect PLAYED_CLAMPED at the ceiling)
  p CEILING [STEREO|SSD|HAPTIC_ONLY] [haptic_max]        send PROFILE
  w                    toggle PINGs (watchdog check)   j  save latest JPEG to ./bench_latest.jpg
  h                    help                           q  quit"""


def interactive(bench: Bench, args, color: bool, out: Callable[[str], None]):
    cmds: queue.Queue[str] = queue.Queue()

    def reader():
        for line in sys.stdin:
            cmds.put(line.strip())
        cmds.put("q")
    threading.Thread(target=reader, daemon=True).start()
    messages: deque[str] = deque(maxlen=6)
    bench.log = messages.append
    last_draw = 0.0
    while True:
        bench.poll(0.05)
        try:
            while True:
                c = cmds.get_nowait()
                if not c:
                    continue
                if c in ("q", "quit", "exit"):
                    return
                try:
                    messages.append(handle_command(bench, c, args))
                except (ValueError, KeyError, IndexError) as e:
                    messages.append(f"bad command {c!r}: {e}")
        except queue.Empty:
            pass
        if time.monotonic() - last_draw >= args.refresh:
            last_draw = time.monotonic()
            screen = bench.render(color) + "\n" + "\n".join(messages) + "\n> "
            if color:
                sys.stdout.write("\x1b[H\x1b[2J" + screen)
                sys.stdout.flush()
            else:
                out(screen)


def _side(tok: str | None) -> int:
    return dw.SIDE_BOTH if tok is None else SIDE_BY_NAME[tok.upper()]


def handle_command(bench: Bench, c: str, args) -> str:
    t = c.split()
    k = t[0].lower()
    if k in ("f", "m", "n"):
        name = {"f": "OBSTACLE_FAR", "m": "OBSTACLE_MID", "n": "OBSTACLE_NEAR"}[k]
        seq = bench.send_cue(name, _side(t[1] if len(t) > 1 else None), args.gain)
    elif k == "s":
        seq = bench.send_cue("SYSTEM_OFF", dw.SIDE_BOTH, args.gain)
    elif k == "r":
        seq = bench.send_cue("READY", dw.SIDE_BOTH, args.gain)
    elif k == "x":
        seq = bench.send_cue("OBSTACLE_FAR", dw.SIDE_BOTH, 0)
    elif k == "c":
        name = t[1].upper()
        seq = bench.send_cue(name, _side(t[2] if len(t) > 2 else None), int(t[3]) if len(t) > 3 else args.gain,
                             haptic=t[4].upper() if len(t) > 4 else None, hl=int(t[5]) if len(t) > 5 else 180,
                             ttl_ms=int(t[6]) if len(t) > 6 else None)
    elif k == "p":
        mode = MODE_BY_NAME[t[2].upper()] if len(t) > 2 else dw.MODE_STEREO
        seq = bench.send_profile(int(t[1]), mode, int(t[3]) if len(t) > 3 else 255)
        return f"PROFILE sent seq={seq}"
    elif k == "w":
        with bench.lock:
            bench.pinging = not bench.pinging
            for b in bench.boards.values():
                b.next_ping = 0
        return f"pinging {'resumed' if bench.pinging else 'STOPPED: cue board should enter WATCHDOG after 800 ms'}"
    elif k == "j":
        if bench.last_frame is None:
            return "no frame yet"
        with open("bench_latest.jpg", "wb") as fh:
            fh.write(bench.last_frame.jpeg)
        return f"saved bench_latest.jpg ({len(bench.last_frame.jpeg)} B)"
    elif k == "h":
        return HELP
    else:
        raise ValueError("unknown command (h for help)")
    return f"CUE sent seq={seq}" if seq is not None else "CUE not sent (no cue board address or no sync)"


def _addr(s: str | None, port: int):
    if not s:
        return None
    if ":" in s:
        h, p = s.rsplit(":", 1)
        return (h, int(p))
    return (s, port)


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--bind", default="0.0.0.0")
    p.add_argument("--port", type=int, default=dw.PORT_PHONE, help="phone port to bind (default 47000)")
    p.add_argument("--cue-ip", help="cue board IP[:port] (default: learned from its first packet)")
    p.add_argument("--cam-ip", help="camera board IP[:port] (default: learned from its first packet)")
    p.add_argument("--cue", metavar="NAME", choices=sorted(dw.CUE_IDS), help="send this cue (or the --latency cue)")
    p.add_argument("--side", default="BOTH", help="L | R | BOTH (default BOTH)")
    p.add_argument("--gain", type=int, default=-20, help="dBFS, ≤ 0 (default -20; board clamps to its ceiling)")
    p.add_argument("--gain-r", type=int, help="right gain if different from --gain")
    p.add_argument("--haptic", choices=sorted(dw.HAPTIC), help="haptic pattern (default per cue)")
    p.add_argument("--intensity", type=int, default=180, help="haptic intensity 0–255 (default 180)")
    p.add_argument("--ttl", type=int, help="ms (default 150; 1000 for CAL cues)")
    p.add_argument("--interrupt", action="store_true", help="set flags bit0 (interrupt current sound)")
    p.add_argument("--count", type=int, default=1, help="repeat --cue N times")
    p.add_argument("--interval", type=float, default=0.5, help="seconds between repeated cues (default 0.5)")
    p.add_argument("--no-sync-test", action="store_true", help="send deadline 0 (expect ACK NO_SYNC)")
    p.add_argument("--profile", type=int, metavar="CEILING_DB", help="send PROFILE with this ceiling")
    p.add_argument("--mode", default="STEREO", choices=sorted(MODE_BY_NAME), help="PROFILE mode")
    p.add_argument("--haptic-max", type=int, default=255, help="PROFILE haptic_max")
    p.add_argument("--latency", type=int, metavar="N", help="measure command→ACK p50/p95 over N cues")
    p.add_argument("--fault-test", action="store_true", help="stop pinging; verify the cue-board watchdog")
    p.add_argument("--fault-hold", type=float, default=6.0, help="seconds without PING in --fault-test (default 6)")
    p.add_argument("--duration", type=float, help="run the dashboard/plain monitor this many seconds then exit")
    p.add_argument("--json", action="store_true", help="print the final summary as JSON")
    p.add_argument("--plain", action="store_true", help="no ANSI screen (log-friendly)")
    p.add_argument("--refresh", type=float, default=0.5, help="dashboard refresh seconds (default 0.5)")
    p.add_argument("--save-jpeg", metavar="PATH", help="keep saving the latest complete JPEG here (≤ 1/s)")
    p.add_argument("--sync-timeout", type=float, default=8.0, help="seconds to wait for clock sync (default 8)")
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    def out(s: str):
        sys.stdout.write(s + "\n")
        sys.stdout.flush()

    bench = Bench(bind=args.bind, port=args.port, cue_addr=_addr(args.cue_ip, dw.PORT_CUE),
                  cam_addr=_addr(args.cam_ip, dw.PORT_CAM), log=out, save_jpeg=args.save_jpeg)
    color = sys.stdout.isatty() and not args.plain
    rc = 0
    try:
        oneshot = args.fault_test or args.latency or args.cue or args.profile is not None
        if args.profile is not None:
            if not bench.wait_for(lambda: bench.boards["cue"].addr is not None, args.sync_timeout):
                out("FAIL: cue board not heard")
                return 2
            seq = bench.send_profile(args.profile, MODE_BY_NAME[args.mode], args.haptic_max)
            r = bench.wait_ack(seq) if seq is not None else None
            out(f"PROFILE seq={seq}: {r.status if r else 'NO ACK'}" + (f" applied ceiling={r.applied[0]}" if r else ""))
            rc |= 0 if (r and r.status == "PLAYED") else 1
        if args.fault_test:
            rc |= 0 if run_fault_test(bench, args.fault_hold, out) else 1
        if args.latency:
            res = run_latency(bench, args.latency, args.cue or "OBSTACLE_FAR", _side(args.side), args.gain,
                              args.interval, out)
            rc |= 0 if res and res.get("acked") == args.latency else 1
        elif args.cue:
            if not args.no_sync_test and not bench.wait_for(lambda: bench.synced("cue"), args.sync_timeout):
                out("FAIL: no clock sync with the cue board")
                return 2
            if args.no_sync_test:
                bench.wait_for(lambda: bench.boards["cue"].addr is not None, args.sync_timeout)
            for i in range(args.count):
                seq = bench.send_cue(args.cue, _side(args.side), args.gain, args.gain_r, args.haptic, args.intensity,
                                     ttl_ms=args.ttl, flags=1 if args.interrupt else 0, no_sync=args.no_sync_test)
                r = bench.wait_ack(seq) if seq is not None else None
                if r is None:
                    out(f"CUE {args.cue} seq={seq}: NO ACK")
                    rc |= 1
                if i + 1 < args.count:
                    bench.poll(args.interval)
        if not oneshot or args.duration:
            if args.duration:
                end = time.monotonic() + args.duration
                last = 0.0
                while time.monotonic() < end:
                    bench.poll(0.05)
                    if not args.json and time.monotonic() - last >= max(args.refresh, 1.0 if not color else args.refresh):
                        last = time.monotonic()
                        out(("\x1b[H\x1b[2J" if color else "") + bench.render(color))
            elif sys.stdin.isatty():
                out(HELP)
                interactive(bench, args, color, out)
            else:
                while True:
                    bench.poll(1.0)
                    out(bench.render(False))
        if args.json:
            out(json.dumps(bench.summary(), indent=2, default=str))
    except KeyboardInterrupt:
        pass
    finally:
        bench.close()
    return rc


if __name__ == "__main__":
    sys.exit(main())
