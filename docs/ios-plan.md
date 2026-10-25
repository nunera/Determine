# iOS app and Android parity plan

Status: planned, not implemented or device-validated. Android remains the first implementation; iOS is a target platform, not a separate reduced-feature product. This plan supplements the Android-first architecture contract without claiming an iOS build exists. Event work must still follow the HackTX build-on-site rule.

## Product contract: essentially the same app

Android and iOS should look and work as nearly identically as practical: same screen hierarchy, content order, labels, status vocabulary, settings, defaults, cue language and task flows. Native permission sheets, system navigation, safe areas, font scaling and screen-reader gestures may differ; safety semantics may not. Match layouts at equivalent viewport and text sizes rather than forcing identical pixels at the expense of accessibility.

Use the existing Android screens and behavior in [`../android/README.md`](../android/README.md) as the initial parity inventory, not as proof that every architecture proposal is implemented. For example, the ambient-noise calibration check is currently absent on Android; do not describe it as shipped on either platform. No cross-device account or settings synchronization is implied.

| Surface | Required parity |
|---|---|
| Main | Start/stop, pause/resume, live state and reason, current warning, standing silence-is-not-clear disclaimer, explicit absent-headset warning; no headset warning in deliberately selected haptic-only mode. |
| Judge diagnostics | One forward distance and status; left/right explicitly not measured; board health, frame age, clock sync, headset route, latency and Describe backend/silicon/timings. Label phone-camera mode as a software fallback. |
| Calibration | Same per-session, per-ear sequence, tones, heard/not-heard actions, balance and ceiling; headset required even in haptic-only mode. Any route loss or unplayed tone invalidates the result; restore the previous pause setting. Never interpret a failed playback as hearing loss. |
| Settings | Same output modes (stereo, single-sided-deafness/mono, haptic only), nonzero temple strength 1–255, spearcons, camera source, network help and opt-in Describe configuration. Keep session calibration separate from persistent preferences. |
| Describe | Off by default; explicit request plus equivalent screen-reader action; on-phone face redaction before upload, fail closed if unavailable; same cloud contract, “May be wrong” prefix and immediate MID/NEAR interruption. |
| Accessibility | Equivalent TalkBack/VoiceOver reading order, named controls and actions, announced state changes, scalable text, contrast and non-colour status cues. No essential action available only through long-press. |

Keep a single reviewed inventory of strings, design tokens, cue definitions and screen states as implementation proceeds; both native presentations consume or are checked against it. A platform-only omission remains an open parity gap, not an alternate definition of completion.

## Implementation decision

Plan a native Swift/SwiftUI iOS app in a future `ios/` SwiftPM package, built, signed and deployed with [xtool](https://github.com/xtool-org/xtool). Keep Android native. xtool is the iOS toolchain, not a cross-platform UI framework or an Android-to-iOS converter.

The existing `phone/core` is Kotlin/JVM 17, **not** an iOS-ready shared library: its Gradle target is JVM and production code uses `java.util.BitSet`, `Locale` and `ConcurrentLinkedQueue`. Do not plan to load its JAR on iOS or assume that switching a Gradle target makes it portable.

For an xtool-first Linux workflow, plan a platform-independent Swift engine module plus a thin iOS host. Port the existing behavior rather than redesigning it. Share protocol vectors, recorded input scenarios, expected event traces, cue specifications and cloud schemas across the Kotlin and Swift engines. This duplicates implementation and creates drift risk; cross-engine conformance is therefore a release gate, not an optional test. A later shared-core migration requires a separate proven Apple-target build/link workflow; it is not a prerequisite or an unverified promise in this plan.

Keep the engine interface aligned with [`../phone/core/API.md`](../phone/core/API.md): timestamped inputs and ticks in; state, diagnostics, alerts and outgoing packets out. Platform adapters own UDP, a monotonic clock that advances through device sleep, audio routing/playback, speech, phone vibration, battery/lifecycle events, camera capture, redaction, HTTPS and secure credential storage. No UIKit, SwiftUI, networking or wall-clock reads inside the decision module.

Unchanged shared contracts:

- Wire v2 and its eight packet types, ports, deadline/ACK semantics and `haptic_max` floor: [`../protocol/PROTOCOL.md`](../protocol/PROTOCOL.md), with [`../protocol/vectors/vectors.json`](../protocol/vectors/vectors.json).
- One forward ToF zone; identical banding, hysteresis, freshness, scheduling, clock synchronization, calibration and STARTING/READY/DEGRADED/UNAVAILABLE/PAUSED transitions. No object identification or lateral range inferred from the camera.
- No all-clear sound. Unknown is not clear. No cloud dependency in hazard warnings. Preserve existing haptic-only exceptions and explicit output failures.
- Same phone-side cue IDs, waveform/timing definitions, per-channel gain ceiling and mono semantics; same board haptic commands. Actual Bluetooth latency and perceptibility need separate measurements on each platform.
- Same Describe request/response and cancellation behavior: [`../cloud/API.md`](../cloud/API.md). Store API credentials in platform secure storage; never in source or parity fixtures.

## xtool development workflow

Use upstream [Linux installation instructions](https://xtool.sh/documentation/xtooldocs/installation-linux) and the [first-app tutorial](https://xtool.sh/tutorials/xtooldocs/first-app). Pin a tested xtool release, compatible Swift toolchain and extracted Darwin/iOS SDK during the first implementation spike; upstream `main` prerequisites are moving documentation, not a verified local configuration.

1. Prepare Swift, xtool and Linux `usbmuxd`; obtain the required Xcode `.xip` from Apple under its license. Linux development does not remove the Apple SDK requirement. Validate any NixOS/AppImage packaging requirements on the actual workstation.
2. Run `xtool setup` for authentication and SDK extraction. Upstream supports paid-program API-key authentication and Apple-ID password authentication using private Apple APIs. Prefer API keys when membership is available; keep credentials out of the repository and CI logs.
3. Create the SwiftPM app with `xtool new`; configure its bundle identifier and bundling in `xtool.yml`. Keep core logic independently runnable on Linux where possible.
4. Pair a physical iPhone over USB, trust the computer/developer identity and enable Developer Mode as required. Run `xtool dev` from the app package to build, sign and install, then launch and inspect the app on the phone.
5. Record the working tool versions, device/iOS version, signing method and reproducible commands in the future `ios/README.md` only after the workflow succeeds. Development installation is not evidence of TestFlight/App Store distribution; distribution, account eligibility and required entitlements need their own validation before public release.

Do not install tools, download the SDK, authenticate to Apple or create an empty iOS scaffold as part of this documentation-only change.

## iOS feasibility gates

| Risk | Required decision or proof |
|---|---|
| Two boards on iPhone Personal Hotspot | Prove 2.4 GHz compatibility, both boards joining, board-to-host UDP delivery to :47000, return traffic to :47001/:47002, learned addresses and clock sync on a real iPhone. Existing firmware sends to its DHCP gateway; do not assume an arbitrary external access point is a drop-in substitute. If hotspot delivery fails, resolve phone-address provisioning explicitly before changing the topology. |
| Local network permission | Include `NSLocalNetworkUsageDescription`; request access in a foreground onboarding flow and explain denied access. Receiving unicast alone does not prove permission: sending PING/PROFILE/CUE requires it. Current unicast design needs no multicast/broadcast discovery; adding those changes entitlement requirements. Test allow, deny and Settings recovery on hardware. |
| Headset route and interruptions | Prove the active output is the intended Bluetooth headset before warning/calibration playback. On disconnect, call interruption or route change, stop invalid playback and invalidate calibration as appropriate. Never silently play cue audio on the speaker. Prove surviving failure outputs and haptic-only operation rather than assuming Android alarm/vibration behavior carries over. |
| Background, lock and suspension | Android's foreground service/wake locks do not transfer to iOS. Validate legitimate background capabilities and the real session under screen lock, app switching, calls and interruptions, including haptic-only sessions with no audio playback. Never use silent audio to fake ongoing work. Until proven, describe iOS as foreground-only development, not walking-ready parity. Before a known unsupported transition, announce unavailability and stop PINGs so the board watchdog remains effective; after abrupt suspension/termination only the independent board watchdog can be relied on. Re-prove links, route and session calibration on return. |
| Privacy and camera | Prove redaction/model packaging with xtool, permission denial and capture interruption. No unredacted-upload fallback. Validate the labelled phone-camera branch separately; do not invent camera permission requirements for board JPEG input. |
| Phone outputs and power | Validate supported phone vibration patterns, speech priority, battery monitoring and thresholds. Record any hardware limitation explicitly; do not promise identical phone vibration waveforms where hardware differs. Temple haptics still use the same board protocol. |

Apple sources: [local network privacy (TN3179)](https://developer.apple.com/documentation/technotes/tn3179-understanding-local-network-privacy) documents unicast permission rules, multicast entitlements and the requirement to test privacy behavior on a real device; [background execution time](https://developer.apple.com/documentation/uikit/extending-your-app-s-background-execution-time) describes suspension and finite task extensions, not an unlimited UDP hazard service.

## Implementation order and acceptance

1. **Toolchain and hardware spike:** signed SwiftUI screen on a physical iPhone via xtool; demonstrate bidirectional two-board UDP and a verified headset output. Exercise permission denial, headset unplug and lock/background transitions first. Record blockers before building the full UI.
2. **Decision parity:** implement the Swift engine against the same wire vectors and timestamped scenarios as Kotlin. Compare semantic event traces: state/reason, band, cue IDs, gains, haptic commands, deadlines, ACK handling, profile and calibration outcomes. Cover threshold boundaries, invalid/stale ranges, out-of-order/chunked frames, lost boards, missing ACKs, expired cues, route loss, battery critical, pause and haptic-only mode. Keep proposed thresholds tunable and synchronized.
3. **Presentation and features:** implement Main, Judge, Calibration and Settings, then Describe and the labelled camera fallback. Review both apps side by side with equivalent input/state, viewport, light/dark mode and large text; verify VoiceOver/TalkBack completion of each task.
4. **End-to-end release gate:** run both apps against the existing two-board simulator scenarios and then the same physical boards/headset on a staged course with a sighted spotter. Measure range-to-haptic/audio latency and failure announcement timing per platform; do not reuse Android measurements as iOS evidence. Exercise internet loss, board power loss, headset loss, permissions, screen lock/background/resume and app termination/watchdog. No prerecorded results or fabricated timing.

Completion means every parity row and feasibility gate is demonstrated, or a material platform difference is explicitly accepted and documented. A foreground-only prototype, signed scaffold, passing codec tests or matching screenshots alone is not full iOS support. Integrated bone-conduction hardware and the existing sourcing gaps remain unresolved on both platforms; adding iOS does not close them.

## Verification status

This change records the plan only. No iOS package, xtool setup, SDK extraction, signing, device networking, background execution or App Store distribution has been exercised. Existing architecture/brief PDFs and app screenshots remain historical Android/design evidence and are intentionally unchanged.
