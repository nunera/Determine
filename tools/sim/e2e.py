#!/usr/bin/env python3
"""End-to-end harness: the headless phone (phone/cli) against sim_boards.py.

For each scenario: start the phone cli (binds the phone port), start sim_boards.py with the
scenario, collect both logs, then assert the scenario's expectations. Logs are kept under
--out-dir/<scenario>/{phone,sim}.log.

  python3 tools/sim/e2e.py                          # all scenarios, installed cli
  python3 tools/sim/e2e.py --build                  # gradle :cli:installDist first (nix shell)
  python3 tools/sim/e2e.py --scenario approach_centre --scenario cam_dropout
  python3 tools/sim/e2e.py --phone-cmd "java -jar my.jar --port {phone_port}"

The phone cli output format is the stable one in phone/core/API.md ("Stable cli output"):
`KIND <ms since cli start> key=value ...`. Times are mapped onto the simulator's host clock
(CLOCK_MONOTONIC µs) by matching PROFILE-TX/CUE-TX seq with the simulator's PROFILE/CUE log
lines, with the line read time as a fallback.
"""
from __future__ import annotations

import argparse
import glob
import os
import shlex
import signal
import statistics
import subprocess
import sys
import threading
import time
from dataclasses import dataclass, field

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(os.path.dirname(HERE))
sys.path.insert(0, HERE)

import scenarios  # noqa: E402
from simclock import host_us  # noqa: E402

OBSTACLE_IDS = {"OBSTACLE_FAR", "OBSTACLE_MID", "OBSTACLE_NEAR", "PERSON_FAR", "PERSON_MID", "PERSON_NEAR"}
BAND_OF = {"OBSTACLE_FAR": "FAR", "PERSON_FAR": "FAR", "OBSTACLE_MID": "MID", "PERSON_MID": "MID",
           "OBSTACLE_NEAR": "NEAR", "PERSON_NEAR": "NEAR"}
PLAYED = {"PLAYED", "PLAYED_CLAMPED"}
CLEAR_WORDS = ("clear", "all clear", "safe", "nothing ahead", "no obstacle")


# ---------------------------------------------------------------------- parsing

@dataclass
class Line:
    kind: str
    kv: dict
    words: list
    raw: str
    read_us: int           # host time the harness read the line
    ms: float | None = None  # phone lines: ms since cli start
    host: int | None = None  # resolved host µs


def parse_kv(tokens: list[str]) -> tuple[dict, list]:
    kv, words = {}, []
    i = 0
    while i < len(tokens):
        t = tokens[i]
        if "=" in t:
            k, v = t.split("=", 1)
            if v.startswith('"') and not (len(v) > 1 and v.endswith('"')):
                # quoted value with spaces (SPEAK text="...")
                parts = [v]
                while i + 1 < len(tokens) and not parts[-1].endswith('"'):
                    i += 1
                    parts.append(tokens[i])
                v = " ".join(parts)
            kv[k] = v.strip('"')
        else:
            words.append(t)
        i += 1
    return kv, words


def parse_sim_line(raw: str, read_us: int = 0) -> Line | None:
    toks = raw.split()
    if not toks:
        return None
    kv, words = parse_kv(toks[1:])
    ln = Line(toks[0], kv, words, raw, read_us)
    if "host" in kv:
        try:
            ln.host = int(kv["host"])
        except ValueError:
            pass
    return ln


def parse_phone_line(raw: str, read_us: int = 0) -> Line | None:
    toks = raw.split()
    if len(toks) < 2:
        return None
    try:
        ms = float(toks[1])
    except ValueError:
        return None  # not a structured event line (e.g. a startup banner)
    kv, words = parse_kv(toks[2:])
    return Line(toks[0], kv, words, raw, read_us, ms=ms)


def map_phone_times(phone: list[Line], sim: list[Line]) -> int | None:
    """Estimate host µs of the cli's ms=0 and fill Line.host for phone lines.
    Seq matches give host_rx − ms·1000 ≥ zero (tx precedes rx); read times give the same bound.
    The minimum over all bounds is the estimate (error ≲ 1 ms + local delivery)."""
    bounds = []
    sim_cue = {l.kv.get("seq"): l.host for l in sim if l.kind == "CUE" and l.kv.get("src") == "phone" and l.host}
    sim_prof = {l.kv.get("seq"): l.host for l in sim if l.kind == "PROFILE" and l.host}
    for l in phone:
        if l.kind == "CUE-TX" and l.kv.get("seq") in sim_cue:
            bounds.append(sim_cue[l.kv["seq"]] - int(l.ms * 1000))
        elif l.kind == "PROFILE-TX" and l.kv.get("seq") in sim_prof:
            bounds.append(sim_prof[l.kv["seq"]] - int(l.ms * 1000))
        if l.read_us:
            bounds.append(l.read_us - int(l.ms * 1000))
    if not bounds:
        return None
    zero = min(bounds)
    for l in phone:
        l.host = zero + int(l.ms * 1000)
    return zero


# ---------------------------------------------------------------------- checks

@dataclass
class Check:
    name: str
    ok: bool
    detail: str
    warn_only: bool = False


@dataclass
class Run:
    scenario: scenarios.Scenario
    sim: list[Line]
    phone: list[Line]
    t0: int = 0                       # sim start host µs
    checks: list[Check] = field(default_factory=list)

    def __post_init__(self):
        start = next((l for l in self.sim if l.kind == "SIM" and l.kv.get("event") == "start"), None)
        self.t0 = start.host if start else 0
        map_phone_times(self.phone, self.sim)

    # helpers ---------------------------------------------------------
    def T(self, t: float) -> int:
        """Scenario seconds → host µs."""
        return self.t0 + int(t * 1e6)

    def st(self, host: int) -> float:
        return (host - self.t0) / 1e6

    def sim_cues(self, src: str | None = "phone", played: bool = True, ids=None) -> list[Line]:
        out = []
        for l in self.sim:
            if l.kind != "CUE" or (src and l.kv.get("src") != src):
                continue
            if played and l.kv.get("status") not in PLAYED:
                continue
            if ids is not None and l.kv.get("id") not in ids:
                continue
            out.append(l)
        return out

    def obstacle_cues(self) -> list[Line]:
        return self.sim_cues("phone", True, OBSTACLE_IDS)

    def states(self) -> list[tuple[int, str]]:
        return [(l.host, l.words[0]) for l in self.phone if l.kind == "STATE" and l.words]

    def phone_kind(self, kind: str) -> list[Line]:
        return [l for l in self.phone if l.kind == kind]

    def check(self, name: str, ok: bool, detail: str = "", warn_only: bool = False):
        self.checks.append(Check(name, bool(ok), detail, warn_only))

    @property
    def passed(self) -> bool:
        return all(c.ok or c.warn_only for c in self.checks)


def _median_interval_ms(ls: list[Line]) -> float | None:
    hs = sorted(l.host for l in ls)
    gaps = [(b - a) / 1000 for a, b in zip(hs, hs[1:])]
    return statistics.median(gaps) if gaps else None


def check_common(r: Run):
    r.check("phone cli produced events", len(r.phone) > 0, f"{len(r.phone)} phone lines")
    r.check("sim ran", r.t0 > 0, f"{len(r.sim)} sim lines")
    late = [l for l in r.sim_cues(None, True) if int(l.kv.get("late_us", "0")) > 0]
    r.check("no cue played after its deadline (Invariant 5)", not late,
            "; ".join(l.raw for l in late[:3]) or f"{len(r.sim_cues(None, True))} played cues, none late")
    nosync = [l for l in r.sim_cues("phone", False) if l.kv.get("status") == "NO_SYNC"]
    r.check("phone never sent a CUE without clock sync", not nosync, f"{len(nosync)} NO_SYNC acks", warn_only=True)
    speak = [l for l in r.phone_kind("SPEAK") if any(w in l.kv.get("text", "").lower() for w in CLEAR_WORDS)]
    r.check("no 'all clear' speech (Invariant 1)", not speak, "; ".join(l.raw for l in speak[:3]))
    profile = [l for l in r.sim if l.kind == "PROFILE" and "applied" in l.words]
    r.check("phone sent PROFILE to the cue board", bool(profile), f"{len(profile)} applied", warn_only=True)


def check_progression(r: Run):
    m = r.scenario.marks
    cues = r.obstacle_cues()
    bands = [BAND_OF[l.kv["id"]] for l in cues]
    r.check("obstacle cues occurred", bool(cues), f"{len(cues)} played")
    first = {b: next((l.host for l in cues if BAND_OF[l.kv["id"]] == b), None) for b in ("FAR", "MID", "NEAR")}
    r.check("FAR, MID and NEAR all played", all(first.values()),
            " ".join(f"{b}@{'-' if h is None else f'{r.st(h):.2f}s'}" for b, h in first.items()))
    if all(first.values()):
        r.check("onsets in order FAR → MID → NEAR", first["FAR"] < first["MID"] < first["NEAR"],
                f"FAR {r.st(first['FAR']):.2f}s MID {r.st(first['MID']):.2f}s NEAR {r.st(first['NEAR']):.2f}s "
                f"(scene crosses 3/2/1 m at {m['far_at']:.2f}/{m['mid_at']:.2f}/{m['near_at']:.2f}s)")
        # no regression once a closer band started while the bar keeps closing
        reg = [l for l in cues if l.host < r.T(m["hold_end"]) and
               ((BAND_OF[l.kv["id"]] == "FAR" and l.host > first["MID"] + 300_000) or
                (BAND_OF[l.kv["id"]] != "NEAR" and l.host > first["NEAR"] + 300_000))]
        r.check("no band regression while approaching", not reg, f"{len(reg)} regressions", warn_only=True)
    not_both = [l for l in cues if l.kv.get("side") != "BOTH"]
    r.check("all cues centred (side=BOTH)", not not_both, "; ".join(l.raw for l in not_both[:2]))
    # At 1.4 m/s the FAR band (3→2 m) lasts ~0.7 s, shorter than one 1000 ms FAR period, so FAR usually
    # plays once and has no measurable interval. Compare every band that repeated, in urgency order.
    iv = {b: _median_interval_ms([l for l in cues if BAND_OF[l.kv["id"]] == b]) for b in ("FAR", "MID", "NEAR")}
    have = [(b, v) for b, v in iv.items() if v is not None]
    r.check("repetition rate increases with urgency (FAR < MID < NEAR)",
            len(have) >= 2 and all(a[1] > b[1] for a, b in zip(have, have[1:])),
            " ".join(f"{b} {'-' if v is None else f'{v:.0f} ms'}" for b, v in iv.items())
            + f" (Arch §8: 1000/400/200) bands={bands.count('FAR')}/{bands.count('MID')}/{bands.count('NEAR')}")
    early = [l for l in cues if l.host < r.T(m["far_at"] - 0.1)]
    r.check("silent while the bar is beyond 3 m", not early, f"{len(early)} early cues")
    after = [l for l in cues if l.host > r.T(m["hold_end"] + 1.5)]
    r.check("silent again after the bar leaves", not after, f"{len(after)} cues after {m['hold_end'] + 1.5:.1f}s")


def check_approach(r: Run):
    check_progression(r)
    unav = [h for h, s in r.states() if s == "UNAVAILABLE" and h > r.T(scenarios.LEAD_S)]
    r.check("no UNAVAILABLE during the run", not unav, f"{len(unav)} transitions")


def check_wifi_jitter(r: Run):
    check_progression(r)
    unav = [h for h, s in r.states() if s == "UNAVAILABLE" and h > r.T(scenarios.LEAD_S)]
    r.check("no UNAVAILABLE under jitter/loss", not unav,
            f"{len(unav)} transitions (BEAT 500 ms bound should ride out 3% loss)", warn_only=True)
    exp = [l for l in r.sim_cues("phone", False) if l.kv.get("status") == "EXPIRED"]
    r.check("late cues dropped by the board, not played", True, f"{len(exp)} EXPIRED acks")


def check_idle(r: Run):
    cues = r.obstacle_cues()
    r.check("zero obstacle cues in open space", not cues, "; ".join(l.raw for l in cues[:3]))
    alerts = [l for l in r.phone_kind("ALERT") if "none" not in l.words]
    r.check("zero phone alerts", not alerts, "; ".join(l.raw for l in alerts[:3]))
    states = r.states()
    r.check("phone reached READY", any(s == "READY" for _, s in states), str([s for _, s in states]))
    unav = [h for h, s in states if s == "UNAVAILABLE" and h > r.T(scenarios.LEAD_S)]
    r.check("no UNAVAILABLE once connected", not unav, f"{len(unav)} transitions")


def _check_dropout(r: Run, board: str):
    m = r.scenario.marks
    a, b = r.T(m["drop_start"]), r.T(m["drop_end"])
    unav = next((h for h, s in r.states() if s == "UNAVAILABLE" and h >= a), None)
    r.check("phone UNAVAILABLE within 1 s of the dropout (Invariant 2)", unav is not None and unav - a <= 1_000_000,
            "never" if unav is None else f"after {(unav - a) / 1000:.0f} ms")
    so = next((l.host for l in r.phone_kind("SYSTEM-OFF") if l.host >= a), None)
    r.check("phone system-off output (speaker/vibration) within 1.5 s", so is not None and so - a <= 1_500_000,
            "never" if so is None else f"after {(so - a) / 1000:.0f} ms")
    if board == "cue":
        wd = next((l.host for l in r.sim_cues("watchdog", True) if l.host >= a), None)
        r.check("cue board watchdog plays SYSTEM_OFF ≈ 800 ms after losing PINGs",
                wd is not None and 700_000 <= wd - a <= 1_200_000,
                "never" if wd is None else f"after {(wd - a) / 1000:.0f} ms")
    else:
        sc = next((l.host for l in r.sim_cues("phone", True, {"SYSTEM_OFF"}) if l.host >= a), None)
        r.check("system-off cue played on the surviving cue board within 1.5 s", sc is not None and sc - a <= 1_500_000,
                "never" if sc is None else f"after {(sc - a) / 1000:.0f} ms")
    obst = [l for l in r.obstacle_cues() if a + 1_000_000 <= l.host < b]
    r.check("obstacle cues suppressed while UNAVAILABLE", not obst, f"{len(obst)} cues")
    rec = next((h for h, s in r.states() if h > b and s in ("READY", "DEGRADED")), None)
    r.check("recovers after the board returns", rec is not None and rec - b <= 3_000_000,
            "never" if rec is None else f"after {(rec - b) / 1000:.0f} ms", warn_only=True)


def check_cue_dropout(r: Run):
    _check_dropout(r, "cue")


def check_cam_dropout(r: Run):
    _check_dropout(r, "cam")


def check_glass(r: Run):
    g = r.T(r.scenario.marks["glass_from"])
    deg = next((h for h, s in r.states() if s == "DEGRADED" and h >= g), None)
    r.check("phone reports DEGRADED once the corridor is unknown", deg is not None and deg - g <= 2_000_000,
            "never" if deg is None else f"after {(deg - g) / 1000:.0f} ms")
    cues = r.obstacle_cues()
    r.check("no obstacle cues from unknown zones (unknown is never far, never an obstacle distance)",
            not [l for l in cues if l.host >= g], f"{len(cues)} cues")
    ready = [l for l in r.sim_cues("phone", True, {"READY"}) if l.host >= g]
    r.check("no reassurance (READY) while the corridor is unknown", not ready, f"{len(ready)} READY cues")
    dist = [l for l in r.phone_kind("ALERT") if l.host and l.host >= g and l.kv.get("dist_mm", "-") != "-"]
    r.check("no distance reported without valid ToF", not dist, "; ".join(l.raw for l in dist[:2]))


def check_left_right(r: Run):
    m = r.scenario.marks
    cues = r.obstacle_cues()
    left = [l for l in cues if r.T(m["left_start"]) <= l.host < r.T(m["left_end"] + 1.0)]
    right = [l for l in cues if r.T(m["right_start"]) <= l.host < r.T(m["right_end"] + 1.0)]
    r.check("left phase cues are on the LEFT", bool(left) and all(l.kv.get("side") == "L" for l in left),
            f"sides={[l.kv.get('side') for l in left]}")
    r.check("right phase cues are on the RIGHT", bool(right) and all(l.kv.get("side") == "R" for l in right),
            f"sides={[l.kv.get('side') for l in right]}")
    stray = [l for l in cues if l not in left and l not in right]
    r.check("silent between and after the phases", not stray, f"{len(stray)} stray cues")


def check_person(r: Run):
    m = r.scenario.marks
    cues = [l for l in r.obstacle_cues() if l.host < r.T(m["gone"] + 1.0)]
    r.check("cues while the person approaches", bool(cues), f"{len(cues)} cues")
    bands = [BAND_OF[l.kv["id"]] for l in cues]
    r.check("reaches MID as the person closes to 1.2 m", "MID" in bands, f"bands={sorted(set(bands))}")
    if "FAR" in bands and "MID" in bands:
        r.check("FAR precedes MID", bands.index("FAR") < bands.index("MID"), "")
    # Only while the person is straight ahead: stepping aside to the right correctly moves the cue right.
    ahead = [l for l in cues if l.host < r.T(m["aside_start"])]
    r.check("centred (side=BOTH) while the person is ahead", bool(ahead) and all(l.kv.get("side") == "BOTH" for l in ahead),
            f"sides={sorted({l.kv.get('side') for l in ahead})}")
    after = [l for l in r.obstacle_cues() if l.host > r.T(m["gone"] + 1.5)]
    r.check("silent after the person steps aside", not after, f"{len(after)} cues")


CHECKS = {
    "approach_centre": check_approach,
    "wifi_jitter": check_wifi_jitter,
    "idle_open_space": check_idle,
    "cue_board_dropout": check_cue_dropout,
    "cam_dropout": check_cam_dropout,
    "glass_unknown": check_glass,
    "left_then_right": check_left_right,
    "person_ahead": check_person,
}


def evaluate(scenario: scenarios.Scenario, sim_lines: list[Line], phone_lines: list[Line]) -> Run:
    r = Run(scenario, sim_lines, phone_lines)
    check_common(r)
    CHECKS[scenario.name](r)
    return r


# ---------------------------------------------------------------------- processes

class Proc:
    def __init__(self, name: str, cmd: list[str], log_path: str, parse, cwd: str | None = None):
        self.name = name
        self.lines: list[Line] = []
        self.log = open(log_path, "w")
        self.p = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL,
                                  text=True, bufsize=1, cwd=cwd, start_new_session=True)
        self.first_line = threading.Event()
        self._parse = parse
        self.t = threading.Thread(target=self._read, daemon=True)
        self.t.start()

    def _read(self):
        for raw in self.p.stdout:
            now = host_us()
            raw = raw.rstrip("\n")
            self.log.write(f"{raw}\n")
            self.log.flush()
            ln = self._parse(raw, now)
            if ln is not None:
                self.lines.append(ln)
            self.first_line.set()

    def stop(self, grace_s: float = 3.0):
        if self.p.poll() is None:
            try:
                os.killpg(self.p.pid, signal.SIGTERM)
            except ProcessLookupError:
                pass
            try:
                self.p.wait(grace_s)
            except subprocess.TimeoutExpired:
                os.killpg(self.p.pid, signal.SIGKILL)
                self.p.wait()
        self.t.join(timeout=2.0)
        self.log.close()


def find_phone_cli() -> str | None:
    pats = [os.path.join(REPO, "phone/cli/build/install/*/bin/*")]
    for pat in pats:
        for c in sorted(glob.glob(pat)):
            if os.access(c, os.X_OK) and not c.endswith(".bat"):
                return c
    return None


def build_phone_cli() -> int:
    cmd = ["nix", "shell", "nixpkgs#jdk17", "nixpkgs#gradle", "-c"]
    gradlew = os.path.join(REPO, "phone", "gradlew")
    cmd += [gradlew] if os.access(gradlew, os.X_OK) else ["gradle"]
    cmd += [":cli:installDist", "--console=plain"]
    print("building phone cli:", " ".join(cmd), flush=True)
    return subprocess.call(cmd, cwd=os.path.join(REPO, "phone"))


def run_scenario(name: str, args, phone_cmd: list[str]) -> Run:
    scen = scenarios.get(name, args.seed)
    out = os.path.join(args.out_dir, name)
    os.makedirs(out, exist_ok=True)
    subs = dict(phone_port=args.phone_port, cue_port=args.cue_port, cam_port=args.cam_port,
                duration=f"{scen.duration_s + 2:.0f}", scenario=name)
    pcmd = [a.format(**subs) for a in phone_cmd]
    phone = Proc("phone", pcmd, os.path.join(out, "phone.log"), parse_phone_line, cwd=REPO)
    # Give the JVM time to bind the phone port before the boards start talking.
    phone.first_line.wait(args.phone_startup)
    time.sleep(0.3)
    sim_cmd = [sys.executable, os.path.join(HERE, "sim_boards.py"), "--phone", "127.0.0.1",
               "--phone-port", str(args.phone_port), "--cue-port", str(args.cue_port), "--cam-port", str(args.cam_port),
               "--scenario", name, "--seed", str(args.seed), "--bind", "127.0.0.1"]
    sim = Proc("sim", sim_cmd, os.path.join(out, "sim.log"), parse_sim_line)
    try:
        sim.p.wait(scen.duration_s + 30)
    except subprocess.TimeoutExpired:
        pass
    finally:
        sim.stop()
        time.sleep(0.2)
        phone.stop()
    return evaluate(scen, sim.lines, phone.lines)


def report(r: Run) -> str:
    lines = [f"=== {r.scenario.name}: {'PASS' if r.passed else 'FAIL'} — {r.scenario.description}"]
    for c in r.checks:
        tag = "ok  " if c.ok else ("WARN" if c.warn_only else "FAIL")
        lines.append(f"  [{tag}] {c.name}" + (f" — {c.detail}" if c.detail else ""))
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--scenario", action="append", choices=sorted(scenarios.SCENARIOS),
                   help="scenario to run (repeatable; default all)")
    p.add_argument("--phone-cmd", help="phone cli command; placeholders {phone_port} {cue_port} {cam_port} "
                                       "{duration} {scenario} (default: phone/cli/build/install/*/bin/*)")
    p.add_argument("--build", action="store_true", help="run gradle :cli:installDist in phone/ first")
    p.add_argument("--phone-port", type=int, default=47000)
    p.add_argument("--cue-port", type=int, default=47001)
    p.add_argument("--cam-port", type=int, default=47002)
    p.add_argument("--seed", type=int, default=1)
    p.add_argument("--phone-startup", type=float, default=5.0, help="max seconds to wait for the cli's first line")
    p.add_argument("--out-dir", default="/tmp/determine-e2e")
    p.add_argument("--evaluate-only", action="store_true",
                   help="re-check existing logs in --out-dir without running anything")
    args = p.parse_args(argv)
    names = args.scenario or list(scenarios.SCENARIOS)

    runs = []
    if args.evaluate_only:
        for n in names:
            d = os.path.join(args.out_dir, n)
            with open(os.path.join(d, "sim.log")) as fs, open(os.path.join(d, "phone.log")) as fp:
                sim = [x for x in (parse_sim_line(l.rstrip("\n")) for l in fs) if x]
                ph = [x for x in (parse_phone_line(l.rstrip("\n")) for l in fp) if x]
            runs.append(evaluate(scenarios.get(n, args.seed), sim, ph))
    else:
        if args.build and build_phone_cli() != 0:
            print("phone cli build failed", file=sys.stderr)
            return 2
        if args.phone_cmd:
            phone_cmd = shlex.split(args.phone_cmd)
        else:
            exe = find_phone_cli()
            if exe is None:
                print("phone cli not found under phone/cli/build/install/*/bin/ — run with --build "
                      "(or `gradle :cli:installDist` in phone/), or pass --phone-cmd", file=sys.stderr)
                return 2
            phone_cmd = [exe]
        for n in names:
            print(f"--- running {n} ...", flush=True)
            runs.append(run_scenario(n, args, phone_cmd))
            print(report(runs[-1]), flush=True)
    if args.evaluate_only:
        for r in runs:
            print(report(r))
    failed = [r.scenario.name for r in runs if not r.passed]
    print(f"\nE2E SUMMARY: {len(runs) - len(failed)}/{len(runs)} scenarios passed"
          + (f"; failed: {', '.join(failed)}" if failed else ""), flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
