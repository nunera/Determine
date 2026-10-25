#!/usr/bin/env python3
"""Simulate BOTH Determine wearable boards against a phone (real app, phone/cli, or bench.py).

  python3 tools/sim/sim_boards.py --phone 127.0.0.1 --scenario approach_centre

Output: one line per event on stdout (space-separated key=value; `host=` is CLOCK_MONOTONIC µs,
the same clock as the phone cli's System.nanoTime()):
  SIM host=.. t=.. event=start|dropout_start|dropout_end|end ...
  SYNC board=cue|cam first_ping t=<board s> host=..
  CUE t=<board s> id=NAME side=L|R|BOTH haptic=PATTERN status=ACK_STATUS hl=.. hr=..
      rhl=.. rhr=.. flags=.. ttl=.. seq=.. deadline=<board µs> late_us=.. haptic_max=.. src=phone|watchdog host=..
  PROFILE t=.. applied|REJECTED seq=.. mode=.. haptic_max=.. host=..
  WATCHDOG enter|exit t=.. host=.. reason=..
  STATS board=.. <counters>
"""
from __future__ import annotations

import argparse
import os
import signal
import sys
import threading

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import jpeg as jpg  # noqa: E402
import scenarios  # noqa: E402
from boards import DEFAULT_CAM_CLOCK, DEFAULT_CUE_CLOCK, ClockSpec, SimRig  # noqa: E402
from dtwire import dw  # noqa: E402


def _size(s: str) -> tuple[int, int]:
    try:
        w, h = (int(v) for v in s.lower().split("x"))
    except ValueError:
        raise argparse.ArgumentTypeError("size must look like 320x240") from None
    if not (16 <= w <= 1600 and 16 <= h <= 1200):
        raise argparse.ArgumentTypeError("size out of range")
    return w, h


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(description="Determine two-board simulator (protocol v2: one forward ToF zone, "
                                            "haptic-only cue board).",
                                epilog="Scenarios: " + "; ".join(f"{n}: {f(1).description}"
                                                                 for n, f in scenarios.SCENARIOS.items()))
    p.add_argument("--phone", default="127.0.0.1", help="phone IP (default 127.0.0.1)")
    p.add_argument("--phone-port", type=int, default=dw.PORT_PHONE, help="phone UDP port (default 47000)")
    p.add_argument("--bind", default="0.0.0.0", help="local address the boards bind (default 0.0.0.0)")
    p.add_argument("--cue-port", type=int, default=dw.PORT_CUE, help="cue board port (default 47001; 0 = any)")
    p.add_argument("--cam-port", type=int, default=dw.PORT_CAM, help="camera board port (default 47002; 0 = any)")
    p.add_argument("--scenario", default="approach_centre", choices=sorted(scenarios.SCENARIOS))
    p.add_argument("--seed", type=int, default=1, help="scene/noise/impairment seed (default 1)")
    p.add_argument("--duration", type=float, default=None,
                   help="seconds to run (default: scenario duration; 0 = until Ctrl-C)")
    p.add_argument("--fps", type=float, default=11.0, help="camera frame rate 10–15 (default 11)")
    p.add_argument("--jpeg-size", type=_size, default=(320, 240), help="synthetic JPEG size (default 320x240)")
    p.add_argument("--cue-boot-age", type=float, default=DEFAULT_CUE_CLOCK.boot_age_s,
                   help="cue board seconds since boot at start (sets its clock offset)")
    p.add_argument("--cue-drift-ppm", type=float, default=DEFAULT_CUE_CLOCK.drift_ppm)
    p.add_argument("--cam-boot-age", type=float, default=DEFAULT_CAM_CLOCK.boot_age_s)
    p.add_argument("--cam-drift-ppm", type=float, default=DEFAULT_CAM_CLOCK.drift_ppm)
    p.add_argument("--list", action="store_true", help="list scenarios and exit")
    p.add_argument("--loop", action="store_true",
                   help="repeat the scenario every duration_s with the boards running continuously; runs until Ctrl-C")
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.list:
        for name, fn in scenarios.SCENARIOS.items():
            s = fn(args.seed)
            print(f"{name:20s} {s.duration_s:5.1f}s  {s.description}")
        return 0
    scen = scenarios.get(args.scenario, args.seed)
    duration = scen.duration_s if args.duration is None else args.duration
    if args.loop:
        scen = scenarios.looped(scen)
        duration = 0 if args.duration is None else args.duration

    def out(line: str):
        sys.stdout.write(line + "\n")
        sys.stdout.flush()

    jpegs = jpg.make_test_jpegs(3, *args.jpeg_size, seed=args.seed)
    rig = SimRig(scen, phone=(args.phone, args.phone_port), bind=args.bind, cue_port=args.cue_port,
                 cam_port=args.cam_port, log=out, jpegs=jpegs, fps=args.fps,
                 cue_clock=ClockSpec(args.cue_boot_age, args.cue_drift_ppm),
                 cam_clock=ClockSpec(args.cam_boot_age, args.cam_drift_ppm))
    done = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: done.set())
    signal.signal(signal.SIGINT, lambda *_: done.set())
    rig.start()
    try:
        done.wait(None if duration <= 0 else duration)
    finally:
        rig.stop()
    return 0


if __name__ == "__main__":
    sys.exit(main())
