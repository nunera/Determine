#if canImport(SwiftUI)
import SwiftUI

@MainActor
@main
struct DetermineApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            NavigationStack {
                MainView()
            }
            .environmentObject(model)
            .onAppear { VoiceBridge.model = model }
        }
        .onChange(of: scenePhase) { _, newPhase in
            // Foreground only. `.inactive` (permission sheets, Control Center, call banners) keeps the process and
            // its timers running, so the session continues. Only `.background` leads to suspension: it ends the
            // session loudly, and returning never restarts it.
            if newPhase == .background {
                model.sceneEnteredBackground()
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
