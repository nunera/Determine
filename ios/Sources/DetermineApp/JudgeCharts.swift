#if canImport(SwiftUI) && canImport(Charts)
import Charts
import DetermineEngine
import SwiftUI

private let windowS = DebugTrace.windowS

private func stateColor(_ state: SystemState) -> Color {
    switch state {
    case .starting: return .gray
    case .ready: return .green
    case .degraded: return .orange
    case .unavailable: return .red
    case .paused: return .blue
    }
}

private func stateWord(_ state: SystemState) -> String {
    switch state {
    case .starting: return "starting"
    case .ready: return "ready"
    case .degraded: return "limited"
    case .unavailable: return "system off"
    case .paused: return "paused"
    }
}

/// Forward distance over the last 30 s with the NEAR / MID / FAR thresholds and the system state as background
/// shading. Only VALID readings draw the line; NO_TARGET and UNKNOWN are marked along the top edge, because neither
/// is a distance and neither means clear.
@MainActor
struct DistanceChart: View {
    @ObservedObject var trace: DebugTrace
    let tuning: Tuning

    private var yMax: Double { Double(min(max(tuning.farMm + 400, 1600), 8000)) }

    var body: some View {
        let yMax = yMax
        let visible = trace.spans.filter { ($0.endUs ?? Int64.max) >= trace.nowUs - Int64(windowS * 1_000_000) }
        VStack(alignment: .leading, spacing: 6) {
            Chart {
                ForEach(visible) { span in
                    RectangleMark(
                        xStart: .value("from", max(trace.seconds(span.startUs), -windowS)),
                        xEnd: .value("to", trace.seconds(span.endUs ?? trace.nowUs)),
                        yStart: .value("min", 0),
                        yEnd: .value("max", yMax)
                    )
                    .foregroundStyle(stateColor(span.state).opacity(0.18))
                }
                threshold("NEAR", tuning.nearMm, .red)
                threshold("MID", tuning.midMm, .orange)
                threshold("FAR", tuning.farMm, .yellow)
                ForEach(trace.ranges.filter { $0.status == RangeStatus.valid }) { p in
                    LineMark(
                        x: .value("seconds", trace.seconds(p.tUs)),
                        y: .value("mm", min(Double(p.mm), yMax))
                    )
                    .foregroundStyle(Color.primary)
                    .interpolationMethod(.linear)
                }
                ForEach(trace.ranges.filter { $0.status != RangeStatus.valid }) { p in
                    PointMark(
                        x: .value("seconds", trace.seconds(p.tUs)),
                        y: .value("mm", yMax)
                    )
                    .symbol(p.status == RangeStatus.noTarget ? .circle : .cross)
                    .symbolSize(24)
                    .foregroundStyle(p.status == RangeStatus.noTarget ? Color.blue : Color.purple)
                }
            }
            .chartXScale(domain: -windowS...0)
            .chartYScale(domain: 0...yMax)
            .chartXAxisLabel("seconds ago")
            .chartYAxisLabel("mm")
            .frame(height: 220)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("Forward distance, last 30 seconds")
            .accessibilityValue(latestSummary)
            Text("Line: valid distance. Blue circle: no target. Purple cross: unknown. Shading: green ready, orange limited, red system off, blue paused, gray starting. Dashed lines: NEAR, MID, FAR limits. No target and unknown never mean clear.")
                .font(.callout)
            if let current = trace.spans.last?.state {
                Text("Now: \(stateWord(current))").font(.subheadline.weight(.medium))
            }
        }
    }

    @ChartContentBuilder
    private func threshold(_ name: String, _ mm: Int, _ color: Color) -> some ChartContent {
        RuleMark(y: .value(name, Double(mm)))
            .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
            .foregroundStyle(color)
            .annotation(position: .top, alignment: .leading, spacing: 0) {
                Text("\(name) \(mm)").font(.caption2)
            }
    }

    private var latestSummary: String {
        guard let p = trace.ranges.last else { return "no readings yet" }
        switch p.status {
        case RangeStatus.valid: return "\(p.mm) millimetres"
        case RangeStatus.noTarget: return "no target"
        default: return "unknown"
        }
    }
}

/// A plain line over the last 30 s (RSSI, closing speed) with one optional dashed reference line.
@MainActor
struct SeriesChart: View {
    @ObservedObject var trace: DebugTrace
    let title: String
    let unit: String
    let points: [ValuePoint]
    let domain: ClosedRange<Double>
    var reference: (value: Double, label: String)?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Chart {
                if let reference {
                    RuleMark(y: .value(reference.label, reference.value))
                        .lineStyle(StrokeStyle(lineWidth: 1, dash: [4, 3]))
                        .foregroundStyle(.orange)
                        .annotation(position: .top, alignment: .leading, spacing: 0) {
                            Text(reference.label).font(.caption2)
                        }
                }
                ForEach(points) { p in
                    LineMark(
                        x: .value("seconds", trace.seconds(p.tUs)),
                        y: .value(unit, min(max(p.value, domain.lowerBound), domain.upperBound))
                    )
                    .foregroundStyle(Color.primary)
                }
            }
            .chartXScale(domain: -windowS...0)
            .chartYScale(domain: domain)
            .chartXAxisLabel("seconds ago")
            .chartYAxisLabel(unit)
            .frame(height: 140)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel("\(title), last 30 seconds")
            .accessibilityValue(points.last.map { "\(Int($0.value.rounded())) \(unit)" } ?? "no data")
        }
    }
}
#endif
