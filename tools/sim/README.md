# tools/sim — board simulator, bench tool, end-to-end harness

Python 3 stdlib only. Every tool speaks the frozen **wire contract v2** through the reference codec
(`protocol/python/determine_wire.py`, imported by `dtwire.py`); no byte layout is re-implemented here.

The simulated hardware is the staged near-field demo rig: **one** Arduino Modulino Distance (VL53L4ED on the purchased unit) forward
single-zone ToF (~18° FOV, vendor maximum range up to 1.2 m; usable warning distance unvalidated), one camera board, and a **haptic-only** cue board
(one Modulino Vibro on the real board; the simulator still logs per-side intensities). It is *not* a 3 m grid and not a lateral sensor. The cue board makes no sound: the phone synthesises
all audio and plays it to a bone-conduction Bluetooth headset, so the simulator logs haptics only and leaves sound
to the phone.

| Tool | Role |
|---|---|
| `sim_boards.py` | Simulates **both** boards against a phone: own clocks with offset and drift, PING→PONG, BEAT 10 Hz, RANGE 15 Hz from a scene reduced to ONE forward reading (mirrors the firmware mapping: a detection → VALID; no return in view or beyond 1200 mm → NO_TARGET with mm 0; explicit glass/saturation fixtures → UNKNOWN with mm 0), FRAME chunks of small valid JPEGs, CUE validation (deadline, per-motor side gating, intensity clamp to PROFILE `haptic_max`, BUSY unless interrupt) with the haptic output logged, ACK, PROFILE (unknown mode or `haptic_max == 0` → BAD_CUE, previous profile kept), and the 800 ms PING watchdog (haptic LSS every 5 s at `haptic_max`). `--list` shows scenarios. |
| `bench.py` | Acts as the **phone** against the real boards at Gate G0/G3 (or against `sim_boards.py`): offsets/RTT/faults, the live single forward reading with its hazard band, frame rate, test haptic cues, PROFILE, command→ACK p50/p95, `--fault-test` for the watchdog. `--haptic-max 0` deliberately sends a forged PROFILE and expects ACK BAD_CUE. |
| `e2e.py` | Runs `phone/cli` against `sim_boards.py` per scenario and checks the safety invariants and cue behaviour. |

Library modules: `boards.py` (board models), `scene.py` (scene → one forward reading), `scenarios.py`, `simclock.py`,
`netem.py` (loss/jitter injection), `jpeg.py`, `phonelib.py` (bench helpers).

## Hazard bands (measured/tunable, conservative ≤ 1200 mm)

FAR 900–1200 mm · MID 500–899 mm · NEAR < 500 mm · beyond 1200 mm or empty view: NO_TARGET — the sensor reported no target within range (raw VL53L4ED status 2/4). That is quiet, but **not** an all-clear: no speech, no cue, no reassurance, and no DEGRADED. A scripted glass/saturation fault reports UNKNOWN, which leaves the phone DEGRADED without obstacle cues or a reassuring READY. Because one zone cannot tell left from right, every obstacle cue is `side=BOTH`.

## Scenarios

`approach_centre` (bar closes 1.8 m → 0.35 m at 0.5 m/s), `person_ahead`, `glass_unknown` (valid
target at 1.0 m before the reading goes UNKNOWN), `approach_then_unknown` (fault mid-approach, then recovery),
`cue_board_dropout`, `cam_dropout`, `wifi_jitter`, `idle_open_space` (open view reports NO_TARGET; expects no
obstacle cue, alert, DEGRADED, UNAVAILABLE, "clear" speech or "distance sensor limited"/"no reading" speech; READY is allowed),
`hover_boundary` (bar oscillating ±30 mm around 900 mm at 3 Hz with sensor noise; expects no band-onset change faster
than `bandExitMs` = 600 ms and at most 2 reported band changes per second).

## Commands

```sh
python3 -m unittest discover -s tools/sim/tests -v                   # unit + loopback tests
python3 -m unittest discover -s protocol/python -v                   # codec + golden vectors
python3 tools/sim/sim_boards.py --scenario approach_centre            # boards → phone at 127.0.0.1:47000
python3 tools/sim/bench.py                                            # phone side, interactive dashboard
python3 tools/sim/bench.py --cue OBSTACLE_NEAR --side L --intensity 180   # one test haptic cue
python3 tools/sim/bench.py --profile --mode SSD --haptic-max 120      # PROFILE (haptic_max 1–255)
python3 tools/sim/bench.py --latency 50 --cue OBSTACLE_FAR            # command→ACK p50/p95
python3 tools/sim/bench.py --fault-test                               # stop pinging, expect the haptic LSS watchdog
nix shell nixpkgs#jdk17 -c python3 tools/sim/e2e.py [--build] [--scenario NAME ...]
```

`e2e.py` needs `phone/cli` installed (`--build` runs `./gradlew :cli:installDist`) and `java` on PATH.
Logs land in `/tmp/determine-e2e/<scenario>/{phone,sim}.log`; `--evaluate-only` re-checks them.

Bench latency is command→ACK on this host. Command→haptic onset needs an accelerometer or scope on the temple
actuator; command→sound is the phone's own audio path to the headset and is outside this simulator.
