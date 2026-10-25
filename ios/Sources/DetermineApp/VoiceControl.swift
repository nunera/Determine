#if canImport(SwiftUI)
import AppIntents
import Foundation

/// What a spoken command can do. Each maps to an action already on Main; none reaches the hazard decision.
enum VoiceCommand: String, CaseIterable, Sendable {
    case whatsAhead
    case readText
    case pauseWarnings
    case resumeWarnings
    case stopSpeaking
}

enum VoiceControlSettings {
    /// While warnings are paused Determine says so this often ...
    static let pauseReminderSeconds = 30
    /// ... and resumes them by itself after this long.
    static let autoResumeSeconds = 120
}

/// How a Siri request reaches the running session. The intents run in this process; when the app is not running
/// (or no session is), the command answers that and does nothing.
@MainActor
enum VoiceBridge {
    static weak var model: AppModel?

    static func perform(_ command: VoiceCommand) -> String {
        guard let model else { return "Determine is not running. Open the app and tap Start Determine." }
        return model.perform(command)
    }
}

struct WhatsAheadIntent: AppIntent {
    static let title: LocalizedStringResource = "What's ahead"
    static let description = IntentDescription("Describes what the camera sees ahead, spoken aloud.")
    static let openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        .result(dialog: IntentDialog(stringLiteral: VoiceBridge.perform(.whatsAhead)))
    }
}

struct ReadTextIntent: AppIntent {
    static let title: LocalizedStringResource = "Read text"
    static let description = IntentDescription("Reads the text the camera sees, aloud.")
    static let openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        .result(dialog: IntentDialog(stringLiteral: VoiceBridge.perform(.readText)))
    }
}

struct PauseWarningsIntent: AppIntent {
    static let title: LocalizedStringResource = "Pause warnings"
    static let description = IntentDescription("Pauses obstacle warnings. Determine reminds you and resumes by itself.")
    static let openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        .result(dialog: IntentDialog(stringLiteral: VoiceBridge.perform(.pauseWarnings)))
    }
}

struct ResumeWarningsIntent: AppIntent {
    static let title: LocalizedStringResource = "Resume warnings"
    static let description = IntentDescription("Resumes obstacle warnings.")
    static let openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        .result(dialog: IntentDialog(stringLiteral: VoiceBridge.perform(.resumeWarnings)))
    }
}

struct StopSpeakingIntent: AppIntent {
    static let title: LocalizedStringResource = "Stop reading"
    static let description = IntentDescription("Stops a description or text reading that is being read aloud.")
    static let openAppWhenRun = false

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        .result(dialog: IntentDialog(stringLiteral: VoiceBridge.perform(.stopSpeaking)))
    }
}

/// "Hey Siri, what's ahead in Determine". Every phrase must contain the app name, which Siri uses to pick the app.
struct DetermineShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(
            intent: WhatsAheadIntent(),
            phrases: [
                "What's ahead in \(.applicationName)",
                "What is ahead in \(.applicationName)",
                "\(.applicationName) what's ahead",
            ],
            shortTitle: "What's ahead",
            systemImageName: "eye"
        )
        AppShortcut(
            intent: ReadTextIntent(),
            phrases: [
                "Read text in \(.applicationName)",
                "\(.applicationName) read text",
            ],
            shortTitle: "Read text",
            systemImageName: "text.viewfinder"
        )
        AppShortcut(
            intent: PauseWarningsIntent(),
            phrases: [
                "Pause warnings in \(.applicationName)",
                "\(.applicationName) pause warnings",
            ],
            shortTitle: "Pause warnings",
            systemImageName: "pause.circle"
        )
        AppShortcut(
            intent: ResumeWarningsIntent(),
            phrases: [
                "Resume warnings in \(.applicationName)",
                "\(.applicationName) resume warnings",
            ],
            shortTitle: "Resume warnings",
            systemImageName: "play.circle"
        )
        AppShortcut(
            intent: StopSpeakingIntent(),
            phrases: [
                "Stop reading in \(.applicationName)",
                "\(.applicationName) stop reading",
            ],
            shortTitle: "Stop reading",
            systemImageName: "speaker.slash"
        )
    }
}
#endif
