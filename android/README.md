# Determine — Android app

The phone is the only integrator (Arch §4): it receives the two boards' UDP streams, runs the hazard loop from
`phone/core` (`org.determine:core`, pulled in with `includeBuild("../phone")`), runs the MediaPipe detector on the
camera frames, and sends cue commands back to the cue board. This module is only the Android shell around the core:
UDP socket, foreground service, detector, phone-side alerts, and the accessible UI.

**Cross-platform target:** [`../ios/`](../ios/README.md) contains a native SwiftUI interactive prototype of
these workflows, with explicitly synthetic inputs and xtool packaging. It is not yet a live hardware client.
Android remains the first implementation; the [iOS and Android parity plan](../docs/ios-plan.md) defines the
remaining device gates. Platform-specific permissions and lifecycle behavior must not weaken the safety contract.

| Piece | File | Notes |
|---|---|---|
| Foreground service | `service/DetermineService.kt` | type `connectedDevice`; partial wake lock + Wi-Fi lock; battery → `engine.setBatteryCritical` |
| Engine host | `runtime/EngineHost.kt` | owns `Engine`, 10 ms tick on an urgent-priority thread, engine lock, calibration session |
| UDP | `net/UdpLink.kt`, `net/BoardAddressBook.kt` | binds :47000; learns board IPs; PONG attributed by source IP; sends to CUE :47001 / CAM :47002 |
| Detector | `detect/DetectorRunner.kt`, `detect/FramePacer.kt` | MediaPipe ObjectDetector LIVE_STREAM, allowlist, score ≥ 0.5; one frame in flight, stale/busy frames skipped, never queued |
| Phone alerts | `alerts/AndroidPhoneAlerts.kt` | TTS spearcons (rate 1.8), vibration, system-off tone + "System off" on the alarm stream |
| UI | `ui/` | Main (state live region, start/stop, pause), Judge diagnostics, Calibration (Arch §9), Settings |

Clock: phone time is `SystemClock.elapsedRealtimeNanos() / 1000` µs (monotonic, counts through deep sleep).

## Setup

Nothing is installed globally; everything comes from nix.

1. Android SDK (platforms 34 + 35, build-tools 34.0.0 + 35.0.0), non-interactively:

   ```sh
   NIXPKGS_ALLOW_UNFREE=1 NIXPKGS_ACCEPT_ANDROID_SDK_LICENSE=1 nix build --impure --expr \
     'with import <nixpkgs> { config.allowUnfree = true; config.android_sdk.accept_license = true; };
      (androidenv.composeAndroidPackages {
        platformVersions = ["34" "35"]; buildToolsVersions = ["34.0.0" "35.0.0"];
        includeEmulator = false; includeSystemImages = false; includeNDK = false;
      }).androidsdk' -o /tmp/android-sdk-result
   ```

   (`<nixpkgs>` resolves through `NIX_PATH`; on a flakes-only system use
   `--expr 'with import (builtins.getFlake "nixpkgs") {...}'`.)

2. Point Gradle at it in the gitignored `android/local.properties`:

   ```sh
   echo "sdk.dir=$(readlink -f /tmp/android-sdk-result)/libexec/android-sdk" > android/local.properties
   ```

3. aapt2 on NixOS: AGP downloads a dynamically linked `aapt2` from Maven. With `nix-ld` enabled it runs as is.
   Without `nix-ld`, add `-Pandroid.aapt2FromMavenOverride=<sdk.dir>/build-tools/35.0.0/aapt2` to every Gradle
   command.

## Build, test, install

```sh
cd android
nix shell nixpkgs#jdk17 nixpkgs#gradle -c ./gradlew assembleDebug        # → app/build/outputs/apk/debug/app-debug.apk
nix shell nixpkgs#jdk17 nixpkgs#gradle -c ./gradlew testDebugUnitTest    # JVM tests: pacing, addressing, battery gate
adb install -r app/build/outputs/apk/debug/app-debug.apk                 # adb: <sdk.dir>/platform-tools/adb
```

The core (wire codec, clock sync, policy, state machine, calibration) is tested in `phone/`
(`cd phone && gradle test`).

### Detector model

`./gradlew assembleDebug` runs `:app:downloadDetectorModel`, which fetches EfficientDet-Lite0 (int8, COCO, ~4.6 MB)
from
`https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/1/efficientdet_lite0.tflite`
into `app/build/generated/detectorModel/assets/` and checks its SHA-256
(`0720bf24…c723bb`). The model is never committed. Offline builds: place the file there by hand (same hash) and
the task is up to date. If the model is missing at runtime the app runs ToF-only with the generic "obstacle"
label and the Judge screen shows "Detector unavailable" (Arch §11.3 abort branch).

Allowlist (COCO): person, bicycle, car, motorcycle, bus, truck, traffic light, fire hydrant, stop sign, parking
meter, bench, chair, couch, potted plant, dining table, dog, suitcase, refrigerator, tv. COCO has no door class;
doors are reported by the ToF corridor as "obstacle".

## Hotspot setup

1. On the phone, turn on the Wi-Fi hotspot (2.4 GHz band: both ESP32s are 2.4 GHz only) with WPA2 and the SSID
   and password compiled into both boards (`firmware/common/include/secrets.h`; Arch §10.1: unique password).
2. Power the boards. They join the hotspot and send BEAT/RANGE/FRAME to their DHCP gateway (this phone) on
   UDP 47000. The app learns each board's IP from its first packet.
3. Open Determine → **Start Determine**. Allow notifications (optional; the service runs without it).
4. Settings → *Hotspot and boards* shows this phone's addresses and each board's learned IP. Judge diagnostics
   shows heartbeat ages, clock sync, ToF grid and latency.
5. Status reaches **Ready** once frames are fresh, ToF is valid and the cue board acknowledges (Arch §4.2).

Some phones isolate hotspot clients or pause the hotspot when idle; disable "turn off hotspot automatically".
The Wi-Fi lock only matters if the phone is a client of an external access point instead of the hotspot.

## Runtime behaviour worth knowing

- **No all-clear.** With no warning, the main screen shows nothing in the alert area plus the standing line
  "Silence … never means the path is clear". There is no clear sound.
- **Failure is loud.** UNAVAILABLE (missing BEAT/frames > 500 ms, cue NACK/missed ACK, battery critical) makes
  the engine call `systemOff()` every 5 s: three descending notes and "System off" on the alarm stream, plus
  long-short-short vibration. Stopping the app stops PINGs, so the cue board's own watchdog also plays SYSTEM OFF.
- **Battery critical** = ≤ 5 % and not charging, cleared above 8 % or on charge (`power/BatteryCriticalGate.kt`).
  Android has no public "critical" broadcast; these thresholds are PROPOSED.
- **Head yaw.** The boards carry no IMU and the phone is not head-mounted, so `Engine.setHeadYawRate` is never
  called (yaw stays 0; the scanning hold of FAR cues is inactive).
- **Calibration** pauses obstacle cues, sends tones through the cue board (`Engine.sendCalibrationCue`, ttl
  1000 ms), and applies the resulting profile with `Engine.setProfile`. Leaving the screen cancels it and lifts
  the pause. The profile lives for the service session only (Arch §9.1: re-run every session). The ambient-noise
  check of Arch §9 step 1 is not implemented (it needs microphone permission).
- **Spearcons** toggle in Settings maps to `Engine.setSpearcons`; state announcements are always spoken.
- **Describe** (Arch §7.3): off by default. Settings → Describe: enable, enter the service URL (deployed
  `cloud/describe-api` Function URL, or `http://<laptop-ip>:8787` from `local_server.py` in debug builds) and the
  API key (stored with EncryptedSharedPreferences). The main screen then shows "What's ahead?": long-press, or the
  TalkBack action "Describe what's ahead". The phone blurs faces with BlazeFace (`describe/FaceRedactor`); if that
  fails nothing is uploaded. The answer is spoken as "May be wrong. …"; a MID/NEAR warning cancels it. The Judge
  screen shows the backend, silicon and measured timings of the last request.
- **Fallbacks** (Arch §11.4 demo branches, labelled "software fallback — not the proposed glasses" on the Judge
  screen): Settings → Fallbacks → Camera = phone camera on a headband (CameraX, asks for CAMERA permission), and
  Warning sounds = phone audio to headphones (`audio/PhoneCueSink`, same cue bank as the firmware, phone
  vibration instead of temple motors). Changing either restarts the session. The cue board's ToF is still required.
