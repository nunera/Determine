# Determine — native iOS phone

The iPhone is the phone half of Determine: a Swift port of the Kotlin phone engine (`phone/core`) driven by the
cue board's forward distance sensor over the iPhone's Personal Hotspot (wire v2 UDP, `protocol/PROTOCOL.md`),
with a SwiftUI host. iOS 17+, unsigned IPA only. Do not use it while walking: nothing here has been run on a device.

## What the app does

- **Live session** (Main → Start Determine): binds UDP 47000, learns the boards' IPs from their packets, PINGs
  them at 10 Hz, and ticks the engine every 10 ms on one serial queue. Warnings are temple vibration on the cue
  board (one Modulino Vibro, driven at the larger of the two haptic fields). The app keeps the screen awake while running.
- **Foreground only.** Going to the background says "System off", stops the session so PINGs end and the board
  watchdog fires, and shows why on Main. Returning never restarts it. `.inactive` (permission sheets, Control Center,
  notification pull-down, call banners) keeps the process and the session running.
- **Warning sounds are opt-in** (Settings → Warning sounds): **Off (haptic only)** is the default and the shipped
  behaviour; the cue sink is never asked to play audio. **Bluetooth headset** plays the cue language only while the
  current audio route is Bluetooth (A2DP, LE Audio or hands-free); otherwise the sink answers `AUDIO_ROUTE_LOST` and
  the engine reports system off (`headset route lost`). **iPhone speaker (debug)** plays the same cues through the
  built-in speaker so they can be tried without a headset; it is labelled debug everywhere, uses louder
  uncalibrated levels (-10 dBFS, the hard ceiling) and also answers `AUDIO_ROUTE_LOST` if Bluetooth or wired audio holds
  the route. Sound never silently falls back to another output. See [Warning sounds](#warning-sounds). Hearing
  calibration is not ported to iOS; its screen says so.
- **Phone alerts**: spearcons and "System off" through `AVSpeechSynthesizer`, vibration through Core Haptics
  (system vibrate if unsupported).
- **iPhone camera fallback** (Settings → Camera): Tier 0 `FrameSourceKind.phoneCamera`. Back camera, 640×480 sensor
  frames rotated upright (480×640), at most ~10 fps, JPEG quality 75, stamped with the engine clock. Permission
  denial, interruption and runtime errors are shown on Main and Judge; the engine independently reports
  `frame_stale` without fresh frames. It is labelled a software fallback. Changing the camera restarts the session.
- **Describe** (Settings → Describe; on request only, never automatic): sends the newest camera frame (at most 500 ms
  old, same freshness rule as Read text) to the describe-api in `cloud/` and speaks the answer as returned. This build
  has **no face blur**, so it uploads the frame as captured, including bystanders' faces, and works
  only while *Upload frames with faces unblurred* is on (default off; with it off nothing is sent). The request
  states `redacted: false` and `unredacted_opt_in: true`, which the API accepts only together. Server URL and bearer
  key are set in Settings (the key is kept in the Keychain; plain `http://` is allowed for local-network addresses
  only, via `NSAllowsLocalNetworking`). A MID/NEAR warning or system off cuts the request or the speech.
  A permanent instance runs on the home server (SmolVLM-256M on CPU, tailnet only, about 2 s per answer measured
  with a 640×360 test image from a laptop client): URL `https://mansfield.tail06a975.ts.net/describe`, so the
  iPhone must be on the tailnet. The key is in `/etc/determine/describe.env` on that server. Not verified from
  the app on a device: see Real device tests.
- **Read text** (Main button, also a VoiceOver custom action on the status panel): on-device OCR (Vision
  `VNRecognizeTextRequest`) of the newest camera frame (iPhone camera or camera board), only if the frame is at most
  500 ms old. The result is spoken as read, an empty result as "No text found", a stale or missing
  frame as "No fresh camera image". It is display and speech only and never feeds hazard logic. It is not started
  while a MID or NEAR warning is active or the system is off, and a MID/NEAR alert or system off cuts a reading in
  progress (a hazard word or "System off" that replaced it is never cut). A second tap stops it.
- **Link quality**: when the engine reports `linkWeak` (cue board RSSI below the threshold for the hold time) Main shows
  an orange "Weak connection" banner and Judge highlights the RSSI. The engine speaks "weak connection" once. It is
  informational and never changes the system state.
- **Exploration** (experimental, default off): quick toggle on Main, parameters in Settings. Haptic pulses whose
  strength follows the distance instead of band cues; silence still never means clear.

### Personal Hotspot setup

Settings → Personal Hotspot → Allow Others to Join, and turn on **Maximize Compatibility** (the boards' ESP32
radios are 2.4 GHz only). The hotspot name (the iPhone's name) and password must equal `DT_WIFI_SSID` and `DT_WIFI_PASS` in
`firmware/common/include/secrets.h`. The boards send to the hotspot gateway, which is this iPhone, on port 47000;
the cue board listens on 47001 and the camera board on 47002. iOS asks for Local Network access on first use; the session keeps running behind the prompt.

## Warning sounds

`Settings → Warning sounds` picks where the phone's cue language (`CueBank.swift`, a port of Android's `CueBank.kt`:
same note table, waveforms, dBFS gain maths and BAD_CUE / BUSY_DROPPED / APPLIED / APPLIED_CLAMPED decision) plays.
`AudioEngineCueSink` renders each cue to PCM and plays it through an `AVAudioEngine`; the engine thread only decides
and a private queue does the audio work.

| Choice | Engine mode | Plays | Answers when the route is wrong |
|---|---|---|---|
| **Off (haptic only)**, default | HAPTIC_ONLY | nothing; the sink is never asked for audio | n/a |
| **Bluetooth headset** | stereo or one-ear (Settings → Sound layout) | only while the route is Bluetooth A2DP, LE Audio or HFP | `AUDIO_ROUTE_LOST` → system off (`headset route lost`); a cue in flight is cut |
| **iPhone speaker (debug)** | stereo or one-ear | only while the route is the built-in speaker, at -10 dBFS | `AUDIO_ROUTE_LOST` (e.g. Bluetooth or wired audio is connected) |

A muted link probe (the engine's route check) plays nothing and returns `APPLIED` only for a usable route; a sound
that cannot start returns a failure, which the engine counts like a missing ACK. Sound never silently falls back to
another output. The Judge screen shows the route, whether it is usable, the audio engine state and the played /
dropped / route-lost / failed counters. Levels are the uncalibrated defaults (calibration is not ported to iOS); the
speaker choice raises them to the -10 dBFS hard ceiling so the cues are audible on a phone speaker.

## Settings → Experimental & debug

Every engine `Tuning` value (feature contract §7) is a slider or toggle here, saved on the device and applied with
`Engine.setTuning` at once, also during a session. The engine validates the whole value; a refused change is shown and
not applied.

- **Warning distances**: NEAR / MID / FAR thresholds, 100–8000 mm. They stay in order (NEAR < MID < FAR, at least 50 mm
  apart). Note shown: the VL53L4ED is specified for about 1.2 m; larger values are outside its spec.
- **Smoothing**: enter time, leave time, hysteresis. **Cue periods** per band. **Spoken status**: announce hold and repeat.
- **Weak connection**: RSSI threshold, hold and repeat.
- **Approach speed** and **Exploration**, both labelled experimental and off by default (exploration also has a toggle
  on Main).
- **Reset to defaults**. Temple vibration strength (haptic max, 1–255) is the existing slider higher up in Settings.

## Judge: chart and debug panel

- **Last 30 seconds** (Swift Charts): forward distance with dashed NEAR / MID / FAR lines and background shading per
  system state (valid readings are the line; no target and unknown are marked along the top edge), the cue board RSSI
  with the weak-link threshold, and the closing speed (positive = approaching).
- **Debug panel**: counters, last RANGE (distance, status, age), board IPs, approach/exploration flags, the event log
  (last 50 state, alert, link and test-buzz events with timestamps) and **Test buzz** buttons (PULSE40, PULSE60,
  BUZZ150, LSS) with an intensity slider. A test buzz goes through `Engine.sendTestHaptic` (the normal CUE path:
  deadline, haptic_max clamp, ACK) and the log says whether it was sent.
- **Weak link** shows as a banner on Main and Judge and highlights the RSSI.

## Real device tests

Device evidence comes from the user's reports, not independent observation. The user reported that every
v0.2.0 feature worked after fitting the cue board's antenna, then reported that **every v0.3.0 feature works,
including exploration mode** (an earlier report of exploration failing was withdrawn). These are summary
reports, not per-row logs; the table retains the earlier per-feature evidence below.

| Check | What to do | Status |
|---|---|---|
| Personal Hotspot join | Hotspot on, Maximize Compatibility on; both boards join and appear under Judge → Link | Passed (user, v0.2.0) — cue board; no camera board yet |
| Local Network prompt | First Start shows the iOS prompt; Allow; the session keeps running behind it | Passed (user, v0.2.0) |
| Live distance | Judge → Forward distance follows a hand in front of the cue board; NO_TARGET in open space is not "clear" | Passed (user, v0.2.0); quiet open space (NO_TARGET) not yet tested on device |
| Vibro cues | NEAR / MID / FAR give distinct temple vibration; strength slider changes it | Passed (user, v0.2.0) |
| iPhone camera fallback | Settings → Camera → iPhone camera; frames stream, no `frame_stale` | Passed (user, v0.2.0) |
| Foreground / background | Going to the background says "System off" and stops; returning never restarts; Control Center / prompts do not stop it | Passed (user, v0.2.0) |
| System off | Unplug a board or turn the hotspot off: "System off" within 1 s, repeated every 5 s | Observed by the user during the antenna-less weak-link episode (v0.2.0) |
| Speech | Spearcons, "system ready" (held ≥ 1.5 s), weak connection, rate 0.62 is audible and sensible | Speech audible (user, v0.2.0); announcement hold and "weak connection" not yet tested on device |
| Tuning sliders | Change distances, smoothing, periods; the running session reacts at once; Reset to defaults | not yet tested on device |
| Judge chart + debug panel | 30 s chart, RSSI, closing speed, event log, counters | not yet tested on device |
| Test buzz | Each pattern buzzes the temple; "NOT sent" before clock sync | not yet tested on device |
| Weak-link banner | Move the phone away from the glasses: banner on Main, RSSI on Judge, spoken once | not yet tested on device |
| Speaker sound (debug) | Warning sounds → iPhone speaker; cues audible; Bluetooth connected gives system off | not yet tested on device |
| Bluetooth headset sound | Warning sounds → Bluetooth headset; plays only on the headset; disconnect gives system off | not yet tested on device |
| Read text | Point the camera at text; the text is spoken as read; "No text found" otherwise; cut by a MID/NEAR alert | not yet tested on device |
| Exploration mode | Main toggle; pulses strengthen as an object nears; silent with nothing in range | Works (user, v0.3.0); summary report, not independently observed |
| Approach-speed escalation | Settings → Approach; walk toward a wall; band escalates by one step | not yet tested on device |
| Describe | Settings → Describe: enable, turn on unblurred upload, set server URL and key; Main → What's ahead? with the iPhone camera running; the answer is spoken as returned; with the opt-in off nothing is sent; a MID/NEAR warning cuts it | not yet tested on device (wire client smoke-tested on Linux against the real handler with a stub model; no deployed server or real model) |

## Unsigned IPA

Built artifact: `xtool/DetermineApp.ipa` (arm64, iOS 17+, bundle id `org.determine.prototype`, display name
**Determine**). It has no code signature, `_CodeSignature` or provisioning profile; install it through a
third-party loader. `Support/Info.plist` declares `NSLocalNetworkUsageDescription`, `NSCameraUsageDescription`
and no background modes. Never add `--sign`.

### Rebuild on this Linux workstation

The native build uses xtool **1.22.2**, Swift **6.4.1**, and a slim Darwin SDK extracted from the user's licensed
**Xcode 27.1** archive. `Containerfile` pins the official Swift image digest and adds `zip`, which xtool needs to create an IPA.

From the repository root:

```sh
podman build -f ios/Containerfile -t localhost/determine-ios-build:6.4.1 ios
podman run --rm --userns=keep-id \
  -e HOME=/home/ubuntu -e APPIMAGE_EXTRACT_AND_RUN=1 -e TERM=dumb \
  -v "$HOME/.cache/determine-ios:/home/ubuntu" \
  -v "$PWD:/workspace" \
  -v "$HOME/.cache/determine-ios/xtool-1.22.2.AppImage:/usr/local/bin/xtool:ro" \
  -w /workspace/ios localhost/determine-ios-build:6.4.1 \
  script -qec 'xtool dev build --ipa --configuration release' /dev/null
```

Do **not** add `--sign`. `script` supplies a real pseudo-terminal for xtool's SwiftNIO process handling; non-TTY
invocations hit `epoll_ctl` errors here. The persistent mount must match the container's passwd home
(`/home/ubuntu` for UID 1000 in this image); setting only `HOME` left the SDK in a disposable container.

The cache already contains the SDK and pinned xtool AppImage on this workstation. For a new machine, obtain the
AppImage from the [1.22.2 release](https://github.com/xtool-org/xtool/releases/tag/1.22.2), verify SHA-256
`e3176cdf861f58b7f135c755c7bfaec92d0568ef826027b4e5e3d82b3219ddfe`, and put it at the cache path above. Mount a
licensed Xcode archive read-only into the same container and run `xtool sdk install /downloads/Xcode_27.1.xip --slim`
once, with the same persistent home. Follow the official [xtool Linux setup](https://xtool.sh/documentation/xtooldocs/installation-linux)
for SDK prerequisites. Unsigned building requires neither `xtool auth` nor a connected iPhone.

## Layout

| Path | Role |
|---|---|
| `Sources/DetermineEngine/` | Pure engine port of `phone/core` (Foundation only, no I/O, never reads a clock). |
| `Sources/DetermineEngineChecks/` | Executable assertions: protocol vectors and ported Kotlin tests. |
| `Sources/DetermineLink/` | BSD-socket UDP on :47000, board address book and attribution, shared by the app and the cli. |
| `Sources/DeterminePhone/` | Linux/macOS headless cli (same options and output lines as `phone/cli`), checked by `tools/sim/e2e.py`. |
| `Sources/DetermineApp/` | iPhone app. `SessionHost` owns the engine queue, tick, link and camera; `AppModel` holds settings, tuning and published state; `DebugTrace` the chart history and event log; `IosPhoneAlerts`, `PhoneCameraSource`, `CueBank` + `AudioEngineCueSink` (warning sounds), `TextRecognition` (Read text); SwiftUI views (`MainView`, `JudgeView` + `JudgeCharts` + `JudgeLogViews`, `SettingsView` + `ExperimentalSettings`, `CalibrationView`). |
| `Support/Info.plist`, `xtool.yml` | Bundle metadata and xtool packaging. |

`DetermineApp` is SwiftUI/UIKit/AVFoundation only: on Linux it compiles to a stub, so build the Linux targets
by name (`swift build --product DeterminePhone`, `swift run DetermineEngineChecks` inside `nix-shell -p swift swiftpm`).

## Verification status

- **Engine:** `swift run DetermineEngineChecks` inside `nix-shell -p swift swiftpm` (protocol vectors and the ported Kotlin
  tests; the counts are printed by the command).
- **End to end (Linux):** `python3 tools/sim/e2e.py --phone-cmd "<path>/DeterminePhone --port {phone_port} --duration {duration}"`.
  Copy the binary out of `.build/release` first: the xtool container build shares `.build`. These scenarios use the
  simulated camera board; the iPhone-camera path is covered only by the engine's FallbackTest port.
- **App:** native arm64 release build compiled with the Apple SDK and packaged as an unsigned IPA. The user
  reports all v0.3.0 features working on their iPhone, including exploration mode.
  No independent device or simulator observation of the UI, audio routes, camera, Vision, VoiceOver or
  lifecycle behaviour has been performed. See [Real device tests](#real-device-tests). Spearcon speech
  rate (0.62) is an estimate of Android's 1.8×.
