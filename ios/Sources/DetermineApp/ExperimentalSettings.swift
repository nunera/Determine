#if canImport(SwiftUI)
import DetermineEngine
import SwiftUI

/// One tuning slider: label with the live value, a stepped slider, optional footnote.
private struct TuningSlider: View {
    let title: String
    let unit: String
    let range: ClosedRange<Int>
    let step: Int
    let value: Int
    let set: (Int) -> Void
    var note: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("\(title): \(value) \(unit)").font(.headline)
            Slider(
                value: Binding(get: { Double(value) }, set: { set(Int($0.rounded())) }),
                in: Double(range.lowerBound)...Double(range.upperBound),
                step: Double(step)
            ) {
                Text(title)
            }
            .accessibilityValue("\(value) \(unit)")
            if let note {
                Text(note).font(.callout)
            }
        }
    }
}

/// Settings → Experimental & debug: every `Tuning` field the engine accepts at runtime. Changes apply immediately to
/// a running session (no restart) and are saved. Nothing here has been tested on a device.
@MainActor
struct ExperimentalSettings: View {
    @EnvironmentObject private var model: AppModel

    private func slider(
        _ title: String, _ unit: String, _ path: WritableKeyPath<Tuning, Int>, _ range: ClosedRange<Int>, step: Int, note: String? = nil
    ) -> some View {
        TuningSlider(
            title: title, unit: unit, range: range, step: step,
            value: model.tuning[keyPath: path], set: { model.setTuningValue(path, $0) }, note: note
        )
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            header
            bands
            smoothing
            cues
            spoken
            weakLink
            approach
            exploration
            BigAction(title: "Reset to defaults", primary: false) { model.resetTuning() }
        }
    }

    @ViewBuilder private var header: some View {
        SectionTitle(title: "Experimental & debug")
        Callout(
            text: "These change how warnings behave. Changes apply at once, also to a running session, and are saved. None of this has been tested on a device yet. Temple vibration strength (haptic max, 1–255) is the slider above.",
            color: .orange
        )
        if let message = model.tuningMessage {
            Callout(text: message)
                .accessibilityAddTraits(.updatesFrequently)
        }

    }

    @ViewBuilder private var bands: some View {
        let t = model.tuning
        Text("Warning distances").font(.title3.bold())
        TuningSlider(
            title: "NEAR below", unit: "mm", range: 100...8000, step: 50, value: t.nearMm,
            set: { model.setNear($0) }
        )
        TuningSlider(
            title: "MID below", unit: "mm", range: 100...8000, step: 50, value: t.midMm,
            set: { model.setMid($0) }
        )
        TuningSlider(
            title: "FAR up to", unit: "mm", range: 100...8000, step: 50, value: t.farMm,
            set: { model.setFar($0) },
            note: "Kept in order: NEAR < MID < FAR, at least \(AppModel.bandGapMm) mm apart."
        )
        Text("The distance sensor (VL53L4ED) is specified for about 1.2 m. Readings beyond that are unreliable, especially in bright light, so distances above 1200 mm are outside its spec and may miss real obstacles.")
            .font(.callout)
        if t.farMm > 1200 {
            Callout(text: "FAR is \(t.farMm) mm, beyond the sensor's 1.2 m spec.", color: .orange)
        }

    }

    @ViewBuilder private var smoothing: some View {
        Text("Smoothing").font(.title3.bold())
        slider("Enter a band after", "ms", \.bandEnterMs, 0...1000, step: 10,
               note: "Readings must keep indicating a band this long before it is reported.")
        slider("Leave a band after", "ms", \.bandExitMs, 0...3000, step: 50,
               note: "Moving to a less urgent band, or to nothing, needs this long of consistent evidence. Unknown readings still cancel at once.")
        slider("Hysteresis", "mm", \.bandHysteresisMm, 0...500, step: 10,
               note: "To leave a band toward less urgent, the distance must exceed its limit by this much.")

    }

    @ViewBuilder private var cues: some View {
        Text("Cue repeat periods").font(.title3.bold())
        slider("FAR every", "ms", \.farPeriodMs, 100...3000, step: 50)
        slider("MID every", "ms", \.midPeriodMs, 100...2000, step: 50)
        slider("NEAR every", "ms", \.nearPeriodMs, 100...1000, step: 25)

    }

    @ViewBuilder private var spoken: some View {
        Text("Spoken status").font(.title3.bold())
        slider("Announce after holding", "ms", \.announceHoldMs, 0...5000, step: 100,
               note: "\"System ready\" and \"distance sensor limited\" are spoken only once the state has held this long. System off is never delayed.")
        slider("Same phrase again after", "ms", \.announceRepeatMs, 1000...60_000, step: 500)

    }

    @ViewBuilder private var weakLink: some View {
        Text("Weak connection").font(.title3.bold())
        slider("Weak below", "dBm", \.weakLinkRssiDbm, -100...(-40), step: 1,
               note: "Cue board signal strength. Below this for the hold time shows a banner and says \"weak connection\" once. It never changes the system state.")
        slider("Weak after", "ms", \.weakLinkHoldMs, 500...10_000, step: 100)
        slider("Say again after", "ms", \.weakLinkRepeatMs, 5000...120_000, step: 1000)

    }

    @ViewBuilder private var approach: some View {
        let t = model.tuning
        Text("Approach speed (experimental)").font(.title3.bold())
        Toggle(isOn: Binding(get: { t.approachEnabled }, set: { model.setTuningFlag(\.approachEnabled, $0) })) {
            Text("Escalate when closing fast").font(.headline)
        }
        Text("Experimental, off by default. When the closing speed is high enough that contact is near, the reported band is raised by one step (FAR to MID, MID to NEAR).")
            .font(.callout)
        slider("Speed window", "ms", \.approachWindowMs, 100...1000, step: 50)
        slider("Time to contact under", "ms", \.approachTtcMs, 500...5000, step: 100)
        slider("Minimum speed", "mm/s", \.approachMinSpeedMmPerS, 100...2000, step: 50)

    }

    @ViewBuilder private var exploration: some View {
        let t = model.tuning
        Text("Exploration (experimental)").font(.title3.bold())
        Toggle(isOn: Binding(get: { t.exploreEnabled }, set: { model.setTuningFlag(\.exploreEnabled, $0) })) {
            Text("Exploration mode").font(.headline)
        }
        Text("Experimental, off by default (also on the main screen). Replaces band cues with steady temple pulses that get stronger as the nearest thing ahead gets closer. No spoken distance words.")
            .font(.callout)
        slider("Pulse every", "ms", \.explorePeriodMs, 60...500, step: 10)
        slider("Explore up to", "mm", \.exploreMaxMm, 300...3000, step: 50)
        slider("Weakest pulse", "of 255", \.exploreMinIntensity, 1...255, step: 1)
    }
}
#endif
