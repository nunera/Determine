# phone — hazard engine and headless phone

**Hardware status (24 Oct 2026):** existing Bluetooth headset playback/calibration is a software/bench path, not an approved integrated bone-conduction build. Cue-board haptics are one Modulino Vibro, so per-side CUE intensities play on a single motor; integrated audio remains open. See the [cue-board README](../firmware/cue-board/README.md).

Gradle (Kotlin/JVM 17) project with two modules:

| Module | What it is |
|---|---|
| `core` | Pure-Kotlin engine, no Android or I/O: wire codec (protocol v2), clock sync, frame assembly, forward ToF reading, hazard policy (Arch §7.2, one forward zone, NEAR < 500 / MID 500–899 / FAR 900–1200 mm, PROPOSED and tunable), cue scheduler (Arch §8), calibration (Arch §9), system state machine (Arch §4.2). Public API: [`core/API.md`](core/API.md). |
| `cli` | `determine-phone`: the real `Engine` over UDP :47000 with stdout alerts and no camera detector. Every cue's audio half is logged as an `AUDIO` line (there is no headset in a headless run); the haptic half goes to the cue board as real `CUE` packets. Used by `tools/sim/e2e.py` and for bench runs. |

The Android app (`../android`) consumes `core` through `includeBuild("../phone")`.

**Planned iOS peer:** this is Kotlin/JVM, not an iOS-ready shared library. The xtool-first plan uses a Swift
engine checked against the same protocol vectors and behavioral traces, with native platform adapters.
See the [iOS and Android parity plan](../docs/ios-plan.md) for the duplication tradeoff and conformance gates.

## Build and test

```sh
cd phone
nix shell nixpkgs#jdk17 -c ./gradlew build              # compiles + runs the JUnit tests (core + cli)
nix shell nixpkgs#jdk17 -c ./gradlew :cli:installDist   # → cli/build/install/determine-phone/bin/determine-phone
```

`core` tests include the golden vectors in `../protocol/vectors/vectors.json`.

## Run the headless phone

```sh
cli/build/install/determine-phone/bin/determine-phone --help
cli/build/install/determine-phone/bin/determine-phone --duration 30 --ceiling -20 --mode STEREO
```

Board addresses are learned from each board's first BEAT (board field), RANGE/ACK (cue board) or FRAME
(camera board); `--cam HOST:PORT` / `--cue HOST:PORT` pin them. `--ceiling` is the local (phone audio) ceiling;
the board's haptic cap is the profile's `haptic_max` (1..255, never off). `--approach` / `--explore` start the engine
with the EXPERIMENTAL `Tuning.approachEnabled` (time-to-contact band escalation) / `Tuning.exploreEnabled`
(distance-proportional haptic pulse train) switched on, every other `Tuning` field at its default; they exist for
simulator A/B runs (`tools/sim/world_bridge.py --approach/--explore`) and are not validated with users.

Output is one event per line, `KIND <ms since start> key=value …`, defined in
[`core/API.md` → "Stable cli output"](core/API.md): `STATE`, `ALERT`, `AUDIO`, `CUE-TX`, `PROFILE-TX`, `SPEAK`,
`VIBRATE`, `SYSTEM-OFF`, `DIAG` (the `DIAG` line ends with the single forward measurement `range_mm`/`range_status`
and `range_left=unavailable range_right=unavailable`). Lines whose second token is not a number (the startup banner) are not events.

## End to end against the simulator

```sh
nix shell nixpkgs#jdk17 -c python3 ../tools/sim/e2e.py        # simulator scenarios, logs in /tmp/determine-e2e/
```
