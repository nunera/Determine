#!/usr/bin/env python3
"""Determine World bridge: browser-simulated or real HITL boards, with the REAL phone engine deciding.

  python3 tools/sim/world_bridge.py --serve world/dist             # built world on http://127.0.0.1:8765/
  python3 tools/sim/world_bridge.py                                # with `npm run dev` in world/ (Vite proxies /ws, /api)
  python3 tools/sim/world_bridge.py --serve world/dist --approach  # spawned engine with EXPERIMENTAL approach escalation
  python3 tools/sim/world_bridge.py --phone 192.168.1.20:47000     # external phone (desktop app or Android)
  python3 tools/sim/world_bridge.py --describe-url https://…/ --describe-key KEY   # enable "What's ahead?"
  python3 tools/sim/world_bridge.py --port 8766 --hitl 0.0.0.0:47100 --engine-port 47110  # real boards, isolated ports

Truth chain (wire v2): the world measures ONE forward ToF reading (Modulino Distance, ~18° cone, ≤ 1.2 m) and
renders the wearer's camera → WebSocket /ws → this bridge's simulated cue and camera boards (boards.py, protocol v2
over UDP, own clocks, watchdog) → the phone engine (phone/cli, or an external phone) decides → its AUDIO half (what
the phone plays on the Bluetooth bone-conduction headset) and haptic-only CUE packets → the simulated cue board
applies/clamps/expires/drops the haptic → `audio`, `cueTx` and `played` events back to the browser. The bridge never
decides a hazard; it only translates.

With --hitl, no simulated boards are created: hitl.py relays real UDP packets through stable role twins, optionally
injecting world range readings and camera overlays while retaining real board clocks and sequence numbers.
WebSocket messages are the `ToBridge`/`FromBridge` types of world/src/contract.ts: binary 0x01 + JPEG is the
simulated camera, 0x02 + RGBA PNG the HITL overlay, and 0x11 + JPEG the HITL preview sent to the browser.
`POST /api/describe` (body image/jpeg) forwards to cloud/describe-api.
"""
from __future__ import annotations

import argparse
import base64
import json
import ipaddress
import math
import os
import secrets
import signal
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from typing import Callable, NamedTuple

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)

import jpeg as jpg  # noqa: E402
from boards import DEFAULT_CAM_CLOCK, DEFAULT_CUE_CLOCK, CamBoardSim, CueBoardSim, SimRig, _locked  # noqa: E402
from dtwire import ACK_NAMES, CUE_NAMES, FAULT_NAMES, HAPTIC_NAMES, SIDE_NAMES, dw  # noqa: E402
from e2e import parse_phone_line, parse_sim_line  # noqa: E402
from hitl import CAMERA_MODES, RANGE_MODES, HitlRelay, composite_available  # noqa: E402
from netem import CLEAN, NetProfile  # noqa: E402
from scenarios import WIFI_JITTER_NET, Scenario  # noqa: E402
from simclock import BoardClock, host_us  # noqa: E402
from wsserver import WebSocketServer, WSConn  # noqa: E402

DEFAULT_ENGINE = os.path.join(REPO, "phone", "cli", "build", "install", "determine-phone", "bin", "determine-phone")
RANGE_MAX_AGE_US = 500_000   # no fresh reading for 500 ms → the sensor has failed; say so, never repeat old data
JPEG_MAX_AGE_US = 1_000_000  # the world renders the camera at ~8 fps; 1 s without one is a capture failure
LIVE_DURATION_S = 1e9        # the world has no scripted end
RANGE_STATUSES = (dw.RANGE_VALID, dw.RANGE_NO_TARGET, dw.RANGE_UNKNOWN)
FAULT_SENSOR_READ_ERR = 1 << FAULT_NAMES.index("SENSOR_READ_ERR")
FAULT_CAPTURE_FAIL = 1 << FAULT_NAMES.index("CAPTURE_FAIL")
NET_PROFILES: dict[str, NetProfile] = {"clean": CLEAN, "wifi_jitter": WIFI_JITTER_NET}
BOARDS = ("cue", "cam")
SYSTEM_STATES = {"STARTING", "READY", "DEGRADED", "UNAVAILABLE", "PAUSED"}
BANDS = {"FAR", "MID", "NEAR"}
ACK_STATUS_NAMES = set(ACK_NAMES.values())
DESCRIBE_TIMEOUT_S = 8.0           # cloud/API.md: phone timeout 8 s end to end
DESCRIBE_MAX_JPEG = 512 * 1024     # cloud/API.md: decoded size ≤ 512 KiB


# ---------------------------------------------------------------------- live inputs from the browser

class Reading(NamedTuple):
    host_us: int  # when the bridge received it (stands in for "sensor reported")
    mm: int       # 0 unless status == RANGE_VALID
    status: int   # canonical v2 RANGE status


class Jpeg(NamedTuple):
    host_us: int
    data: bytes
    w: int
    h: int


class WorldFeed:
    """Latest forward reading and camera JPEG from the browser. Writers replace whole immutable tuples, so readers
    on the board threads always see a consistent sample without locking."""

    def __init__(self, host: Callable[[], int] = host_us):
        self.host = host
        self.reading: Reading | None = None
        self.jpeg: Jpeg | None = None

    def put_range(self, mm: int, status: int):
        self.reading = Reading(self.host(), mm if status == dw.RANGE_VALID else 0, status)

    def put_jpeg(self, data: bytes, w: int, h: int):
        self.jpeg = Jpeg(self.host(), data, w, h)


def parse_range(msg: dict) -> tuple[int, int] | None:
    """`range` message → (mm, status), or None if malformed. Status must be canonical (0 VALID, 1 NO_TARGET,
    2 UNKNOWN); mm rounds and clamps to u16 and is forced to 0 unless VALID (PROTOCOL.md RANGE)."""
    mm, st = msg.get("mm"), msg.get("status")
    if isinstance(st, bool) or not isinstance(st, int) or st not in RANGE_STATUSES:
        return None
    if isinstance(mm, bool) or not isinstance(mm, (int, float)) or not math.isfinite(mm):
        return None
    if st != dw.RANGE_VALID:
        return 0, st
    return min(0xFFFF, max(0, int(round(mm)))), st


def parse_tuning(v) -> dict | None:
    """`EngineTuning` → {"approach", "explore"} (both bool), or None if malformed."""
    if isinstance(v, dict) and isinstance(v.get("approach"), bool) and isinstance(v.get("explore"), bool):
        return {"approach": v["approach"], "explore": v["explore"]}
    return None

def parse_profile(v) -> dict | None:
    """Validate browser profile fields without coercing booleans, strings or fractional numbers."""
    if not isinstance(v, dict) or v.get("mode") not in ("HAPTIC_ONLY", "STEREO", "SSD"):
        return None
    ceiling, haptic = v.get("ceilingDb"), v.get("hapticMax")
    if (type(ceiling) is not int or not -60 <= ceiling <= -10
            or type(haptic) is not int or not 0 <= haptic <= 255):
        return None
    return {"mode": v["mode"], "ceilingDb": ceiling, "hapticMax": haptic}



class LiveScenario(Scenario):
    """The world is the scene: no scripted objects and no scripted end; dropouts are the browser's toggles."""

    def __init__(self, seed: int = 1):
        super().__init__("world", "Determine World: live forward reading and camera from the browser",
                         LIVE_DURATION_S, seed)
        self.unplugged = {b: False for b in BOARDS}

    def cue_down(self, t: float) -> bool:
        return self.unplugged["cue"]

    def cam_down(self, t: float) -> bool:
        return self.unplugged["cam"]


class LiveCueBoard(CueBoardSim):
    """Cue board whose forward ToF is the browser's raycast. Each browser reading is sent as one RANGE, once, within
    one poll period of its arrival (a real sensor reports each measurement once). A missing or stale reading is a
    sensor read error: no RANGE is sent and BEAT carries SENSOR_READ_ERR. Haptics, ACK and watchdog are
    CueBoardSim's."""
    RANGE_PERIOD_US = 5_000  # polls the feed at 200 Hz; the browser sets the rate (contract RANGE_HZ = 15)

    def __init__(self, *, feed: WorldFeed, **kw):
        super().__init__(**kw)
        self.feed = feed
        self._sensor_ok: bool | None = None
        self._last_sent: Reading | None = None

    def fresh_reading(self, h: int) -> Reading | None:
        r = self.feed.reading
        return r if r is not None and h - r.host_us <= RANGE_MAX_AGE_US else None

    def faults(self) -> int:
        f = super().faults()
        if self.fresh_reading(self.host()) is None:
            f |= FAULT_SENSOR_READ_ERR
        return f

    def send_range(self, h: int):
        r = self.fresh_reading(h)
        ok = r is not None
        if ok != self._sensor_ok:
            self._sensor_ok = ok
            self.log(f"SENSOR board=cue state={'ok' if ok else 'no_fresh_reading'} host={h}")
        if r is None or r is self._last_sent:
            return
        self._last_sent = r
        self.stats["range_sent"] += 1
        self.send(dw.Range(self.clock.at(r.host_us), r.mm if r.status == dw.RANGE_VALID else 0, r.status), h)


class LiveCamBoard(CamBoardSim):
    """Camera board whose frames are the browser's renders, each sent once. Until the first one arrives it sends the
    synthetic test card (like sim_boards.py); afterwards a stale render is a capture failure (CAPTURE_FAIL, no
    FRAME)."""

    def __init__(self, *, feed: WorldFeed, **kw):
        super().__init__(**kw)
        self.feed = feed
        self._last_sent: Jpeg | None = None

    def _stale(self, h: int) -> bool:
        j = self.feed.jpeg
        return j is not None and h - j.host_us > JPEG_MAX_AGE_US

    def faults(self) -> int:
        f = super().faults()
        if self._stale(self.host()):
            f |= FAULT_CAPTURE_FAIL
        return f

    def send_frame(self, h: int):
        j = self.feed.jpeg
        if j is None:
            super().send_frame(h)
            return
        if self._stale(h):
            self.stats["frame_stale"] += 1
            return
        if j is self._last_sent:
            self.stats["frame_no_new_render"] += 1
            return
        self._last_sent = j
        for chunk in jpg.chunk_frame(self.frame_id, self.clock.at(j.host_us), j.w, j.h, j.data):
            self.send(chunk, h)
        self.stats["frames"] += 1
        self.frame_id = (self.frame_id + 1) & 0xFFFFFFFF


class LiveRig(SimRig):
    """SimRig's two boards (same clocks, threads, stop event; start()/stop() inherited), built from the live
    subclasses, plus runtime board toggles and network profile swaps."""

    def __init__(self, feed: WorldFeed, *, phone: tuple[str, int], bind: str, cue_port: int, cam_port: int,
                 log: Callable[[str], None], seed: int = 1, fps: float = 11.0, host: Callable[[], int] = host_us):
        self.scenario = LiveScenario(seed)
        self.log = _locked(log)
        self.host = host
        self.net_name = "clean"
        t0 = host()
        common = dict(scenario=self.scenario, t0_host_us=t0, phone_addr=phone, bind=bind, log=self.log,
                      seed=seed, host=host)
        self.cue = LiveCueBoard(feed=feed, clock=BoardClock(DEFAULT_CUE_CLOCK.boot_age_s, DEFAULT_CUE_CLOCK.drift_ppm,
                                                            host, t0), port=cue_port, **common)
        try:
            self.cam = LiveCamBoard(feed=feed, clock=BoardClock(DEFAULT_CAM_CLOCK.boot_age_s,
                                                                DEFAULT_CAM_CLOCK.drift_ppm, host, t0),
                                    port=cam_port, jpegs=jpg.make_test_jpegs(3, 320, 240, seed=seed), fps=fps,
                                    **common)
        except Exception:
            self.cue.close()
            raise
        self.t0 = t0
        self.stop_event = threading.Event()
        self.threads: list[threading.Thread] = []

    def set_down(self, board: str, down: bool):
        self.scenario.unplugged[board] = down  # the board loop logs dropout_start/dropout_end

    def set_net(self, name: str):
        profile = NET_PROFILES[name]
        for b in (self.cue, self.cam):
            b.imp_out.p = profile  # Impairment.decide reads the profile once per datagram
            b.imp_in.p = profile
        self.net_name = name


# ---------------------------------------------------------------------- line → event translation

def log_event(line: str) -> dict:
    return {"t": "log", "line": line}


def played_event(kv: dict) -> dict | None:
    """Fields of a cue-board `CUE ...` log line (boards.py) → `played`, or None if it does not fit the contract."""
    try:
        ev = {"t": "played", "hostMs": int(kv["host"]) / 1000.0,
              "seq": None if kv["seq"] == "-" else int(kv["seq"]),
              "cue": kv["id"], "side": kv["side"], "status": kv["status"], "haptic": kv["haptic"],
              "hl": int(kv["hl"]), "hr": int(kv["hr"]), "hapticMax": int(kv["haptic_max"]),
              "src": kv["src"], "lateUs": int(kv["late_us"])}
    except (KeyError, ValueError):
        return None
    if (ev["cue"] not in dw.CUE_IDS or ev["side"] not in SIDE_NAMES.values() or ev["haptic"] not in dw.HAPTIC
            or ev["status"] not in ACK_STATUS_NAMES or ev["src"] not in ("phone", "watchdog")
            or (ev["seq"] is None) != (ev["src"] == "watchdog")):
        return None  # e.g. BAD_CUE with a numeric id: shown as a log line instead
    return ev


def board_event(line: str) -> dict:
    ln = parse_sim_line(line)
    if ln is not None and ln.kind == "CUE":
        ev = played_event(ln.kv)
        if ev is not None:
            return ev
    return log_event(line)


def _speak_text(raw: str) -> str:
    text = raw.partition('text="')[2]
    return text[:-1] if text.endswith('"') else text


def _ack_name(v: str) -> str:
    """cli AUDIO `status=` (the numeric wire.AckStatus; a name is accepted too) → contract AckStatus.
    Raises KeyError/ValueError for anything else (e.g. AUDIO_ROUTE_LOST −2), which then stays a log line."""
    return v if v in ACK_STATUS_NAMES else ACK_NAMES[int(v)]


def engine_event(raw: str) -> dict:
    """One phone/cli stdout line (phone/core/API.md "Stable cli output") → a FromBridge event."""
    ln = parse_phone_line(raw)
    if ln is None:
        return log_event(raw)
    kv, ms = ln.kv, ln.ms
    try:
        if ln.kind == "PROFILE-RESULT":
            if kv.get("status") == "applied":
                profile = parse_profile({"mode": kv["mode"], "ceilingDb": int(kv["ceiling_db"]),
                                         "hapticMax": int(kv["haptic_max"])})
                if profile is not None and profile["hapticMax"] > 0:
                    return {"t": "profile", "status": "applied", "profile": profile}
            elif kv.get("status") == "rejected":
                return {"t": "profile", "status": "rejected", "profile": None,
                        "error": kv.get("error", "Engine rejected profile").replace("_", " ")}
        if ln.kind == "STATE" and ln.words and ln.words[0] in SYSTEM_STATES:
            return {"t": "state", "ms": ms, "state": ln.words[0], "reason": kv.get("reason", "-")}
        if ln.kind == "ALERT":
            if ln.words == ["none"] and not kv:
                return {"t": "alert", "ms": ms, "alert": None}
            if kv["band"] in BANDS:
                d = kv["dist_mm"]
                return {"t": "alert", "ms": ms,
                        "alert": {"band": kv["band"], "distMm": None if d == "-" else int(round(float(d)))}}
        elif ln.kind == "AUDIO":
            return {"t": "audio", "ms": ms, "cue": CUE_NAMES[int(kv["cue"])], "side": SIDE_NAMES[int(kv["side"])],
                    "glDb": int(kv["gl"]), "grDb": int(kv["gr"]), "status": _ack_name(kv["status"])}
        elif ln.kind == "CUE-TX":
            return {"t": "cueTx", "ms": ms, "seq": int(kv["seq"]), "cue": CUE_NAMES[int(kv["cue"])],
                    "side": SIDE_NAMES[int(kv["side"])], "haptic": HAPTIC_NAMES[int(kv["hp"])],
                    "hl": int(kv["hl"]), "hr": int(kv["hr"]), "flags": int(kv["flags"]), "ttlMs": int(kv["ttl"])}
        elif ln.kind == "SPEAK":
            return {"t": "speak", "ms": ms, "text": _speak_text(raw)}
        elif ln.kind == "VIBRATE":
            return {"t": "vibrate", "ms": ms, "pattern": [int(x) for x in kv.get("pattern", "").split(",") if x]}
        elif ln.kind == "SYSTEM-OFF":
            return {"t": "systemOff", "ms": ms}
        elif ln.kind == "DIAG":
            return {"t": "diag", "ms": ms, "kv": dict(kv)}
    except (KeyError, ValueError):
        pass
    return log_event(raw)  # PROFILE-TX, banners, unknown kinds, malformed values


class LogThrottle:
    """At most `per_s` log lines per kind (first token) per second; the rest are counted and summarised."""

    def __init__(self, per_s: int = 10, clock: Callable[[], float] = time.monotonic):
        self.per_s = per_s
        self.clock = clock
        self._lock = threading.Lock()
        self._state: dict[str, list] = {}  # kind → [window start, admitted, suppressed]

    def _summary(self, kind: str, n: int) -> str:
        return f"BRIDGE suppressed {n} {kind} log lines (more than {self.per_s}/s)"

    def admit(self, line: str) -> list[str]:
        kind = line.split(" ", 1)[0] or "-"
        now = self.clock()
        out = []
        with self._lock:
            st = self._state.setdefault(kind, [now, 0, 0])
            if now - st[0] >= 1.0:
                if st[2]:
                    out.append(self._summary(kind, st[2]))
                st[:] = [now, 0, 0]
            if st[1] < self.per_s:
                st[1] += 1
                out.append(line)
            else:
                st[2] += 1
        return out

    def flush(self) -> list[str]:
        """Summaries for windows that have ended with suppressed lines."""
        now = self.clock()
        out = []
        with self._lock:
            for kind, st in self._state.items():
                if st[2] and now - st[0] >= 1.0:
                    out.append(self._summary(kind, st[2]))
                    st[:] = [now, 0, 0]
        return out


# ---------------------------------------------------------------------- describe (cloud/API.md §1)

def describe_endpoint(url: str) -> str:
    base = url.rstrip("/")
    return base if base.endswith("/v1/describe") else base + "/v1/describe"


class DescribeClient:
    def __init__(self, url: str, key: str, urlopen: Callable = urllib.request.urlopen):
        self.endpoint = describe_endpoint(url)
        self.key = key
        self.urlopen = urlopen

    def __call__(self, jpeg: bytes) -> tuple[int, dict]:
        """(HTTP status for the browser, DescribeResult). Never invents text: every failure is ok=false."""
        if len(jpeg) > DESCRIBE_MAX_JPEG:
            return 413, {"ok": False, "error": "Image is larger than the 512 KiB Describe limit"}
        try:
            w, h = jpg.jpeg_size(jpeg)
        except (ValueError, IndexError):
            return 400, {"ok": False, "error": "Body is not a JPEG image"}
        body = json.dumps({
            "request_id": "world-" + secrets.token_hex(8),
            "image_jpeg_b64": base64.b64encode(jpeg).decode("ascii"),
            "width": w, "height": h,
            # Rendered frames of the virtual world contain no real people or faces, so nothing needs blurring.
            "redacted": True,
            "locale": "en",
        }).encode("utf-8")
        req = urllib.request.Request(self.endpoint, data=body, method="POST", headers={
            "Authorization": f"Bearer {self.key}", "Content-Type": "application/json"})
        t0 = time.monotonic()

        def rtt() -> dict:
            return {"bridge_round_trip": round((time.monotonic() - t0) * 1000.0, 1)}
        try:
            with self.urlopen(req, timeout=DESCRIBE_TIMEOUT_S) as resp:
                data = json.loads(resp.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            code, retry = "http_error", None
            try:
                err = json.loads(e.read().decode("utf-8"))
                code, retry = str(err.get("error", code)), err.get("retryable")
            except (ValueError, AttributeError, OSError):
                pass
            msg = f"Describe failed: {code} (HTTP {e.code})" + (", retryable" if retry is True else "")
            return 502, {"ok": False, "error": msg, "timings": rtt()}
        except (TimeoutError, urllib.error.URLError) as e:
            reason = getattr(e, "reason", e)
            if isinstance(e, TimeoutError) or isinstance(reason, TimeoutError):
                return 504, {"ok": False, "error": f"Describe timed out after {DESCRIBE_TIMEOUT_S:.0f} s",
                             "timings": rtt()}
            return 502, {"ok": False, "error": f"Describe service unreachable: {reason}", "timings": rtt()}
        except (ValueError, OSError) as e:
            return 502, {"ok": False, "error": f"Describe returned an unreadable response: {e}", "timings": rtt()}
        timings = rtt()
        text = data.get("text") if isinstance(data, dict) else None
        if not isinstance(text, str) or not text.strip():
            return 502, {"ok": False, "error": "Describe returned no text", "timings": timings}
        tm = data.get("timings_ms")
        for k, v in (tm.items() if isinstance(tm, dict) else ()):
            if isinstance(v, (int, float)) and not isinstance(v, bool):
                timings[k] = v
        result = {"ok": True, "text": text.strip(), "timings": timings}
        for k in ("backend", "silicon", "model"):
            if isinstance(data.get(k), str):
                result[k] = data[k]
        return 200, result


def describe_route(client: DescribeClient | None):
    """POST /api/describe handler for wsserver."""
    def route(body: bytes, content_type: str) -> tuple[int, str, bytes]:
        if client is None:
            status, result = 503, {"ok": False, "error": "Describe service not configured"}
        else:
            status, result = client(body)
        return status, "application/json", json.dumps(result).encode("utf-8")
    return route


# ---------------------------------------------------------------------- engine process

def engine_cmd(engine: str, phone_port: int, profile: dict, tuning: dict) -> list[str]:
    """Start the real CLI with its last acknowledged settings and opt-in in-place profile control."""
    cmd = [engine, "--port", str(phone_port), "--bind", "127.0.0.1", "--control-stdin",
           "--ceiling", str(profile["ceilingDb"]), "--mode", profile["mode"],
           "--haptic-max", str(profile["hapticMax"])]
    if tuning["approach"]:
        cmd.append("--approach")
    if tuning["explore"]:
        cmd.append("--explore")
    return cmd


class EngineProcess:
    """phone/cli as a child process; each stdout line goes to on_line, each exit to on_exit. `cmd` is read at every
    spawn, so assigning it before restart() changes the next engine's flags."""

    def __init__(self, cmd: list[str], on_line: Callable[[str], None], on_exit: Callable[[str], None]):
        self.cmd = cmd
        self.on_line = on_line
        self.on_exit = on_exit
        self.proc: subprocess.Popen | None = None
        self.ready = threading.Event()
        self._lock = threading.Lock()
        self._control_lock = threading.Lock()
        self._request = 0
        self._pending: tuple[int, threading.Event] | None = None
        self._control_error: str | None = None
        self._closed = False

    @property
    def running(self) -> bool:
        p = self.proc
        return p is not None and p.poll() is None

    def start(self):
        with self._lock:
            if not self._closed and not self.running:
                self._spawn()

    def stop(self):
        with self._lock:
            self._closed = True
            self._kill()

    def restart(self):
        with self._lock:
            if self._closed:
                return
            self._kill()
            self._spawn()

    def set_profile(self, profile: dict) -> str | None:
        """Wait a bounded time for a real CLI result; None means the stdout result was delivered."""
        with self._control_lock:
            with self._lock:
                p = self.proc
                if p is None or p.poll() is not None or p.stdin is None:
                    return "Engine is stopped; restart it before applying a profile"
                self._request += 1
                request = self._request
                done = threading.Event()
                self._pending = (request, done)
                self._control_error = None
                try:
                    p.stdin.write(f"PROFILE {request} {profile['mode']} {profile['ceilingDb']} {profile['hapticMax']}\n")
                    p.stdin.flush()
                except (OSError, ValueError) as e:
                    self._pending = None
                    return f"Engine control pipe failed: {e}"
            if not done.wait(3.0):
                with self._lock:
                    self._pending = None
                return "Engine did not acknowledge the profile within 3 seconds"
            return self._control_error

    def _cancel_control(self, error: str):
        if self._pending is not None:
            self._control_error = error
            self._pending[1].set()
            self._pending = None

    def _spawn(self):
        self.ready.clear()
        try:
            p = subprocess.Popen(list(self.cmd), stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                 stdin=subprocess.PIPE, text=True, errors="replace", bufsize=1, cwd=REPO,
                                 start_new_session=True)
        except OSError as e:
            self.proc = None
            self.on_exit(f"engine failed to start: {e}")
            return
        self.proc = p
        threading.Thread(target=self._read, args=(p,), name=f"engine-{p.pid}", daemon=True).start()

    def _read(self, p: subprocess.Popen):
        with p.stdout:
            for raw in p.stdout:
                with self._lock:
                    if p is not self.proc:
                        continue
                    self.ready.set()
                    line = raw.rstrip("\n")
                    self.on_line(line)
                    parsed = parse_phone_line(line)
                    if (parsed is not None and parsed.kind == "PROFILE-RESULT"
                            and engine_event(line)["t"] == "profile"):
                        if self._pending is not None and parsed.kv.get("request") == str(self._pending[0]):
                            self._pending[1].set()
                            self._pending = None
        code = p.wait()
        with self._lock:
            if p is self.proc:
                self._cancel_control("Engine exited before acknowledging the profile")
                self.on_exit(f"engine exited pid={p.pid} code={code}")

    def _kill(self):
        p = self.proc
        self.proc = None
        self.ready.clear()
        self._cancel_control("Engine stopped or restarted before acknowledging the profile")
        if p is None:
            return
        self.on_exit(f"engine stopped pid={p.pid}")
        if p.stdin is not None:
            try:
                p.stdin.close()
            except OSError:
                pass
        if p.poll() is not None:
            return
        try:
            os.killpg(p.pid, signal.SIGTERM)
            p.wait(3.0)
        except subprocess.TimeoutExpired:
            os.killpg(p.pid, signal.SIGKILL)
            p.wait()
        except ProcessLookupError:
            pass


# ---------------------------------------------------------------------- bridge

class Bridge:
    def __init__(self, args):
        self.external = args.phone is not None
        phone = args.phone or ("127.0.0.1", args.phone_port)
        self.phone = f"{phone[0]}:{phone[1]}"
        board_bind = args.board_bind or ("0.0.0.0" if self.external else "127.0.0.1")
        self.throttle = LogThrottle()
        self.feed = WorldFeed()
        self._stop = threading.Event()
        self._tuning_lock = threading.Lock()
        # The spawned engine's tuning is what we started it with; an external phone's tuning is unknown here.
        self.tuning: dict | None = None if self.external else {"approach": args.approach, "explore": args.explore}
        self.profile: dict | None = None
        self._desired_profile = {"mode": args.mode, "ceilingDb": args.ceiling, "hapticMax": args.haptic_max}
        self._engine_argv = (args.engine, args.phone_port)
        self.describe = DescribeClient(args.describe_url, args.describe_key) if args.describe_url else None
        self.engine = None if self.external else EngineProcess(
            engine_cmd(*self._engine_argv, self._desired_profile, self.tuning), self.on_engine_line, self.on_engine_exit)
        self.hitl = None
        self.rig = None
        if args.hitl is not None:
            self.hitl = HitlRelay(args.hitl, phone, self.feed, range_mode=args.hitl_range,
                                  camera_mode=args.hitl_camera, cue_ip=args.hitl_cue, cam_ip=args.hitl_cam,
                                  emit=self.emit, preview=self.on_preview, note=self.note)
        else:
            self.rig = LiveRig(self.feed, phone=phone, bind=board_bind, cue_port=args.cue_port, cam_port=args.cam_port,
                               log=self.on_board_line)
        try:
            self.ws = WebSocketServer(args.bind, args.port, static_dir=args.serve,
                                      post_routes={"/api/describe": describe_route(self.describe)},
                                      on_connect=self.on_connect, on_text=self.on_text, on_binary=self.on_binary)
        except OSError:
            if self.hitl is not None:
                self.hitl.stop()
            else:
                self.rig.cue.close()
                self.rig.cam.close()
            raise

    # -- outgoing
    def emit(self, ev: dict):
        self.ws.broadcast_text(json.dumps(ev, separators=(",", ":")))

    def on_preview(self, data: bytes):
        self.ws.broadcast_binary(data)

    def log(self, line: str):
        for ln in self.throttle.admit(line):
            self.emit(log_event(ln))

    def note(self, msg: str):
        """A bridge message: to the console and to every browser (rate-limited like every log line)."""
        for ln in self.throttle.admit(f"BRIDGE {msg}"):
            print(ln, file=sys.stderr, flush=True)
            self.emit(log_event(ln))

    def _tuning(self) -> dict | None:
        t = self.tuning
        return None if t is None else dict(t)

    def hello(self) -> dict:
        return {"t": "hello", "engine": "external" if self.external else "spawned", "phone": self.phone,
                "describe": self.describe is not None, "tuning": self._tuning(), "profile": self._profile(),
                "hitl": self.hitl.hello() if self.hitl is not None else {
                    "enabled": False, "listen": None, "range": "fuse", "camera": "real",
                    "compositeAvailable": composite_available()}}

    def status(self) -> dict:
        if self.external:
            engine = "external"
        else:
            engine = "running" if self.engine.running else "exited"
        if self.hitl is not None:
            telemetry = self.hitl.status()
            boards = {b: "up" if telemetry[b]["ageMs"] is not None and telemetry[b]["ageMs"] <= 500 else "down"
                      for b in BOARDS}
            net = "real"
        else:
            boards = {b: "down" if self.rig.scenario.unplugged[b] else "up" for b in BOARDS}
            net = self.rig.net_name
        return {"t": "bridge", "engine": engine, "boards": boards,
                "net": net, "tuning": self._tuning(), "profile": self._profile()}

    def _profile(self) -> dict | None:
        profile = self.profile
        return None if profile is None else dict(profile)

    def reject_profile(self, error: str):
        self.emit({"t": "profile", "status": "rejected", "profile": self._profile(), "error": error})

    def set_profile(self, value):
        profile = parse_profile(value)
        if profile is None:
            self.reject_profile("Invalid profile: choose HAPTIC_ONLY/STEREO/SSD, ceiling -60..-10 dBFS, "
                                "and integer haptic maximum 1..255")
        elif profile["hapticMax"] == 0:
            self.reject_profile("Haptic maximum must be 1..255; the watchdog failure channel cannot be disabled")
        elif self.external:
            self.reject_profile("The engine is an external phone; configure the profile on that phone")
        elif self._stop.is_set():
            self.reject_profile("Bridge is stopping; reconnect before applying a profile")
        elif self.profile is None and self.engine.running:
            self.reject_profile("Engine has not acknowledged its initial profile yet; wait for engine readiness")
        else:
            error = self.engine.set_profile(profile)
            if error is not None:
                self.reject_profile(error)

    # -- sources
    def on_board_line(self, line: str):
        ev = board_event(line)
        if ev["t"] == "log":
            self.log(line)
        else:
            self.emit(ev)

    def on_engine_line(self, line: str):
        ev = engine_event(line)
        if ev["t"] == "profile":
            if ev["status"] == "applied":
                with self._tuning_lock:
                    self.profile = ev["profile"]
                    self._desired_profile = dict(self.profile)
                    self.engine.cmd = engine_cmd(*self._engine_argv, self._desired_profile, self.tuning)
            else:
                ev["profile"] = self._profile()
        if ev["t"] == "log":
            self.log(line)
        else:
            self.emit(ev)

    def on_engine_exit(self, msg: str):
        self.profile = None
        self.note(msg)
        self.emit(self.status())

    # -- browser
    def on_connect(self, conn: WSConn):
        conn.send_text(json.dumps(self.hello(), separators=(",", ":")))
        conn.send_text(json.dumps(self.status(), separators=(",", ":")))
        if self.hitl is not None:
            conn.send_text(json.dumps(self.hitl.status(), separators=(",", ":")))

    def on_text(self, conn: WSConn | None, text: str):
        try:
            msg = json.loads(text)
        except ValueError:
            msg = None
        if not isinstance(msg, dict):
            self.note("rejected a non-JSON WebSocket message")
            return
        t = msg.get("t")
        if t == "profile":
            self.set_profile(msg.get("profile"))
            return
        if t == "range":
            reading = parse_range(msg)
            if reading is None:
                self.note("rejected a malformed range message (needs numeric mm and status 0 VALID, 1 NO_TARGET "
                          "or 2 UNKNOWN)")
            else:
                self.feed.put_range(*reading)
        elif t == "hitl":
            if self.hitl is None:
                self.note("HITL mode change ignored: HITL is disabled")
            elif (not any(k in msg for k in ("range", "camera")) or
                  ("range" in msg and msg["range"] not in RANGE_MODES) or
                  ("camera" in msg and msg["camera"] not in CAMERA_MODES)):
                self.note("rejected malformed HITL mode change")
            else:
                try:
                    self.hitl.set_modes(**{k: msg[k] for k in ("range", "camera") if k in msg})
                except ValueError as e:
                    self.note(str(e))
                self.emit(self.hitl.status())
        elif self.hitl is not None and t in ("board", "net"):
            self.note("simulated board disconnect and network impairment are disabled in HITL")
        elif t == "board" and msg.get("board") in BOARDS and isinstance(msg.get("down"), bool):
            self.rig.set_down(msg["board"], msg["down"])
            self.note(f"{msg['board']} board {'unplugged' if msg['down'] else 'plugged in'}")
            self.emit(self.status())
        elif t == "net" and msg.get("profile") in NET_PROFILES:
            self.rig.set_net(msg["profile"])
            self.note(f"network profile {msg['profile']}")
            self.emit(self.status())
        elif t == "engine" and msg.get("action") == "restart":
            if self.external:
                self.note("engine restart ignored: the engine is an external phone")
            else:
                self._restart_async("engine restart requested")
        elif t == "tuning" and parse_tuning(msg.get("tuning")) is not None:
            self.set_tuning(parse_tuning(msg["tuning"]))
        else:
            self.note(f"rejected unsupported or malformed message t={t!r}")

    def set_tuning(self, tuning: dict):
        """Restart the spawned engine with the matching `--approach`/`--explore` flags."""
        if self.external:
            self.note("tuning rejected: the engine is an external phone; tune the external phone on the phone")
            return
        with self._tuning_lock:
            if tuning == self.tuning:
                changed = False
            else:
                changed = True
                self.tuning = tuning
                self.engine.cmd = engine_cmd(*self._engine_argv, self._desired_profile, tuning)
        desc = f"approach={'on' if tuning['approach'] else 'off'} explore={'on' if tuning['explore'] else 'off'}"
        if not changed:
            self.note(f"tuning unchanged ({desc}); engine not restarted")
            self.emit(self.status())
            return
        self._restart_async(f"tuning {desc}: restarting the engine")

    def _restart_async(self, why: str):
        threading.Thread(target=self.restart_engine, args=(why,), name="engine-restart", daemon=True).start()

    def on_binary(self, conn: WSConn | None, data: bytes):
        if self.hitl is not None:
            if data and data[0] == 0x02:
                try:
                    self.hitl.put_overlay(data[1:])
                except ValueError as e:
                    self.note(str(e))
            elif not data or data[0] != 0x01:
                self.note("rejected a HITL binary message without the 0x02 overlay tag")
            # Browser simulation JPEGs never become real board traffic.
            return
        if not data or data[0] != 0x01:
            self.note("rejected a binary message without the 0x01 camera tag")
            return
        jpeg = data[1:]
        try:
            w, h = jpg.jpeg_size(jpeg)
        except (ValueError, IndexError):
            self.note("rejected a camera frame that is not a JPEG")
            return
        self.feed.put_jpeg(jpeg, w, h)

    def restart_engine(self, why: str = "engine restart requested"):
        self.note(why)
        if self._stop.is_set():
            return
        self.profile = None
        self.engine.restart()
        self.emit(self.status())

    # -- lifecycle
    def start(self):
        self.ws.start()
        if self.engine is not None:
            self.engine.start()
            # Let the JVM bind the phone port before the boards start talking (as e2e.py does).
            if self.engine.running:
                self.engine.ready.wait(5.0)
        if self.hitl is not None:
            self.hitl.start()
        else:
            self.rig.start()
        threading.Thread(target=self._status_loop, name="bridge-status", daemon=True).start()

    def _status_loop(self):
        ticks = 0
        while not self._stop.wait(0.2 if self.hitl is not None else 1.0):
            if self.hitl is not None:
                self.emit(self.hitl.status())
            ticks += 1
            if self.hitl is None or ticks % 5 == 0:
                for ln in self.throttle.flush():
                    self.emit(log_event(ln))
                self.emit(self.status())

    def stop(self):
        self._stop.set()
        if self.hitl is not None:
            self.hitl.stop()
        else:
            self.rig.stop()
        if self.engine is not None:
            self.engine.stop()
        self.ws.stop()


# ---------------------------------------------------------------------- cli

def _host_port(s: str) -> tuple[str, int]:
    host, sep, port = s.rpartition(":")
    try:
        p = int(port)
    except ValueError:
        p = -1
    if not sep or not host or not 0 < p < 65536:
        raise argparse.ArgumentTypeError("expected HOST:PORT")
    return host, p


def _ipv4(s: str) -> str:
    try:
        return str(ipaddress.IPv4Address(s))
    except ipaddress.AddressValueError as e:
        raise argparse.ArgumentTypeError("expected an IPv4 board address") from e


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Determine World bridge: browser world or real HITL boards → phone engine.",
                                formatter_class=argparse.RawDescriptionHelpFormatter,
                                epilog=__doc__.split("\n\n", 1)[1])
    p.add_argument("--port", type=int, default=8765, help="HTTP/WebSocket port (default 8765)")
    p.add_argument("--bind", default="127.0.0.1", help="HTTP/WebSocket bind address (default 127.0.0.1)")
    p.add_argument("--serve", metavar="DIR", help="serve the built world from DIR (e.g. world/dist)")
    eng = p.add_mutually_exclusive_group()
    eng.add_argument("--engine", metavar="PATH", default=DEFAULT_ENGINE,
                     help="phone cli to spawn (default phone/cli/build/install/determine-phone/bin/determine-phone)")
    eng.add_argument("--phone", metavar="HOST:PORT", type=_host_port,
                     help="external phone (desktop app or Android phone): spawn nothing, boards send there")
    p.add_argument("--engine-port", "--phone-port", dest="phone_port", type=int, default=dw.PORT_PHONE,
                   help="UDP port of the spawned engine (default 47000; --phone-port is an alias)")
    p.add_argument("--hitl", metavar="HOST:PORT", type=_host_port,
                   help="relay real boards arriving at HOST:PORT instead of starting simulated boards")
    p.add_argument("--hitl-cue", metavar="IP", type=_ipv4, help="pin the real cue board to this IPv4 address")
    p.add_argument("--hitl-cam", metavar="IP", type=_ipv4, help="pin the real camera board to this IPv4 address")
    p.add_argument("--hitl-range", choices=RANGE_MODES, default="fuse", help="real/world/fused RANGE (default fuse)")
    p.add_argument("--hitl-camera", choices=CAMERA_MODES, default="real",
                   help="real JPEG or Pillow overlay composite (default real)")
    p.add_argument("--board-bind", help="address the simulated boards bind (default 127.0.0.1; 0.0.0.0 with --phone)")
    p.add_argument("--cue-port", type=int, default=dw.PORT_CUE, help="cue board UDP port (default 47001; 0 = any)")
    p.add_argument("--cam-port", type=int, default=dw.PORT_CAM, help="camera board UDP port (default 47002; 0 = any)")
    p.add_argument("--describe-url", help="describe-api base URL (POST <url>/v1/describe)")
    p.add_argument("--describe-key", default=os.environ.get("DETERMINE_DESCRIBE_KEY"),
                   help="describe-api key (default: $DETERMINE_DESCRIBE_KEY)")
    p.add_argument("--ceiling", type=int, default=-20, help="spawned engine's profile ceiling, dBFS (default -20)")
    p.add_argument("--haptic-max", type=int, default=255, help="board haptic cap, 1..255 (default 255)")
    p.add_argument("--mode", choices=("STEREO", "SSD", "HAPTIC_ONLY"), default="STEREO",
                   help="spawned engine's hearing mode (default STEREO)")
    p.add_argument("--approach", action="store_true",
                   help="start the spawned engine with EXPERIMENTAL Tuning.approachEnabled (time-to-contact escalation)")
    p.add_argument("--explore", action="store_true",
                   help="start the spawned engine with EXPERIMENTAL Tuning.exploreEnabled (distance-proportional "
                        "haptic pulses)")
    return p


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.describe_url and not args.describe_key:
        parser.error("--describe-url needs --describe-key or DETERMINE_DESCRIBE_KEY")
    if not -60 <= args.ceiling <= -10:
        parser.error("--ceiling must be -60..-10 dBFS")
    if not 1 <= args.haptic_max <= 255:
        parser.error("--haptic-max must be 1..255; the watchdog cannot be disabled")
    if args.phone is not None and (args.approach or args.explore):
        parser.error("--approach/--explore tune the spawned engine; tune an external phone on the phone")
    if not 0 < args.phone_port < 65536:
        parser.error("--engine-port must be between 1 and 65535")
    if args.hitl is not None and args.phone is not None:
        parser.error("--hitl uses the spawned loopback engine; use --engine-port rather than --phone")
    if args.hitl is None and (args.hitl_cue or args.hitl_cam or args.hitl_range != "fuse" or args.hitl_camera != "real"):
        parser.error("--hitl-cue/--hitl-cam/--hitl-range/--hitl-camera require --hitl HOST:PORT")
    if args.hitl is not None and args.hitl_camera == "composite" and not composite_available():
        parser.error("--hitl-camera composite requires Pillow (python3 -m pip install Pillow)")
    if args.hitl is not None and args.hitl[1] == args.phone_port:
        parser.error("--hitl and --engine-port must use distinct UDP ports")
    if args.serve and not os.path.isfile(os.path.join(args.serve, "index.html")):
        parser.error(f"{args.serve}/index.html not found — run `npm run build` in world/ first")
    if args.phone is None and not os.access(args.engine, os.X_OK):
        parser.error(f"phone cli not found at {args.engine} — build it with `./gradlew :cli:installDist` in phone/ "
                     "(see tools/sim/README.md), pass --engine PATH, or use --phone HOST:PORT")
    try:
        bridge = Bridge(args)
    except OSError as e:
        print(f"world_bridge: cannot bind: {e}", file=sys.stderr)
        return 2
    done = threading.Event()
    signal.signal(signal.SIGINT, lambda *_: done.set())
    signal.signal(signal.SIGTERM, lambda *_: done.set())
    bridge.start()
    where = f"http://{args.bind}:{bridge.ws.port}/" if args.serve else f"ws://{args.bind}:{bridge.ws.port}/ws"
    if bridge.external:
        engine = f"external phone {bridge.phone}"
    else:
        engine = (f"spawned engine on udp {bridge.phone} (approach {'on' if args.approach else 'off'}, "
                  f"explore {'on' if args.explore else 'off'})")
    boards = (f"HITL {bridge.hitl.hello()['listen']}" if bridge.hitl is not None else
              f"boards cue:{bridge.rig.cue.port} cam:{bridge.rig.cam.port}")
    print(f"world_bridge: {where} · {engine} · {boards} · "
          f"describe {'on' if bridge.describe else 'off'} · Ctrl-C to stop", file=sys.stderr, flush=True)
    try:
        done.wait()
    finally:
        bridge.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
