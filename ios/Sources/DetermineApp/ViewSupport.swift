#if canImport(SwiftUI)
import DetermineEngine
import SwiftUI
import UIKit

/// `Alert` alone is ambiguous with SwiftUI's `Alert`.
typealias HazardAlert = DetermineEngine.Alert

let nearYellow = Color(red: 1, green: 0.82, blue: 0)
let failureRed = Color(red: 0.62, green: 0.07, blue: 0.13)

/// State wording shared with the Android app (`strings.xml`).
enum StateText {
    static func title(_ state: SystemState) -> String {
        switch state {
        case .starting: return "Starting: waiting for the boards"
        case .ready: return "Ready"
        case .degraded: return "Limited: distance sensor not fully usable"
        case .unavailable: return "System off: warnings unavailable"
        case .paused: return "Paused: no warnings"
        }
    }

    static let stopped = "Stopped: no warnings"

    /// Engine reasons are snake_case tokens (`headset_route_lost`).
    static func reason(_ token: String) -> String { token.replacingOccurrences(of: "_", with: " ") }
}

/// "Obstacle ahead, near, 0.4 metres". Distance only when backed by a valid forward reading (Arch §7.2).
func describeAlert(_ alert: HazardAlert) -> String {
    let band: String? = switch alert.band {
    case .near: "near"
    case .mid: "mid"
    case .far: "far"
    case .none: nil
    }
    let distance = alert.distanceMm.map { String(format: "%.1f metres", Double($0) / 1000) }
    return ["Obstacle ahead", band, distance].compactMap { $0 }.joined(separator: ", ")
}

func modeName(_ mode: HearingMode) -> String {
    switch mode {
    case .stereo: return "Stereo warning sounds"
    case .ssd: return "One-ear warning sounds"
    case .hapticOnly: return "Haptic only (temple vibration, no sound)"
    }
}

struct SectionTitle: View {
    let title: String
    var body: some View {
        Text(title)
            .font(.title2.bold())
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 8)
            .accessibilityAddTraits(.isHeader)
    }
}

struct BigAction: View {
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

struct DetailLine: View {
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

/// A bordered call-out for a standing failure or limitation (not a transient alert).
struct Callout: View {
    let text: String
    var color: Color = .red

    var body: some View {
        Text(text)
            .font(.headline)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
            .overlay { Rectangle().strokeBorder(color, lineWidth: 2) }
            .fixedSize(horizontal: false, vertical: true)
    }
}

struct ScreenContent<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                content
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 20)
            .padding(.vertical, 16)
        }
        .background(Color(uiColor: .systemBackground))
    }
}

@MainActor
func navigationAction(_ title: String) -> some View {
    Text(title)
        .font(.title3.bold())
        .multilineTextAlignment(.center)
        .foregroundStyle(.primary)
        .frame(maxWidth: .infinity, minHeight: 72)
        .padding(.horizontal, 12)
        .overlay { RoundedRectangle(cornerRadius: 6).strokeBorder(.primary, lineWidth: 2) }
        .contentShape(Rectangle())
}
#endif
