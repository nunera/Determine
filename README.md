# Determine

Parking sensors for people: dumb glasses, a capable phone, and a calibrated warning about what is directly
ahead. Design: [`Determine-Architecture.html`](Determine-Architecture.html) (contract) and
[`Determine-Brief.html`](Determine-Brief.html) (8-page brief). Status: proposed design; untested with
blind or low-vision users.

**Platform status:** Android is implemented; [`ios/`](ios/README.md) now contains a native SwiftUI interactive
prototype translating its four main workflows, packaged for [xtool](https://github.com/xtool-org/xtool).
The prototype uses explicitly synthetic inputs, not live hardware. Its Swift model runs on Linux, and an unsigned
arm64 iOS IPA now builds with xtool. Phone runtime and native visual verification remain outstanding. Full parity: [iOS plan](docs/ios-plan.md).

<p align="center">
  <a href="Determine-Brief.pdf"><img src="docs/images/brief-1.png" width="49%" alt="Brief cover: a two-stave score where beeps speed up as an obstacle approaches"></a>
  <a href="Determine-Brief.pdf"><img src="docs/images/brief-3.png" width="49%" alt="Brief page 3: dumb glasses, the phone does the thinking"></a>
  <a href="Determine-Brief.pdf"><img src="docs/images/brief-4.png" width="49%" alt="Brief page 4: the cue language, tempo, side and timbre"></a>
  <a href="Determine-Brief.pdf"><img src="docs/images/brief-6.png" width="49%" alt="Brief page 6: Reflex loop on the phone, Describe loop on Annapurna silicon"></a>
</p>

All 8 brief pages: [`docs/images/brief-1.png` … `brief-8.png`](docs/images/) · PDF: [`Determine-Brief.pdf`](Determine-Brief.pdf)

## App screenshots

Android app on an emulator, driven by the two-board simulator (`tools/sim/sim_boards.py`); not real hardware.

<p align="center">
  <img src="docs/images/app-main-running.png" width="19%" alt="Main screen: status Ready, all ok">
  <img src="docs/images/app-judge.png" width="19%" alt="Judge diagnostics: both boards synced, frame and ToF ages">
  <img src="docs/images/app-judge-alert.png" width="19%" alt="Judge diagnostics during an approach: obstacle ahead, near, 0.5 metres, ToF grid highlighted">
  <img src="docs/images/app-settings.png" width="19%" alt="Settings: Describe service and software fallbacks">
  <img src="docs/images/app-calibration.png" width="19%" alt="Hearing calibration start screen">
</p>

Main · Judge diagnostics · Judge during an approach (NEAR at 0.5 m) · Settings (Describe, fallbacks) · Calibration

## Software layout

| Path | What | Build / test |
|---|---|---|
| [`protocol/`](protocol/PROTOCOL.md) | Wire contract v1 (UDP, 8 packet types), Python reference codec, golden vectors | `cd protocol/python && python3 -m unittest` |
| [`firmware/cue-board/`](firmware/cue-board/README.md) | XIAO ESP32-C3: 8×8 ToF, I²S stereo cues, DRV2605L haptics, watchdog | `pio run -e xiao_esp32c3`, `pio test -e native` |
| [`firmware/cam-board/`](firmware/cam-board/README.md) | ESP32-CAM: JPEG capture, chunked FRAME stream, PSRAM fallback | `pio run -e esp32cam`, `pio test -e native` |
| [`phone/`](phone/README.md) | Kotlin hazard engine (`core`) + headless phone (`cli`) | `./gradlew build` |
| [`android/`](android/README.md) | Android app: service, MediaPipe detector, TTS/vibration, judge + calibration UI, Describe client with on-phone face blur, phone-camera and phone-audio fallbacks | `./gradlew assembleDebug testDebugUnitTest` |
| [`ios/`](ios/README.md) | Native SwiftUI interactive prototype: Main, Judge, virtual Calibration, Settings; synthetic inputs only, no live boards/audio/uploads | `nix-shell -p swift swiftpm --run 'swift run DetermineSmoke'` from `ios/`; native build requires xtool + Apple SDK |
| [`cloud/`](cloud/README.md) | Describe loop on Annapurna silicon: Graviton2 Lambda API + SmolVLM server on Inferentia2 | `python3 -m unittest discover -s cloud/describe-api/tests` (and `cloud/inference/tests`) |
| [`tools/sim/`](tools/sim/README.md) | Two-board simulator, bench tool for real boards, end-to-end harness | `python3 -m unittest discover -s tools/sim/tests` |
| [`docs/flows/`](docs/flows/README.md) | Hardware flow maps and software flowcharts (Mermaid) | `nix shell nixpkgs#mermaid-cli -c python3 docs/flows/render.py` |

Toolchains come from nix: `nix shell nixpkgs#jdk17 nixpkgs#platformio -c <cmd>`. Firmware Wi-Fi credentials:
copy `firmware/common/include/secrets.example.h` to `secrets.h` (gitignored).

```mermaid
flowchart LR
  CAM["ESP32-CAM<br/>JPEG"] -- "FRAME, BEAT, PONG" --> PHONE
  CUE["XIAO ESP32-C3<br/>ToF, audio, haptics"] -- "RANGE, BEAT, PONG, ACK" --> PHONE
  PHONE["Phone<br/>phone/core Engine"] -- "PING" --> CAM
  PHONE -- "PING, CUE, PROFILE" --> CUE
```

The phone makes every hazard decision; the wearable never decides. There is no "all clear" sound, and a
failure is announced, never silent (Arch §1.2, §4.2).

## Machine learning and Annapurna silicon

| Where | Model / chip | Role | Status |
|---|---|---|---|
| Phone | MediaPipe EfficientDet-Lite0 (COCO) | labels hazards ("person", "chair") for the cue timbre | in app |
| Phone | MediaPipe BlazeFace short-range | blurs faces before any upload | in app |
| AWS Graviton2 | Lambda arm64 `describe-api` | auth, prompt, safety filter | code + SAM template; not deployed |
| AWS Inferentia2 | SmolVLM-256M via optimum-neuron, inf2.xlarge | "What's ahead?" two-sentence description | code + compile/setup scripts; needs Inf quota; CPU path measured at p50 2.0 s |
| Bedrock | Nova Lite | fallback if Inf2 is down (silicon unspecified) | code |

No learned model is in the hazard decision: warnings come from the 8×8 ToF corridor and fixed rules (Arch §7.2).
See [`cloud/README.md`](cloud/README.md) for the event-day order and measurements.

## Diagrams

Sources and the full set of 15: [`docs/flows/`](docs/flows/README.md). Re-render with
`nix shell nixpkgs#mermaid-cli -c python3 docs/flows/render.py`.

### Hardware

**Physical layout and power** — front camera and ToF, rear-strap boards, temple outputs, pocket power bank.

![Physical layout and power](docs/flows/rendered/hardware-1.svg)

**Electrical buses and pins** — I²C (ToF, mux, haptic drivers), I²S (amps, bone transducers), GPIO, programming.

![Electrical buses and pins](docs/flows/rendered/hardware-2.svg)

**Network and data topology** — both boards on the phone hotspot; ports, packet types and rates.

![Network topology](docs/flows/rendered/hardware-3.svg)

**Steady-state cue round trip** — capture → RANGE/FRAME → phone decides → CUE with deadline → play → ACK.

![Cue round trip](docs/flows/rendered/hardware-5.svg)

**Failure paths** — cue board power loss, phone silence (board watchdog), camera unplugged.

![Failure paths](docs/flows/rendered/hardware-6.svg)

### Software

**Phone pipeline** — UDP ingest → clock sync → frame assembly and freshness → detector + ToF corridor → hazard
policy → cue scheduler.

![Phone pipeline](docs/flows/rendered/software-4.svg)

**Hazard policy** — bands, persistence, priority, rate limit; there is no CLEAR output.

![Hazard policy](docs/flows/rendered/software-5.svg)

**System state machine** — STARTING, READY, DEGRADED, UNAVAILABLE, PAUSED.

![System state machine](docs/flows/rendered/software-6.svg)

**Cue board CUE handling** — validate → clock sync and deadline → clamp → mode → play → ACK.

![CUE handling](docs/flows/rendered/software-2.svg)

More: [boot and join sequence](docs/flows/rendered/hardware-4.svg) ·
[cue-board tasks](docs/flows/rendered/software-1.svg) · [camera loop](docs/flows/rendered/software-3.svg) ·
[calibration](docs/flows/rendered/software-7.svg) · [Describe loop](docs/flows/rendered/software-8.svg) ·
[module map](docs/flows/rendered/software-9.svg)

## End to end without hardware

```sh
(cd phone && nix shell nixpkgs#jdk17 -c ./gradlew :cli:installDist)
nix shell nixpkgs#jdk17 -c python3 tools/sim/e2e.py
```
