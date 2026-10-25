#if canImport(SwiftUI)
import DetermineEngine
import SwiftUI

private struct StatusPanel: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        let failing = model.running && model.state == .unavailable
        let title = model.running ? StateText.title(model.state) : StateText.stopped
        let reason = model.running ? StateText.reason(model.reason) : ""
        VStack(alignment: .leading, spacing: 8) {
            Text("Status").font(.headline)
            Text(title).font(.largeTitle.bold())
            if !reason.isEmpty { Text(reason).font(.body) }
        }
        .foregroundStyle(failing ? Color.white : Color.primary)
        .frame(maxWidth: .infinity, minHeight: 108, alignment: .leading)
        .padding(16)
        .background(failing ? failureRed : Color.clear)
        .overlay { Rectangle().strokeBorder(failing ? failureRed : Color.primary, lineWidth: 3) }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Status: \(title). \(reason)")
        .accessibilityAddTraits(.updatesFrequently)
        .accessibilityAction(named: "Read text") { model.readText() }
    }
}

@MainActor
struct MainView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        ScreenContent {
            StatusPanel()

            if let notice = model.stopNotice, !model.running {
                Callout(text: notice)
            }

            // Sound is opt-in. With the headset chosen the phone never falls back to its speaker: no Bluetooth route
            // means system off. The speaker choice is a labelled debug aid. Haptic only needs neither.
            if model.soundOutput == .headset {
                Callout(text: "Warning sounds go to a Bluetooth headset only. If none is connected Determine reports system off (headset route lost) and the phone speaker is never used.")
                    .accessibilityAddTraits(.updatesFrequently)
            }
            if model.soundOutput == .speaker {
                Callout(text: "Debug: warning sounds play through the iPhone speaker. This is not the product configuration. Connected Bluetooth or wired audio keeps iOS from using the speaker; Determine then reports system off.", color: .orange)
            }

            // Informational only: a weak link never changes the system state.
            if model.running, model.diagnostics?.linkWeak == true {
                Callout(
                    text: "Weak connection to the glasses\(model.diagnostics?.cue.rssiDbm.map { " (\($0) dBm)" } ?? ""). Warnings may be late or lost. Move the phone closer to the glasses.",
                    color: .orange
                )
                .accessibilityAddTraits(.updatesFrequently)
            }

            if model.running, model.frameSource == .phoneCamera, let error = model.camera?.error {
                Callout(text: "iPhone camera: \(error). Without fresh camera frames Determine reports system off.")
                    .accessibilityAddTraits(.updatesFrequently)
            }

            // Nothing is shown when there is no warning: silence is not a clear path and the app never claims one.
            if model.running, let alert = model.currentAlert, alert.band != .none {
                let near = alert.band == .near
                Text(describeAlert(alert))
                    .font(.title2.bold())
                    .foregroundStyle(near ? Color.black : Color.primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .background(near ? nearYellow : Color.clear)
                    .overlay { Rectangle().strokeBorder(.primary, lineWidth: 2) }
                    .accessibilityLabel("Current warning: \(describeAlert(alert))")
            }

            Text("Silence means nothing to report from a working system. It never means the path is clear. Keep using your cane or guide dog.")
                .font(.body)

            if model.running {
                BigAction(title: "Stop Determine", primary: false) { model.stop() }
                BigAction(title: model.paused ? "Resume warnings" : "Pause warnings") {
                    model.setPaused(!model.paused)
                }
                Text("Keep this app open with the screen on. Leaving it stops Determine.")
                    .font(.callout)
            } else {
                BigAction(title: "Start Determine", enabled: !model.stopping) { model.start() }
            }

            // Display-only OCR of the newest camera frame. Never changes a warning.
            if model.running {
                BigAction(title: model.reader == .reading ? "Stop reading" : "Read text", primary: false) { model.readText() }
                switch model.reader {
                case .idle:
                    Text("Reads the text the camera sees, aloud. It can be wrong and never affects warnings.")
                        .font(.callout)
                case .reading:
                    Text("Reading the camera image…")
                        .font(.headline)
                        .accessibilityAddTraits(.updatesFrequently)
                case let .done(text):
                    Text(text)
                        .font(.headline)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                        .accessibilityAddTraits(.updatesFrequently)
                }
            }

            Toggle(isOn: Binding(
                get: { model.tuning.exploreEnabled },
                set: { model.setTuningFlag(\.exploreEnabled, $0) }
            )) {
                Text("Exploration mode (experimental)").font(.headline)
            }
            if model.tuning.exploreEnabled {
                Text("Experimental: instead of band cues the temples pulse steadily, stronger the closer the nearest thing ahead is, within \(model.tuning.exploreMaxMm) mm. Silence never means the path is clear.")
                    .font(.callout)
            }

            if model.running, model.wakeWordEnabled {
                Text(model.wakeState == .listening ? "Listening for \"Hey Determine\"." : "Voice: \(model.wakeState.label).")
                    .font(.callout)
                    .accessibilityAddTraits(.updatesFrequently)
            }

            if model.running && model.describeEnabled {
                BigAction(title: model.describeState == .requesting ? "Cancel description" : "What's ahead?", primary: false) { model.requestDescription() }
                switch model.describeState {
                case .idle:
                    Text("On request only. Sends one camera frame to your describe server, with faces unblurred if you turned that on in Settings. It can be wrong and never affects warnings.")
                        .font(.callout)
                case .requesting:
                    Text("Asking the describe server…")
                        .font(.headline)
                        .accessibilityAddTraits(.updatesFrequently)
                case let .done(text):
                    Text(text)
                        .font(.headline)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .textSelection(.enabled)
                        .accessibilityAddTraits(.updatesFrequently)
                }
            }

            SectionTitle(title: "Output")
            Text("\(model.soundsOn ? "\(model.soundOutput.label), \(modeName(model.mode))" : modeName(model.mode)). Camera: \(model.frameSource == .phoneCamera ? "iPhone camera (software fallback)" : "glasses camera board").")
                .font(.body)
            Text(model.soundsOn
                ? "Hearing profile: not calibrated; default quiet levels. Hearing calibration is not available on iOS yet."
                : "Warning sounds are off: warnings are temple vibration only.")
                .font(.body)
            NavigationLink(destination: CalibrationView()) {
                navigationAction("Calibrate hearing")
            }
            .buttonStyle(.plain)
            NavigationLink(destination: JudgeView()) {
                navigationAction("Judge diagnostics")
            }
            .buttonStyle(.plain)
            NavigationLink(destination: SettingsView()) {
                navigationAction("Settings")
            }
            .buttonStyle(.plain)
        }
        .navigationTitle("Determine")
        .navigationBarTitleDisplayMode(.large)
    }
}
#endif
