#if canImport(AVFoundation)
import AVFoundation
import AudioToolbox
import DetermineEngine
import Foundation
#if canImport(CoreHaptics)
import CoreHaptics
#endif

/// Phone-side outputs (Arch §8.2 spoken layer, §4.2 failure path), the iOS counterpart of Android
/// `AndroidPhoneAlerts`:
///  - `speak`: AVSpeechSynthesizer at a raised rate so short hazard words act as spearcons. The newest utterance
///    flushes older ones: a stale spoken hint must never delay a fresh one.
///  - `vibrate`: the phone's own vibration for a `[off, on, off, on …]` millisecond pattern (Android waveform
///    convention). Core Haptics when the hardware supports it, else the system vibrate sound.
///  - `systemOff`: the spoken words "System off" at normal rate plus a long vibration. The engine follows every
///    `systemOff()` with its own `vibrate` pattern and repeats both every 5 s while UNAVAILABLE.
///
/// Engine callbacks arrive on the engine queue; everything is forwarded to the main queue in FIFO order, where
/// the synthesizer and the haptic engine live. This type is therefore safely `Sendable`: it only holds the main-actor
/// `Output`.
final class IosPhoneAlerts: PhoneAlerts, @unchecked Sendable {
    static let systemOffText = "System off"

    @MainActor
    private final class Output {
        /// `AVSpeechUtteranceDefaultSpeechRate` is 0.5; Android speeds hazard words up 1.8×. The iOS rate scale is
        /// not linear, so 0.62 is an estimate that must be tuned by ear on a device (untested).
        private static let spearconRate: Float = 0.62

        private let synth = AVSpeechSynthesizer()
        /// True while the utterance being spoken is a Read text result (and nothing newer replaced it).
        private var readingActive = false
        #if canImport(CoreHaptics)
        private var haptics: CHHapticEngine?
        private var hapticsTried = false
        #endif

        func prepareAudioSession() {
            PhoneAudioSession.shared.activate()
        }

        func speak(_ text: String, alarm: Bool) {
            prepareAudioSession()
            readingActive = false
            synth.stopSpeaking(at: .immediate)
            let utterance = AVSpeechUtterance(string: text)
            utterance.rate = alarm ? AVSpeechUtteranceDefaultSpeechRate : Self.spearconRate
            utterance.volume = 1
            utterance.voice = AVSpeechSynthesisVoice(language: AVSpeechSynthesisVoice.currentLanguageCode())
            synth.speak(utterance)
        }

        /// Read text output: normal rate, and `stopReading` can cut it without touching hazard speech.
        func speakReading(_ text: String) {
            prepareAudioSession()
            synth.stopSpeaking(at: .immediate)
            readingActive = true
            let utterance = AVSpeechUtterance(string: text)
            utterance.rate = AVSpeechUtteranceDefaultSpeechRate
            utterance.volume = 1
            utterance.voice = AVSpeechSynthesisVoice(language: AVSpeechSynthesisVoice.currentLanguageCode())
            synth.speak(utterance)
        }

        /// Stops the speech only if it is still a reading: a hazard word or "System off" that replaced it stays.
        func stopReading() {
            guard readingActive else { return }
            readingActive = false
            synth.stopSpeaking(at: .immediate)
        }

        func vibrate(_ pattern: [Int64]) {
            guard !pattern.isEmpty else { return }
            #if canImport(CoreHaptics)
            if CHHapticEngine.capabilitiesForHardware().supportsHaptics, let engine = hapticEngine() {
                var events: [CHHapticEvent] = []
                var t: Int64 = 0
                for (i, ms) in pattern.enumerated() {
                    if i % 2 == 1, ms > 0 {
                        events.append(CHHapticEvent(
                            eventType: .hapticContinuous,
                            parameters: [
                                CHHapticEventParameter(parameterID: .hapticIntensity, value: 1),
                                CHHapticEventParameter(parameterID: .hapticSharpness, value: 0.5),
                            ],
                            relativeTime: Double(t) / 1000,
                            duration: Double(ms) / 1000))
                    }
                    t += max(ms, 0)
                }
                if !events.isEmpty {
                    do {
                        let player = try engine.makePlayer(with: CHHapticPattern(events: events, parameters: []))
                        try engine.start()
                        try player.start(atTime: CHHapticTimeImmediate)
                        return
                    } catch {
                        NSLog("Determine: Core Haptics failed, using system vibrate: \(error)")
                    }
                }
            }
            #endif
            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
        }

        #if canImport(CoreHaptics)
        private func hapticEngine() -> CHHapticEngine? {
            if let haptics { return haptics }
            guard !hapticsTried else { return nil }
            hapticsTried = true
            do {
                let engine = try CHHapticEngine()
                // The engine stops by itself (idle, audio interruption); `start()` before each pattern restarts it.
                engine.isAutoShutdownEnabled = true
                engine.resetHandler = { [weak engine] in try? engine?.start() }
                haptics = engine
                return engine
            } catch {
                NSLog("Determine: cannot create haptic engine: \(error)")
                return nil
            }
        }
        #endif

        func stopAll() {
            readingActive = false
            synth.stopSpeaking(at: .immediate)
            #if canImport(CoreHaptics)
            haptics?.stop()
            #endif
        }
    }

    private let output: Output

    @MainActor
    init() {
        output = Output()
    }

    /// Forward to the main queue in FIFO order (`Task` hops are not ordered).
    private func onMain(_ body: @escaping @MainActor @Sendable (Output) -> Void) {
        let output = output
        DispatchQueue.main.async { MainActor.assumeIsolated { body(output) } }
    }

    func speak(_ text: String) {
        onMain { $0.speak(text, alarm: false) }
    }

    /// Read text result: normal speech rate; a later hazard word or "System off" replaces it.
    func speakReading(_ text: String) {
        onMain { $0.speakReading(text) }
    }

    /// Stops the speech only while a Read text result is the active utterance (hazard words stay).
    func stopReading() {
        onMain { $0.stopReading() }
    }

    func vibrate(pattern: [Int64]) {
        onMain { $0.vibrate(pattern) }
    }

    func systemOff() {
        onMain {
            $0.speak(Self.systemOffText, alarm: true)
            // Android's three-note alarm tone has no iOS counterpart yet; a long buzz keeps the failure non-visual.
            $0.vibrate([0, 700])
        }
    }

    /// Cancels speech and vibration (session ended deliberately, not by a failure).
    func silence() {
        onMain { $0.stopAll() }
    }
}
#endif
