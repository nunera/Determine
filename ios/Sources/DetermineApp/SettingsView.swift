#if canImport(SwiftUI)
import DetermineEngine
import SwiftUI

@MainActor
struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var urlDraft = ""
    @State private var keyDraft = ""
    @State private var savedServer = false

    var body: some View {
        Form {
            Section {
                Toggle("Scene descriptions", isOn: Binding(get: { model.describeEnabled }, set: model.setDescribeEnabled))
                Picker("Length", selection: Binding(get: { model.descriptionLength }, set: model.setDescriptionLength)) {
                    ForEach(DescriptionLength.allCases) { Text($0.label).tag($0) }
                }
                Picker("Style", selection: Binding(get: { model.descriptionStyle }, set: model.setDescriptionStyle)) {
                    ForEach(DescriptionStyle.allCases) { Text($0.label).tag($0) }
                }
                Text(lengthHelp).font(.footnote).foregroundStyle(.secondary)
                Text(styleHelp).font(.footnote).foregroundStyle(.secondary)
            } header: { Text("Descriptions") } footer: {
                Text("Answers arrive and are spoken sentence by sentence. They describe the image, never decide warnings or tell you it is safe to walk.")
            }
            Section {
                NavigationLink { SpeechSettingsView() } label: {
                    LabeledContent("Voice & playback", value: model.speechPreferences.provider.label)
                }
                NavigationLink { TranscriptView() } label: { Label("Answer transcript", systemImage: "text.bubble") }
            } footer: { Text("Transcripts stay in memory only. Urgent warnings interrupt all description voices, including cloud speech.") }
            Section {
                Picker("Image source", selection: Binding(get: { model.frameSource }, set: { source in Task { await model.selectFrameSource(source) } })) {
                    Text("Headband camera").tag(FrameSourceKind.camBoard)
                    Text("iPhone camera (fallback)").tag(FrameSourceKind.phoneCamera)
                }
                if model.frameSource == .camBoard {
                    Toggle("Correct mirrored headband image", isOn: Binding(get: { model.headbandMirrorCorrection }, set: model.setHeadbandMirrorCorrection))
                    Text("Applies the same left–right correction to the live image, scene descriptions, and text reading. Enabled for this headband camera by default.")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                if let message = model.cameraMessage { Text(message).foregroundStyle(.red) }
            } header: { Text("Camera") } footer: {
                Text("Changing source restarts a running session. Use Camera on the home screen to check aim. Both sources still need the distance board.")
            }
            Section {
                Toggle("Allow unblurred image uploads", isOn: Binding(get: { model.describeUnredactedOptIn }, set: model.setDescribeUnredactedOptIn))
                TextField("Server URL", text: $urlDraft)
                    .textContentType(.URL).keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .accessibilityLabel("Description server URL")
                SecureField(model.describeKeySet ? "Key saved · Enter to replace" : "Server access key", text: $keyDraft)
                    .textInputAutocapitalization(.never).autocorrectionDisabled().accessibilityLabel("Description server access key")
                Button("Save server settings") {
                    model.setDescribeUrl(urlDraft)
                    if !keyDraft.isEmpty { model.setDescribeKey(keyDraft); keyDraft = "" }
                    savedServer = true
                }
                if savedServer { Label("Server settings saved", systemImage: "checkmark.circle").foregroundStyle(.secondary) }
                if model.describeKeySet { Button("Remove saved access key", role: .destructive) { model.setDescribeKey(""); savedServer = false } }
            } header: { Text("Privacy & server") } footer: {
                Text("Only a request sends an image. Images are not face-blurred and may include bystanders. Uploads require your explicit permission above. The server key stays in Keychain; speech provider keys stay on your server. Local HTTP is for local-network development only.")
            }
            Section {
                Picker("Warning output", selection: Binding(get: { model.soundOutput }, set: model.setSoundOutput)) {
                    ForEach(SoundOutput.allCases, id: \.self) { Text($0.label).tag($0) }
                }
                if model.soundsOn {
                    Picker("Hearing layout", selection: Binding(get: { model.audioLayout }, set: model.setAudioLayout)) {
                        Text(modeName(.stereo)).tag(HearingMode.stereo)
                        Text(modeName(.ssd)).tag(HearingMode.ssd)
                    }
                }
                Toggle("Quick spoken warning words", isOn: Binding(get: { model.spearcons }, set: model.setSpearcons))
                VStack(alignment: .leading, spacing: 8) {
                    LabeledContent("Vibration strength", value: "\(model.hapticMax) of 255")
                    Slider(value: Binding(get: { Double(model.hapticMax) }, set: { model.setHapticMax(Int($0.rounded())) }), in: Double(MIN_HAPTIC_MAX)...255, step: 1)
                        .accessibilityLabel("Temple vibration strength").accessibilityValue("\(model.hapticMax) of 255")
                }
                NavigationLink("Hearing calibration") { CalibrationView() }
            } header: { Text("Warnings") } footer: {
                Text(model.soundOutput == .speaker ? "iPhone speaker output is a debug mode. Sound never silently switches to another output. Calibration is not available on iOS; levels are capped at −10 dBFS." : "Warning audio never silently switches away from your selected output. Vibration cannot be disabled: it is the remaining alert if the phone connection is lost. Choose a strength you can reliably feel.")
            }
            Section {
                Toggle("Listen for ‘Hey Determine’", isOn: Binding(get: { model.wakeWordEnabled }, set: model.setWakeWordEnabled))
                Toggle("Understand other questions with AI", isOn: Binding(get: { model.wakeAIEnabled }, set: model.setWakeAIEnabled))
                if model.wakeWordEnabled { LabeledContent("Listening", value: model.wakeState.label) }
            } header: { Text("Voice control · Experimental") } footer: {
                Text("Wake-word recognition runs on this iPhone and needs iOS 26 and microphone access. Fixed commands stay local. With AI understanding enabled, unmatched words go to your server as text; a scene question can upload an image, including after a misheard phrase. Pausing always reminds you and resumes automatically.")
            }
            Section {
                NavigationLink("Diagnostics & test vibration") { JudgeView() }
                NavigationLink("Experimental warning tuning") {
                    ScreenContent { ExperimentalSettings() }.navigationTitle("Warning tuning")
                }
                NavigationLink("Hotspot setup & connection details") { HotspotHelpView() }
            } header: { Text("Setup & diagnostics") }
        }
        .navigationTitle("Settings").navigationBarTitleDisplayMode(.inline)
        .onAppear { urlDraft = model.describeUrl; model.refreshAddresses() }
    }
    private var lengthHelp: String {
        switch model.descriptionLength {
        case .brief: return "Brief: one short sentence, focused on the most relevant visible detail."
        case .standard: return "Standard: up to two sentences for everyday questions."
        case .detailed: return "Detailed: up to five sentences; may take longer to generate and speak."
        }
    }
    private var styleHelp: String {
        switch model.descriptionStyle {
        case .direct: return "Direct: concise, factual language with no introduction."
        case .conversational: return "Conversational: natural, approachable wording without extra chatter."
        case .spatial: return "Spatial: organize objects by their position in the image, not walking directions."
        }
    }
}

struct SpeechSettingsView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var deviceVoices: [DeviceVoice] = []
    private var preferences: SpeechPreferences { model.speechPreferences }
    var body: some View {
        Form {
            Section {
                Picker("Speech provider", selection: Binding(get: { preferences.provider }, set: selectProvider)) {
                    ForEach(SpeechProvider.allCases) { provider in
                        Text(provider.label).tag(provider)
                    }
                }
                if preferences.provider == .device {
                    Picker("Voice", selection: Binding(get: { preferences.deviceVoice }, set: { value in update { $0.deviceVoice = value } })) {
                        Text("Best installed voice · Automatic").tag("")
                        if !preferences.deviceVoice.isEmpty && !deviceVoices.contains(where: { $0.id == preferences.deviceVoice }) {
                            Text("Previously selected voice · Unavailable").tag(preferences.deviceVoice)
                        }
                        ForEach(deviceVoices) { voice in
                            Text("\(voice.name) · \(voice.language) · \(voice.quality)").tag(voice.id)
                        }
                    }
                    Text("For more natural offline voices, download an Enhanced or Premium voice in iPhone Settings → Accessibility → Read & Speak (Spoken Content on older iOS) → Voices, then return here.").font(.footnote).foregroundStyle(.secondary)
                } else {
                    let provider = model.speechProviders.first { $0.id == preferences.provider.rawValue }
                    if let provider, provider.available {
                        if preferences.provider == .openrouter {
                            Picker("Speech model", selection: Binding(get: { preferences.cloudModel }, set: selectModel)) {
                                if !(provider.models ?? []).contains(where: { $0.id == preferences.cloudModel }) {
                                    Text("Selected model unavailable").tag(preferences.cloudModel)
                                }
                                ForEach(provider.models ?? []) { Text($0.name).tag($0.id) }
                            }
                            Text("Flash models favor quick starts. Compare the voice previews to choose the delivery you prefer.")
                                .font(.footnote).foregroundStyle(.secondary)
                        }
                        Picker("Voice", selection: Binding(get: { preferences.cloudVoice }, set: { value in update { $0.cloudVoice = value } })) {
                            if !cloudVoices.contains(where: { $0.id == preferences.cloudVoice }) { Text("Choose a voice").tag(preferences.cloudVoice) }
                            ForEach(cloudVoices) { Text($0.name).tag($0.id) }
                        }
                    } else {
                        Label(provider?.reason ?? "Load the server’s voice catalog to check availability.", systemImage: "exclamationmark.circle")
                            .font(.callout).foregroundStyle(.secondary)
                    }
                }
                VStack(alignment: .leading, spacing: 8) {
                    LabeledContent("Speaking speed", value: preferences.rate.formatted(.number.precision(.fractionLength(2))) + "×")
                    Slider(value: Binding(get: { preferences.rate }, set: { value in update { $0.rate = value } }), in: 0.7...1.2, step: 0.05)
                        .accessibilityLabel("Description speaking speed")
                }
                Button { model.sampleVoice() } label: { Label("Preview voice", systemImage: "speaker.wave.2") }.disabled(!canPreview)
                Button("Stop preview") { model.stopSpeaking() }
                if let message = model.speechMessage { Text(message).font(.callout).foregroundStyle(.secondary) }
            } header: { Text("Description & reading voice") } footer: {
                Text("This voice is also used for repeat and Read text. Warning words always use on-device speech and take priority. Cloud voices are AI-generated and require an internet connection; the spoken text is sent to the selected provider, not the camera image.")
            }
            Section {
                Button { model.refreshSpeechProviders() } label: {
                    HStack { Text(model.loadingVoices ? "Loading voices…" : "Refresh cloud voices"); if model.loadingVoices { Spacer(); ProgressView() } }
                }.disabled(model.loadingVoices)
                ForEach(model.speechProviders) { provider in
                    VStack(alignment: .leading, spacing: 4) {
                        Label(provider.name, systemImage: provider.available ? "checkmark.circle" : "minus.circle")
                        Text(provider.available ? availabilityLabel(provider) : provider.reason ?? "Unavailable")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            } header: { Text("Cloud provider availability") } footer: {
                Text("OpenRouter uses the server’s existing OPENROUTER_API_KEY. Direct providers optionally use OPENAI_API_KEY or ELEVENLABS_API_KEY. Usage may incur charges. A failed cloud voice never silently switches provider; the answer remains in Transcript. Cloud playback speed is adjusted on this iPhone without changing pitch.")
            }
        }
        .navigationTitle("Voice & playback").navigationBarTitleDisplayMode(.inline)
        .onAppear { deviceVoices = DeviceVoice.installed; model.refreshSpeechProviders() }
        .onChange(of: scenePhase) { _, phase in if phase == .active { deviceVoices = DeviceVoice.installed } }
        .onDisappear { model.stopSpeaking() }
    }
    private var canPreview: Bool {
        if model.running && (model.state == .unavailable || (model.currentAlert?.band ?? .none) >= .mid) { return false }
        return preferences.provider == .device || (model.speechProviders.contains { $0.id == preferences.provider.rawValue && $0.available } && cloudVoices.contains { $0.id == preferences.cloudVoice })
    }
    private var cloudVoices: [CloudVoice] {
        guard let provider = model.speechProviders.first(where: { $0.id == preferences.provider.rawValue }) else { return [] }
        if preferences.provider == .openrouter {
            return provider.models?.first(where: { $0.id == preferences.cloudModel })?.voices ?? []
        }
        return provider.voices
    }
    private func availabilityLabel(_ provider: CloudSpeechProvider) -> String {
        if provider.id == SpeechProvider.openrouter.rawValue { return "Available · \(provider.models?.count ?? 0) speech models" }
        return "Available · \(provider.voices.count) voices"
    }
    private func selectModel(_ modelID: String) {
        update {
            $0.cloudModel = modelID
            $0.cloudVoice = model.speechProviders.first(where: { $0.id == SpeechProvider.openrouter.rawValue })?
                .models?.first(where: { $0.id == modelID })?.voices.first?.id ?? ""
        }
    }
    private func update(_ edit: (inout SpeechPreferences) -> Void) { var next = preferences; edit(&next); model.setSpeechPreferences(next) }
    private func selectProvider(_ provider: SpeechProvider) {
        update {
            $0.provider = provider
            if provider == .openrouter {
                let models = model.speechProviders.first(where: { $0.id == provider.rawValue })?.models ?? []
                let selected = models.first(where: { $0.id == preferences.cloudModel }) ?? models.first
                $0.cloudModel = selected?.id ?? "elevenlabs/eleven-flash-v2.5"
                $0.cloudVoice = selected?.voices.first?.id ?? "george"
            }
            if provider == .openai { $0.cloudVoice = "alloy" }
            if provider == .elevenlabs { $0.cloudVoice = model.speechProviders.first(where: { $0.id == provider.rawValue })?.voices.first?.id ?? "" }
        }
    }
}

struct HotspotHelpView: View {
    @EnvironmentObject private var model: AppModel
    var body: some View {
        Form {
            Section("Personal Hotspot") {
                Text("Turn on Allow Others to Join and Maximize Compatibility in iPhone Settings → Personal Hotspot. Both boards require 2.4 GHz Wi-Fi.")
                Text("The hotspot name and password must match secrets.h in the board firmware. Keep Determine open and allow Local Network access when asked.")
            }
            Section("Connection details") {
                LabeledContent("Listening", value: model.running ? (model.link.bound ? "UDP 47000" : "Port unavailable") : "Stopped")
                LabeledContent("Camera", value: model.link.camIp ?? "Not heard")
                LabeledContent("Distance board", value: model.link.cueIp ?? "Not heard")
                ForEach(model.phoneAddresses, id: \.address) { address in LabeledContent(address.interface, value: address.address) }
                Button("Refresh addresses") { model.refreshAddresses() }
            }
        }.navigationTitle("Hotspot setup").navigationBarTitleDisplayMode(.inline)
    }
}
#endif
