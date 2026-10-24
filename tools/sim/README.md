# tools/sim — board simulator, bench tool, end-to-end harness

Python 3 stdlib only. Every tool speaks the frozen wire contract through the reference codec
(`protocol/python/determine_wire.py`, imported by `dtwire.py`); no byte layout is re-implemented here.

| Tool | Role |
|---|---|
| `sim_boards.py` | Simulates **both** boards against a phone: own clocks with offset and drift, PING→PONG, BEAT 10 Hz, RANGE 15 Hz from a 3-D scene projected into the 8×8 grid (63° diagonal FOV, NO_TARGET beyond 4 m, invalid "glass" zones), FRAME chunks of small valid JPEGs, CUE validation (deadline, clamp to min(profile ceiling, −10 dBFS)), ACK, and the 800 ms PING watchdog. `--list` shows scenarios. |
| `bench.py` | Acts as the **phone** against the real boards at Gate G0/G3 (or against `sim_boards.py`): offsets/RTT/faults, live ToF heatmap, frame rate, test cues, PROFILE, command→ACK p50/p95, `--fault-test` for the watchdog. |
| `e2e.py` | Runs `phone/cli` against `sim_boards.py` per scenario and checks the safety invariants and cue behaviour. |

Library modules: `boards.py` (board models), `scene.py` (scene → grid), `scenarios.py`, `simclock.py`,
`netem.py` (loss/jitter injection), `jpeg.py`, `phonelib.py` (bench helpers).

## Scenarios

`approach_centre`, `left_then_right`, `person_ahead`, `glass_unknown`, `cue_board_dropout`, `cam_dropout`,
`wifi_jitter`, `idle_open_space` (must stay silent).

## Commands

```sh
python3 -m unittest discover -s tools/sim/tests -v                   # unit + loopback tests
python3 tools/sim/sim_boards.py --scenario approach_centre            # boards → phone at 127.0.0.1:47000
python3 tools/sim/bench.py                                            # phone side, interactive dashboard
python3 tools/sim/bench.py --cue OBSTACLE_NEAR --side L --gain -20    # one test cue
python3 tools/sim/bench.py --latency 50 --cue OBSTACLE_FAR            # command→ACK p50/p95
python3 tools/sim/bench.py --fault-test                               # stop pinging, expect SYSTEM_OFF
nix shell nixpkgs#jdk17 -c python3 tools/sim/e2e.py [--build] [--scenario NAME ...]
```

`e2e.py` needs `phone/cli` installed (`--build` runs `./gradlew :cli:installDist`) and `java` on PATH.
Logs land in `/tmp/determine-e2e/<scenario>/{phone,sim}.log`; `--evaluate-only` re-checks them.

Bench latency is command→ACK on this host. Gate G3's command→sound also needs the board's audio onset,
measured with a microphone.
