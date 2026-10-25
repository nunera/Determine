#if canImport(SwiftUI)
import SwiftUI
import DetermineCore

/// The SwiftUI seam owns presentation and preferences; the session never stores a hearing profile on disk.
@MainActor
final class PrototypeModel: ObservableObject {
    @Published var session = PrototypeSession()

    private let defaults: UserDefaults
    private enum Key {
        static let mode = "prototype.outputMode"
        static let hapticMax = "prototype.hapticMax"
        static let spearcons = "prototype.spearcons"
        static let describeEnabled = "prototype.describeEnabled"
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        if let raw = defaults.string(forKey: Key.mode), let mode = OutputMode(rawValue: raw) {
            session.setMode(mode)
        }
        if let strength = defaults.object(forKey: Key.hapticMax) as? Int {
            session.setHapticMax(strength)
        }
        if let spearcons = defaults.object(forKey: Key.spearcons) as? Bool {
            session.spearcons = spearcons
        }
        if let enabled = defaults.object(forKey: Key.describeEnabled) as? Bool {
            session.describeEnabled = enabled
        }
    }

    private func change(_ operation: (inout PrototypeSession) -> Void) {
        var next = session
        operation(&next)
        session = next
    }

    func start() { change { $0.start() } }
    func stop() { change { $0.stop() } }
    func setPaused(_ paused: Bool) { change { $0.setPaused(paused) } }
    func selectScenario(_ scenario: DemoScenario) { change { $0.selectScenario(scenario) } }
    func requestDescription() { change { $0.requestDescription() } }
    func enterBackground() { change { $0.enterBackground() } }

    func setMode(_ mode: OutputMode) {
        change { $0.setMode(mode) }
        defaults.set(session.mode.rawValue, forKey: Key.mode)
    }

    func setHapticMax(_ strength: Int) {
        change { $0.setHapticMax(strength) }
        defaults.set(session.hapticMax, forKey: Key.hapticMax)
    }

    func setSpearcons(_ enabled: Bool) {
        change { $0.spearcons = enabled }
        defaults.set(enabled, forKey: Key.spearcons)
    }

    func setDescribeEnabled(_ enabled: Bool) {
        change { $0.describeEnabled = enabled }
        defaults.set(enabled, forKey: Key.describeEnabled)
    }

    func beginCalibration() { change { $0.beginCalibration() } }
    func fitCalibration(weakerEar: Ear?) { change { $0.fitCalibration(weakerEar: weakerEar) } }
    func answerHeard(_ heard: Bool) { change { $0.answerHeard(heard) } }
    func setSensationLevel(_ level: Int) { change { $0.setSensationLevel(level) } }
    func nudgeBalance(_ amount: Int) { change { $0.nudgeBalance(amount) } }
    func confirmBalance() { change { $0.confirmBalance() } }
    func answerSide(_ ear: Ear) { change { $0.answerSide(ear) } }
    func applyCalibration() {
        change { $0.applyCalibration() }
        defaults.set(session.mode.rawValue, forKey: Key.mode)
    }
    func replayCalibration() { change { $0.replayCalibration() } }
    func chooseHapticOnly() { change { $0.chooseHapticOnly() } }
    func cancelCalibration() { change { $0.cancelCalibration() } }
}

@MainActor
@main
struct DetermineApp: App {
    @StateObject private var model = PrototypeModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            NavigationStack {
                MainPrototypeView()
            }
            .environmentObject(model)
        }
        .onChange(of: scenePhase) { _, newPhase in
            if newPhase == .background {
                model.enterBackground()
            }
        }
    }
}
#else
@main
struct DetermineApp {
    static func main() {
        print("Determine's native interface is unavailable: SwiftUI and an iOS runtime are required.")
    }
}
#endif
