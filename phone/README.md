# phone — hazard engine and headless phone

Gradle (Kotlin/JVM 17) project with two modules:

| Module | What it is |
|---|---|
| `core` | Pure-Kotlin engine, no Android or I/O: wire codec, clock sync, frame assembly, ToF corridor, hazard policy (Arch §7.2), cue scheduler (Arch §8), calibration (Arch §9), system state machine (Arch §4.2). Public API: [`core/API.md`](core/API.md). |
| `cli` | `determine-phone`: the real `Engine` over UDP :47000 with stdout alerts, no detector. Used by `tools/sim/e2e.py` and for bench runs. |

The Android app (`../android`) consumes `core` through `includeBuild("../phone")`.

## Build and test

```sh
cd phone
nix shell nixpkgs#jdk17 -c ./gradlew build              # compiles + 58 JUnit tests (core + cli)
nix shell nixpkgs#jdk17 -c ./gradlew :cli:installDist   # → cli/build/install/determine-phone/bin/determine-phone
```

`core` tests include the golden vectors in `../protocol/vectors/vectors.json`.

## Run the headless phone

```sh
cli/build/install/determine-phone/bin/determine-phone --help
cli/build/install/determine-phone/bin/determine-phone --duration 30 --ceiling -20 --mode STEREO
```

Board addresses are learned from each board's first BEAT (board field), RANGE/ACK (cue board) or FRAME
(camera board); `--cam HOST:PORT` / `--cue HOST:PORT` pin them.

Output is one event per line, `KIND <ms since start> key=value …`, defined in
[`core/API.md` → "Stable cli output"](core/API.md): `STATE`, `ALERT`, `CUE-TX`, `PROFILE-TX`, `SPEAK`,
`VIBRATE`, `SYSTEM-OFF`, `DIAG`. Lines whose second token is not a number (the startup banner) are not events.

## End to end against the simulator

```sh
nix shell nixpkgs#jdk17 -c python3 ../tools/sim/e2e.py        # 8 scenarios, logs in /tmp/determine-e2e/
```
