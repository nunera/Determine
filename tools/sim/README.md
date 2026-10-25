# tools/sim — board simulator, bench tool, end-to-end harness

Python 3 stdlib by default; **HITL camera compositing additionally requires Pillow**. Every tool speaks the frozen
**wire contract v2** through the reference codec (`protocol/python/determine_wire.py`, imported by `dtwire.py`);
no byte layout is re-implemented here.

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
| `world_bridge.py` | Connects Determine World to the real phone engine using simulated boards by default, or **real hardware only** with `--hitl HOST:PORT`; `hitl.py` provides the UDP relay and optional range/camera signal injection. |

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
(`phone/cli`, or an external phone) decides → its **audio half** (`audio`: the phone-side output sink;
the cli logs it as an `AUDIO` line, not physical headset playback) and **haptic-only** `CUE` packets (`cueTx`) → the
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

### Live website studio and profiles

Run `npm run live` from `web/` to build the CLI/World and start the product website, a SIM bridge on
HTTP 8765/engine UDP 47000, and a HITL bridge on HTTP 8766/engine UDP 47110/board relay UDP 47100.
The launcher uses `HAPTIC_ONLY`, ceiling −20 dBFS and haptic maximum 96; standalone bridge defaults
remain STEREO/−20/255. See the [website setup](../../README.md#product-website) for prerequisites.

The website embeds `/?studio=1&parentOrigin=<website origin>&quality=low`; the existing standalone World
remains available. The embed starts one World/audio/link and omits the standalone HUD/recorder panels.
`world/src/studioProtocol.ts` defines validated commands/events. Both directions require the expected
window source and exact origin. Embedding accepts same-origin or loopback website ports 5175/4175;
browser WebSocket upgrades must have an HTTP/HTTPS Origin matching the request Host. Nonbrowser
clients may omit Origin. This does not add authentication or authorize exposing the bridge publicly.

Send `{t:"profile",profile:{mode:"HAPTIC_ONLY",ceilingDb:-30,hapticMax:96}}` to change the spawned
engine **in place**. Modes: HAPTIC_ONLY/STEREO/SSD; integer ceiling −60…−10 dBFS; integer haptic
maximum 1…255. Zero cannot disable the watchdog. `hello.profile` / `bridge.profile` are the last
engine-acknowledged object, or null when unconfirmed/external/stopped. Responses:

```json
{"t":"profile","status":"applied","profile":{"mode":"HAPTIC_ONLY","ceilingDb":-30,"hapticMax":96}}
{"t":"profile","status":"rejected","profile":null,"error":"reason"}
```

Rejected responses retain the previous acknowledged object when one exists. The bridge serializes
bounded stdin requests to the CLI and emits `applied` only after a real `Engine.setProfile` result.
Stopped/not-ready/external engines, invalid input, failed pipes and missing acknowledgement reject
explicitly. Tuning/restart retains the last acknowledged settings. The acknowledgement confirms the
engine configuration, **not** wire PROFILE delivery, board acceptance, physical motor onset or safe
wearable performance; those are separate observations. The studio uses current DIAG snapshots when
joining after a transition-only STATE event.

Disconnect tears down the browser producer, not the bridge or physical boards. One World producer
per bridge; Ctrl-C stops all launcher-managed processes. Camera compositing requires Pillow; the
hardware camera bring-up remains in progress and absent frames are never fabricated.


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

Options: `--ceiling DB` / `--haptic-max 1..255` / `--mode STEREO|SSD|HAPTIC_ONLY` / `--approach` / `--explore` for the spawned engine,
`--engine PATH`, `--phone-port`, `--cue-port`, `--cam-port`, `--board-bind`, `--port`, `--bind`. `wsserver.py` is the
small stdlib HTTP/1.1 + WebSocket server it runs on; `tests/test_world_bridge.py` covers framing, cli and board line
translation, the live cue board (RANGE, mm 0 unless VALID, SENSOR_READ_ERR), tuning restarts and the describe proxy.

### Real hardware in the loop (HITL)

Use the real cue board (XIAO ESP32-C3, Modulino Distance and Vibro) and ESP32-CAM instead of the simulated boards.
The engine receives actual board BEAT/RANGE/FRAME/ACK/PONG packets, with real sequence numbers, clocks, Wi-Fi
timing and firmware behaviour. No attached hardware is needed to run the unit tests, but **a physical
end-to-end claim requires the boards**. HITL does not generate fallback boards, heartbeats, sensor packets,
clock replies, watchdog events or ACKs. A missing board remains missing to the engine.

Build/install the phone CLI as above and configure the boards to send to the laptop's reachable IPv4 address.
Normal firmware sends to its Wi-Fi gateway on UDP 47000, so either the laptop must be the hotspot/gateway, or
use the firmware's `xiao_esp32c3_hitl` / `esp32cam_hitl` environments with `DT_HITL_PHONE_IP` and
`DT_HITL_PHONE_PORT` in `secrets.h` (see the firmware `secrets.example.h`). Both boards must send to the same
relay endpoint. Permit that UDP port through the laptop firewall; keep the browser HTTP interface loopback
unless access from another machine is intentional.

```sh
# Optional: needed only for --hitl-camera composite, not for real camera or range injection.
python3 -m pip install Pillow

# Isolated from an existing World session on HTTP 8765 / engine 47000 / sim boards 47001,47002.
# Configure real firmware destination as LAPTOP_LAN_IP:47100.
python3 tools/sim/world_bridge.py --serve world/dist --port 8766 \
  --hitl 0.0.0.0:47100 --engine-port 47110 \
  --hitl-cue 192.168.1.51 --hitl-cam 192.168.1.52 \
  --hitl-range fuse --hitl-camera real
# Open http://127.0.0.1:8766/ ; substitute actual board addresses above.
```

`--engine-port` (alias `--phone-port`) selects the spawned engine's **loopback UDP** port, default 47000.
It must differ from the `--hitl` port. HITL uses this spawned engine rather than `--phone`; simulated
`--board-bind`, `--cue-port` and `--cam-port` sockets are not created. Do not start a second engine on a port
already used by a live session. `--hitl-cue IP` and `--hitl-cam IP` optionally pin each board role to an IPv4
address; without pins, roles are learned from real packet contents (BEAT.board, RANGE/ACK→cue, FRAME→cam;
PONG uses learned source identity). Pins are routing filters, **not authentication**: use a trusted LAN.

The relay binds one listener at `--hitl HOST:PORT` and one stable ephemeral `127.0.0.1` UDP **twin socket per
board role**. Real→engine traffic leaves from that role's twin, so the engine sees one stable cue address and
one stable camera address. Engine PING/CUE/PROFILE replies arriving at the twin are forwarded byte-for-byte
from the relay listener to the board IP at **UDP 47001 (cue) / 47002 (camera)**, not to an incidental source
port. Ensure the laptop can reach those board ports. Sequence numbers and all board timestamps pass through
unchanged, except the frame re-chunk numbering described below.

#### Range injection

The world still sends its forward raycast at the normal rate; only an **arriving real RANGE** can trigger an
engine-bound RANGE. The packet retains its real header sequence and `t_us`. Modes:

| `--hitl-range` | Engine receives |
|---|---|
| `real` | Original real RANGE bytes. |
| `world` | World mm/status when the latest raycast is ≤500 ms old; otherwise original real bytes. |
| `fuse` (default) | Nearest valid reading: world VALID wins over real NO_TARGET, or over a farther real VALID. Equal distances favour real. Real UNKNOWN is preserved; a world fault never masks a valid real reading. |

Both injection modes fall back to the real packet when the world is stale or absent; telemetry reports
`real-stale-world`. Rewritten non-VALID readings carry mm 0. No timeout manufactures RANGE packets, and a
stalled browser never hides a real board outage. The 500 ms boundary is inclusive.

#### Camera injection and optical screen-in-the-loop

- `--hitl-camera real` (default) forwards original camera FRAME chunks immediately. For optical stimulation,
  point the ESP32-CAM at the World's fullscreen `?view=camera` page (fiducials and frame counter). The physical
  camera then sees the screen; this does **not** stimulate ToF. To exercise the physical ToF, move a real target.
- `--hitl-camera composite` requires Pillow and is explicitly refused if it is unavailable. The browser sends
  binary **0x02 + 320×240 RGBA PNG**, containing only virtual objects over a transparent background. The relay
  reassembles actual camera frames within **150 ms** of their first chunk; incomplete/invalid frames are
  dropped in composite mode. A fresh overlay (≤1 s old) is scaled to the real frame size, alpha-composited over
  the decoded real JPEG, and encoded at JPEG quality 80. Stale/missing overlays or decode/composite failures
  forward the completed frame's original chunks unchanged; failures are reported, not hidden.
- Composited chunks are ≤1400 bytes each and preserve real `frame_id`, `t_cap_us`, width and height. Their
  sequence numbers start with the real **chunk-index-zero** sequence plus output chunk index, wrapping u32.
  Re-chunking may change the number of chunks; it does not claim new physical captures.
- Binary **0x11 + JPEG** previews show completed frames as sent to the engine, at most 4 fps. No simulated
  browser JPEG (`0x01`) is ever injected as a real camera frame in HITL.

#### Browser controls and honest acknowledgement

`hello.hitl` advertises `{enabled, listen, range, camera, compositeAvailable}`. While enabled, the bridge emits
`{t:'hitl'}` telemetry at 5 Hz: real board address/age/RSSI/faults, real/world/output range and source, camera
FPS and frame/composite/error counters. The HITL panel's mode selectors send
`{t:'hitl', range?:'real'|'world'|'fuse', camera?:'real'|'composite'}` and show the actual accepted modes.
Simulated disconnect and loss/jitter controls are disabled/ignored in HITL; unplugging a board or impairing
the real network must produce the real failure. Engine restart and tuning still operate on the real engine.

For a matching real CUE ACK with APPLIED/APPLIED_CLAMPED, the bridge emits the existing `played` compatibility
event with `source:'real'` and a log explicitly saying **accepted, not physical motor onset**. The pulse is
an acknowledgement visualisation, not proof that the Vibro moved, nor a measured command→actuator latency.
`hostMs` is ACK receipt time and `lateUs:0` is not an onset measurement. Establish physical onset using firmware
`[haptic] onset ...` serial instrumentation and/or an accelerometer/scope on the actuator. The bridge cannot
observe independent real watchdog pulses from ACKs.

`tests/test_hitl.py` covers fusion statuses/ties/hazard boundaries and freshness limits, fixed-port reverse
forwarding and stable twins over loopback UDP, silence without real input, preserved sequence/timestamps,
camera reassembly/compositing/passthrough, ACK acceptance and the browser contract. Pillow-specific tests skip
when Pillow is absent. Run with the simulator unit-test command above; loopback board reply sockets use
127.0.0.2/3 so they do not bind a running simulator's 127.0.0.1:47001/47002.
