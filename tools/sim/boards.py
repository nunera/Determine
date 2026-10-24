"""Simulators of both wearable boards (cue board = XIAO ESP32-C3, camera board = ESP32-CAM).

They speak protocol v1 through the reference codec and behave like the firmware:
- each board has its own µs-since-boot clock with a distinct offset and drift (simclock.py);
- PING → PONG with rx stamped on receipt and tx stamped just before send;
- BEAT at 10 Hz; NO_CLOCK_SYNC until the first PING; WATCHDOG_ACTIVE while in watchdog;
- cue board: RANGE at 15 Hz from the scenario's scene (scene.py), CUE/PROFILE handling that
  mirrors firmware/cue-board/lib/cue_logic.c (dt_cue_decide, dt_profile_apply, dt_wd_update)
  — deadline vs board clock, gain clamp to min(profile ceiling, FW hard cap −10 dBFS), BUSY
  unless the interrupt flag is set — ACK for every CUE/PROFILE, and the PING watchdog
  (no PING 800 ms, or none 5 s after boot → SYSTEM_OFF + LSS every 5 s at min(ceiling, −20));
- camera board: FRAME chunks at 10–12 fps from small valid JPEGs.
Dropouts make a board silent on the network in both directions; NetProfile adds loss/jitter.
"""
from __future__ import annotations

import heapq
import random
import select
import socket
import threading
from collections import Counter
from dataclasses import dataclass
from typing import Callable

import jpeg as jpg
from dtwire import (ACK_NAMES, CUE_NAMES, FAULT_NO_CLOCK_SYNC, FAULT_WATCHDOG_ACTIVE, HAPTIC_NAMES, MODE_NAMES,
                    SIDE_NAMES, WATCHDOG_BOOT_GRACE_S, WATCHDOG_GAIN_DB, WATCHDOG_NO_PING_S, WATCHDOG_REPEAT_S, dw)
from netem import Impairment
from scenarios import Scenario
from scene import project
from simclock import BoardClock, host_us

CUE_FLAG_INTERRUPT = 0x01
ESP_RST_POWERON = 1

# Audible length of each cue (notes + internal gaps), from the firmware cue bank
# (firmware/cue-board/lib/cue_logic/cue_logic.c k_cues; Arch §8.1 timing grammar).
CUE_LENGTH_MS = {
    0: 85, 1: 210, 2: 195, 3: 90, 4: 220, 5: 200, 6: 90, 7: 440, 8: 300, 9: 300, 10: 300, 11: 210,
}
# Haptic pattern lengths (PROTOCOL.md "Haptic patterns").
HAPTIC_LENGTH_MS = {0: 0, 1: 40, 2: 60, 3: 150, 4: 400 + 100 + 120 + 100 + 120}


# ---------------------------------------------------------------------- pure cue-board logic

def clamp_gain(requested_db: int, ceiling_db: int) -> int:
    """dt_clamp_gain: never above min(profile ceiling, FW hard cap); mute stays mute (Invariant 4)."""
    cap = min(ceiling_db, dw.FW_HARD_CEILING_DB)
    if requested_db == dw.GAIN_MUTE:
        return dw.GAIN_MUTE
    return cap if requested_db > cap else requested_db


def cue_time_check(deadline_board_us: int, now_board_us: int) -> int:
    """dt_cue_time_check: never play without sync or after the deadline (Invariant 5)."""
    if deadline_board_us == 0:
        return dw.ACK_NO_SYNC
    if now_board_us > deadline_board_us:
        return dw.ACK_EXPIRED
    return dw.ACK_PLAYED


@dataclass
class CueDecision:
    status: int
    play: bool
    gain_l_db: int = dw.GAIN_MUTE
    gain_r_db: int = dw.GAIN_MUTE
    haptic_l: int = 0
    haptic_r: int = 0


@dataclass
class ProfileState:
    ceiling_db: int = dw.FW_HARD_CEILING_DB  # boot default: hard cap, STEREO, full haptics
    mode: int = dw.MODE_STEREO
    haptic_max: int = 255

    def apply(self, p: "dw.Profile") -> bool:
        """dt_profile_apply: unknown mode rejected; ceiling can only go to/below the hard cap."""
        if p.mode > dw.MODE_HAPTIC_ONLY:
            return False
        self.ceiling_db = min(p.ceiling_db, dw.FW_HARD_CEILING_DB)
        self.mode = p.mode
        self.haptic_max = p.haptic_max
        return True


def decide_cue(cue: "dw.Cue", now_board_us: int, prof: ProfileState, output_busy: bool) -> CueDecision:
    """Mirror of dt_cue_decide (cue_logic.c): BAD_CUE → NO_SYNC/EXPIRED → BUSY_DROPPED → PLAYED[_CLAMPED]."""
    if cue.cue_id not in CUE_LENGTH_MS or cue.side > dw.SIDE_BOTH or cue.haptic_pattern not in HAPTIC_LENGTH_MS:
        return CueDecision(dw.ACK_BAD_CUE, False)
    t = cue_time_check(cue.deadline_board_us, now_board_us)
    if t != dw.ACK_PLAYED:
        return CueDecision(t, False)
    if output_busy and not (cue.flags & CUE_FLAG_INTERRUPT):
        return CueDecision(dw.ACK_BUSY_DROPPED, False)
    gl = clamp_gain(cue.gain_l_db, prof.ceiling_db)
    gr = clamp_gain(cue.gain_r_db, prof.ceiling_db)
    status = dw.ACK_PLAYED_CLAMPED if (gl != cue.gain_l_db or gr != cue.gain_r_db) else dw.ACK_PLAYED
    left = cue.side != dw.SIDE_R
    right = cue.side != dw.SIDE_L
    audio = prof.mode != dw.MODE_HAPTIC_ONLY  # HAPTIC_ONLY mutes audio (PROTOCOL.md PROFILE)
    d = CueDecision(status, True, gl if (left and audio) else dw.GAIN_MUTE, gr if (right and audio) else dw.GAIN_MUTE)
    if cue.haptic_pattern != 0:
        d.haptic_l = min(cue.haptic_l, prof.haptic_max) if left else 0
        d.haptic_r = min(cue.haptic_r, prof.haptic_max) if right else 0
    return d


class Watchdog:
    """Mirror of dt_wd_* (cue_logic.c), all times in board µs since boot (PROTOCOL.md Watchdog)."""
    PING_TIMEOUT_US = int(WATCHDOG_NO_PING_S * 1e6)
    BOOT_GRACE_US = int(WATCHDOG_BOOT_GRACE_S * 1e6)
    ALARM_PERIOD_US = int(WATCHDOG_REPEAT_S * 1e6)

    def __init__(self):
        self.active = False
        self.ever_pinged = False
        self.last_ping_us = 0
        self.last_alarm_us = 0

    def on_ping(self, now_us: int):
        self.ever_pinged = True
        self.last_ping_us = now_us
        self.active = False

    def update(self, now_us: int) -> bool:
        """True when SYSTEM_OFF + LSS must be played now (on entry and every 5 s while active)."""
        starved = (now_us - self.last_ping_us >= self.PING_TIMEOUT_US) if self.ever_pinged \
            else (now_us >= self.BOOT_GRACE_US)
        if not starved:
            self.active = False
            return False
        if not self.active:
            self.active = True
            self.last_alarm_us = now_us
            return True
        if now_us - self.last_alarm_us >= self.ALARM_PERIOD_US:
            self.last_alarm_us = now_us
            return True
        return False

    def next_check_us(self, now_us: int) -> int:
        """Board time at which update() could next change its answer."""
        if self.active:
            return self.last_alarm_us + self.ALARM_PERIOD_US
        if self.ever_pinged:
            return self.last_ping_us + self.PING_TIMEOUT_US
        return max(now_us, self.BOOT_GRACE_US)


def watchdog_gain_db(ceiling_db: int) -> int:
    """dt_wd_gain_db: min(profile ceiling, −20 dBFS), then the normal clamp."""
    return clamp_gain(min(ceiling_db, WATCHDOG_GAIN_DB), ceiling_db)


# ---------------------------------------------------------------------- networked boards

LogFn = Callable[[str], None]


class SimBoard:
    BOARD_ID = 0
    NAME = "?"

    def __init__(self, *, clock: BoardClock, scenario: Scenario, t0_host_us: int, phone_addr: tuple[str, int],
                 bind: str, port: int, log: LogFn, seed: int, host: Callable[[], int] = host_us):
        self.clock = clock
        self.scenario = scenario
        self.t0 = t0_host_us
        self.phone_addr = phone_addr
        self.log = log
        self.host = host
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1 << 20)
        self.sock.bind((bind, port))
        self.sock.setblocking(False)
        self.port = self.sock.getsockname()[1]
        self.seq: Counter = Counter()
        self.rng = random.Random(seed * 1000 + self.BOARD_ID)
        self.imp_out = Impairment(scenario.net, random.Random(seed * 1000 + 10 + self.BOARD_ID))
        self.imp_in = Impairment(scenario.net, random.Random(seed * 1000 + 20 + self.BOARD_ID))
        self._outq: list = []
        self._inq: list = []
        self._n = 0
        self.ever_pinged = False
        self.next_beat = t0_host_us
        self.stats: Counter = Counter()
        self._was_down = False

    # -- helpers
    def scen_t(self, h: int) -> float:
        return (h - self.t0) / 1e6

    def down(self, t: float) -> bool:
        return False

    def faults(self) -> int:
        return 0 if self.ever_pinged else FAULT_NO_CLOCK_SYNC

    def send(self, msg, h: int):
        typ = msg.TYPE
        pkt = dw.encode(msg, self.seq[typ])
        self.seq[typ] = (self.seq[typ] + 1) & 0xFFFFFFFF
        if self.down(self.scen_t(h)):
            self.stats["tx_suppressed_dropout"] += 1
            return
        d = self.imp_out.decide()
        if d is None:
            self.stats["tx_lost"] += 1
            return
        if d <= 0:
            self._tx(pkt)
        else:
            self._n += 1
            heapq.heappush(self._outq, (h + int(d * 1e6), self._n, pkt))

    def _tx(self, pkt: bytes):
        try:
            self.sock.sendto(pkt, self.phone_addr)
            self.stats[f"tx_{pkt[3]:#04x}"] += 1
        except OSError:
            self.stats["tx_error"] += 1

    def _rx_all(self, h: int):
        while True:
            try:
                pkt, _addr = self.sock.recvfrom(2048)
            except (BlockingIOError, InterruptedError):
                return
            except OSError:
                self.stats["rx_error"] += 1
                return
            if self.down(self.scen_t(h)):
                self.stats["rx_ignored_dropout"] += 1
                continue
            d = self.imp_in.decide()
            if d is None:
                self.stats["rx_lost"] += 1
                continue
            if d <= 0:
                self.handle(pkt, self.host())
            else:
                self._n += 1
                heapq.heappush(self._inq, (h + int(d * 1e6), self._n, pkt))

    def handle(self, pkt: bytes, h: int):
        try:
            seq, msg = dw.decode(pkt)
        except dw.WireError:
            self.stats["rx_malformed"] += 1  # firmware drops silently (dt_validate)
            return
        self.stats[f"rx_{type(msg).__name__.lower()}"] += 1
        if isinstance(msg, dw.Ping):
            rx = self.clock.at(h)
            self.on_ping(rx)
            tx = self.clock.now()  # as late as possible before send, like the firmware
            self.send(dw.Pong(msg.t_phone_us, rx, tx), self.host())
        else:
            self.on_message(seq, msg, h)

    def on_ping(self, rx_board_us: int):
        if not self.ever_pinged:
            self.log(f"SYNC board={self.NAME} first_ping t={rx_board_us / 1e6:.6f} host={self.host()}")
        self.ever_pinged = True

    def on_message(self, seq: int, msg, h: int):
        self.stats["rx_ignored_type"] += 1  # e.g. CUE sent to the camera board

    def send_beat(self, h: int):
        self.send(dw.Beat(self.BOARD_ID, ESP_RST_POWERON, self.faults(), self.clock.at(h),
                          -52 - self.rng.randint(0, 8), self.temp_c(), self.heap_kb()), h)

    def temp_c(self) -> int:
        return 127

    def heap_kb(self) -> int:
        return 150

    def periodic(self, h: int) -> int:
        """Run due periodic work; return the next host time anything is due."""
        if h >= self.next_beat:
            self.send_beat(h)
            self.next_beat += 100_000  # 10 Hz
            if self.next_beat < h:
                self.next_beat = h + 100_000
        return self.next_beat

    # -- loop
    def step(self) -> int:
        h = self.host()
        t = self.scen_t(h)
        is_down = self.down(t)
        if is_down != self._was_down:
            self._was_down = is_down
            self.log(f"SIM host={h} t={t:.3f} event={'dropout_start' if is_down else 'dropout_end'} board={self.NAME}")
        while self._outq and self._outq[0][0] <= h:
            _, _, pkt = heapq.heappop(self._outq)
            if self.down(t):
                self.stats["tx_suppressed_dropout"] += 1
            else:
                self._tx(pkt)
        while self._inq and self._inq[0][0] <= h:
            _, _, pkt = heapq.heappop(self._inq)
            if not self.down(t):
                self.handle(pkt, self.host())
        nxt = self.periodic(self.host())
        for q in (self._outq, self._inq):
            if q:
                nxt = min(nxt, q[0][0])
        return nxt

    def run(self, stop: threading.Event):
        while not stop.is_set():
            self._rx_all(self.host())
            nxt = self.step()
            timeout = max(0.0, min((nxt - self.host()) / 1e6, 0.005))
            try:
                select.select([self.sock], [], [], timeout)
            except (OSError, ValueError):
                return

    def close(self):
        self.sock.close()


class CueBoardSim(SimBoard):
    BOARD_ID = dw.BOARD_CUE
    NAME = "cue"
    RANGE_PERIOD_US = 1_000_000 // 15  # 15 Hz (PROTOCOL.md RANGE)
    SENSOR_LATENCY_US = 3_000          # sensor-ready → send (I²C readout)

    def __init__(self, **kw):
        super().__init__(**kw)
        self.profile = ProfileState()
        self.wd = Watchdog()
        self.audio_busy_until = 0   # board µs
        self.haptic_busy_until = 0
        self.next_range = self.t0
        self.range_rng = random.Random(self.rng.random())

    def down(self, t: float) -> bool:
        return self.scenario.cue_down(t)

    def faults(self) -> int:
        f = super().faults()  # NO_CLOCK_SYNC until the first PING
        if self.wd.active:
            f |= FAULT_WATCHDOG_ACTIVE
        return f

    def heap_kb(self) -> int:
        return 168

    def temp_c(self) -> int:
        return 38

    def output_busy(self, now_b: int) -> bool:
        return now_b < self.audio_busy_until or now_b < self.haptic_busy_until

    def _start_output(self, cue_id: int, pattern: int, hl: int, hr: int, now_b: int):
        self.audio_busy_until = now_b + CUE_LENGTH_MS[cue_id] * 1000
        self.haptic_busy_until = now_b + (HAPTIC_LENGTH_MS[pattern] * 1000 if (hl or hr) else 0)

    def on_ping(self, rx_board_us: int):
        if self.wd.active:
            self.log(f"WATCHDOG exit t={rx_board_us / 1e6:.6f} host={self.host()} reason=ping")
        super().on_ping(rx_board_us)
        self.wd.on_ping(rx_board_us)

    def on_message(self, seq: int, msg, h: int):
        if isinstance(msg, dw.Cue):
            now_b = self.clock.at(h)
            d = decide_cue(msg, now_b, self.profile, self.output_busy(now_b))
            if d.play:
                self._start_output(msg.cue_id, msg.haptic_pattern, d.haptic_l, d.haptic_r, now_b)
            self.send(dw.Ack(seq, d.status, d.gain_l_db, d.gain_r_db, self.clock.now()), self.host())
            late = now_b - msg.deadline_board_us if msg.deadline_board_us else 0
            self.stats[f"cue_{ACK_NAMES[d.status]}"] += 1
            self.log(f"CUE t={now_b / 1e6:.6f} id={CUE_NAMES.get(msg.cue_id, msg.cue_id)} "
                     f"side={SIDE_NAMES.get(msg.side, msg.side)} gl={d.gain_l_db} gr={d.gain_r_db} "
                     f"haptic={HAPTIC_NAMES.get(msg.haptic_pattern, msg.haptic_pattern)} status={ACK_NAMES[d.status]} "
                     f"hl={d.haptic_l} hr={d.haptic_r} rgl={msg.gain_l_db} rgr={msg.gain_r_db} flags={msg.flags} "
                     f"ttl={msg.ttl_ms} seq={seq} deadline={msg.deadline_board_us} late_us={late} "
                     f"ceiling={self.profile.ceiling_db} src=phone host={h}")
        elif isinstance(msg, dw.Profile):
            ok = self.profile.apply(msg)
            p = self.profile
            self.send(dw.Ack(seq, dw.ACK_PLAYED if ok else dw.ACK_BAD_CUE, p.ceiling_db, p.ceiling_db,
                             self.clock.now()), self.host())
            self.log(f"PROFILE t={self.clock.at(h) / 1e6:.6f} {'applied' if ok else 'REJECTED'} seq={seq} "
                     f"req_ceiling={msg.ceiling_db} ceiling={p.ceiling_db} mode={MODE_NAMES.get(p.mode, p.mode)} "
                     f"haptic_max={p.haptic_max} host={h}")
        else:
            super().on_message(seq, msg, h)

    def periodic(self, h: int) -> int:
        nxt = super().periodic(h)
        now_b = self.clock.at(h)
        was_active = self.wd.active
        if self.wd.update(now_b):
            # Failure is loud (Invariant 2): SYSTEM_OFF + LSS every 5 s at min(ceiling, −20 dBFS).
            p = self.profile
            g = dw.GAIN_MUTE if p.mode == dw.MODE_HAPTIC_ONLY else watchdog_gain_db(p.ceiling_db)
            hap = min(255, p.haptic_max)
            self._start_output(dw.CUE_IDS["SYSTEM_OFF"], dw.HAPTIC["LSS"], hap, hap, now_b)
            if not was_active:
                why = "no_ping_800ms" if self.wd.ever_pinged else "no_ping_since_boot"
                self.log(f"WATCHDOG enter t={now_b / 1e6:.6f} host={h} reason={why}")
            self.stats["watchdog_alarm"] += 1
            self.log(f"CUE t={now_b / 1e6:.6f} id=SYSTEM_OFF side=BOTH gl={g} gr={g} haptic=LSS status=PLAYED "
                     f"hl={hap} hr={hap} seq=- deadline=- late_us=0 ceiling={p.ceiling_db} src=watchdog host={h}")
        nxt = min(nxt, self.clock.host_at(self.wd.next_check_us(now_b)) + 1)
        if h >= self.next_range:
            self.send_range(h)
            self.next_range += self.RANGE_PERIOD_US
            if self.next_range < h:
                self.next_range = h + self.RANGE_PERIOD_US
        return min(nxt, self.next_range)

    def send_range(self, h: int):
        t_sensor = h - self.SENSOR_LATENCY_US
        t = self.scen_t(t_sensor)
        mm, status = project(self.scenario.objects(t), self.scenario.invalid_zones(t, self.range_rng),
                             self.range_rng)
        self.send(dw.Range(self.clock.at(t_sensor), mm, status), h)


class CamBoardSim(SimBoard):
    BOARD_ID = dw.BOARD_CAM
    NAME = "cam"
    CAPTURE_TO_SEND_US = 45_000  # exposure + on-chip JPEG encode (Arch §6.3 budget ≤ 60 ms)

    def __init__(self, *, jpegs: list[bytes], fps: float = 11.0, **kw):
        super().__init__(**kw)
        if not 10.0 <= fps <= 15.0:
            raise ValueError("fps must be within 10–15 (PROTOCOL.md rates)")
        self.jpegs = jpegs
        self.fps = fps
        self.frame_id = 0
        self.next_frame = self.t0 + 50_000

    def down(self, t: float) -> bool:
        return self.scenario.cam_down(t)

    def heap_kb(self) -> int:
        return 3900  # PSRAM present

    def temp_c(self) -> int:
        return 47

    def periodic(self, h: int) -> int:
        nxt = super().periodic(h)
        if h >= self.next_frame:
            self.send_frame(h)
            period = 1e6 / self.fps
            self.next_frame += int(period + self.rng.uniform(-0.05, 0.05) * period)
            if self.next_frame < h:
                self.next_frame = h + int(period)
        return min(nxt, self.next_frame)

    def send_frame(self, h: int):
        base = self.jpegs[self.frame_id % len(self.jpegs)]
        data = jpg.with_comment(base, f"determine-sim frame={self.frame_id}")
        w, hgt = jpg.jpeg_size(data)
        t_cap = self.clock.at(h - self.CAPTURE_TO_SEND_US)
        for chunk in jpg.chunk_frame(self.frame_id, t_cap, w, hgt, data):
            self.send(chunk, h)
        self.stats["frames"] += 1
        self.frame_id = (self.frame_id + 1) & 0xFFFFFFFF


# ---------------------------------------------------------------------- rig

@dataclass
class ClockSpec:
    boot_age_s: float
    drift_ppm: float


DEFAULT_CUE_CLOCK = ClockSpec(2.5, +35.0)
DEFAULT_CAM_CLOCK = ClockSpec(4.2, -22.0)


class SimRig:
    """Both boards, sharing one scenario start, each in its own thread."""

    def __init__(self, scenario: Scenario, *, phone: tuple[str, int] = ("127.0.0.1", dw.PORT_PHONE),
                 bind: str = "0.0.0.0", cue_port: int = dw.PORT_CUE, cam_port: int = dw.PORT_CAM,
                 log: LogFn = print, cue_clock: ClockSpec = DEFAULT_CUE_CLOCK,
                 cam_clock: ClockSpec = DEFAULT_CAM_CLOCK, jpegs: list[bytes] | None = None, fps: float = 11.0,
                 host: Callable[[], int] = host_us):
        self.scenario = scenario
        self.log = _locked(log)
        self.host = host
        jpegs = jpegs or jpg.make_test_jpegs(3, 320, 240, seed=scenario.seed)
        t0 = host()
        common = dict(scenario=scenario, t0_host_us=t0, phone_addr=phone, bind=bind, log=self.log,
                      seed=scenario.seed, host=host)
        self.cue = CueBoardSim(clock=BoardClock(cue_clock.boot_age_s, cue_clock.drift_ppm, host, t0),
                               port=cue_port, **common)
        try:
            self.cam = CamBoardSim(clock=BoardClock(cam_clock.boot_age_s, cam_clock.drift_ppm, host, t0),
                                   port=cam_port, jpegs=jpegs, fps=fps, **common)
        except Exception:
            self.cue.close()
            raise
        self.t0 = t0
        self.stop_event = threading.Event()
        self.threads: list[threading.Thread] = []

    def start(self):
        s = self.scenario
        self.log(f"SIM host={self.t0} t=0.000 event=start scenario={s.name} seed={s.seed} duration_s={s.duration_s:.2f} "
                 f"cue_port={self.cue.port} cam_port={self.cam.port} phone={self.cue.phone_addr[0]}:{self.cue.phone_addr[1]} "
                 f"cue_offset_us={self.cue.clock.offset_at(self.t0)} cue_drift_ppm={self.cue.clock.drift_ppm} "
                 f"cam_offset_us={self.cam.clock.offset_at(self.t0)} cam_drift_ppm={self.cam.clock.drift_ppm}")
        for b in (self.cue, self.cam):
            th = threading.Thread(target=b.run, args=(self.stop_event,), name=f"sim-{b.NAME}", daemon=True)
            th.start()
            self.threads.append(th)

    def stop(self):
        self.stop_event.set()
        for th in self.threads:
            th.join(timeout=2.0)
        h = self.host()
        self.log(f"SIM host={h} t={(h - self.t0) / 1e6:.3f} event=end")
        for b in (self.cue, self.cam):
            self.log(f"STATS board={b.NAME} " + " ".join(f"{k}={v}" for k, v in sorted(b.stats.items())))
            b.close()

    def __enter__(self):
        self.start()
        return self

    def __exit__(self, *exc):
        self.stop()


def _locked(fn: LogFn) -> LogFn:
    lock = threading.Lock()

    def log(line: str):
        with lock:
            fn(line)
    return log
