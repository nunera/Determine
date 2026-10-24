"""Reference codec for the Determine wire protocol v1 (see protocol/PROTOCOL.md)."""
from __future__ import annotations

import struct
from dataclasses import dataclass, fields

MAGIC = 0x5444
VERSION = 1
HEADER = struct.Struct("<HBBI")

PORT_PHONE = 47000
PORT_CUE = 47001
PORT_CAM = 47002

MAX_CHUNK = 1400
STATUS_NO_TARGET = 255
VALID_STATUS = frozenset({5, 9})
GAIN_MUTE = -128
FW_HARD_CEILING_DB = -10

BOARD_CAM, BOARD_CUE = 1, 2
SIDE_L, SIDE_R, SIDE_BOTH = 0, 1, 2
MODE_STEREO, MODE_SSD, MODE_HAPTIC_ONLY = 0, 1, 2
ACK_PLAYED, ACK_EXPIRED, ACK_PLAYED_CLAMPED, ACK_NO_SYNC, ACK_BAD_CUE, ACK_BUSY_DROPPED = range(6)

CUE_IDS = {
    "READY": 0, "OBSTACLE_FAR": 1, "OBSTACLE_MID": 2, "OBSTACLE_NEAR": 3,
    "PERSON_FAR": 4, "PERSON_MID": 5, "PERSON_NEAR": 6, "SYSTEM_OFF": 7,
    "CAL_TONE_500": 8, "CAL_TONE_1K": 9, "CAL_TONE_2K": 10, "CAL_OBSTACLE": 11,
}
HAPTIC = {"NONE": 0, "PULSE40": 1, "PULSE60": 2, "BUZZ150": 3, "LSS": 4}


class WireError(ValueError):
    pass


@dataclass
class Beat:
    TYPE = 0x01
    FMT = struct.Struct("<BBHQbbH")
    board: int
    reset_reason: int
    faults: int
    t_us: int
    rssi_dbm: int
    temp_c: int
    heap_kb: int

    def body(self) -> bytes:
        return self.FMT.pack(self.board, self.reset_reason, self.faults, self.t_us,
                             self.rssi_dbm, self.temp_c, self.heap_kb)

    @classmethod
    def from_body(cls, b: bytes) -> "Beat":
        return cls(*cls.FMT.unpack(b))


@dataclass
class Range:
    TYPE = 0x02
    FMT = struct.Struct("<QBBH64H64B")
    t_us: int
    mm: list[int]
    status: list[int]
    res: int = 64

    def body(self) -> bytes:
        if len(self.mm) != 64 or len(self.status) != 64:
            raise WireError("RANGE needs 64 zones")
        return self.FMT.pack(self.t_us, self.res, 0, 0, *self.mm, *self.status)

    @classmethod
    def from_body(cls, b: bytes) -> "Range":
        v = cls.FMT.unpack(b)
        return cls(t_us=v[0], res=v[1], mm=list(v[4:68]), status=list(v[68:132]))


@dataclass
class Frame:
    TYPE = 0x03
    FMT = struct.Struct("<IQHHIIHHH")
    frame_id: int
    t_cap_us: int
    w: int
    h: int
    total_len: int
    offset: int
    chunk_idx: int
    chunk_count: int
    data: bytes

    def body(self) -> bytes:
        if len(self.data) > MAX_CHUNK:
            raise WireError("chunk too large")
        return self.FMT.pack(self.frame_id, self.t_cap_us, self.w, self.h, self.total_len,
                             self.offset, len(self.data), self.chunk_idx, self.chunk_count) + self.data

    @classmethod
    def from_body(cls, b: bytes) -> "Frame":
        n = cls.FMT.size
        if len(b) < n:
            raise WireError("short FRAME")
        fid, t, w, h, total, off, clen, idx, cnt = cls.FMT.unpack(b[:n])
        if len(b) != n + clen or clen > MAX_CHUNK:
            raise WireError("FRAME length mismatch")
        return cls(fid, t, w, h, total, off, idx, cnt, bytes(b[n:]))


@dataclass
class Ping:
    TYPE = 0x10
    FMT = struct.Struct("<Q")
    t_phone_us: int

    def body(self) -> bytes:
        return self.FMT.pack(self.t_phone_us)

    @classmethod
    def from_body(cls, b: bytes) -> "Ping":
        return cls(*cls.FMT.unpack(b))


@dataclass
class Pong:
    TYPE = 0x11
    FMT = struct.Struct("<QQQ")
    t_phone_us: int
    t_board_rx_us: int
    t_board_tx_us: int

    def body(self) -> bytes:
        return self.FMT.pack(self.t_phone_us, self.t_board_rx_us, self.t_board_tx_us)

    @classmethod
    def from_body(cls, b: bytes) -> "Pong":
        return cls(*cls.FMT.unpack(b))


@dataclass
class Cue:
    TYPE = 0x20
    FMT = struct.Struct("<QQBBbbBBBBH")
    t_issued_phone_us: int
    deadline_board_us: int
    cue_id: int
    side: int
    gain_l_db: int
    gain_r_db: int
    haptic_pattern: int
    haptic_l: int
    haptic_r: int
    flags: int
    ttl_ms: int

    def body(self) -> bytes:
        return self.FMT.pack(self.t_issued_phone_us, self.deadline_board_us, self.cue_id, self.side,
                             self.gain_l_db, self.gain_r_db, self.haptic_pattern, self.haptic_l,
                             self.haptic_r, self.flags, self.ttl_ms)

    @classmethod
    def from_body(cls, b: bytes) -> "Cue":
        return cls(*cls.FMT.unpack(b))


@dataclass
class Ack:
    TYPE = 0x21
    FMT = struct.Struct("<IBBbbQ")
    cue_seq: int
    status: int
    applied_gain_l_db: int
    applied_gain_r_db: int
    t_board_us: int

    def body(self) -> bytes:
        return self.FMT.pack(self.cue_seq, self.status, 0, self.applied_gain_l_db,
                             self.applied_gain_r_db, self.t_board_us)

    @classmethod
    def from_body(cls, b: bytes) -> "Ack":
        seq, st, _r, gl, gr, t = cls.FMT.unpack(b)
        return cls(seq, st, gl, gr, t)


@dataclass
class Profile:
    TYPE = 0x22
    FMT = struct.Struct("<bBBB")
    ceiling_db: int
    mode: int
    haptic_max: int

    def body(self) -> bytes:
        return self.FMT.pack(self.ceiling_db, self.mode, self.haptic_max, 0)

    @classmethod
    def from_body(cls, b: bytes) -> "Profile":
        c, m, h, _r = cls.FMT.unpack(b)
        return cls(c, m, h)


TYPES = {c.TYPE: c for c in (Beat, Range, Frame, Ping, Pong, Cue, Ack, Profile)}


def encode(msg, seq: int) -> bytes:
    return HEADER.pack(MAGIC, VERSION, msg.TYPE, seq & 0xFFFFFFFF) + msg.body()


def decode(pkt: bytes):
    """Return (seq, message). Raises WireError on any malformed packet."""
    if len(pkt) < HEADER.size:
        raise WireError("short header")
    magic, ver, typ, seq = HEADER.unpack_from(pkt)
    if magic != MAGIC:
        raise WireError("bad magic")
    if ver != VERSION:
        raise WireError("bad version")
    cls = TYPES.get(typ)
    if cls is None:
        raise WireError(f"unknown type {typ:#x}")
    body = pkt[HEADER.size:]
    if cls is not Frame and len(body) != cls.FMT.size:
        raise WireError(f"{cls.__name__} length {len(body)} != {cls.FMT.size}")
    try:
        return seq, cls.from_body(body)
    except struct.error as e:
        raise WireError(str(e)) from e


def as_dict(msg) -> dict:
    d = {f.name: getattr(msg, f.name) for f in fields(msg)}
    if isinstance(msg, Frame):
        d["data"] = msg.data.hex()
    return d
