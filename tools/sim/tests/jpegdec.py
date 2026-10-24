"""Test helper: a small independent baseline-JPEG decoder (Huffman, 8-bit, no restart markers,
any H×V sampling) used to prove the simulator's frames are real decodable JPEGs."""
from __future__ import annotations

import math
import struct

ZZ = [0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14,
      21, 28, 35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60,
      61, 54, 47, 55, 62, 63]
_COS = [[math.cos((2 * x + 1) * u * math.pi / 16) * (math.sqrt(0.5) if u == 0 else 1.0) for u in range(8)]
        for x in range(8)]


class _Bits:
    def __init__(self, data: bytes, pos: int):
        self.d, self.p, self.acc, self.n = data, pos, 0, 0

    def bit(self) -> int:
        if self.n == 0:
            b = self.d[self.p]
            self.p += 1
            if b == 0xFF:
                nxt = self.d[self.p]
                if nxt != 0x00:
                    raise ValueError(f"marker FF{nxt:02X} inside entropy data")
                self.p += 1
            self.acc, self.n = b, 8
        self.n -= 1
        return (self.acc >> self.n) & 1

    def bits(self, k: int) -> int:
        v = 0
        for _ in range(k):
            v = (v << 1) | self.bit()
        return v


def _huff(bits_counts, vals):
    table, code, k = {}, 0, 0
    for length in range(1, 17):
        for _ in range(bits_counts[length - 1]):
            table[(length, code)] = vals[k]
            code += 1
            k += 1
        code <<= 1
    return table


def _decode_sym(br: _Bits, table) -> int:
    code = 0
    for length in range(1, 17):
        code = (code << 1) | br.bit()
        if (length, code) in table:
            return table[(length, code)]
    raise ValueError("bad Huffman code")


def _extend(v: int, s: int) -> int:
    return v if s == 0 or v >= (1 << (s - 1)) else v - (1 << s) + 1


def _idct(coef):
    out = [0.0] * 64
    for y in range(8):
        cy = _COS[y]
        for x in range(8):
            cx = _COS[x]
            s = 0.0
            for v in range(8):
                for u in range(8):
                    c = coef[v * 8 + u]
                    if c:
                        s += cx[u] * cy[v] * c
            out[y * 8 + x] = s / 4
    return out


def decode(data: bytes) -> tuple[int, int, bytearray]:
    """Returns (width, height, rgb888)."""
    if data[:2] != b"\xFF\xD8":
        raise ValueError("no SOI")
    if data[-2:] != b"\xFF\xD9":
        raise ValueError("no EOI")
    qt, ht, comps = {}, {}, []
    w = h = 0
    i = 2
    while True:
        if data[i] != 0xFF:
            raise ValueError("expected marker")
        m = data[i + 1]
        ln = struct.unpack(">H", data[i + 2:i + 4])[0]
        seg = data[i + 4:i + 2 + ln]
        if m == 0xDB:
            j = 0
            while j < len(seg):
                pq, tq = seg[j] >> 4, seg[j] & 15
                if pq:
                    raise ValueError("16-bit tables unsupported")
                qt[tq] = list(seg[j + 1:j + 65])
                j += 65
        elif m == 0xC0:
            if seg[0] != 8:
                raise ValueError("precision")
            h, w = struct.unpack(">HH", seg[1:5])
            comps = [dict(id=seg[6 + 3 * k], H=seg[7 + 3 * k] >> 4, V=seg[7 + 3 * k] & 15, tq=seg[8 + 3 * k])
                     for k in range(seg[5])]
        elif m in (0xC1, 0xC2, 0xC3):
            raise ValueError("not baseline")
        elif m == 0xC4:
            j = 0
            while j < len(seg):
                tc_th = seg[j]
                counts = list(seg[j + 1:j + 17])
                n = sum(counts)
                ht[tc_th] = _huff(counts, list(seg[j + 17:j + 17 + n]))
                j += 17 + n
        elif m == 0xDA:
            ns = seg[0]
            sel = {seg[1 + 2 * k]: seg[2 + 2 * k] for k in range(ns)}
            for c in comps:
                c["td"], c["ta"] = sel[c["id"]] >> 4, sel[c["id"]] & 15
            pos = i + 2 + ln
            break
        i += 2 + ln
    hmax = max(c["H"] for c in comps)
    vmax = max(c["V"] for c in comps)
    mcux, mcuy = (w + 8 * hmax - 1) // (8 * hmax), (h + 8 * vmax - 1) // (8 * vmax)
    planes = []
    for c in comps:
        c["pw"], c["ph"] = mcux * c["H"] * 8, mcuy * c["V"] * 8
        planes.append([0.0] * (c["pw"] * c["ph"]))
        c["pred"] = 0
    br = _Bits(data, pos)
    for my in range(mcuy):
        for mx in range(mcux):
            for ci, c in enumerate(comps):
                for by in range(c["V"]):
                    for bx in range(c["H"]):
                        coef = [0] * 64
                        s = _decode_sym(br, ht[0x00 | c["td"]])
                        c["pred"] += _extend(br.bits(s), s)
                        coef[0] = c["pred"] * qt[c["tq"]][0]
                        k = 1
                        while k < 64:
                            rs = _decode_sym(br, ht[0x10 | c["ta"]])
                            r, s = rs >> 4, rs & 15
                            if s == 0:
                                if r != 15:
                                    break
                                k += 16
                                continue
                            k += r
                            coef[ZZ[k]] = _extend(br.bits(s), s) * qt[c["tq"]][k]
                            k += 1
                        px = _idct(coef)
                        ox, oy = (mx * c["H"] + bx) * 8, (my * c["V"] + by) * 8
                        pl, pw = planes[ci], c["pw"]
                        for yy in range(8):
                            base = (oy + yy) * pw + ox
                            pl[base:base + 8] = px[yy * 8:yy * 8 + 8]
    rgb = bytearray(w * h * 3)
    for y in range(h):
        for x in range(w):
            vals = []
            for ci, c in enumerate(comps):
                sx, sy = x * c["H"] // hmax, y * c["V"] // vmax
                vals.append(planes[ci][sy * c["pw"] + sx])
            Y = vals[0] + 128
            cb, cr = (vals[1], vals[2]) if len(vals) == 3 else (0.0, 0.0)
            o = (y * w + x) * 3
            rgb[o] = min(255, max(0, round(Y + 1.402 * cr)))
            rgb[o + 1] = min(255, max(0, round(Y - 0.344136 * cb - 0.714136 * cr)))
            rgb[o + 2] = min(255, max(0, round(Y + 1.772 * cb)))
    return w, h, rgb
