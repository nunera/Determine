"""Real-board UDP relay for Determine World (wire v2, no synthetic board traffic).

Each board role has one stable loopback twin address learned by the real engine. Only
RANGE values and explicitly composited FRAME payloads may change; board clocks remain real.
"""
from __future__ import annotations

import io
import selectors
import socket
import threading
from collections import deque
from dataclasses import dataclass, field
from typing import Callable

import jpeg as jpg
from dtwire import ACK_NAMES, CUE_NAMES, HAPTIC_NAMES, SIDE_NAMES, dw
from simclock import host_us

try:
    from PIL import Image
except ImportError:
    Image = None

RANGE_MODES = ("real", "world", "fuse")
CAMERA_MODES = ("real", "composite")
WORLD_MAX_AGE_US = 500_000
OVERLAY_MAX_AGE_US = 1_000_000
FRAME_TIMEOUT_US = 150_000
MAX_FRAME_BYTES = 512 * 1024
BOARD_PORTS = {"cue": dw.PORT_CUE, "cam": dw.PORT_CAM}


def composite_available() -> bool:
    return Image is not None


def fuse_range(real: dw.Range, world, now_us: int, mode: str) -> tuple[dw.Range, str]:
    """Keep the real timestamp; ties favour real and an UNKNOWN real sensor stays UNKNOWN in fuse mode."""
    if mode not in RANGE_MODES:
        raise ValueError("range mode must be real, world or fuse")
    if mode == "real":
        return real, "real"
    if world is None or not 0 <= now_us - world.host_us <= WORLD_MAX_AGE_US:
        return real, "real-stale-world"
    use_world = mode == "world" or (
        world.status == dw.RANGE_VALID and (
            real.status == dw.RANGE_NO_TARGET or
            (real.status == dw.RANGE_VALID and world.mm < real.mm)))
    if use_world:
        return dw.Range(real.t_us, world.mm if world.status == dw.RANGE_VALID else 0, world.status), "world"
    return real, "real"


@dataclass
class BoardState:
    addr: tuple[str, int] | None = None
    last_us: int | None = None
    rssi: int | None = None
    faults: int | None = None

    def status(self, now: int) -> dict:
        return {"addr": None if self.addr is None else f"{self.addr[0]}:{self.addr[1]}",
                "ageMs": None if self.last_us is None else max(0, now - self.last_us) / 1000,
                "rssi": self.rssi, "faults": self.faults}


@dataclass
class PendingFrame:
    first_us: int
    meta: tuple[int, int, int, int, int, int]
    chunks: dict[int, tuple[int, dw.Frame, bytes]] = field(default_factory=dict)


class FrameBuffer:
    """Bounded, out-of-order reassembly retaining original packets for byte-exact fallback."""
    def __init__(self):
        self.pending: dict[int, PendingFrame] = {}
        self.retired: deque[tuple[int, int]] = deque(maxlen=64)

    def expire(self, now: int):
        for fid, p in list(self.pending.items()):
            if now - p.first_us > FRAME_TIMEOUT_US:
                self.retired.append((fid, p.meta[1]))
                del self.pending[fid]

    def add(self, seq: int, f: dw.Frame, raw: bytes, now: int):
        self.expire(now)
        if (f.frame_id, f.t_cap_us) in self.retired:
            return None
        if (not 0 < f.total_len <= MAX_FRAME_BYTES or not f.w or not f.h or not f.data or
                not 0 < f.chunk_count <= f.total_len or f.chunk_idx >= f.chunk_count or
                f.offset + len(f.data) > f.total_len):
            return None
        meta = (f.frame_id, f.t_cap_us, f.w, f.h, f.total_len, f.chunk_count)
        p = self.pending.get(f.frame_id)
        if p is None:
            if len(self.pending) >= 4:
                oldest = next(iter(self.pending))
                old = self.pending.pop(oldest)
                self.retired.append((oldest, old.meta[1]))
            p = self.pending[f.frame_id] = PendingFrame(now, meta)
        elif p.meta != meta:
            del self.pending[f.frame_id]
            self.retired.append((f.frame_id, f.t_cap_us))
            return None
        old = p.chunks.get(f.chunk_idx)
        if old is not None and old[2] != raw:
            del self.pending[f.frame_id]
            self.retired.append((f.frame_id, f.t_cap_us))
            return None
        p.chunks[f.chunk_idx] = (seq, f, raw)
        if len(p.chunks) != f.chunk_count:
            return None
        del self.pending[f.frame_id]
        self.retired.append((f.frame_id, f.t_cap_us))
        chunks = [p.chunks[i] for i in range(f.chunk_count)]
        offset = 0
        for _, chunk, _ in chunks:
            if chunk.offset != offset:
                return None
            offset += len(chunk.data)
        if offset != f.total_len:
            return None
        return chunks, b"".join(c.data for _, c, _ in chunks)


class HitlRelay:
    def __init__(self, listen: tuple[str, int], engine: tuple[str, int], feed, *,
                 range_mode: str = "fuse", camera_mode: str = "real",
                 cue_ip: str | None = None, cam_ip: str | None = None,
                 emit: Callable[[dict], None] = lambda _: None,
                 preview: Callable[[bytes], None] = lambda _: None,
                 note: Callable[[str], None] = lambda _: None,
                 host: Callable[[], int] = host_us):
        self._validate_modes(range_mode, camera_mode)
        self.engine = (socket.gethostbyname(engine[0]), engine[1])
        self.feed, self.emit, self.preview, self.note, self.host = feed, emit, preview, note, host
        self.range_mode, self.camera_mode = range_mode, camera_mode
        self.pins = {"cue": cue_ip, "cam": cam_ip}
        self.boards = {role: BoardState() for role in BOARD_PORTS}
        self._lock = threading.RLock()
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._frames = FrameBuffer()
        self._overlay = None
        self._last_preview: int | None = None
        self._frame_times: deque[int] = deque()
        self.frames_out = self.composited = 0
        self.last_error: str | None = None
        self._real_range: dw.Range | None = None
        self._out_range: dw.Range | None = None
        self._range_source = "real"
        self._pending_commands: dict[tuple[str, int], object] = {}
        self._haptic_max = 255  # firmware boot profile; updated only by an accepted real PROFILE ACK
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.twins: dict[str, socket.socket] = {}
        self._selector = selectors.DefaultSelector()
        try:
            self.sock.bind(listen)
            self.address = self.sock.getsockname()
            self._selector.register(self.sock, selectors.EVENT_READ, None)
            for role in BOARD_PORTS:
                twin = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                self.twins[role] = twin
                twin.bind(("127.0.0.1", 0))
                self._selector.register(twin, selectors.EVENT_READ, role)
        except Exception:
            self.stop()
            raise

    @staticmethod
    def _validate_modes(range_mode: str, camera_mode: str):
        if range_mode not in RANGE_MODES or camera_mode not in CAMERA_MODES:
            raise ValueError("invalid HITL range or camera mode")
        if camera_mode == "composite" and not composite_available():
            raise ValueError("HITL composite camera requires Pillow (install with: python3 -m pip install Pillow)")

    def set_modes(self, *, range: str | None = None, camera: str | None = None):
        with self._lock:
            r = self.range_mode if range is None else range
            c = self.camera_mode if camera is None else camera
            self._validate_modes(r, c)
            if c != self.camera_mode:
                self._frames = FrameBuffer()
            self.range_mode, self.camera_mode = r, c

    def put_overlay(self, data: bytes):
        if Image is None:
            raise ValueError("HITL overlay requires Pillow")
        try:
            with Image.open(io.BytesIO(data)) as image:
                if image.format != "PNG" or image.size != (320, 240) or image.mode != "RGBA":
                    raise ValueError("HITL overlay must be a 320x240 RGBA PNG")
                overlay = image.copy()
        except (OSError, Image.DecompressionBombError) as e:
            raise ValueError(f"invalid HITL overlay PNG: {e}") from e
        with self._lock:
            self._overlay = (self.host(), overlay)

    def hello(self) -> dict:
        with self._lock:
            return {"enabled": True, "listen": f"{self.address[0]}:{self.address[1]}",
                    "range": self.range_mode, "camera": self.camera_mode,
                    "compositeAvailable": composite_available()}

    def status(self) -> dict:
        with self._lock:
            now = self.host()
            while self._frame_times and now - self._frame_times[0] > 1_000_000:
                self._frame_times.popleft()
            world = self.feed.reading
            real, out = self._real_range, self._out_range
            return {"t": "hitl", "cue": self.boards["cue"].status(now),
                    "cam": {**self.boards["cam"].status(now),
                            "fps": None if self.boards["cam"].last_us is None else len(self._frame_times)},
                    "range": {"mode": self.range_mode,
                              "realMm": None if real is None else real.mm,
                              "realStatus": None if real is None else real.status,
                              "worldMm": None if world is None else world.mm,
                              "worldStatus": None if world is None else world.status,
                              "worldAgeMs": None if world is None else max(0, now - world.host_us) / 1000,
                              "outMm": None if out is None else out.mm,
                              "outStatus": None if out is None else out.status,
                              "source": self._range_source},
                    "camera": {"mode": self.camera_mode, "framesOut": self.frames_out,
                               "composited": self.composited, "lastError": self.last_error}}

    def _role(self, msg, addr: tuple[str, int]) -> str | None:
        if isinstance(msg, dw.Beat):
            role = {dw.BOARD_CUE: "cue", dw.BOARD_CAM: "cam"}.get(msg.board)
        elif isinstance(msg, (dw.Range, dw.Ack)):
            role = "cue"
        elif isinstance(msg, dw.Frame):
            role = "cam"
        elif isinstance(msg, dw.Pong):
            exact = [r for r, b in self.boards.items() if b.addr == addr]
            candidates = exact or [r for r, b in self.boards.items()
                                   if (b.addr is not None and b.addr[0] == addr[0]) or self.pins[r] == addr[0]]
            role = candidates[0] if len(candidates) == 1 else None
        else:
            return None
        return role if role is not None and self.pins[role] in (None, addr[0]) else None

    def process_board(self, raw: bytes, addr: tuple[str, int]):
        try:
            seq, msg = dw.decode(raw)
        except dw.WireError:
            return
        with self._lock:
            role = self._role(msg, addr)
            if role is None:
                return
            now = self.host()
            board = self.boards[role]
            if board.addr is not None and board.addr[0] != addr[0]:
                if role == "cam":
                    self._frames = FrameBuffer()
                else:
                    self._pending_commands.clear()
                    self._haptic_max = 255
            board.addr, board.last_us = addr, now
            if isinstance(msg, dw.Beat):
                board.rssi, board.faults = msg.rssi_dbm, msg.faults
            if isinstance(msg, dw.Range):
                self._real_range = msg
                self._out_range, self._range_source = fuse_range(msg, self.feed.reading, now, self.range_mode)
                if self._out_range is not msg:
                    raw = dw.encode(self._out_range, seq)
            if isinstance(msg, dw.Frame):
                self._camera(seq, msg, raw, now)
            else:
                self.twins[role].sendto(raw, self.engine)
                if isinstance(msg, dw.Ack):
                    self._ack(msg, now)

    def process_engine(self, role: str, raw: bytes, addr: tuple[str, int]):
        if addr != self.engine:
            return
        try:
            seq, msg = dw.decode(raw)
        except dw.WireError:
            return
        if not isinstance(msg, (dw.Ping, dw.Cue, dw.Profile)):
            return
        with self._lock:
            board = self.boards[role]
            ip = board.addr[0] if board.addr is not None else self.pins[role]
            if ip is None:
                return
            self.sock.sendto(raw, (ip, BOARD_PORTS[role]))
            if isinstance(msg, (dw.Cue, dw.Profile)):
                self._pending_commands[(role, seq)] = msg
                if len(self._pending_commands) > 256:
                    del self._pending_commands[next(iter(self._pending_commands))]

    def _ack(self, ack: dw.Ack, now: int):
        cmd = self._pending_commands.pop(("cue", ack.cue_seq), None)
        if ack.status not in (dw.ACK_APPLIED, dw.ACK_APPLIED_CLAMPED):
            self.note(f"real ACK seq={ack.cue_seq} status={ACK_NAMES.get(ack.status, ack.status)}")
            return
        if isinstance(cmd, dw.Profile):
            if cmd.valid():
                self._haptic_max = cmd.haptic_max
            return
        if not isinstance(cmd, dw.Cue):
            return
        if cmd.cue_id not in CUE_NAMES or cmd.side not in SIDE_NAMES or cmd.haptic_pattern not in HAPTIC_NAMES:
            return
        hl = cmd.haptic_l if cmd.side in (dw.SIDE_L, dw.SIDE_BOTH) else 0
        hr = cmd.haptic_r if cmd.side in (dw.SIDE_R, dw.SIDE_BOTH) else 0
        if cmd.haptic_pattern == dw.HAPTIC["NONE"]:
            hl = hr = 0
        self.emit({"t": "played", "hostMs": now / 1000, "seq": ack.cue_seq,
                   "cue": CUE_NAMES[cmd.cue_id], "side": SIDE_NAMES[cmd.side],
                   "status": ACK_NAMES[ack.status], "haptic": HAPTIC_NAMES[cmd.haptic_pattern],
                   "hl": min(hl, self._haptic_max), "hr": min(hr, self._haptic_max),
                   "hapticMax": self._haptic_max, "src": "phone", "lateUs": 0, "source": "real"})
        self.note(f"real ACK seq={ack.cue_seq} accepted ({ACK_NAMES[ack.status]}); not physical motor onset")

    def _camera(self, seq: int, frame: dw.Frame, raw: bytes, now: int):
        if self.camera_mode == "real":
            self.twins["cam"].sendto(raw, self.engine)
        complete = self._frames.add(seq, frame, raw, now)
        if complete is None:
            return
        chunks, jpeg = complete
        composited = False
        if self.camera_mode == "composite":
            overlay = self._overlay
            if overlay is not None and 0 <= now - overlay[0] <= OVERLAY_MAX_AGE_US:
                try:
                    with Image.open(io.BytesIO(jpeg)) as real:
                        if real.format != "JPEG" or real.size != (frame.w, frame.h):
                            raise ValueError("real JPEG dimensions do not match FRAME metadata")
                        merged = Image.alpha_composite(real.convert("RGBA"), overlay[1].resize(real.size))
                        buf = io.BytesIO()
                        merged.convert("RGB").save(buf, format="JPEG", quality=80)
                        jpeg = buf.getvalue()
                    output = jpg.chunk_frame(frame.frame_id, frame.t_cap_us, frame.w, frame.h, jpeg)
                    first_seq = chunks[0][0]
                    for i, chunk in enumerate(output):
                        self.twins["cam"].sendto(dw.encode(chunk, first_seq + i), self.engine)
                    composited = True
                    self.composited += 1
                    self.last_error = None
                except (OSError, ValueError, Image.DecompressionBombError) as e:
                    self.last_error = f"camera composite failed: {e}"
                    self.note(self.last_error)
            if not composited:
                for _, _, original in chunks:
                    self.twins["cam"].sendto(original, self.engine)
        self.frames_out += 1
        self._frame_times.append(now)
        while self._frame_times and now - self._frame_times[0] > 1_000_000:
            self._frame_times.popleft()
        if self._last_preview is None or now - self._last_preview >= 250_000:
            self._last_preview = now
            self.preview(b"\x11" + jpeg)

    def start(self):
        self._thread = threading.Thread(target=self._run, name="hitl-udp", daemon=True)
        self._thread.start()

    def _run(self):
        while not self._stop.is_set():
            for key, _ in self._selector.select(0.025):
                try:
                    raw, addr = key.fileobj.recvfrom(65535)
                    if key.data is None:
                        self.process_board(raw, addr)
                    else:
                        self.process_engine(key.data, raw, addr)
                except OSError as e:
                    if not self._stop.is_set():
                        self.note(f"HITL UDP forwarding failed: {e}")
            with self._lock:
                self._frames.expire(self.host())

    def stop(self):
        self._stop.set()
        if self._thread is not None:
            self._thread.join(2.0)
        self._selector.close()
        self.sock.close()
        for twin in self.twins.values():
            twin.close()
