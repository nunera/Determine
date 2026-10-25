#if canImport(SwiftUI)
import DetermineEngine
import SwiftUI

/// PROTOCOL.md BEAT fault bits. Bit 2 is reserved since v2 (the board's I2S audio was removed).
private let faultNames = [
    "sensor init failed", "sensor read error", "reserved", "left haptic error", "right haptic error",
    "brownout seen", "no clock sync", "watchdog active", "low heap", "capture failed",
]

private func faultText(_ faults: Int) -> String {
    let names = faultNames.enumerated().filter { faults & (1 << $0.offset) != 0 }.map(\.element)
    return names.isEmpty ? "none" : names.joined(separator: ", ")
}

private func ms(_ v: Int64?) -> String { v.map { "\($0) ms" } ?? "-" }
private func msD(_ v: Double?) -> String { v.map { String(format: "%.0f ms", $0) } ?? "-" }

private func rangeText(mm: Int, status: Int) -> String {
    switch status {
    case RangeStatus.valid: return "\(mm) mm"
    case RangeStatus.noTarget: return "no target"
    case RangeStatus.unknown: return "unknown"
    default: return "invalid status \(status)"
    }
}

/// Band a VALID reading falls in by the live thresholds alone (before the engine's smoothing); nil otherwise.
private func band(of range: RangeSnapshot, _ t: Tuning) -> Band? {
    guard range.status == RangeStatus.valid else { return nil }
    if range.mm < t.nearMm { return .near }
    if range.mm < t.midMm { return .mid }
    if range.mm <= t.farMm { return .far }
    return Band.none
}

private struct BoardBlock: View {
    let name: String
    let board: BoardDiagnostics?
    let ip: String?
    /// True while the engine reports the link weak (cue board only).
    var linkWeak = false

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(name).font(.headline)
            DetailLine(title: "Heartbeat age", value: ms(board?.beatAgeMs))
            DetailLine(title: "Clock", value: clock)
            DetailLine(title: "Learned IP / RSSI", value: "\(ip ?? "not heard") / \(board?.rssiDbm.map { "\($0) dBm" } ?? "-")")
            if linkWeak {
                Text("WEAK LINK: signal below the weak-connection threshold. Informational only.")
                    .font(.headline)
                    .foregroundStyle(.orange)
            }
            DetailLine(title: "Faults", value: board.map { faultText($0.faults) } ?? "-")
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay { Rectangle().strokeBorder(.primary.opacity(0.5), lineWidth: 1) }
    }

    private var clock: String {
        guard let board else { return "-" }
        guard board.synced else { return "NOT synced" }
        let offset = board.offsetUs.map { "\($0 / 1000) ms" } ?? "-"
        let rtt = board.rttUs.map { String(format: "%.1f ms", Double($0) / 1000) } ?? "-"
        return "synced, offset \(offset), RTT \(rtt)"
    }
}

/// Judge diagnostics (Arch §11.4): stream ages, the one forward distance with its status, the chosen alert, measured
/// latency, board faults, link addresses. Numbers come only from the engine and the link; nothing is simulated.
@MainActor
struct JudgeView: View {
    @EnvironmentObject private var model: AppModel
    @State private var buzzIntensity: Double = 128

    var body: some View {
        let d = model.diagnostics
        ScreenContent {
            if !model.running {
                Text("Determine is stopped. Start it on the main screen to see live data.")
            }

            if model.running, let d, d.linkWeak {
                Callout(
                    text: "Weak connection to the glasses\(d.cue.rssiDbm.map { " (\($0) dBm)" } ?? ""). Warnings may be late or lost.",
                    color: .orange
                )
                .accessibilityAddTraits(.updatesFrequently)
            }

            SectionTitle(title: "Streams")
            DetailLine(
                title: "Frame source",
                value: model.frameSource == .phoneCamera
                    ? "iPhone camera — software fallback, not the proposed glasses"
                    : "Glasses camera board (ESP32-CAM)"
            )
            if model.running, model.frameSource == .phoneCamera {
                cameraLines
            }
            if model.running {
                DetailLine(title: "State", value: stateLine)
                DetailLine(title: "Camera frame age", value: ms(d?.frameAgeMs))
                DetailLine(title: "Forward distance age", value: ms(d?.rangeAgeMs))
            }
            headsetBlock
            if model.running {
                BoardBlock(name: "Camera board", board: d?.cam, ip: model.link.camIp)
                BoardBlock(name: "Cue board (forward ToF + temple haptics)", board: d?.cue, ip: model.link.cueIp, linkWeak: d?.linkWeak == true)
            }

            if model.running {
                SectionTitle(title: "Forward distance")
                forwardRange
            }

            JudgeChartsSection(trace: model.trace, tuning: model.tuning, running: model.running)

            if model.running {
                SectionTitle(title: "Chosen alert")
                Text(model.currentAlert.map(describeAlert) ?? "No alert (not a clear path)").font(.headline)

                SectionTitle(title: "Latency (measured)")
                DetailLine(title: "Capture to cue, last", value: msD(d?.lastCueLatencyMs))
                DetailLine(title: "Capture to cue p50 / p95", value: "\(msD(d?.captureToCueP50Ms)) / \(msD(d?.captureToCueP95Ms))")
                DetailLine(title: "Cue to ACK p50 / p95", value: "\(msD(d?.ackP50Ms)) / \(msD(d?.ackP95Ms))")

                SectionTitle(title: "Describe")
                DetailLine(title: "Scene description", value: model.describeMessage ?? "Not available on iOS: no face blur, no upload")

                SectionTitle(title: "Debug panel")
                debugPanel(d)
            }

            SectionTitle(title: "Link")
            if model.running {
                DetailLine(title: "UDP :47000", value: model.link.bound ? "listening" : "FAILED: \(model.link.bindError ?? "unknown")")
                DetailLine(
                    title: "Unattributed packets / send errors / sends to unheard board",
                    value: "\(model.link.unattributedPackets) / \(model.link.sendErrors) / \(model.link.sendsWithoutAddress)"
                )
            }
            DetailLine(title: "Camera board IP (port 47002)", value: model.running ? (model.link.camIp ?? "not heard") : "stopped")
            DetailLine(title: "Cue board IP (port 47001)", value: model.running ? (model.link.cueIp ?? "not heard") : "stopped")
            DetailLine(
                title: "This iPhone's addresses",
                value: model.phoneAddresses.isEmpty
                    ? "none"
                    : model.phoneAddresses.map { "\($0.interface) \($0.address)" }.joined(separator: ", ")
            )
        }
        .navigationTitle("Judge diagnostics")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { model.refreshAddresses() }
    }

    private var stateLine: String {
        let name = StateText.title(model.state)
        return model.reason.isEmpty ? name : "\(name) (\(StateText.reason(model.reason)))"
    }

    @ViewBuilder
    private var cameraLines: some View {
        if let c = model.camera {
            DetailLine(
                title: "iPhone camera",
                value: c.error ?? "streaming \(c.width)×\(c.height), \(c.frames) frames, \(c.skipped) skipped"
            )
            if let encode = c.lastEncodeMs {
                DetailLine(title: "iPhone camera JPEG encode", value: String(format: "%.1f ms", encode))
            }
        } else {
            DetailLine(title: "iPhone camera", value: "starting")
        }
    }

    /// Warning sound route: off by default; headset or (debug) speaker when chosen.
    private var headsetBlock: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Warning sounds").font(.headline)
            DetailLine(title: "Output", value: model.soundOutput.label)
            if model.running, model.soundsOn {
                DetailLine(title: "Audio route now", value: model.sound.route)
                DetailLine(title: "Route usable", value: model.sound.routeOk ? "yes" : "NO: warnings report system off (headset route lost)")
                DetailLine(title: "Audio engine", value: model.sound.error ?? (model.sound.engineRunning ? "running" : "stopped"))
                DetailLine(
                    title: "Cues played / dropped busy / route lost / failed",
                    value: "\(model.sound.played) / \(model.sound.dropped) / \(model.sound.routeLost) / \(model.sound.failures)"
                )
            }
            Text(model.soundOutput == .off
                ? "Haptic only: no warning sound plays. Spoken words and failure alerts still use the phone."
                : "Warning sounds never fall back to another output: a wrong route means system off.")
                .font(.callout)
        }
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .overlay { Rectangle().strokeBorder(.primary.opacity(0.5), lineWidth: 1) }
    }

    /// Counters, last RANGE, board IPs, state-change event log and the test buzz.
    @ViewBuilder
    private func debugPanel(_ d: Diagnostics?) -> some View {
        Text("Counters").font(.headline)
        if let d {
            DetailLine(title: "Cues sent / ACKs", value: "\(d.cuesSent) / \(d.acks)")
            DetailLine(title: "NACKs / missed ACKs", value: "\(d.nacks) / \(d.missedAcks)")
            DetailLine(title: "Malformed / stale dropped", value: "\(d.malformed) / \(d.staleDropped)")
            DetailLine(title: "Camera-board packets ignored (iPhone camera)", value: "\(d.ignoredCamPackets)")
        }
        Text("Last RANGE").font(.headline)
        if let r = model.range {
            DetailLine(title: "Distance / status", value: "\(rangeText(mm: r.mm, status: r.status)), raw status \(r.status)")
            DetailLine(title: "Age", value: ms(d?.rangeAgeMs))
        } else {
            Text("none received yet")
        }
        Text("Boards").font(.headline)
        DetailLine(title: "Cue board IP / camera board IP", value: "\(model.link.cueIp ?? "not heard") / \(model.link.camIp ?? "not heard")")
        Text("Approach and exploration").font(.headline)
        DetailLine(title: "Approach escalation active", value: d.map { $0.approachActive ? "YES" : "no" } ?? "-")
        DetailLine(title: "Exploration pulses", value: d.map { $0.exploring ? "YES" : "no" } ?? "-")

        Text("Test buzz").font(.headline)
        Text("Sends one haptic cue to the temples through the normal cue path (deadline, strength cap, ACK). Needs a synced cue board.")
            .font(.callout)
        Slider(value: $buzzIntensity, in: Double(MIN_HAPTIC_MAX)...255, step: 1) {
            Text("Test buzz intensity")
        }
        .accessibilityValue("\(Int(buzzIntensity.rounded())) of 255")
        Text("Intensity: \(Int(buzzIntensity.rounded())) of 255").font(.subheadline.weight(.medium))
        ForEach(AppModel.testPatterns, id: \.id) { pattern in
            BigAction(title: "Test buzz \(pattern.name)", primary: false) {
                model.sendTestBuzz(pattern: pattern.id, intensity: Int(buzzIntensity.rounded()))
            }
        }

        Text("Events (last \(DebugTrace.eventLimit))").font(.headline)
        EventLogView(trace: model.trace)
    }

    @ViewBuilder
    private var forwardRange: some View {
        if let r = model.range {
            let b = band(of: r, model.tuning)
            let near = b == .near
            Text(rangeText(mm: r.mm, status: r.status))
                .font(.title2.bold())
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(16)
                .background(near ? nearYellow : Color.clear)
                .foregroundStyle(near ? Color.black : Color.primary)
                .overlay { Rectangle().strokeBorder(.primary, lineWidth: 2) }
            DetailLine(title: "Band", value: bandText(b, status: r.status))
        } else {
            Text("No forward distance received yet. Not a clear path.")
        }
        DetailLine(title: "Left", value: "Not measured — one forward sensor only")
        DetailLine(title: "Right", value: "Not measured — one forward sensor only")
        Text("Bands from the tuning: near below \(model.tuning.nearMm) mm, mid up to \(model.tuning.midMm - 1) mm, far up to \(model.tuning.farMm) mm. The engine reports a band only after it has held (smoothing). Unknown never means clear.")
            .font(.callout)
        if let d = model.diagnostics {
            DetailLine(
                title: "Closing speed",
                value: d.closingSpeedMmPerS.map { String(format: "%.0f mm/s", $0) } ?? "-"
            )
        }
    }

    private func bandText(_ band: Band?, status: Int) -> String {
        switch band {
        case .near: return "near"
        case .mid: return "mid"
        case .far: return "far"
        case .some(.none): return "beyond \(model.tuning.farMm) mm: not reported"
        case nil: return status == RangeStatus.noTarget ? "nothing seen: not a clear path" : "do not know: not far, not clear"
        }
    }
}
#endif
