#if canImport(SwiftUI)
import DetermineEngine
import Foundation
import SwiftUI

/// One state-change event line (newest first in the UI).
struct DebugEvent: Identifiable, Equatable {
    let id: Int
    let time: Date
    let text: String
}

/// A shaded stretch of one `SystemState` on the chart time axis (engine clock µs).
struct StateSpan: Identifiable, Equatable {
    let id: Int
    let startUs: Int64
    var endUs: Int64?
    let state: SystemState
}

struct RangePoint: Identifiable, Equatable {
    let tUs: Int64
    let mm: Int
    let status: Int
    var id: Int64 { tUs }
}

struct ValuePoint: Identifiable, Equatable {
    let tUs: Int64
    let value: Double
    var id: Int64 { tUs }
}

/// Rolling history behind the Judge chart and debug panel: forward distance, cue-board RSSI and closing speed for the
/// last `windowS` seconds, `SystemState` spans for shading, and the last 50 state/link/alert events. A separate
/// object (not part of `AppModel`) so only the Judge screen re-renders at the sample rate.
@MainActor
final class DebugTrace: ObservableObject {
    nonisolated static let windowS = 30.0
    nonisolated static let eventLimit = 50
    /// Keep a little more than the window so the left edge never shows a gap.
    private static let keepUs = Int64((windowS + 5) * 1_000_000)
    /// At most ~25 distance points per second reach the chart.
    private static let minPointGapUs: Int64 = 40_000

    @Published private(set) var ranges: [RangePoint] = []
    @Published private(set) var rssi: [ValuePoint] = []
    @Published private(set) var speed: [ValuePoint] = []
    @Published private(set) var spans: [StateSpan] = []
    @Published private(set) var events: [DebugEvent] = []
    /// Engine-clock µs of the newest sample: the right edge (0 s) of the charts.
    @Published private(set) var nowUs: Int64 = 0

    private var nextId = 0

    func reset() {
        ranges = []
        rssi = []
        speed = []
        spans = []
        events = []
        nowUs = 0
    }

    func log(_ text: String) {
        nextId += 1
        events.insert(DebugEvent(id: nextId, time: Date(), text: text), at: 0)
        if events.count > Self.eventLimit { events.removeLast(events.count - Self.eventLimit) }
    }

    func recordState(_ state: SystemState, reason: String, atUs us: Int64) {
        if let last = spans.indices.last { spans[last].endUs = us }
        nextId += 1
        spans.append(StateSpan(id: nextId, startUs: us, endUs: nil, state: state))
        advance(to: us)
        log(reason.isEmpty ? "State: \(StateText.title(state))" : "State: \(StateText.title(state)) (\(StateText.reason(reason)))")
    }

    func addRanges(_ batch: [RangeSnapshot]) {
        guard !batch.isEmpty else { return }
        var out = ranges
        for r in batch {
            if let last = out.last, r.rxPhoneUs - last.tUs < Self.minPointGapUs { continue }
            out.append(RangePoint(tUs: r.rxPhoneUs, mm: r.mm, status: r.status))
        }
        ranges = out
        advance(to: batch[batch.count - 1].rxPhoneUs)
    }

    func addDiagnostics(_ d: Diagnostics) {
        if let dbm = d.cue.rssiDbm { rssi.append(ValuePoint(tUs: d.nowPhoneUs, value: Double(dbm))) }
        if let v = d.closingSpeedMmPerS { speed.append(ValuePoint(tUs: d.nowPhoneUs, value: v)) }
        advance(to: d.nowPhoneUs)
    }

    /// Drops what has left the window and moves the right edge forward.
    private func advance(to us: Int64) {
        guard us > nowUs else { return }
        nowUs = us
        let cutoff = us - Self.keepUs
        if let first = ranges.first, first.tUs < cutoff { ranges.removeAll { $0.tUs < cutoff } }
        if let first = rssi.first, first.tUs < cutoff { rssi.removeAll { $0.tUs < cutoff } }
        if let first = speed.first, first.tUs < cutoff { speed.removeAll { $0.tUs < cutoff } }
        if spans.count > 1 { spans.removeAll { ($0.endUs ?? Int64.max) < cutoff } }
    }

    /// Seconds before the newest sample (≤ 0), the chart x value.
    func seconds(_ us: Int64) -> Double { Double(us - nowUs) / 1_000_000 }
}
#endif
