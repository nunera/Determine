#if canImport(SwiftUI)
import DetermineEngine
import SwiftUI

@MainActor
struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var strengthDraft: Double = 255
    @State private var describeUrlDraft = ""
    @State private var describeKeyDraft = ""

    var body: some View {
        ScreenContent {
            SectionTitle(title: "Spoken words")
            Toggle("Spearcons: quick spoken word after each warning", isOn: Binding(
                get: { model.spearcons }, set: { model.setSpearcons($0) }
            ))
            .font(.body)

            SectionTitle(title: "Warning sounds")
            Picker("Warning sounds output", selection: Binding(
                get: { model.soundOutput }, set: { model.setSoundOutput($0) }
            )) {
                ForEach(SoundOutput.allCases, id: \.self) { output in
                    Text(output.label).tag(output)
                }
            }
            .pickerStyle(.menu)
            switch model.soundOutput {
            case .off:
                Text("Warnings reach you as vibration in the glasses temples. The phone speaks the words and vibrates only for failures and spearcons. No warning sound plays.")
                    .font(.callout)
            case .headset:
                Callout(text: "Warning sounds play to a Bluetooth headset only (A2DP, LE Audio or hands-free). If none is connected or it drops, Determine reports system off (headset route lost); sound never falls back to the phone speaker.", color: .primary)
            case .speaker:
                Callout(text: "Debug: warning sounds play through the iPhone speaker so the cues can be tried without the headset. Not the product configuration and untested on a device. Connected Bluetooth or wired audio keeps iOS from using the speaker; Determine then reports system off.", color: .orange)
            }
            if model.soundsOn {
                Picker("Sound layout", selection: Binding(
                    get: { model.audioLayout }, set: { model.setAudioLayout($0) }
                )) {
                    Text(modeName(.stereo)).tag(HearingMode.stereo)
                    Text(modeName(.ssd)).tag(HearingMode.ssd)
                }
                .pickerStyle(.menu)
                Text("Uncalibrated default levels are used (hearing calibration is not available on iOS yet), never louder than -10 dBFS.")
                    .font(.callout)
                if model.running {
                    DetailLine(title: "Current state", value: "\(StateText.title(model.state)) (\(StateText.reason(model.reason)))")
                    DetailLine(title: "Audio route now", value: model.sound.route)
                }
            }

            SectionTitle(title: "Temple vibration strength")
            Text("Strongest vibration the glasses temples may use for warnings. It cannot be turned off: if the phone connection is lost the temples are the only alert left. The weakest setting (\(MIN_HAPTIC_MAX)) is not a tested guarantee that you will feel it, so pick a strength you can feel reliably.")
                .font(.body)
            Slider(value: $strengthDraft, in: Double(MIN_HAPTIC_MAX)...255, step: 1) {
                Text("Temple vibration strength")
            } onEditingChanged: { editing in
                if !editing { model.setHapticMax(Int(strengthDraft.rounded())) }
            }
            .accessibilityValue("\(Int(strengthDraft.rounded())) of 255")
            Text("Strength: \(Int(strengthDraft.rounded())) of 255").font(.headline)

            SectionTitle(title: "Voice: \"Hey Determine\" (experimental)")
            Toggle("Listen for \"Hey Determine\"", isOn: Binding(
                get: { model.wakeWordEnabled }, set: { model.setWakeWordEnabled($0) }
            ))
            Text("While Determine runs, it listens on the iPhone's own microphone, never the headset's, for \"Hey Determine\" and then: what's ahead, read text, pause warnings, resume warnings, stop. Everything is recognised on this iPhone and nothing is recorded or sent. Needs iOS 26 and microphone access; the first use downloads Apple's speech model. It costs battery and can mishear in traffic or wind, and a pause always reminds you and resumes by itself. It never starts Determine and never changes what you are warned about.")
                .font(.body)
            Toggle("Understand other questions with AI", isOn: Binding(
                get: { model.wakeAIEnabled }, set: { model.setWakeAIEnabled($0) }
            ))
            Text("Off by default. When on, words after \"Hey Determine\" that match no fixed command are sent, as text only, to your describe server, which decides whether they are one of the fixed commands or a question about what the camera sees (\"is the door open?\"). A question uploads one camera frame, with faces unblurred, the same as What's ahead, so it needs Describe set up below and the unblurred-upload switch on. A mis-heard phrase can therefore send one frame. Audio is never sent.")
                .font(.callout)
            if model.wakeWordEnabled, model.running {
                DetailLine(title: "Voice listening", value: model.wakeState.label)
            }

            SectionTitle(title: "Describe (what's ahead)")
            Toggle("Scene description on request", isOn: Binding(
                get: { model.describeEnabled }, set: { model.setDescribeEnabled($0) }
            ))
            Text("Only when you ask, never automatically. Describe sends one camera frame to your describe server and speaks its answer. Warning decisions never depend on it.")
                .font(.body)
            Toggle("Upload frames with faces unblurred", isOn: Binding(
                get: { model.describeUnredactedOptIn }, set: { model.setDescribeUnredactedOptIn($0) }
            ))
            Callout(text: "This iPhone build has no face blurring. Describe uploads the frame as the camera saw it, including the faces of people near you, and works only while this is on. It is off by default; with it off nothing is sent.", color: .primary)
            TextField("Server URL (https://… or http://<laptop-ip>:8787)", text: $describeUrlDraft)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .keyboardType(.URL)
                .textContentType(.URL)
                .onSubmit { model.setDescribeUrl(describeUrlDraft) }
            SecureField(model.describeKeySet ? "Key saved (type to replace)" : "Server key", text: $describeKeyDraft)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .onSubmit { saveDescribeKey() }
            Button("Save Describe server") {
                model.setDescribeUrl(describeUrlDraft)
                saveDescribeKey()
            }
            if model.describeKeySet {
                Button("Remove saved key", role: .destructive) { model.setDescribeKey("") }
            }
            Text("The key is kept in this iPhone's Keychain. Plain http works only for addresses on your local network.")
                .font(.callout)

            SectionTitle(title: "Camera (software fallback)")
            Picker("Camera", selection: Binding(
                get: { model.frameSource },
                set: { choice in Task { await model.selectFrameSource(choice) } }
            )) {
                Text("Glasses camera board (ESP32-CAM)").tag(FrameSourceKind.camBoard)
                Text("iPhone camera on a headband").tag(FrameSourceKind.phoneCamera)
            }
            .pickerStyle(.menu)
            if let message = model.cameraMessage {
                Callout(text: message)
            }
            Text("The iPhone camera is a software fallback, not the proposed glasses. Wear the phone upright in portrait with the back camera facing forward. Frames stay on the phone. Changing the camera restarts Determine. The forward distance sensor on the cue board is always required.")
                .font(.callout)

            ExperimentalSettings()

            SectionTitle(title: "Personal Hotspot and boards")
            VStack(alignment: .leading, spacing: 8) {
                Text("1. On the iPhone open Settings → Personal Hotspot and turn on Allow Others to Join.")
                Text("2. Turn on Maximize Compatibility. The boards' ESP32 radios are 2.4 GHz only, and this setting forces the hotspot onto 2.4 GHz.")
                Text("3. The hotspot name and password must equal DT_WIFI_SSID and DT_WIFI_PASS in firmware/common/include/secrets.h. The hotspot name is the iPhone's name (Settings → General → About → Name); reflash the boards if you change either.")
                Text("4. Keep this app open with the screen on. The boards send to the hotspot gateway (this iPhone) on UDP port 47000. iOS asks once to allow Local Network access: allow it; Determine keeps running behind the prompt.")
            }
            .font(.body)
            DetailLine(
                title: "This iPhone's addresses",
                value: model.phoneAddresses.isEmpty
                    ? "none"
                    : model.phoneAddresses.map { "\($0.interface) \($0.address)" }.joined(separator: ", ")
            )
            DetailLine(
                title: "Listening",
                value: !model.running ? "stopped" : (model.link.bound ? "UDP 47000" : "FAILED: \(model.link.bindError ?? "unknown")")
            )
            DetailLine(title: "Camera board", value: model.link.camIp.map { "\($0), port 47002" } ?? "not heard")
            DetailLine(title: "Cue board", value: model.link.cueIp.map { "\($0), port 47001" } ?? "not heard")
            Text("Warning sound output, strength, spearcons, camera, Describe and tuning choices remain on this device.")
                .font(.callout)
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            strengthDraft = Double(model.hapticMax)
            describeUrlDraft = model.describeUrl
            model.refreshAddresses()
        }
    }

    private func saveDescribeKey() {
        guard !describeKeyDraft.isEmpty else { return }
        model.setDescribeKey(describeKeyDraft)
        describeKeyDraft = ""
    }
}
#endif
