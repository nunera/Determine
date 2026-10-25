#if canImport(SwiftUI)
import DetermineEngine
import SwiftUI

private let eventClock: DateFormatter = {
    let f = DateFormatter()
    f.dateFormat = "HH:mm:ss.SSS"
    return f
}()

/// The last `DebugTrace.eventLimit` state, alert, link and debug events, newest first, with timestamps.
@MainActor
struct EventLogView: View {
    @ObservedObject var trace: DebugTrace

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            if trace.events.isEmpty {
                Text("No events yet.")
            }
            ForEach(trace.events) { event in
                Text("\(eventClock.string(from: event.time))  \(event.text)")
                    .font(.system(.footnote, design: .monospaced))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityLabel("\(eventClock.string(from: event.time)), \(event.text)")
            }
        }
    }
}

/// Judge charts: forward distance, cue-board RSSI and closing speed over the last 30 s. Observes the trace itself so
/// only this part of the Judge screen redraws at the sample rate.
@MainActor
struct JudgeChartsSection: View {
    @ObservedObject var trace: DebugTrace
    let tuning: Tuning
    let running: Bool

    var body: some View {
        SectionTitle(title: "Last 30 seconds")
        if trace.ranges.isEmpty, trace.rssi.isEmpty {
            Text(running ? "Waiting for readings." : "No data yet. Start Determine to record.")
        }
        #if canImport(Charts)
        Text("Forward distance").font(.headline)
        DistanceChart(trace: trace, tuning: tuning)
        Text("Cue board signal (RSSI)").font(.headline)
        SeriesChart(
            trace: trace, title: "Cue board RSSI", unit: "dBm", points: trace.rssi, domain: -100 ... -30,
            reference: (value: Double(tuning.weakLinkRssiDbm), label: "weak below \(tuning.weakLinkRssiDbm) dBm")
        )
        Text("Closing speed (positive = approaching)").font(.headline)
        SeriesChart(
            trace: trace, title: "Closing speed", unit: "mm/s", points: trace.speed, domain: speedDomain,
            reference: tuning.approachEnabled ? (value: Double(tuning.approachMinSpeedMmPerS), label: "approach at \(tuning.approachMinSpeedMmPerS) mm/s") : nil
        )
        #endif
    }

    /// Symmetric around 0, at least ±1000 mm/s, wide enough for what was seen.
    private var speedDomain: ClosedRange<Double> {
        let peak = max(1000, ceil((trace.speed.map { abs($0.value) }.max() ?? 0) / 500) * 500)
        return -peak ... peak
    }
}
#endif
