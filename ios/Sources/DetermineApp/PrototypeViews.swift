#if canImport(SwiftUI)
import SwiftUI
import UIKit
import DetermineCore

private let demoNotice = "Interactive demo — synthetic inputs. No boards connected. No warning sounds or uploads."
private let nearYellow = Color(red: 1, green: 0.82, blue: 0)

private func demoState(_ state: PrototypeState) -> String {
    state.label.hasPrefix("Demo:") ? state.label : "Demo: \(state.label)"
}

private struct DemoNotice: View {
    var body: some View {
        Text(demoNotice)
            .font(.headline)
            .fixedSize(horizontal: false, vertical: true)
            .accessibilityAddTraits(.isHeader)
    }
}

private struct SectionTitle: View {
    let title: String
    var body: some View {
        Text(title)
            .font(.title2.bold())
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 8)
            .accessibilityAddTraits(.isHeader)
    }
}

private struct BigAction: View {
    let title: String
    var primary = true
    var enabled = true
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Text(title)
                .font(.title3.bold())
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, minHeight: 72)
                .padding(.horizontal, 12)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(primary ? Color(uiColor: .systemBackground) : Color.primary)
        .background(primary ? Color.primary : Color.clear, in: RoundedRectangle(cornerRadius: 6))
        .overlay {
            RoundedRectangle(cornerRadius: 6)
                .strokeBorder(primary ? Color.clear : Color.primary, lineWidth: 2)
        }
        .disabled(!enabled)
        .opacity(enabled ? 1 : 0.5)
    }
}

private struct DetailLine: View {
    let title: String
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title).font(.subheadline.weight(.medium))
            Text(value).font(.body.weight(.semibold))
                .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .combine)
    }
}

private struct ScreenContent<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                DemoNotice()
                content
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
        }
        .background(Color(uiColor: .systemBackground))
    }
}

private struct StatusPanel: View {
    let session: PrototypeSession

    var body: some View {
        let unavailable = session.running && session.state == .unavailable
        let foreground: Color = unavailable ? .white : .primary
        VStack(alignment: .leading, spacing: 8) {
            Text("Status").font(.headline)
            Text(demoState(session.state))
                .font(.largeTitle.bold())
            if session.running && !session.reason.isEmpty {
                Text(session.reason).font(.body)
            }
        }
        .foregroundStyle(foreground)
        .frame(maxWidth: .infinity, minHeight: 108, alignment: .leading)
        .padding(16)
        .background(unavailable ? Color(red: 0.62, green: 0.07, blue: 0.13) : Color.clear)
        .overlay { Rectangle().strokeBorder(unavailable ? Color(red: 0.62, green: 0.07, blue: 0.13) : Color.primary, lineWidth: 3) }
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Status: \(demoState(session.state)). \(session.running ? session.reason : "")")
        .accessibilityAddTraits(.updatesFrequently)
    }
}

@MainActor
struct MainPrototypeView: View {
    @EnvironmentObject private var model: PrototypeModel

    var body: some View {
        let session = model.session
        ScreenContent {
            StatusPanel(session: session)
            if session.running && !session.headsetReady && session.mode != .hapticOnly {
                Text("No synthetic headset route. Warning sounds are unavailable. The phone speaker is never a fallback.")
                    .font(.headline)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .overlay { Rectangle().strokeBorder(.red, lineWidth: 2) }
            }
            if session.running && !session.paused, let band = session.bandLabel {
                let near = session.distanceMm.map { $0 < 500 } ?? false
                Text(band)
                    .font(.title2.bold())
                    .foregroundStyle(near ? Color.black : Color.primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .background(near ? nearYellow : Color.clear)
                    .overlay { Rectangle().strokeBorder(.primary, lineWidth: 2) }
                    .accessibilityLabel("Synthetic forward warning: \(band)")
            }
            Text("Silence means nothing to report from a working system. It never means the path is clear. Keep using your cane or guide dog.")
                .font(.body)

            if session.running {
                BigAction(title: "Stop Determine", primary: false) { model.stop() }
                BigAction(title: session.paused ? "Resume warnings" : "Pause warnings") {
                    model.setPaused(!session.paused)
                }
            } else {
                BigAction(title: "Start Determine") { model.start() }
            }

            SectionTitle(title: "Demo scenario")
            Text("Choose a synthetic situation. This does not connect to hardware or measure a distance.")
            Picker("Demo scenario", selection: Binding(
                get: { model.session.scenario },
                set: { model.selectScenario($0) }
            )) {
                ForEach(DemoScenario.allCases, id: \.self) { scenario in
                    Text(scenario.label).tag(scenario)
                }
            }
            .pickerStyle(.menu)
            .frame(maxWidth: .infinity, alignment: .leading)

            if session.running && session.describeEnabled {
                BigAction(title: "Request what's ahead?", primary: false) { model.requestDescription() }
                Text("On request only. This demo cannot redact or upload a camera frame; it returns an unavailable result.")
                    .font(.callout)
                if let message = session.describeMessage {
                    Text(message).font(.headline)
                        .accessibilityAddTraits(.updatesFrequently)
                }
            }

            SectionTitle(title: "Hearing profile")
            Text(session.profileSummary).font(.body)
            NavigationLink(destination: CalibrationPrototypeView()) {
                navigationAction("Calibrate hearing — virtual exercise")
            }
            .buttonStyle(.plain)
            NavigationLink(destination: JudgePrototypeView()) {
                navigationAction("Judge diagnostics")
            }
            .buttonStyle(.plain)
            NavigationLink(destination: SettingsPrototypeView()) {
                navigationAction("Settings")
            }
            .buttonStyle(.plain)
        }
        .navigationTitle("Determine")
        .navigationBarTitleDisplayMode(.large)
    }
}

@MainActor
private func navigationAction(_ title: String) -> some View {
    Text(title)
        .font(.title3.bold())
        .multilineTextAlignment(.center)
        .foregroundStyle(.primary)
        .frame(maxWidth: .infinity, minHeight: 72)
        .padding(.horizontal, 12)
        .overlay { RoundedRectangle(cornerRadius: 6).strokeBorder(.primary, lineWidth: 2) }
        .contentShape(Rectangle())
}

@MainActor
private struct JudgePrototypeView: View {
    @EnvironmentObject private var model: PrototypeModel

    var body: some View {
        let session = model.session
        ScreenContent {
            if !session.running {
                Text("Determine is stopped. Start it on the main screen to inspect a synthetic scenario.")
            }
            SectionTitle(title: "Streams")
            DetailLine(title: "State", value: "\(demoState(session.state)) — \(session.reason)")
            DetailLine(title: "Camera board", value: route(session.cameraBoardReady, running: session.running))
            DetailLine(title: "Cue board (forward sensor and temple haptics)", value: route(session.cueBoardReady, running: session.running))
            DetailLine(title: "Headset audio", value: session.mode == .hapticOnly
                       ? "Not required for haptic-only mode; no sound plays"
                       : route(session.headsetReady, running: session.running))
            Text("Board addresses, frame age, heartbeat, signal strength and faults: not measured. No boards are connected.")
                .font(.callout)

            SectionTitle(title: "Forward distance")
            if session.running && session.cueBoardReady {
                Text(session.distanceMm.map { "\($0) mm — synthetic forward input" } ?? "Unknown — no valid forward distance")
                    .font(.title2.bold())
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(16)
                    .background(session.distanceMm.map { $0 < 500 } == true ? nearYellow : Color.clear)
                    .foregroundStyle(session.distanceMm.map { $0 < 500 } == true ? Color.black : Color.primary)
                    .overlay { Rectangle().strokeBorder(.primary, lineWidth: 2) }
                DetailLine(title: "Range status", value: session.rangeStatus)
            } else {
                Text("No forward distance received. Not a clear path.")
            }
            DetailLine(title: "Left", value: "Not measured — one forward sensor only")
            DetailLine(title: "Right", value: "Not measured — one forward sensor only")
            Text("Proposed bands: near below 500 mm, mid 500–899 mm, far 900–1200 mm. Unknown never means clear. Neither side is measured.")
                .font(.callout)

            SectionTitle(title: "Chosen alert")
            Text(session.running && !session.paused
                 ? session.bandLabel.map { "\($0) (no output)" } ?? "No alert — not a clear path"
                 : "No alert — warnings stopped or paused")
                .font(.headline)
            SectionTitle(title: "Latency")
            DetailLine(title: "Capture to cue · last / p50 / p95", value: "Not measured")
            DetailLine(title: "Cue to ACK · p50 / p95", value: "Not measured")
            SectionTitle(title: "Describe and counters")
            DetailLine(title: "Scene description", value: session.describeMessage ?? "Not requested; no redaction or uploads available")
            DetailLine(title: "Cues / acknowledgements / dropped packets", value: "Not measured — no network or output")
        }
        .navigationTitle("Judge diagnostics")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func route(_ active: Bool, running: Bool) -> String {
        guard running else { return "Stopped — no hardware connected" }
        return active ? "Synthetic route available in selected demo; no hardware connected" : "Unavailable in selected demo; no hardware connected"
    }
}

@MainActor
private struct SettingsPrototypeView: View {
    @EnvironmentObject private var model: PrototypeModel

    var body: some View {
        let session = model.session
        ScreenContent {
            SectionTitle(title: "Spoken words")
            Toggle("Spearcons: quick spoken word after each warning sound", isOn: Binding(
                get: { model.session.spearcons }, set: { model.setSpearcons($0) }
            ))
            .font(.body)
            Text("Preference only; this demo makes no warning sounds.").font(.callout)

            SectionTitle(title: "Output mode")
            Picker("Output mode", selection: Binding(
                get: { model.session.mode }, set: { model.setMode($0) }
            )) {
                ForEach(OutputMode.allCases, id: \.self) { mode in
                    Text(mode.label).tag(mode)
                }
            }
            .pickerStyle(.menu)
            DetailLine(title: "Headset", value: session.mode == .hapticOnly
                       ? "Not required for haptic-only warnings"
                       : "Synthetic route \(session.headsetReady ? "selected" : "unavailable"); no headset connected")
            Text("Sounds would use only a headset, never the phone speaker. Calibration requires a synthetic headset route even in haptic-only mode.")
                .font(.callout)

            SectionTitle(title: "Temple vibration strength")
            Text("Maximum requested haptic level. Cannot be turned off: the floor is 1 of 255. No motor is driven, and this setting is not a tested guarantee of felt vibration.")
                .font(.body)
            Slider(value: Binding(
                get: { Double(model.session.hapticMax) },
                set: { model.setHapticMax(Int($0.rounded())) }
            ), in: 1...255, step: 1) {
                Text("Temple vibration strength")
            }
            .accessibilityValue("\(session.hapticMax) of 255, demo preference")
            Text("Strength: \(session.hapticMax) of 255").font(.headline)

            SectionTitle(title: "Describe (what's ahead)")
            Toggle("Scene description on request", isOn: Binding(
                get: { model.session.describeEnabled }, set: { model.setDescribeEnabled($0) }
            ))
            Text("Requests explicitly fail closed: face redaction is unavailable, so there are no camera frames, uploads, or generated descriptions. Warning decisions never depend on Describe.")
                .font(.body)

            SectionTitle(title: "Connections and camera")
            Text("Camera selection, hotspot, board addresses, Describe service URL and API key are unavailable in this interactive prototype. No live connection or phone-camera fallback is implemented.")
                .font(.body)
            Text("Mode, strength, spearcons and Describe choices remain on this device. A demo hearing profile lasts only until Stop or backgrounding; it is never saved.")
                .font(.callout)
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
    }
}

@MainActor
private struct CalibrationPrototypeView: View {
    @EnvironmentObject private var model: PrototypeModel
    @Environment(\.dismiss) private var dismiss
    @State private var weakerChoice = 0
    @State private var levelChoice = 15
    @State private var replayCount = 0

    var body: some View {
        let session = model.session
        ScreenContent {
            if !session.running {
                Text("Start Determine first. This exercise uses only virtual tones; no sound or hearing measurement occurs.")
                    .font(.body)
            } else if let calibration = session.calibration {
                stage(calibration, session: session)
                SectionTitle(title: "Change synthetic routes")
                Picker("Demo scenario during calibration", selection: Binding(
                    get: { model.session.scenario },
                    set: { model.selectScenario($0) }
                )) {
                    ForEach(DemoScenario.allCases, id: \.self) { scenario in
                        Text(scenario.label).tag(scenario)
                    }
                }
                .pickerStyle(.menu)
                .frame(minHeight: 44)
                Text("Change this input to try losing a board or headset before applying the demo profile. No real connection changes.")
                    .font(.callout)
                if calibration.step != .failed {
                    Text("Virtual ceiling: \(calibration.ceilingDb) dBFS. No sound is played. Responses are synthetic and not a hearing test.")
                        .font(.callout)
                }
                if calibration.step != .done && calibration.step != .failed {
                    BigAction(title: "Use haptics only instead", primary: false) { model.chooseHapticOnly() }
                    BigAction(title: "Cancel calibration", primary: false) { leave() }
                }
            } else {
                intro(session)
            }
        }
        .navigationTitle("Hearing calibration")
        .navigationBarTitleDisplayMode(.inline)
        .onDisappear {
            // System Back, edge swipe and a background stop all discard unfinished per-session answers.
            if model.session.calibration != nil { model.cancelCalibration() }
        }
    }

    @ViewBuilder
    private func intro(_ session: PrototypeSession) -> some View {
        SectionTitle(title: "Virtual calibration")
        Text("Explore the Android fit → threshold → level → balance → side-check sequence using labelled synthetic stimuli. No tone is played; your taps only advance a demo profile. This cannot test or restore hearing. Seek an audiologist for hearing concerns.")
        Text("While the exercise is open, demo warnings pause. They resume to their previous pause setting when you apply or cancel.")
        if !session.headsetReady || !session.cueBoardReady || !session.cameraBoardReady {
            Text("Choose a demo scenario with synthetic board and headset routes before starting. No headset or phone speaker will play sound.")
                .font(.headline)
        }
        BigAction(title: "Start virtual calibration", enabled: session.headsetReady && session.cueBoardReady && session.cameraBoardReady) {
            weakerChoice = 0
            levelChoice = 15
            replayCount = 0
            model.beginCalibration()
        }
        if !session.headsetReady {
            Text("Return to the main screen to choose Far, Mid, Near or Unknown as the demo scenario. Calibration needs the virtual headset route, even for haptic-only mode.")
                .font(.callout)
        }
    }

    @ViewBuilder
    private func stage(_ calibration: PrototypeCalibration, session: PrototypeSession) -> some View {
        switch calibration.step {
        case .intro:
            intro(session)
        case .fit:
            SectionTitle(title: "Step 1 of 5: fit")
            Text("Imagine seating the headset as worn. Note a weaker side if you wish; this choice does not alter virtual thresholds and is not a hearing assessment.")
            Picker("Weaker side (synthetic)", selection: $weakerChoice) {
                Text("Neither or not sure").tag(0)
                Text("Left").tag(1)
                Text("Right").tag(2)
            }
            .pickerStyle(.menu)
            BigAction(title: "Continue to virtual thresholds") {
                model.fitCalibration(weakerEar: weakerChoice == 1 ? .left : weakerChoice == 2 ? .right : nil)
            }
        case .threshold:
            SectionTitle(title: "Step 2 of 5: virtual threshold")
            Text("Synthetic stimulus: \(calibration.ear.label) side · \(calibration.frequencyHz) Hz · \(calibration.levelDb) dBFS. No tone is played.")
                .font(.title3.bold())
            Text("Choose a simulated response to advance the demo staircase. ‘Not heard’ is a choice, never inferred from a failed route.")
            BigAction(title: "Mark virtual tone heard") { model.answerHeard(true) }
            BigAction(title: "Mark virtual tone not heard", primary: false) { model.answerHeard(false) }
            replayAction(for: calibration)
        case .level:
            SectionTitle(title: "Step 3 of 5: virtual level")
            Text("Choose 10–20 dB above the synthetic threshold. This changes only the demo result and never plays sound.")
            Stepper("Virtual sensation level: \(levelChoice) dB", value: $levelChoice, in: 10...20)
                .font(.headline)
            BigAction(title: "Continue to balance") { model.setSensationLevel(levelChoice) }
        case .balance:
            SectionTitle(title: "Step 4 of 5: virtual balance")
            Text("Synthetic left/right alternation, with no audio. Set the demo balance, then confirm twice. Check \(min(calibration.balanceConfirms + 1, 2)) of 2.")
            Text(balanceText(calibration.balanceDb)).font(.headline)
            BigAction(title: "Make virtual left side louder") { model.nudgeBalance(-2) }
            BigAction(title: "Make virtual right side louder") { model.nudgeBalance(2) }
            replayAction(for: calibration)
            BigAction(title: "Confirm virtual balance") { model.confirmBalance() }
        case .verify:
            SectionTitle(title: "Step 5 of 5: virtual side check")
            Text("Trial \(min(calibration.verifyTrials + 1, 5)) of 5 · \(calibration.verifyCorrect) correct so far. Synthetic indicator: \(calibration.verificationEar.label) side. No sound is played.")
                .font(.title3.bold())
            Text("Use the visible synthetic indicator to choose a side. This cannot validate actual hearing.")
            BigAction(title: "Choose left") { model.answerSide(.left) }
            BigAction(title: "Choose right") { model.answerSide(.right) }
            replayAction(for: calibration)
        case .done:
            SectionTitle(title: "Virtual calibration finished")
            Text("Demo profile: \(calibration.resultMode?.label ?? "not available"). \(calibration.verifyCorrect) of \(calibration.verifyTrials) synthetic side checks matched the indicator. No hearing ability was measured.")
                .font(.body)
            Text("The forward sensor cannot identify left or right. Discard the demo profile when you stop or background the app.")
            BigAction(title: "Use this demo profile", enabled: calibration.resultMode != nil) {
                model.applyCalibration()
                if model.session.calibration == nil { dismiss() }
            }
            BigAction(title: "Discard virtual calibration", primary: false) { leave() }
        case .failed:
            SectionTitle(title: "Virtual calibration stopped")
            Text(calibration.failureReason ?? "The synthetic route is unavailable; no profile was saved.")
                .font(.headline)
            Text("No hearing profile was saved. Previous answers cannot be reused.")
            if !session.headsetReady || !session.cueBoardReady || !session.cameraBoardReady {
                Text("Use Change synthetic routes below to restore a demo board and headset, then restart this exercise.")
                    .font(.callout)
            }
            BigAction(title: "Restart virtual calibration", enabled: session.running && session.headsetReady && session.cueBoardReady && session.cameraBoardReady) {
                replayCount = 0
                model.beginCalibration()
            }
            BigAction(title: "Cancel calibration", primary: false) { leave() }
        }
    }

    @ViewBuilder
    private func replayAction(for calibration: PrototypeCalibration) -> some View {
        BigAction(title: "Show virtual stimulus again", primary: false) {
            model.replayCalibration()
            replayCount += 1
            let detail: String
            switch calibration.step {
            case .threshold:
                detail = "\(calibration.ear.label) side, \(calibration.frequencyHz) hertz, \(calibration.levelDb) virtual dBFS."
            case .balance:
                detail = "\(calibration.ear.label) side, \(calibration.levelDb) virtual dBFS. \(balanceText(calibration.balanceDb))."
            case .verify:
                detail = "\(calibration.verificationEar.label) side, \(calibration.levelDb) virtual dBFS."
            default:
                detail = "No virtual stimulus is active."
            }
            UIAccessibility.post(notification: .announcement, argument: "Synthetic stimulus, no sound. \(detail)")
        }
        if replayCount > 0 {
            Text("Virtual replay requests this exercise: \(replayCount). Current indicator remains visible; no sound was played.")
                .font(.callout)
                .accessibilityAddTraits(.updatesFrequently)
        }
    }

    private func balanceText(_ value: Int) -> String {
        if value < 0 { return "Virtual balance: left louder by \(-value) dB" }
        if value > 0 { return "Virtual balance: right louder by \(value) dB" }
        return "Virtual balance: centred"
    }

    private func leave() {
        model.cancelCalibration()
        dismiss()
    }
}
#endif
