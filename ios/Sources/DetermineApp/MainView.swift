#if canImport(SwiftUI)
import DetermineEngine
import SwiftUI

@MainActor
struct MainView: View {
    @EnvironmentObject private var model: AppModel
    @State private var showPreview = false
    @State private var showAlignment = false
    private var canAsk: Bool { model.running && model.state != .unavailable && (model.currentAlert?.band ?? .none) < .mid }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 24) {
                status
                if let notice = model.stopNotice, !model.running { Callout(text: notice) }
                if model.running, model.diagnostics?.linkWeak == true {
                    Callout(text: "Weak connection. Move your phone closer to the headband; warnings may be delayed or lost.", color: .orange)
                }
                if let alert = model.currentAlert, model.running, alert.band != .none {
                    Label(describeAlert(alert), systemImage: "exclamationmark.triangle.fill")
                        .font(.title2.bold()).padding(20).frame(maxWidth: .infinity, alignment: .leading)
                        .foregroundStyle(Color.black).background(nearYellow, in: RoundedRectangle(cornerRadius: 12))
                        .accessibilityLabel("Current warning: \(describeAlert(alert))")
                }
                actions
                ConnectionPanel()
                ProductSection(title: "Camera") {
                    Toggle("Show live preview", isOn: $showPreview)
                    if showPreview {
                        CameraPreview().clipShape(RoundedRectangle(cornerRadius: 12))
                        Button { showAlignment = true } label: { Label("Open alignment view", systemImage: "viewfinder") }
                            .frame(minHeight: 44)
                    } else {
                        Text("Check camera aim without starting another video stream. The preview stays on this iPhone.").font(.subheadline).foregroundStyle(.secondary)
                    }
                }
                ProductSection(title: "Latest answer") {
                    if model.describeState == .requesting {
                        HStack { ProgressView(); Text(model.requestPhase.isEmpty ? "Asking" : model.requestPhase).font(.subheadline) }
                            .accessibilityElement(children: .combine)
                    }
                    if !model.streamedDescription.isEmpty && model.describeState == .requesting {
                        Text(model.streamedDescription).textSelection(.enabled)
                    } else if let entry = model.transcript.last {
                        Text(entry.text).textSelection(.enabled)
                        if entry.interrupted { Text("Interrupted · Partial answer").font(.caption).foregroundStyle(.secondary) }
                    } else {
                        Text("Ask what’s ahead or read visible text. Your answers will appear here.").foregroundStyle(.secondary)
                    }
                    if case let .done(message) = model.describeState, message != model.transcript.last?.text {
                        Text(message).font(.callout).foregroundStyle(.secondary)
                    }
                    if case let .done(message) = model.reader, message != model.transcript.last?.text {
                        Text(message).font(.callout).foregroundStyle(.secondary)
                    }
                    if let message = model.speechMessage { Label(message, systemImage: "speaker.slash").font(.callout).foregroundStyle(.secondary) }
                    ViewThatFits(in: .horizontal) {
                        HStack(spacing: 24) { answerControls }
                        VStack(alignment: .leading, spacing: 12) { answerControls }
                    }
                }
                if model.running {
                    ProductSection(title: "Session") {
                        Button { model.setPaused(!model.paused) } label: {
                            Label(model.paused ? "Resume warnings" : "Pause warnings", systemImage: model.paused ? "play" : "pause")
                        }.frame(minHeight: 44)
                        Toggle("Exploration mode", isOn: Binding(get: { model.tuning.exploreEnabled }, set: { model.setTuningFlag(\.exploreEnabled, $0) }))
                        if model.tuning.exploreEnabled {
                            Text("Experimental: vibration strength follows forward distance. Silence never means clear.").font(.callout).foregroundStyle(.secondary)
                        }
                        if model.wakeWordEnabled { Label(model.wakeState.label, systemImage: "mic").font(.callout) }
                        Button("Stop Determine", role: .destructive) { model.stop() }.frame(minHeight: 44)
                    }
                }
                Text("Keep using your cane or guide dog. Silence never means the path is clear. Keep this app open while Determine runs.")
                    .font(.footnote).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }.padding(20)
        }
        .background(Color(uiColor: .systemGroupedBackground))
        .navigationTitle("Determine")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                NavigationLink { SettingsView() } label: { Image(systemName: "gearshape").accessibilityLabel("Settings") }
            }
        }
        .fullScreenCover(isPresented: $showAlignment) { CameraAlignmentView().environmentObject(model) }
        .onChange(of: showPreview) { _, enabled in model.setPreviewEnabled(enabled) }
        .onAppear { model.setPreviewEnabled(showPreview) }
        .onDisappear { if !showAlignment { model.setPreviewEnabled(false) } }
        .onChange(of: showAlignment) { _, showing in if !showing { model.setPreviewEnabled(showPreview) } }
    }

    private var status: some View {
        VStack(alignment: .leading, spacing: 12) {
            Label(model.running ? StateText.title(model.state) : "Ready when you are",
                  systemImage: !model.running ? "circle.dotted" : (model.state == .unavailable ? "exclamationmark.triangle" : "waveform.path"))
                .font(.largeTitle.weight(.bold)).fixedSize(horizontal: false, vertical: true)
            if model.running {
                let reason = StateText.reason(model.reason)
                if !reason.isEmpty { Text(reason).font(.body) }
                if model.paused { Text("Warnings are paused. They resume automatically after two minutes.").font(.callout) }
            } else {
                Text("Connect your headband, turn on Personal Hotspot, then start your session.").font(.body).foregroundStyle(.secondary)
                Button { model.start() } label: {
                    Label(model.stopping ? "Finishing session…" : "Start Determine", systemImage: "play.fill")
                        .font(.headline).frame(maxWidth: .infinity, minHeight: 44)
                }.buttonStyle(.borderedProminent).controlSize(.large).disabled(model.stopping)
            }
        }
        .padding(.vertical, 12)
        .foregroundStyle(model.running && model.state == .unavailable ? Color.red : Color.primary)
        .accessibilityElement(children: .contain)
        .accessibilityAction(named: "Read text") { model.readText() }
    }

    private var actions: some View {
        VStack(spacing: 12) {
            Button { model.requestDescription() } label: {
                Label(model.describeState == .requesting ? "Cancel description" : "What’s ahead?", systemImage: model.describeState == .requesting ? "xmark" : "sparkle")
                    .font(.headline).frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent).controlSize(.large)
            .disabled(!canAsk || !model.describeEnabled)
            Button { model.readText() } label: {
                Label(model.reader == .reading ? "Cancel reading" : "Read text", systemImage: "text.viewfinder")
                    .font(.headline).frame(maxWidth: .infinity, minHeight: 44)
            }.buttonStyle(.bordered).controlSize(.large).disabled(!canAsk)
            if !model.describeEnabled {
                NavigationLink("Set up scene descriptions") { SettingsView() }.font(.callout).frame(minHeight: 44)
            }
        }
    }

    @ViewBuilder private var answerControls: some View {
        Button { model.repeatLastAnswer() } label: { Label("Repeat", systemImage: "arrow.counterclockwise") }
            .disabled((model.running && !canAsk) || model.transcript.isEmpty).frame(minHeight: 44)
        Button { model.stopSpeaking() } label: { Label("Stop speaking", systemImage: "speaker.slash") }.frame(minHeight: 44)
        NavigationLink { TranscriptView() } label: { Label("Transcript", systemImage: "text.bubble") }.frame(minHeight: 44)
    }
}
#endif
