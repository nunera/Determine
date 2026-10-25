# Determine — native iOS interactive prototype

A SwiftUI translation of the Android Main, Judge diagnostics, Calibration and Settings workflows, targeting iPhone / iOS 17+. Large outlined controls, high-contrast status and near-warning panels, the same safety disclaimer, native back navigation and Dynamic Type preserve the Android app's structure without recreating Android system chrome.

**This is an interactive software demo, not a working wearable controller or hearing test.** Every screen labels its synthetic inputs. There is no UDP connection, audio playback, temple vibration, camera capture or cloud upload. “Demo: Ready” describes only the selected synthetic scenario. Do not use it while walking.

## Unsigned IPA

Built artifact: `xtool/DetermineApp.ipa`. A delivery copy is in `~/Downloads/Determine-unsigned.ipa`.
The archive contains an arm64 iOS executable targeting iOS 17+, with no Mach-O code signature, `_CodeSignature` resources or embedded provisioning profile. ZIP integrity and bundle metadata were checked. No Apple signing account or device was used. Installation through a third-party loader and runtime behavior on a phone remain untested.

`xtool.yml` selects the `DetermineApp` library product and `org.determine.prototype` bundle identifier. Display name: **Determine Demo**. No background modes or camera/network permission requests are declared because this prototype performs none of those operations.

### Rebuild on this Linux workstation

The native build uses xtool **1.22.2**, Swift **6.4.1**, and a slim Darwin SDK extracted from the user's licensed **Xcode 27.1** archive. `Containerfile` pins the official Swift image digest and adds `zip`, which xtool needs to create an IPA. Swift 6.2.4 from Nix remains sufficient for the separate Linux model smoke, not this native SDK.

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

Do **not** add `--sign`. `script` supplies a real pseudo-terminal for xtool's SwiftNIO process handling; non-TTY invocations hit `epoll_ctl` errors here. The persistent mount must match the container's passwd home (`/home/ubuntu` for UID 1000 in this image); setting only `HOME` left the SDK in a disposable container.

The cache already contains the SDK and pinned xtool AppImage on this workstation. For a new machine, obtain the AppImage from the [1.22.2 release](https://github.com/xtool-org/xtool/releases/tag/1.22.2), verify SHA-256 `e3176cdf861f58b7f135c755c7bfaec92d0568ef826027b4e5e3d82b3219ddfe`, and put it at the cache path above. Mount a licensed Xcode archive read-only into the same container and run `xtool sdk install /downloads/Xcode_27.1.xip --slim` once, with the same persistent home. Follow the official [xtool Linux setup](https://xtool.sh/documentation/xtooldocs/installation-linux) for SDK prerequisites. Unsigned building requires neither `xtool auth` nor a connected iPhone.

## Interactive walkthrough

1. Tap **Start Determine**. With the default **No hardware** input, the status is unavailable, never ready.
2. Choose **Far**, **Mid** or **Near** under **Demo scenario** to inspect synthetic warnings at 1050, 700 and 350 mm. **Unknown** removes the distance and enters degraded state; lost-board and lost-headset scenarios demonstrate failure gating.
3. Pause/resume, or open **Judge diagnostics**. Only synthetic range/state is displayed; addresses, timing, counters and heartbeat measurements explicitly say not measured.
4. In **Settings**, change output mode or temple-strength preference. Strength stays in 1–255. The headset-loss scenario becomes usable for haptic-only demo warnings, but virtual calibration still requires a synthetic headset route.
5. Open **Calibrate hearing — virtual exercise** with a synthetic board/headset scenario selected. Advance fit, the three-frequency staircase for each ear, sensation level, two balance confirmations and five side checks. No sound plays: choose simulated heard/not-heard responses and use the visible virtual side indicator. Apply/discard changes the in-memory demo profile and restores the previous pause setting.
6. Enable the Describe preference, then request **what's ahead?** on Main. The request explicitly refuses because capture/redaction is absent; no image is sent and no answer is fabricated.
7. Stop or background the app. The session and demo profile are discarded; it never resumes itself. Mode, strength and the two feature preferences persist locally, but calibration and scenario do not persist across launches.

During calibration, use **Change synthetic routes** to disconnect a virtual board/headset, including after the result is finished but before applying. The exercise fails, discards that result and requires a fresh run; restoring the route does not salvage earlier answers.

## Layout

| Path | Role |
|---|---|
| `Sources/DetermineApp/` | SwiftUI screens, native navigation, VoiceOver labels/replay announcements, preferences and lifecycle host. |
| `Sources/DetermineCore/PrototypeSession.swift` | Foundation-only synthetic workflow model, warning/failure gates and translated staircase/calibration behavior. No I/O or hardware decisions. |
| `Sources/DetermineSmoke/main.swift` | Executable assertions that exercise the model through its public interface. |
| `Support/Info.plist`, `xtool.yml` | iPhone bundle metadata and xtool packaging. |

The model is intentionally named `PrototypeSession`: it is not the Kotlin hazard `Engine`, wire codec or a hardware-ready Swift equivalent. The full implementation and conformance requirements remain in the [iOS parity plan](../docs/ios-plan.md).

## Verified on this workstation

Run from `ios/`:

```sh
nix-shell -p swift swiftpm --run 'swift run DetermineSmoke'
nix-shell -p swift swiftpm --run 'swift build'
nix-shell -p swift swiftpm --run 'swiftc -frontend -parse -target arm64-apple-ios17.0 Sources/DetermineApp/DetermineApp.swift Sources/DetermineApp/PrototypeViews.swift'
```

On this NixOS workstation, `nix-shell` is important: it runs the Swift package setup hooks. A plain `nix shell ...` invocation found the compiler but failed to link Swift runtime libraries while evaluating the manifest.

- **Passed:** Swift 6.2.4 Linux build and smoke executable covering no-hardware startup; synthetic range/failure cases; pause; haptic-only headset exception; calibration staircase, ceiling, balance and side-check outcomes; failure invalidation before apply; prior-pause restoration; stop/background reset; fail-closed Describe.
- **Passed:** source parser command above. Parsing does not type-check or render SwiftUI.
- **Passed:** native arm64 iOS release compilation/linking and unsigned IPA packaging with Swift 6.4.1 + xtool 1.22.2. Archive integrity, app payload, executable architecture, bundle identifier and absence of signing artifacts were inspected.
- **Not verified:** bundle installation, native layout, dark/large-text rendering, VoiceOver interaction or physical iPhone lifecycle behavior. Compilation is not visual or runtime proof.
- **Not implemented:** live wire-v2 networking, clock sync, freshness, ACK/deadline handling, continuous hazard scheduling, headset audio/routing, real calibration, failure outputs, battery monitoring, camera/face redaction and Describe networking. No physical timing or safety claims.

Before expanding to live mode, pass the [parity plan's device gates](../docs/ios-plan.md#ios-feasibility-gates). Before calling the UI visually verified, build on an Apple SDK and inspect an iPhone capture in light/dark appearance and large text; complete the flow with VoiceOver.

## Sources

- [xtool project template](https://github.com/xtool-org/xtool/blob/1.22.2/Sources/XToolSupport/NewCommand.swift) and [bundle schema](https://github.com/xtool-org/xtool/blob/1.22.2/Sources/PackLib/PackSchema.swift).
- [Swift package manifest reference](https://docs.swift.org/package-manager/PackageDescription/PackageDescription.html).
- [Apple NavigationStack](https://developer.apple.com/documentation/swiftui/navigationstack).
- Existing Android [screens](../android/app/src/main/kotlin/org/determine/android/ui/) and Kotlin [calibration algorithms](../phone/core/src/main/kotlin/org/determine/core/hearing/).
