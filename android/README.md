# Determine — Android app

**Hardware status (24 Oct 2026):** headset playback below is the implemented software/bench path, not approval of the separate OpenRun for the build. Integrated bone-conduction output is unresolved. The cue board's haptics are one Modulino Vibro (no left/right); see the [cue-board README](../firmware/cue-board/README.md). Architecture §12.2–12.4 holds the older stock snapshot.

The phone is the only integrator (Arch §4): it receives the two boards' UDP streams, runs the hazard loop from
`phone/core` (`org.determine:core`, pulled in with `includeBuild("../phone")`), plays every warning sound through a
Bluetooth bone-conduction headset (Shokz OpenRun class), and sends temple-haptic cues to the cue board. Distance
comes from exactly one source: the cue board's single forward VL53L4CD zone. The camera feeds Describe only. This
module is only the Android shell around the core: UDP socket, foreground service, headset audio, phone-side
alerts, and the accessible UI.

**Cross-platform target:** [`../ios/`](../ios/README.md) contains a native SwiftUI interactive prototype of
these workflows, with explicitly synthetic inputs and xtool packaging. It is not yet a live hardware client.
Android remains the first implementation; the [iOS and Android parity plan](../docs/ios-plan.md) defines the
remaining device gates. Platform-specific permissions and lifecycle behavior must not weaken the safety contract.

| Piece | File | Notes |
|---|---|---|
| Foreground service | `service/DetermineService.kt` | type `connectedDevice`; partial wake lock + Wi-Fi lock; battery → `engine.setBatteryCritical` |
| Engine host | `runtime/EngineHost.kt` | owns `Engine`, 10 ms tick on an urgent-priority thread, engine lock, calibration session, live haptic cap |
| UDP | `net/UdpLink.kt`, `net/BoardAddressBook.kt` | binds :47000; learns board IPs; PONG attributed by source IP; sends to CUE :47001 / CAM :47002 |
| Headset audio | `audio/PhoneCueSink.kt`, `audio/CueBank.kt` | the phone sound bank (cue ids 0–11) rendered on a low-latency `AudioTrack` bound to the connected Bluetooth (A2DP / LE Audio) headset; per-side ceiling `min(profile, −10 dBFS)`; **no headset or a route diverted elsewhere → explicit `headset_route_lost` (system off), never the phone speaker** |
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
(`cd phone && gradle test`). The app's own JVM tests cover the sound bank (`CueBankTest`), addressing and the battery gate.

### Face model

`./gradlew assembleDebug` runs `:app:downloadFaceModel`, which fetches MediaPipe BlazeFace (short range, ~230 KB) into
`app/build/generated/faceModel/assets/` and checks its SHA-256. It is used only to blur faces before a Describe
upload; if it is missing, Describe uploads are disabled. There is no object-detection model: hazard distance, band
and label never depend on an image (one forward ToF zone, alerts read "obstacle ahead").

## Hotspot setup

1. On the phone, turn on the Wi-Fi hotspot (2.4 GHz band: both ESP32s are 2.4 GHz only) with WPA2 and the SSID
   and password compiled into both boards (`firmware/common/include/secrets.h`; Arch §10.1: unique password).
2. Power the boards. They join the hotspot and send BEAT/RANGE/FRAME to their DHCP gateway (this phone) on
   UDP 47000. The app learns each board's IP from its first packet.
3. Open Determine → **Start Determine**. Allow notifications (optional; the service runs without it).
4. Settings → *Hotspot and boards* shows this phone's addresses and each board's learned IP. Judge diagnostics
   shows heartbeat ages, clock sync, the headset route, the **one forward distance** (left and right are shown as
   "not measured") and latency.
5. Status reaches **Ready** once frames are fresh, the forward ToF is valid, the cue board acknowledges the haptic
   profile **and** the headset route plays (Arch §4.2). Without a paired headset the status is **System off:
   headset route lost** within a second, the main screen says "No Bluetooth headset connected. Warning sounds are
   off", and Judge diagnostics still shows the forward distance and board links. Exception: with warning sounds
   switched off on purpose (Settings → Output mode → haptic only, or "Use haptics only" in calibration) no headset
   is needed: the audio sink is never called, Ready needs only the cue board, and the main screen shows no headset
   warning. Calibration tones always need the headset, and switching back to a sound mode re-proves it.

Some phones isolate hotspot clients or pause the hotspot when idle; disable "turn off hotspot automatically".
The Wi-Fi lock only matters if the phone is a client of an external access point instead of the hotspot.

## Runtime behaviour worth knowing

- **No all-clear.** With no warning, the main screen shows nothing in the alert area plus the standing line
  "Silence … never means the path is clear". There is no clear sound.
- **Failure is loud.** UNAVAILABLE (missing BEAT/frames > 500 ms, cue NACK/missed ACK, headset route lost, battery
  critical) makes the engine call `systemOff()` every 5 s: three descending notes and "System off" on the alarm
  stream, plus long-short-short vibration, the same cue on the headset if it is still there, and temple haptics (LSS).
  Stopping the app stops PINGs, so the cue board's own watchdog plays haptic LSS (the board makes no sound).
- **Battery critical** = ≤ 5 % and not charging, cleared above 8 % or on charge (`power/BatteryCriticalGate.kt`).
  Android has no public "critical" broadcast; these thresholds are PROPOSED.
- **Head yaw.** The boards carry no IMU and the phone is not head-mounted, so `Engine.setHeadYawRate` is never
  called (yaw stays 0; the scanning hold of FAR cues is inactive).
- **Calibration** pauses obstacle cues, plays tones on the headset (`Engine.sendCalibrationCue`, ttl 1000 ms; they
  never go to the board), and applies the resulting audio profile with `Engine.setProfile` only after successful
  completion and a final headset-route check (the user's haptic cap is kept). An unaccepted tone, playback error,
  or lost route during any calibration step—even while idle or after finishing but before applying—stops the session,
  discards its result, resumes the user's previous pause setting, and asks for headset reconnection and a fresh
  calibration. An unanswered/unplayed tone cannot count as hearing loss. Haptic-only warnings outside calibration
  remain usable without a headset. The profile lives for the service session only (Arch §9.1: re-run every session).
  The ambient-noise check of Arch §9 step 1 is not implemented (it needs microphone permission). Left/right
  calibration fits the two audio channels; the single forward sensor never says which side a hazard is on.
- **Temple vibration strength** (Settings): the board haptic cap, 1..255, never off (the temples are the only alert
  left when the phone link is lost). The lowest setting is not a tested guarantee that you will feel it.
- **Spearcons** toggle in Settings maps to `Engine.setSpearcons`; state announcements are always spoken.
- **Describe** (Arch §7.3): off by default. Settings → Describe: enable, enter the service URL (deployed
  `cloud/describe-api` Function URL, or `http://<laptop-ip>:8787` from `local_server.py` in debug builds) and the
  API key (stored with EncryptedSharedPreferences). The main screen then shows "What's ahead?": long-press, or the
  TalkBack action "Describe what's ahead". The phone blurs faces with BlazeFace (`describe/FaceRedactor`); if that
  fails nothing is uploaded. The answer is spoken as "May be wrong. …"; a MID/NEAR warning cancels it. The Judge
  screen shows the backend, silicon and measured timings of the last request.
- **Camera fallback** (Arch §11.4 demo branch, labelled "software fallback — not the proposed glasses" on the Judge
  screen): Settings → Camera = phone camera on a headband (CameraX, asks for CAMERA permission). Changing it
  restarts the session. The cue board's forward ToF and the headset are still required.
