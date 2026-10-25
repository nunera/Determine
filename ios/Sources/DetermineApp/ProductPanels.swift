#if canImport(SwiftUI)
import DetermineEngine
import SwiftUI
import UIKit

struct ProductSection<Content: View>: View {
    let title: String
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text(title).font(.title3.weight(.semibold)).accessibilityAddTraits(.isHeader)
            content
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(20)
        .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 16))
    }
}

struct ConnectionPanel: View {
    @EnvironmentObject private var model: AppModel
    var body: some View {
        ProductSection(title: "Connections") {
            row("Camera", icon: "camera", status: cameraStatus, good: cameraGood)
            Divider()
            row("Distance & vibration", icon: "waveform.path", status: cueStatus, good: cueGood)
            Divider()
            row("Warning audio", icon: "headphones", status: audioStatus, good: model.running && (model.soundOutput == .off || model.sound.routeOk))
            if model.running && !model.link.bound {
                Text("Network port unavailable. Check Local Network permission and restart Determine.").font(.callout).foregroundStyle(.red)
            }
        }
    }
    private var cameraGood: Bool { model.running && (model.diagnostics?.frameAgeMs).map { $0 <= 500 } == true }
    private var cueGood: Bool { model.running && (model.diagnostics?.cue.beatAgeMs).map { $0 <= 1000 } == true && model.diagnostics?.cue.synced == true }
    private var cameraStatus: String {
        guard model.running else { return "Not running" }
        if cameraGood { return model.frameSource == .phoneCamera ? "iPhone camera · Receiving" : "Headband camera · Receiving" }
        return model.camera?.error ?? "No fresh image · Check camera power and hotspot"
    }
    private var cueStatus: String {
        guard model.running else { return "Not running" }
        if cueGood {
            let fresh = (model.diagnostics?.rangeAgeMs).map { $0 <= 500 } == true
            return fresh && model.diagnostics?.rangeStatus == 0 ? "Connected · Distance received" : "Connected · No usable distance"
        }
        return "Not ready · Check board power and hotspot"
    }
    private var audioStatus: String {
        guard model.running else { return "Not running" }
        if model.soundOutput == .off { return "Haptic-only warnings · Speech uses current iPhone audio route" }
        return model.sound.routeOk ? model.sound.route : "Selected output unavailable · Check audio connection"
    }
    private func row(_ title: String, icon: String, status: String, good: Bool) -> some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: icon).font(.title3).frame(width: 26).accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.headline)
                Text(status).font(.subheadline).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 4)
            Image(systemName: good ? "checkmark.circle.fill" : "minus.circle")
                .foregroundStyle(good ? Color.green : Color.secondary)
                .accessibilityLabel(good ? "Connected" : "Not ready")
        }.accessibilityElement(children: .combine)
    }
}

private struct DecodedPreview: @unchecked Sendable { let image: CGImage? }
@MainActor
private final class PreviewDecoder: ObservableObject {
    @Published var image: UIImage?
    private var token = 0
    func update(_ data: Data?, mirrored: Bool) {
        token += 1
        let current = token
        guard let data else { image = nil; return }
        Task {
            let decoded = await Task.detached(priority: .utility) {
                return DecodedPreview(image: CameraImageCorrection.image(data, mirrored: mirrored))
            }.value
            guard current == token else { return }
            image = decoded.image.map { UIImage(cgImage: $0) }
        }
    }
}

struct CameraPreview: View {
    @EnvironmentObject private var model: AppModel
    @StateObject private var decoder = PreviewDecoder()
    var alignment = false
    var body: some View {
        TimelineView(.periodic(from: .now, by: 0.25)) { _ in
            let age = model.previewCaptureUs.map { max(0, EngineClock.nowUs() - $0) / 1000 }
            let stale = !model.running || age == nil || age! > 500
            ZStack {
                Color.black
                if let image = decoder.image, let cgImage = image.cgImage {
                    let orientations: [UIImage.Orientation] = [.up, .right, .down, .left]
                    Image(uiImage: UIImage(cgImage: cgImage, scale: 1, orientation: orientations[(model.previewRotation / 90) % 4]))
                        .resizable().scaledToFit()
                        .opacity(stale ? 0.35 : 1)
                }
                if alignment {
                    GeometryReader { geo in
                        Path { path in
                            for fraction in [1.0 / 3, 2.0 / 3] {
                                path.move(to: CGPoint(x: geo.size.width * fraction, y: 0))
                                path.addLine(to: CGPoint(x: geo.size.width * fraction, y: geo.size.height))
                                path.move(to: CGPoint(x: 0, y: geo.size.height * fraction))
                                path.addLine(to: CGPoint(x: geo.size.width, y: geo.size.height * fraction))
                            }
                        }.stroke(.white.opacity(0.55), lineWidth: 1)
                    }.accessibilityHidden(true)
                    Image(systemName: "plus").font(.title).foregroundStyle(.white).accessibilityHidden(true)
                }
                if stale {
                    VStack(spacing: 8) {
                        Image(systemName: "camera.badge.ellipsis").font(.title)
                        Text(model.running ? "Waiting for a fresh image" : "Start Determine to see the camera").font(.headline).multilineTextAlignment(.center)
                        if let age { Text("Last image \(age) ms ago").font(.caption.monospacedDigit()) }
                    }.foregroundStyle(.white).padding()
                } else {
                    VStack { Spacer(); HStack { Label("Live", systemImage: "circle.fill"); Spacer(); Text("\(age ?? 0) ms old").monospacedDigit() } }
                        .font(.caption.weight(.semibold)).foregroundStyle(.white)
                        .padding(12).background(alignment: .bottom) { LinearGradient(colors: [.clear, .black.opacity(0.8)], startPoint: .center, endPoint: .bottom) }
                }
            }
            .aspectRatio(4 / 3, contentMode: .fit)
            .clipped()
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(stale ? "Camera preview unavailable or stale" : "Live camera preview. Image \(age ?? 0) milliseconds old")
        }
        .onAppear { decoder.update(model.previewJPEG, mirrored: model.cameraImageNeedsMirroring) }
        .onChange(of: model.previewCaptureUs) { _, _ in decoder.update(model.previewJPEG, mirrored: model.cameraImageNeedsMirroring) }
        .onChange(of: model.cameraImageNeedsMirroring) { _, mirrored in decoder.update(model.previewJPEG, mirrored: mirrored) }
        .onChange(of: model.previewJPEG == nil) { _, empty in if empty { decoder.update(nil, mirrored: false) } }
    }
}

struct CameraAlignmentView: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 24) {
                    CameraPreview(alignment: true).clipShape(RoundedRectangle(cornerRadius: 12))
                    Text("Aim the headband camera forward").font(.title2.bold())
                    Text("Use the center guide to check camera aim. These guides do not show the distance sensor’s field of view. Rotation changes only this preview. Headband mirror correction also applies to descriptions and text reading.").foregroundStyle(.secondary)
                    Button { model.setPreviewRotation(model.previewRotation + 90) } label: { Label("Rotate preview", systemImage: "rotate.right") }
                    if model.frameSource == .camBoard {
                        Toggle("Correct mirrored headband image", isOn: Binding(get: { model.headbandMirrorCorrection }, set: model.setHeadbandMirrorCorrection))
                    }
                    if let range = model.range, range.status == 0, (model.diagnostics?.rangeAgeMs).map({ $0 <= 500 }) == true {
                        Text("Forward sensor: \(range.mm) mm").font(.headline.monospacedDigit())
                    } else { Text("Forward sensor: no usable distance").foregroundStyle(.secondary) }
                    Text("Alignment is a setup aid, not confirmation that the path is clear.").font(.callout)
                }.padding(20)
            }
            .background(Color(uiColor: .systemGroupedBackground))
            .navigationTitle("Camera alignment").navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .onAppear { model.setPreviewEnabled(true) }
        }
    }
}

struct TranscriptView: View {
    @EnvironmentObject private var model: AppModel
    var body: some View {
        List {
            if model.transcript.isEmpty {
                ContentUnavailableView("No answers yet", systemImage: "text.bubble", description: Text("Scene descriptions and text readings appear here. They stay in memory only and are not saved to disk."))
            }
            ForEach(model.transcript.reversed()) { entry in
                VStack(alignment: .leading, spacing: 12) {
                    HStack { Label(entry.kind == .description ? "Description" : "Read text", systemImage: entry.kind == .description ? "sparkle" : "text.viewfinder").font(.subheadline.weight(.semibold)); Spacer(); Text(entry.date, style: .time).font(.caption).foregroundStyle(.secondary) }
                    if let question = entry.question { Text(question).font(.headline) }
                    Text(entry.text).textSelection(.enabled)
                    if entry.interrupted { Label("Interrupted · Partial answer", systemImage: "pause.circle").font(.caption).foregroundStyle(.secondary) }
                    Button { model.repeatAnswer(entry) } label: { Label("Read aloud", systemImage: "speaker.wave.2") }
                        .disabled((model.running && (model.state == .unavailable || (model.currentAlert?.band ?? .none) >= .mid)) || entry.text.isEmpty)
                }.padding(.vertical, 8)
            }
        }
        .navigationTitle("Transcript")
        .toolbar { ToolbarItem(placement: .primaryAction) { Button("Clear", role: .destructive) { model.clearTranscript() }.disabled(model.transcript.isEmpty) } }
    }
}
#endif
