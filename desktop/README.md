# desktop — the Determine app on a desktop

The Compose screens, session, engine host, UDP link and Describe client in
[`../app-shared`](../app-shared) are compiled into both desktop and Android. Both use
[`../phone/core`](../phone/core)'s real wire-v2 engine: **one forward ToF zone**, no camera object detector,
no inferred left/right hazard position, and a haptic-only cue board with one physical motor. Left/right
in hearing calibration are audio channels, not measured obstacle directions.

| Piece | Android app | Desktop test app |
|---|---|---|
| Screens, navigation, hearing calibration, Describe flow | `app-shared` | same shared code |
| Engine, clock sync, hazard policy, cue scheduling | `phone/core` | same real engine |
| All warning sounds | `PhoneCueSink` → AudioTrack, explicitly bound to a Bluetooth headset | `CueSinkCore` → Java Sound system device |
| Bluetooth route verification | actual Android device/track status; loss makes the engine unavailable | **not available**; system audio may be speakers, never reported as a verified headset |
| Spoken words / system-off | Android TTS + tone | `espeak-ng` + tone; printed speech if espeak is absent |
| Board haptics | wire-v2 CUE/PROFILE to the physical motor | same packets to real boards or simulator; no local physical motor |
| Phone-side fallback vibration | phone motor | printed (`VIBRATE …`) |
| Face blur before Describe upload | BlazeFace | **unavailable**: every upload is refused, nothing is sent without redaction |
| Phone camera fallback | CameraX | **unavailable**, option disabled; no synthetic camera fallback |
| Settings storage | SharedPreferences, encrypted API key | java.util.prefs, API key in plain text |

The main, settings and judge screens distinguish unverified desktop audio from an actual Android headset
route. An unavailable Java Sound line is an explicit cue failure, displayed as a local audio error and
handled by the real engine's availability rules; no silent sink pretends a sound played. Restore a working
system audio device to recover. Desktop calibration exercises the real flow using system audio, with an
explicit warning that it does not verify a wearable headset or fit. Its profile is identified as
`desktop-system-audio` and is session-only.

The persisted haptic strength (`hapticMax`, clamped to the core's nonzero minimum through 255) applies live
to the board profile and survives audio calibration. Spearcons also apply live. Changing the camera source
or Describe opt-in restarts the engine session. There is no obsolete board-audio/phone-audio selector:
warning audio always uses the local sink, and board haptics always use wire v2.

## Run

From the repository root, with a working graphical session and system audio device:

```sh
nix run .#desktop -- --sim approach_then_unknown --start
nix run .#desktop                                     # app alone, real boards or an external simulator
nix run .#desktop -- --help
```

Without the flake, use JDK 17, Python 3 and the platform graphics/audio libraries; install `espeak-ng` for
spoken words. On NixOS, `nix develop` supplies the environment. From the repository root:

```sh
./desktop/gradlew -p desktop run --args="--sim approach_then_unknown --start"
```

`--sim SCENARIO` starts `tools/sim/sim_boards.py --loop` against `127.0.0.1` until the app exits.
`--start` starts the engine immediately; without it, press **Start Determine**. Both simulated boards use
the same host IP but separate source ports; shared address attribution learns source endpoints so both
clock estimates can synchronize. Board destination ports remain 47001/47002. Only run one simulator on
those ports at a time. `--port N` changes the host receive port for the app and its child simulator, not
the fixed board ports; `--repo DIR` locates the simulator when launching outside the checkout.

Current scenario names are `approach_centre`, `person_ahead`, `glass_unknown`, `approach_then_unknown`,
`cue_board_dropout`, `cam_dropout`, `wifi_jitter`, `idle_open_space`, and `hover_boundary`. The simulator's
authoritative list is available with:

```sh
python3 tools/sim/sim_boards.py --list
```

`person_ahead` is still a forward scalar obstacle scenario: the app does not classify a person from camera
frames. UNKNOWN remains unknown, never far or clear. The judge view shows the scalar distance/status,
stream ages, real engine state, measured packet latency and explicit audio/redaction limitations.
Simulator `CUE`, `PROFILE` and `WATCHDOG` lines, plus app `SPEAK` and fallback `VIBRATE` lines, go to stdout.

Simulator startup logs `event=start scenario=...`. That is not evidence of sound, a connected headset,
real vibration, engine READY, or wearable safety. In a graphical smoke run, inspect the actual window
and judge screen as well as the logs.

## Build and checks

From the repository root:

```sh
./desktop/gradlew -p desktop build
```

This compiles the shared and desktop Kotlin sources and runs the shared JVM tests. For a graphical smoke
run, launch with `--sim approach_then_unknown --start`, observe valid forward bands followed by UNKNOWN,
and confirm no lateral measurement or camera detector is presented. Audio and physical hardware checks
require real devices; a desktop simulation cannot certify them.
