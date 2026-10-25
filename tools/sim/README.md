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
python3 tools/sim/sim_boards.py --scenario approach_then_unknown --loop # repeat warning, UNKNOWN and recovery
python3 tools/sim/bench.py                                            # phone side, interactive dashboard
python3 tools/sim/bench.py --cue OBSTACLE_NEAR --side L --intensity 180   # one test haptic cue
python3 tools/sim/bench.py --profile --mode SSD --haptic-max 120      # PROFILE (haptic_max 1–255)
python3 tools/sim/bench.py --latency 50 --cue OBSTACLE_FAR            # command→ACK p50/p95
python3 tools/sim/bench.py --fault-test                               # stop pinging, expect the haptic LSS watchdog
nix shell nixpkgs#jdk17 -c python3 tools/sim/e2e.py [--build] [--scenario NAME ...]
```

`--loop` repeats scene motion, scalar sensor-invalid intervals and board dropouts without rebooting the
simulated boards. Sensor recovery and the next lap's UNKNOWN interval both recur; clock sync and sequence
numbers continue across laps.

`e2e.py` needs `phone/cli` installed (`--build` runs `./gradlew :cli:installDist`) and `java` on PATH.
Logs land in `/tmp/determine-e2e/<scenario>/{phone,sim}.log`; `--evaluate-only` re-checks them.

Bench latency is command→ACK on this host. Command→haptic onset needs an accelerometer or scope on the temple
actuator; command→sound is the phone's own audio path to the headset and is outside this simulator.

## Determine World bridge

`world_bridge.py` connects the browser 3-D simulation in `world/` to the **real** phone engine. The browser only
renders and raycasts; every hazard decision is made by the engine, exactly as on the headband.

Truth chain (wire v2): the world measures **one forward reading** (the Modulino Distance's ~18° cone, ≤ 1.2 m:
`{t:'range', simMs, mm, status}` with status 0 VALID / 1 NO_TARGET / 2 UNKNOWN) and renders the ESP32-CAM view →
WebSocket `/ws` → the bridge's simulated cue and camera boards (`boards.py`: own clocks with offset and drift, BEAT,
PING/PONG, PROFILE, the haptic LSS watchdog, CUE deadline/`haptic_max` clamp/ACK) → protocol v2 over UDP → the engine
(`phone/cli`, or an external phone) decides → its **audio half** (`audio`: what the phone would play on the Bluetooth
bone-conduction headset; the cli logs it as an `AUDIO` line) and the **haptic-only** `CUE` packets (`cueTx`) → the
simulated cue board applies, clamps, expires or drops each haptic (`played`, incl. `hapticMax`; the bench board has
one Vibro and plays max(hl, hr), so it cannot lateralise) → the browser plays the phone's sound and shows both.
The board makes no sound. One zone cannot tell left from right: obstacle cues are always `side=BOTH`.
Messages are the `ToBridge`/`FromBridge` types in `world/src/contract.ts`.

- Each browser reading becomes one RANGE (board time = when the bridge received it), sent once; `mm` is forced to 0
  unless VALID, and a status other than 0/1/2 is rejected. No reading for 500 ms (or none yet): the cue board sends
  no RANGE and raises `SENSOR_READ_ERR` in its BEAT — a stalled world is a sensor failure, never a repeat of old
  data. NO_TARGET is "nothing seen", never "clear". Camera renders (binary frames `0x01` + JPEG) are sent once each;
  older than 1 s raises `CAPTURE_FAIL`; until the first render the camera board sends the synthetic test card.
- Every cli line kind is translated (phone/core/API.md "Stable cli output"): `STATE` → `state`, `ALERT` → `alert`
  (`{band, distMm}` or null), `AUDIO` → `audio`, `CUE-TX` → `cueTx`, `SPEAK`, `VIBRATE`, `SYSTEM-OFF` →
  `systemOff`, `DIAG` → `diag` (every key, incl. `range_mm`, `range_status`, `closing_mm_s`, `approach`,
  `exploring`). `PROFILE-TX`, the banner, board `SYNC`/`PROFILE`/`WATCHDOG`/`SENSOR`/`SIM`/`STATS` lines and
  anything malformed go out as `log` lines, at most 10 per kind per second (the rest are counted and summarised).
- The browser can unplug either board (silent both ways, as in the dropout scenarios), switch the network
  between `clean` and `wifi_jitter` (`scenarios.WIFI_JITTER_NET`), and restart the engine. If the engine exits
  the bridge says so and keeps running; the cue board's watchdog then runs the haptic LSS on its own.
- `POST /api/describe` (body `image/jpeg`) forwards to `cloud/describe-api` per `cloud/API.md`, returns the text as
  the API gave it (no prefix), and reports the bridge round trip. Without `--describe-url` it
  answers 503 `Describe service not configured`; failures are `ok:false`, never invented text.

### Tuning A/B (EXPERIMENTAL engine features)

`--approach` / `--explore` start the spawned engine with `Tuning.approachEnabled` (time-to-contact band escalation)
/ `Tuning.exploreEnabled` (distance-proportional PULSE40 train instead of band cues) via the cli flags of the same
name. At run time `{t:'tuning', tuning:{approach, explore}}` restarts the spawned engine with the matching flags
(clock sync, PROFILE and READY start over). `hello` and the 1 s `bridge` status carry the current `tuning`. With
`--phone HOST:PORT` the bridge cannot know or change the phone's tuning: `tuning` is `null`, the flags are refused
at startup, and a `tuning` message is answered with a `log` line saying to tune the external phone on the phone.
Neither feature is validated with users; compare them in the simulator only.

```sh
# built world + spawned phone/cli engine (needs `./gradlew :cli:installDist` in phone/ and java on PATH)
(cd world && npm run build)
python3 tools/sim/world_bridge.py --serve world/dist                 # open http://127.0.0.1:8765/
python3 tools/sim/world_bridge.py --serve world/dist --approach      # A/B: start with approach escalation
python3 tools/sim/world_bridge.py --serve world/dist --explore       # A/B: start with the exploration pulse train

# development: Vite on :5173 proxies /ws and /api to the bridge on :8765
python3 tools/sim/world_bridge.py
(cd world && npm run dev)                                             # open http://127.0.0.1:5173/

# external phone (desktop app or Android phone on the same Wi-Fi): spawn nothing, boards send to it
python3 tools/sim/world_bridge.py --serve world/dist --phone 192.168.1.20:47000

# "What's ahead?" through the describe-api (local_server.py or the deployed Lambda URL)
DETERMINE_DESCRIBE_KEY=dev-key python3 tools/sim/world_bridge.py --serve world/dist \
    --describe-url http://127.0.0.1:8787
```

Options: `--ceiling DB` / `--mode STEREO|SSD|HAPTIC_ONLY` / `--approach` / `--explore` for the spawned engine,
`--engine PATH`, `--phone-port`, `--cue-port`, `--cam-port`, `--board-bind`, `--port`, `--bind`. `wsserver.py` is the
small stdlib HTTP/1.1 + WebSocket server it runs on; `tests/test_world_bridge.py` covers framing, cli and board line
translation, the live cue board (RANGE, mm 0 unless VALID, SENSOR_READ_ERR), tuning restarts and the describe proxy.
