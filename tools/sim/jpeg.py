"""Minimal pure-Python baseline JPEG encoder (JFIF, YCbCr 4:2:0, Annex K tables) plus the
FRAME chunker. Used only by the simulator: the frames are synthetic test images whose
content does not match the scene (the phone's detector just needs real, decodable JPEGs).
"""
from __future__ import annotations

import math
import random
import struct

from dtwire import dw

ZIGZAG = [
    0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
    12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
    58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63,
]

# ITU T.81 Annex K.1 quantisation tables (natural order).
Q_LUMA = [
    16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
    14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
    18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
    49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99,
]
Q_CHROMA = [
    17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99,
    24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
] + [99] * 32

# Annex K.3 standard Huffman tables: (bits[1..16], values).
DC_LUMA = ([0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0], list(range(12)))
DC_CHROMA = ([0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0], list(range(12)))
AC_LUMA = ([0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7D], bytes.fromhex(
    "01020300041105122131410613516107227114328191a1082342b1c11552d1f02433627282090a161718191a25262728292a"
    "3435363738393a434445464748494a535455565758595a636465666768696a737475767778797a838485868788898a9293949596"
    "9798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae1e2e3e4e5e6e7e8e9eaf1f2"
    "f3f4f5f6f7f8f9fa"))
AC_CHROMA = ([0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77], bytes.fromhex(
    "000102031104052131061241510761711322328108144291a1b1c109233352f0156272d10a162434e125f11718191a26272829"
    "2a35363738393a434445464748494a535455565758595a636465666768696a737475767778797a82838485868788898a92939495"
    "969798999aa2a3a4a5a6a7a8a9aab2b3b4b5b6b7b8b9bac2c3c4c5c6c7c8c9cad2d3d4d5d6d7d8d9dae2e3e4e5e6e7e8e9eaf2f3"
    "f4f5f6f7f8f9fa"))


def _huff_codes(spec) -> dict[int, tuple[int, int]]:
    bits, vals = spec
    codes, code, k = {}, 0, 0
    for length in range(1, 17):
        for _ in range(bits[length - 1]):
            codes[vals[k]] = (code, length)
            code += 1
            k += 1
        code <<= 1
    return codes


_HUFF = {name: _huff_codes(spec) for name, spec in
         (("dcl", DC_LUMA), ("dcc", DC_CHROMA), ("acl", AC_LUMA), ("acc", AC_CHROMA))}

# DCT-II basis C[u][x] = c(u)/2 · cos((2x+1)uπ/16); 2-D DCT = C · block · Cᵀ.
_C = [[(math.sqrt(0.5) if u == 0 else 1.0) / 2 * math.cos((2 * x + 1) * u * math.pi / 16)
       for x in range(8)] for u in range(8)]


def scaled_qtable(base: list[int], quality: int) -> list[int]:
    """IJG quality scaling, natural order."""
    quality = min(100, max(1, quality))
    scale = 5000 // quality if quality < 50 else 200 - 2 * quality
    return [min(255, max(1, (q * scale + 50) // 100)) for q in base]


class _BitWriter:
    def __init__(self):
        self.out = bytearray()
        self.acc = 0
        self.n = 0

    def write(self, code: int, length: int):
        self.acc = (self.acc << length) | (code & ((1 << length) - 1))
        self.n += length
        while self.n >= 8:
            self.n -= 8
            byte = (self.acc >> self.n) & 0xFF
            self.out.append(byte)
            if byte == 0xFF:
                self.out.append(0x00)  # byte stuffing
        self.acc &= (1 << self.n) - 1

    def flush(self):
        if self.n:
            self.write((1 << (8 - self.n)) - 1, 8 - self.n)  # pad with 1-bits


def _fdct_quant(block: list[float], q: list[int]) -> list[int]:
    """block: 64 level-shifted samples (natural order). Returns quantised coeffs in zigzag order."""
    rows = [block[r * 8:(r + 1) * 8] for r in range(8)]
    tmp = [[sum(cu[x] * row[x] for x in range(8)) for cu in _C] for row in rows]  # tmp[r][u]
    coef = [0.0] * 64
    for v in range(8):
        cv = _C[v]
        for u in range(8):
            coef[v * 8 + u] = sum(cv[r] * tmp[r][u] for r in range(8))
    return [int(round(coef[z] / q[z])) for z in ZIGZAG]


def _category(v: int) -> int:
    return abs(v).bit_length()


def _encode_block(bw: _BitWriter, zz: list[int], prev_dc: int, dc_codes, ac_codes) -> int:
    diff = zz[0] - prev_dc
    s = _category(diff)
    bw.write(*dc_codes[s])
    if s:
        bw.write(diff if diff > 0 else diff + (1 << s) - 1, s)
    run = 0
    for k in range(1, 64):
        v = zz[k]
        if v == 0:
            run += 1
            continue
        while run > 15:
            bw.write(*ac_codes[0xF0])  # ZRL
            run -= 16
        s = _category(v)
        bw.write(*ac_codes[(run << 4) | s])
        bw.write(v if v > 0 else v + (1 << s) - 1, s)
        run = 0
    if run:
        bw.write(*ac_codes[0x00])  # EOB
    return zz[0]


def _segment(marker: int, payload: bytes) -> bytes:
    return struct.pack(">HH", 0xFF00 | marker, len(payload) + 2) + payload


def encode_rgb(width: int, height: int, rgb: bytes | bytearray, quality: int = 75) -> bytes:
    """Encode packed RGB888 (row-major) to a baseline JFIF JPEG, YCbCr 4:2:0."""
    if len(rgb) != width * height * 3:
        raise ValueError("rgb buffer size mismatch")
    if not (0 < width < 65536 and 0 < height < 65536):
        raise ValueError("bad dimensions")
    ql, qc = scaled_qtable(Q_LUMA, quality), scaled_qtable(Q_CHROMA, quality)
    # Colour conversion (JFIF), with edge replication to a multiple of 16.
    pw, ph = (width + 15) // 16 * 16, (height + 15) // 16 * 16
    Y = [0.0] * (pw * ph)
    Cb = [0.0] * (pw * ph)
    Cr = [0.0] * (pw * ph)
    for y in range(ph):
        sy = min(y, height - 1)
        for x in range(pw):
            sx = min(x, width - 1)
            i = (sy * width + sx) * 3
            r, g, b = rgb[i], rgb[i + 1], rgb[i + 2]
            j = y * pw + x
            Y[j] = 0.299 * r + 0.587 * g + 0.114 * b - 128
            Cb[j] = -0.168736 * r - 0.331264 * g + 0.5 * b
            Cr[j] = 0.5 * r - 0.418688 * g - 0.081312 * b
    bw = _BitWriter()
    dc_y = dc_cb = dc_cr = 0
    for my in range(0, ph, 16):
        for mx in range(0, pw, 16):
            for by, bx in ((0, 0), (0, 8), (8, 0), (8, 8)):
                blk = [Y[(my + by + r) * pw + mx + bx + c] for r in range(8) for c in range(8)]
                dc_y = _encode_block(bw, _fdct_quant(blk, ql), dc_y, _HUFF["dcl"], _HUFF["acl"])
            for plane, which in ((Cb, 0), (Cr, 1)):
                blk = []
                for r in range(8):
                    base = (my + 2 * r) * pw + mx
                    for c in range(8):
                        i = base + 2 * c
                        blk.append((plane[i] + plane[i + 1] + plane[i + pw] + plane[i + pw + 1]) / 4)
                zz = _fdct_quant(blk, qc)
                if which == 0:
                    dc_cb = _encode_block(bw, zz, dc_cb, _HUFF["dcc"], _HUFF["acc"])
                else:
                    dc_cr = _encode_block(bw, zz, dc_cr, _HUFF["dcc"], _HUFF["acc"])
    bw.flush()

    out = bytearray(b"\xFF\xD8")
    out += _segment(0xE0, b"JFIF\x00\x01\x01\x00\x00\x01\x00\x01\x00\x00")
    out += _segment(0xDB, bytes([0x00]) + bytes(ql[z] for z in ZIGZAG) + bytes([0x01]) + bytes(qc[z] for z in ZIGZAG))
    out += _segment(0xC0, struct.pack(">BHHB", 8, height, width, 3) + bytes([1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1]))
    for tc_th, (bits, vals) in ((0x00, DC_LUMA), (0x10, AC_LUMA), (0x01, DC_CHROMA), (0x11, AC_CHROMA)):
        out += _segment(0xC4, bytes([tc_th]) + bytes(bits) + bytes(vals))
    out += _segment(0xDA, bytes([3, 1, 0x00, 2, 0x11, 3, 0x11, 0, 63, 0]))
    out += bw.out
    out += b"\xFF\xD9"
    return bytes(out)


def synthetic_rgb(width: int, height: int, seed: int) -> bytearray:
    """A plausible corridor-ish test card: sky/wall gradient, floor, a few rectangles, sensor noise."""
    rng = random.Random(seed)
    buf = bytearray(width * height * 3)
    horizon = height * (0.55 + 0.05 * rng.random())
    rects = [(rng.randrange(width), rng.randrange(height), rng.randrange(8, width // 3),
              rng.randrange(8, height // 2), (rng.randrange(256), rng.randrange(256), rng.randrange(256)))
             for _ in range(4)]
    for y in range(height):
        for x in range(width):
            if y < horizon:
                base = (150 + 60 * y // height, 160 + 50 * y // height, 175 + 40 * y // height)
            else:
                base = (90 + (x * 7 + y * 3) % 40, 80, 70)
            px = base
            for rx, ry, rw, rh, col in rects:
                if rx <= x < rx + rw and ry <= y < ry + rh:
                    px = col
            i = (y * width + x) * 3
            n = rng.randint(-6, 6)
            buf[i] = min(255, max(0, px[0] + n))
            buf[i + 1] = min(255, max(0, px[1] + n))
            buf[i + 2] = min(255, max(0, px[2] + n))
    return buf


def with_comment(jpeg: bytes, text: str) -> bytes:
    """Insert a COM segment right after SOI (cheap per-frame variation that stays valid)."""
    if jpeg[:2] != b"\xFF\xD8":
        raise ValueError("not a JPEG")
    return jpeg[:2] + _segment(0xFE, text.encode("ascii")) + jpeg[2:]


def jpeg_size(jpeg: bytes) -> tuple[int, int]:
    """(width, height) from the SOF0/SOF2 segment; raises ValueError on a malformed stream."""
    if jpeg[:2] != b"\xFF\xD8":
        raise ValueError("missing SOI")
    i = 2
    while i + 4 <= len(jpeg):
        if jpeg[i] != 0xFF:
            raise ValueError(f"expected marker at {i}")
        marker = jpeg[i + 1]
        seglen = struct.unpack(">H", jpeg[i + 2:i + 4])[0]
        if marker in (0xC0, 0xC1, 0xC2):
            h, w = struct.unpack(">HH", jpeg[i + 5:i + 9])
            return w, h
        i += 2 + seglen
    raise ValueError("no SOF segment")


def chunk_frame(frame_id: int, t_cap_us: int, w: int, h: int, jpeg: bytes,
                max_chunk: int = dw.MAX_CHUNK) -> list:
    """Split one JPEG into FRAME messages (PROTOCOL.md "FRAME"): chunk_len ≤ 1400."""
    if not 0 < max_chunk <= dw.MAX_CHUNK:
        raise ValueError("max_chunk out of range")
    count = (len(jpeg) + max_chunk - 1) // max_chunk
    if count > 0xFFFF:
        raise ValueError("frame too large")
    return [dw.Frame(frame_id & 0xFFFFFFFF, t_cap_us, w, h, len(jpeg), off, idx, count,
                     jpeg[off:off + max_chunk])
            for idx, off in enumerate(range(0, len(jpeg), max_chunk))]


def make_test_jpegs(n: int = 3, width: int = 320, height: int = 240, quality: int = 70, seed: int = 1) -> list[bytes]:
    return [encode_rgb(width, height, synthetic_rgb(width, height, seed + k), quality) for k in range(n)]
