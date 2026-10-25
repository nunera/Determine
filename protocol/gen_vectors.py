#!/usr/bin/env python3
"""Regenerate protocol/vectors/vectors.json from the Python reference codec.

Each vector: {"name", "type", "seq", "fields", "hex"}. Other implementations must
decode "hex" to "fields" and encode "fields" back to "hex".
"""
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).parent / "python"))
import determine_wire as w  # noqa: E402

CASES = [
    ("beat_cue", 1, w.Beat(w.BOARD_CUE, 1, 0x0081, 1_234_567_890_123, -61, 41, 212)),
    ("beat_cam_unknown_temp", 4_294_967_295, w.Beat(w.BOARD_CAM, 15, 0x0200, 5, -90, 127, 0)),
    ("range_valid", 42, w.Range(987_654_321, 1187, w.RANGE_VALID)),
    ("range_no_target", 43, w.Range(987_654_322, 0, w.RANGE_NO_TARGET)),
    ("range_unknown", 44, w.Range(987_654_323, 0, w.RANGE_UNKNOWN)),
    ("frame_chunk", 7, w.Frame(3, 111_222_333, 320, 240, 2900, 1400, 1, 3, bytes(range(256)) * 2)),
    ("frame_empty_chunk", 8, w.Frame(4, 1, 640, 480, 0, 0, 0, 1, b"")),
    ("ping", 9, w.Ping(18_000_000_000_000_000)),
    ("pong", 10, w.Pong(18_000_000_000_000_000, 5_000_001, 5_000_150)),
    ("cue_near_both", 11, w.Cue(1_700_000_000, 9_000_150_000, 3, w.SIDE_BOTH, 3, 200, 200, 0, 150)),
    ("cue_no_sync", 12, w.Cue(1, 0, 7, w.SIDE_BOTH, 4, 255, 255, 0, 65535)),
    ("cue_ready_left", 13, w.Cue(1_700_000_001, 9_000_100_000, 0, w.SIDE_L, 1, 180, 0, 1, 150)),
    ("ack_applied", 14, w.Ack(11, w.ACK_APPLIED, 9_000_100_000)),
    ("ack_applied_clamped", 15, w.Ack(12, w.ACK_APPLIED_CLAMPED, 9_000_100_001)),
    ("ack_expired", 16, w.Ack(13, w.ACK_EXPIRED, 9_000_100_002)),
    ("profile_ssd", 17, w.Profile(w.MODE_SSD, 180)),
    ("profile_haptic_only", 18, w.Profile(w.MODE_HAPTIC_ONLY, 255)),
]


def main() -> None:
    out = []
    for name, seq, msg in CASES:
        pkt = w.encode(msg, seq)
        dseq, back = w.decode(pkt)
        assert dseq == seq and back == msg, name
        out.append({"name": name, "type": type(msg).__name__.upper(), "seq": seq,
                    "fields": w.as_dict(msg), "hex": pkt.hex()})
    path = pathlib.Path(__file__).parent / "vectors" / "vectors.json"
    path.parent.mkdir(exist_ok=True)
    path.write_text(json.dumps(out, indent=1) + "\n")
    print(f"wrote {len(out)} vectors to {path}")


if __name__ == "__main__":
    main()
