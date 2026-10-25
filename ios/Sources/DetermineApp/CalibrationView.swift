#if canImport(SwiftUI)
import SwiftUI

/// Calibration plays tones to each ear through a headset and records what the wearer hears. The iPhone build can play
/// warning sounds now (Settings → Warning sounds) but the calibration session is not ported, so there is nothing to
/// calibrate and the screen says so instead of faking a result.
@MainActor
struct CalibrationView: View {
    var body: some View {
        ScreenContent {
            SectionTitle(title: "Hearing calibration is not available on iOS yet")
            Text("Calibration plays tones through your Bluetooth bone-conduction headset and asks what you hear. That calibration session has not been ported to this iPhone app, so no tone is played, no hearing profile is made and nothing is saved.")
                .font(.body)
            Text("Warning sounds are off by default: warnings arrive as vibration in the glasses temples. If you turn them on in Settings, uncalibrated default quiet levels are used, never louder than -10 dBFS.")
                .font(.body)
            Text("Calibration is available in the Android app.")
                .font(.body)
        }
        .navigationTitle("Hearing calibration")
        .navigationBarTitleDisplayMode(.inline)
    }
}
#endif
