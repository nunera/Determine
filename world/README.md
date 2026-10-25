# Determine World

A procedural 3-D campus environment that feeds **the real phone engine**, not browser-side hazard decisions.
Based on upstream `main` at `65cfa0c` and wire v2: one forward Modulino Distance reading, phone audio, one haptic motor on the cue board.

## Run

Requirements: Node.js 22+, npm, Python 3, JDK 17. If using Nix, run the same commands under `nix shell nixpkgs#nodejs_22` or `nix shell nixpkgs#jdk17` as appropriate; latest upstream has no project flake.

```sh
# From the repository root
(cd phone && ./gradlew :cli:installDist)
(cd world && npm ci && npm run build)
python3 tools/sim/world_bridge.py --serve world/dist
# Open http://127.0.0.1:8765/
```

For development, run the bridge without `--serve`, then `npm run dev` from `world/`. Open `http://127.0.0.1:5173/`; Vite proxies `/ws` and `/api` to the bridge on port 8765.

External phone and a real Describe service:

```sh
python3 tools/sim/world_bridge.py --serve world/dist --phone 192.168.1.20:47000
DETERMINE_DESCRIBE_KEY=your-key python3 tools/sim/world_bridge.py --serve world/dist \
  --describe-url http://127.0.0.1:8787
```

Without a Describe endpoint, the button explicitly says unavailable. No caption is fabricated. See [bridge details](../tools/sim/README.md) for network profiles, ports and tuning flags.

## Controls

- Space: play/pause; R: restart course; left/right arrows: previous/next course.
- 1–4: director, wearer, orbit, plan camera; M: mute; D: Describe.
- Experiments toggles: approach/TTC escalation and exploration pulses. Changing tuning restarts the spawned engine; an external phone must be tuned on the phone.
- Unplug cue/camera and Wi-Fi jitter controls exercise actual simulator-board/engine failure behaviour.

Courses: head-height sign, scaffold beside versus ahead, approaching pedestrian, dark branch, glass entrance, open plaza, stairs down. Walker movement is scripted choreography, not an autonomous collision-avoidance agent.

The score distinguishes **sound on the phone/headset** from **vibration applied by the board**. Board ACK timing is not acoustic headset latency.

## Visual design and performance

Architectural maquette: procedural limestone arcade and plaza, matte palette, depth/normal ink edges, GTAO, shadowed key light, SMAA, Neutral tone mapping. No external scene assets, bloom or game-engine spectacle. Colour signals channel or urgency; text and shape carry the same distinction.

Quality drops progressively if frame time stays above 40 ms: AO off, then shadows off, then reduced pixel ratio with direct rendering (ink/SMAA post-processing off). `?quality=low` starts reduced. It does not relax the engine's stale-frame limits. A slow renderer remains an announced failure, never a fake READY state.

JPEG encoding runs in a dedicated worker. Main-thread canvas encoding was observed delaying frames by about a second under software rendering; moving it off-thread allowed the engine to receive current camera images. A hardware-GPU performance measurement is still required before quoting frame rate.

## Sensor model: assumptions, not validation

`src/sensor/tof.ts` samples 37 rays within an 18° cone, reports forward distance with seeded noise, canonical VALID/NO_TARGET/UNKNOWN status, and an ideal 1.2 m cap. Glass fixtures deliberately report UNKNOWN; actual glass can also produce misleading returns or no target, so this is not a guaranteed glass detector.

The range model fits two VL53L4CD datasheet anchors (1200 mm at 88% white reflectance and 450 mm at 17% grey). Applying those figures to the project's VL53L4ED, interpolating with a power law, and assigning reflectance by material kind are **inferences**, not measurements. Firmware timing budget, sunlight, target size and material can shorten range further. Bench calibration must replace these assumptions.

The sensor's `truthM` searches within the same narrow cone up to 4 m to distinguish physical ground truth from reported sensor range. It cannot serve as a full-camera object label, a collision corridor or evidence of path safety.

## Hardware in the loop (HITL)

Start the bridge with `--hitl LISTEN_HOST:PORT` (e.g. `0.0.0.0:47100`; see `tools/sim/world_bridge.py --help`) and flash the boards with the `xiao_esp32c3_hitl` / `esp32cam_hitl` envs, or make the laptop the boards' Wi-Fi gateway. The real cue board and ESP32-CAM then replace the simulated boards; the real engine runs against their packets.

When the bridge's hello reports `hitl.enabled`, a **HITL** panel appears on the right:

- **Range** `real | world | fuse` and **Camera** `real | composite` (composite is disabled when the bridge lacks Pillow). Selectors send `{t:'hitl'}`; readouts always show the bridge's actual modes.
- **Real / World / Out**: the real cue board's reading, the World raycast (with its age) and what the engine received, plus the `source` the bridge used.
- **Cue / Cam**: learned address, ms since last packet, RSSI, fault bits (camera fps too). **Composite**: frames out, frames composited, last error.
- Preview: the JPEG exactly as sent to the engine (bridge binary `0x11`, ≤ 4 fps).
- **Virtual target**: a head-locked box straight ahead of the brow sensor, 30–1300 mm (near face), width × height in mm, surface `white` (88 %), `grey` (17 %) or `glass` (reported UNKNOWN, as in the ToF model). It is in the World ToF raycast that feeds `world`/`fuse` range modes and is drawn on its own render layer, visible in the head camera.
- **Course objects in overlay**: also draw the course's sensed set pieces into the composite overlay.

In `composite` camera mode the World renders the head camera with only the HITL layer over a transparent background and sends it as a 320×240 PNG (binary `0x02`, ≤ 8 fps); the bridge alpha-blends it into the real camera's JPEG. Without HITL the World behaves exactly as before: the target is off and no overlay is rendered.

**Screen in the loop** (optical camera stimulation): open `http://127.0.0.1:8765/?view=camera` in a second window on the monitor the real ESP32-CAM faces, fullscreen it, and keep the main page open in the same browser profile. That page shows only the head camera at display resolution, four corner fiducial squares for framing and a frame counter for spotting dropped or frozen frames. It follows the main page's course, time and virtual target over a `BroadcastChannel`. Use camera mode `real` so the bridge forwards the frames unchanged. A ToF sensor cannot be spoofed by a screen; to stimulate it physically, use real objects or a moving target.

## Research and experiments

Start at [research synthesis](../docs/research/README.md). Detailed competitor, simulation, Annapurna/Austin and Meta reports contain sources and flowcharts; the [work log](../docs/research/WORKLOG.md) tracks implementation and verification.

Session export: [episode recorder](README-recorder.md). Camera-format comparison: [device profiles](README-profiles.md). Real-endpoint Describe evaluation: [evaluation guide](../tools/sim/EVALUATION.md).

No Meta SDK, Aria dataset, VRS exporter, EgoBlur, Llama Guard, ExecuTorch or Trainium training is silently claimed by this browser simulation. Those paths have research plans and explicit prerequisites. The Meta profile is a locally rendered crop/resample study, not Ray-Ban hardware emulation.

## Verification boundaries

The browser/bridge/real-cli path has been exercised, including real FAR → MID → NEAR alert/audio events. Software-rendered Chromium can still trigger honest `frame_stale` transitions under load. No physical sensor, bone-conduction transducer, Bluetooth acoustic delay or blind-user study was measured here.

This work was built at HackTX. Confirm event demo rules with organizers before judging.
