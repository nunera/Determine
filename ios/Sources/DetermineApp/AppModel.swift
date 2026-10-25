#if canImport(SwiftUI)
import AVFoundation
import DetermineEngine
import DetermineLink
import Foundation
import SwiftUI
import UIKit

/// State of the Read text action shown on Main.
enum ReadTextState: Equatable {
    case idle
    case reading
    /// What was spoken (or why nothing was read).
    case done(String)
}

/// State of the Describe action shown on Main.
enum DescribeState: Equatable {
    case idle
    case requesting
    /// What was spoken (or why nothing was described).
    case done(String)
}

/// Main-actor view model: persisted settings, the live session's published state, and the session lifecycle.
///
/// The engine itself lives in `SessionHost` on its own queue; this type only receives value snapshots from it.
/// A session never survives leaving the foreground and is never restarted implicitly (fail loud, restart by hand).
@MainActor
final class AppModel: ObservableObject {
    // MARK: Persisted settings

    /// Warning sounds: off (haptic only) by default; headset and speaker are explicit choices.
    @Published private(set) var soundOutput: SoundOutput
    /// Headphone layout used when sounds are on (`.stereo` or `.ssd`); ignored while sounds are off.
    @Published private(set) var audioLayout: HearingMode
    @Published private(set) var hapticMax: Int
    @Published private(set) var spearcons: Bool
    @Published private(set) var frameSource: FrameSourceKind
    @Published private(set) var describeEnabled: Bool
    @Published private(set) var describeUrl: String
    /// The bearer key lives in the Keychain; only whether one is stored is published.
    @Published private(set) var describeKeySet: Bool
    /// Explicit consent to upload camera frames with faces unblurred. This build has no face redaction, so Describe
    /// never runs without it. Default off.
    @Published private(set) var describeUnredactedOptIn: Bool
    /// "Hey Determine" listening (iOS 26, experimental, default off). While a session runs it listens on the
    /// iPhone's built-in microphone; see `WakeWordListener`.
    @Published private(set) var wakeWordEnabled: Bool
    /// Optional: words after "Hey Determine" that match no fixed command go, as text only, to the describe server's
    /// router, which decides between a command and a question about the camera view. Default off.
    @Published private(set) var wakeAIEnabled: Bool
    @Published private(set) var wakeState: WakeWordState = .off
    /// Engine tuning (bands, timing, link, EXPERIMENTAL approach and exploration). Persisted, applied live.
    @Published private(set) var tuning: Tuning
    /// Why the last tuning change was not applied.
    @Published private(set) var tuningMessage: String?

    // MARK: Live session

    @Published private(set) var running = false
    /// Between `stop` and the old socket actually closing; Start is refused (two sockets on :47000 split packets).
    @Published private(set) var stopping = false
    @Published private(set) var state: SystemState = .starting
    @Published private(set) var reason = ""
    @Published private(set) var currentAlert: HazardAlert?
    @Published private(set) var diagnostics: Diagnostics?
    @Published private(set) var range: RangeSnapshot?
    @Published private(set) var link = LinkInfo()
    @Published private(set) var camera: PhoneCameraStatus?
    @Published private(set) var liveProfile: HearingProfile?
    @Published private(set) var sound = SoundStats()
    /// Why the last session ended without the user pressing Stop (foreground loss), shown on Main until restart.
    @Published private(set) var stopNotice: String?
    /// Camera permission feedback from the Settings → Camera choice.
    @Published private(set) var cameraMessage: String?
    @Published private(set) var describeState: DescribeState = .idle
    @Published private(set) var phoneAddresses: [LocalAddress] = []
    @Published private(set) var reader: ReadTextState = .idle

    /// Chart history and event log for the Judge screen (kept out of this object so Main does not redraw at 10 Hz).
    let trace = DebugTrace()

    private let defaults: UserDefaults
    private let alerts: IosPhoneAlerts
    private var host: SessionHost?
    /// Events from an earlier session are dropped: each session has its own generation.
    private var generation = 0
    private var restartAfterStop = false
    private var lastAddressRefresh = Date.distantPast
    private var lastBand: Band = .none
    private var lastLinkWeak = false
    private var readToken = 0
    private var describeToken = 0
    private var routeToken = 0
    private var pauseGuard: Task<Void, Never>?

    private enum Key {
        static let soundOutput = "determine.soundOutput"
        static let audioLayout = "determine.audioLayout"
        static let hapticMax = "determine.hapticMax"
        static let spearcons = "determine.spearcons"
        static let frameSource = "determine.frameSource"
        static let describeEnabled = "determine.describeEnabled"
        static let describeUrl = "determine.describeUrl"
        static let describeUnredactedOptIn = "determine.describeUnredactedOptIn"
        static let describeKeyAccount = "describeApiKey"
        static let wakeWordEnabled = "determine.wakeWordEnabled"
        static let wakeAIEnabled = "determine.wakeAIEnabled"
        static let tuningPrefix = "determine.tuning."
    }

    /// Every `Tuning` field with its persistence key. A value that fails validation on load is dropped as a whole.
    private static let tuningInts: [(String, WritableKeyPath<Tuning, Int>)] = [
        ("nearMm", \.nearMm), ("midMm", \.midMm), ("farMm", \.farMm),
        ("bandEnterMs", \.bandEnterMs), ("bandExitMs", \.bandExitMs), ("bandHysteresisMm", \.bandHysteresisMm),
        ("farPeriodMs", \.farPeriodMs), ("midPeriodMs", \.midPeriodMs), ("nearPeriodMs", \.nearPeriodMs),
        ("announceHoldMs", \.announceHoldMs), ("announceRepeatMs", \.announceRepeatMs),
        ("weakLinkRssiDbm", \.weakLinkRssiDbm), ("weakLinkHoldMs", \.weakLinkHoldMs), ("weakLinkRepeatMs", \.weakLinkRepeatMs),
        ("approachWindowMs", \.approachWindowMs), ("approachTtcMs", \.approachTtcMs),
        ("approachMinSpeedMmPerS", \.approachMinSpeedMmPerS),
        ("explorePeriodMs", \.explorePeriodMs), ("exploreMaxMm", \.exploreMaxMm), ("exploreMinIntensity", \.exploreMinIntensity),
    ]
    private static let tuningBools: [(String, WritableKeyPath<Tuning, Bool>)] = [
        ("approachEnabled", \.approachEnabled), ("exploreEnabled", \.exploreEnabled),
    ]

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        self.alerts = IosPhoneAlerts()
        self.soundOutput = defaults.string(forKey: Key.soundOutput).flatMap(SoundOutput.init(rawValue:)) ?? .off
        self.audioLayout = defaults.object(forKey: Key.audioLayout) as? Int == HearingMode.ssd.rawValue ? .ssd : .stereo
        self.hapticMax = min(max(defaults.object(forKey: Key.hapticMax) as? Int ?? 255, MIN_HAPTIC_MAX), 255)
        self.spearcons = defaults.object(forKey: Key.spearcons) as? Bool ?? true
        self.frameSource = (defaults.string(forKey: Key.frameSource) == "phoneCamera") ? .phoneCamera : .camBoard
        self.describeEnabled = defaults.object(forKey: Key.describeEnabled) as? Bool ?? false
        self.describeUrl = defaults.string(forKey: Key.describeUrl) ?? ""
        self.describeKeySet = Keychain.get(Key.describeKeyAccount) != nil
        self.describeUnredactedOptIn = defaults.object(forKey: Key.describeUnredactedOptIn) as? Bool ?? false
        self.wakeWordEnabled = defaults.object(forKey: Key.wakeWordEnabled) as? Bool ?? false
        self.wakeAIEnabled = defaults.object(forKey: Key.wakeAIEnabled) as? Bool ?? false
        self.tuning = Self.loadTuning(defaults)
        // The pre-0.3 single "output mode" setting is replaced by the sound output + layout above.
        defaults.removeObject(forKey: "determine.outputMode")
        refreshAddresses()
    }

    private static func loadTuning(_ defaults: UserDefaults) -> Tuning {
        var t = Tuning()
        for (name, path) in tuningInts {
            if let v = defaults.object(forKey: Key.tuningPrefix + name) as? Int { t[keyPath: path] = v }
        }
        for (name, path) in tuningBools {
            if let v = defaults.object(forKey: Key.tuningPrefix + name) as? Bool { t[keyPath: path] = v }
        }
        do {
            try t.validate()
            return t
        } catch {
            return Tuning()
        }
    }

    private func persistTuning() {
        for (name, path) in Self.tuningInts { defaults.set(tuning[keyPath: path], forKey: Key.tuningPrefix + name) }
        for (name, path) in Self.tuningBools { defaults.set(tuning[keyPath: path], forKey: Key.tuningPrefix + name) }
    }

    // MARK: Derived

    var paused: Bool { running && state == .paused }

    /// Engine mode: HAPTIC_ONLY exactly when no warning sound output is chosen.
    var mode: HearingMode { soundOutput == .off ? .hapticOnly : audioLayout }

    /// Warning sounds are on, so the engine needs the chosen audio route (the cue board is never the audio path).
    var soundsOn: Bool { soundOutput != .off }

    private var hazardActive: Bool { (currentAlert?.band ?? Band.none) >= .mid }

    // MARK: Session lifecycle

    func start() {
        guard !running, !stopping else { return }
        generation += 1
        let gen = generation
        running = true
        state = .starting
        reason = "starting"
        currentAlert = nil
        diagnostics = nil
        range = nil
        link = LinkInfo()
        camera = nil
        liveProfile = nil
        sound = SoundStats()
        stopNotice = nil
        describeState = .idle
        tuningMessage = nil
        reader = .idle
        lastBand = .none
        lastLinkWeak = false
        trace.reset()
        trace.log("Session started")
        trace.recordState(.starting, reason: "", atUs: EngineClock.nowUs())
        refreshAddresses()
        UIApplication.shared.isIdleTimerDisabled = true
        let host = SessionHost(
            options: HostOptions(
                spearcons: spearcons, frameSource: frameSource, mode: mode, soundOutput: soundOutput,
                hapticMax: hapticMax, tuning: tuning
            ),
            alerts: alerts
        ) { [weak self] event in
            // FIFO onto the main queue; `Task` hops are not ordered.
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.apply(event, generation: gen) } }
        }
        self.host = host
        host.start()
        startWakeWord()
    }

    /// Stops the session (PINGs end, so the board watchdog fires). `notice` explains an involuntary stop.
    func stop(notice: String? = nil) {
        guard running, let host else { return }
        running = false
        stopping = true
        generation += 1
        pauseGuard?.cancel()
        pauseGuard = nil
        stopWakeWord()
        self.host = nil
        reason = ""
        currentAlert = nil
        stopNotice = notice
        readToken += 1
        reader = .idle
        alerts.stopReading()
        describeToken += 1
        routeToken += 1
        describeState = .idle
        trace.log(notice == nil ? "Session stopped" : "Session stopped: app left the foreground")
        UIApplication.shared.isIdleTimerDisabled = false
        host.stop { [weak self] in
            MainActor.assumeIsolated {
                guard let self else { return }
                self.stopping = false
                if self.restartAfterStop {
                    self.restartAfterStop = false
                    self.start()
                }
            }
        }
    }

    /// Scene entered the background: iOS will suspend the process, so the hazard loop cannot be trusted to run.
    /// Say so and stop (PINGs end, the board watchdog fires). Returning never resumes by itself. `.inactive`
    /// (permission sheets, Control Center, call banners) does not come here: the process keeps running.
    func sceneEnteredBackground() {
        guard running else { return }
        alerts.systemOff()
        stop(notice: "Determine stopped because the app went to the background. Warnings are off. Open the app and tap Start Determine to resume.")
    }

    /// A pause is never silent and never forgotten: Determine repeats "Warnings are paused" every
    /// `VoiceControlSettings.pauseReminderSeconds` and resumes by itself after `autoResumeSeconds`. This applies to
    /// the Main button and to voice alike, so a mistaken or forgotten pause cannot leave the wearer unwarned.
    func setPaused(_ paused: Bool) {
        host?.setPaused(paused)
        pauseGuard?.cancel()
        pauseGuard = nil
        guard paused else { return }
        trace.log("Warnings paused")
        pauseGuard = Task { @MainActor [weak self] in
            var elapsed = 0
            while !Task.isCancelled {
                try? await Task.sleep(for: .seconds(VoiceControlSettings.pauseReminderSeconds))
                guard !Task.isCancelled, let self, self.running else { return }
                elapsed += VoiceControlSettings.pauseReminderSeconds
                if elapsed >= VoiceControlSettings.autoResumeSeconds {
                    self.host?.setPaused(false)
                    self.pauseGuard = nil
                    self.trace.log("Warnings resumed automatically after \(elapsed) s")
                    return
                }
                self.alerts.speak("Warnings are paused")
            }
        }
    }

    // MARK: Voice commands

    /// Runs one voice command (Siri or the wake word) and says what happened. Voice only ever triggers the actions
    /// already on Main; it never reaches the hazard decision. Nothing happens unless a session is running: a voice
    /// command must not start the system or change what the wearer is warned about by itself.
    @discardableResult
    func perform(_ command: VoiceCommand) -> VoiceReply {
        guard running else { return VoiceReply(text: "Determine is not running. Open the app and tap Start Determine.", ok: false) }
        trace.log("Voice command: \(command.rawValue)")
        switch command {
        case .whatsAhead:
            guard describeEnabled else { return VoiceReply(text: "Description is off in Settings.", ok: false) }
            requestDescription()
            if case let .done(message) = describeState { return VoiceReply(text: message, ok: false) }
            return VoiceReply(text: "Asking.", ok: true)
        case .readText:
            readText()
            if case let .done(message) = reader { return VoiceReply(text: message, ok: false) }
            return VoiceReply(text: "Reading.", ok: true)
        case .pauseWarnings:
            guard !paused else { return VoiceReply(text: "Warnings are already paused.", ok: false) }
            setPaused(true)
            return VoiceReply(text: "Warnings paused.", ok: true)
        case .resumeWarnings:
            guard paused else { return VoiceReply(text: "Warnings are not paused.", ok: false) }
            setPaused(false)
            return VoiceReply(text: "Resuming warnings.", ok: true)
        case .stopSpeaking:
            stopSpeaking()
            return VoiceReply(text: "Stopped.", ok: true)
        }
    }

    /// Cuts a reading or description that is being read out. Hazard words and "System off" are never cut.
    private func stopSpeaking() {
        if reader != .idle {
            readToken += 1
            alerts.stopReading()
            reader = .idle
        }
        cancelDescription()
    }

    // MARK: Wake word

    private var wake: WakeWordListener?

    func setWakeWordEnabled(_ enabled: Bool) {
        wakeWordEnabled = enabled
        defaults.set(enabled, forKey: Key.wakeWordEnabled)
        if enabled { startWakeWord() } else { stopWakeWord() }
    }

    /// Listening exists only while a session runs and the wearer turned it on. It starts and ends with the session.
    private func startWakeWord() {
        guard running, wakeWordEnabled, wake == nil else { return }
        let gen = generation
        wakeState = .starting
        let listener = WakeWordListener { [weak self] event in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.handleWake(event, generation: gen) } }
        }
        wake = listener
        listener.start()
    }

    private func stopWakeWord() {
        wake?.stop()
        wake = nil
        wakeState = .off
    }

    private func handleWake(_ event: WakeWordEvent, generation gen: Int) {
        guard gen == generation, running, wakeWordEnabled else { return }
        switch event {
        case let .state(state):
            wakeState = state
            trace.log("Wake word: \(state.label)")
        case let .log(message):
            trace.log("Wake word: \(message)")
        case .wake:
            trace.log("Wake phrase heard")
            alerts.vibrate(pattern: [0, 80])
            alerts.speak("Listening")
        case let .command(command):
            let reply = perform(command)
            // Pause and resume announce themselves; the others acknowledge, and a refusal always says why.
            if !reply.ok || command == .whatsAhead || command == .readText || command == .stopSpeaking {
                alerts.speak(reply.text)
            }
        case let .unmatched(words):
            guard wakeAIEnabled else {
                trace.log("Voice: no fixed command in \"\(words)\" (AI understanding is off)")
                alerts.speak("Didn't catch that")
                return
            }
            routeVoice(words)
        }
    }

    func setWakeAIEnabled(_ enabled: Bool) {
        wakeAIEnabled = enabled
        defaults.set(enabled, forKey: Key.wakeAIEnabled)
        if !enabled { routeToken += 1 }
    }

    /// Words no fixed command matched, sent as text (never audio or an image) to the describe server's router. It
    /// answers with a fixed command to run, a question to ask about the camera view (one frame is then uploaded with
    /// it, the same as What's ahead), or nothing. Needs the Describe settings and an explicit opt-in to upload.
    private func routeVoice(_ words: String) {
        guard describeEnabled, let endpoint = describeEndpoint else {
            trace.log("Voice: AI understanding needs Describe set up in Settings")
            alerts.speak("Set up Describe in Settings first")
            return
        }
        routeToken += 1
        let token = routeToken
        let gen = generation
        trace.log("Voice: asking the server what \"\(words)\" means (text only)")
        Task { [weak self] in
            let decision = await DescribeClient.route(endpoint: endpoint, transcript: words)
            self?.finishRouting(decision, token: token, generation: gen)
        }
    }

    private func finishRouting(_ decision: VoiceDecision, token: Int, generation gen: Int) {
        guard token == routeToken, gen == generation, running else { return }
        switch decision {
        case let .command(command):
            trace.log("Voice: AI chose \(command.rawValue)")
            let reply = perform(command)
            if !reply.ok || command == .whatsAhead || command == .readText || command == .stopSpeaking {
                alerts.speak(reply.text)
            }
        case let .ask(question):
            trace.log("Voice: AI chose a question about the camera view")
            requestDescription(question: question)
            if case let .done(message) = describeState {
                alerts.speak(message)
            } else {
                alerts.speak("Looking.")
            }
        case .none:
            trace.log("Voice: AI chose no action")
            alerts.speak("Didn't catch that")
        case let .failed(code, _):
            trace.log("Voice: router failed: \(code)")
            switch code {
            case "unauthorized": alerts.speak("The server refused the key")
            case "timeout", "network", "unavailable": alerts.speak("The assistant did not answer")
            default: alerts.speak("Didn't catch that")
            }
        }
    }

    // MARK: Settings

    func setSoundOutput(_ output: SoundOutput) {
        guard output != soundOutput else { return }
        soundOutput = output
        defaults.set(output.rawValue, forKey: Key.soundOutput)
        host?.setSoundOutput(output, mode: mode)
    }

    func setAudioLayout(_ layout: HearingMode) {
        guard layout == .stereo || layout == .ssd, layout != audioLayout else { return }
        audioLayout = layout
        defaults.set(layout.rawValue, forKey: Key.audioLayout)
        host?.setSoundOutput(soundOutput, mode: mode)
    }

    func setHapticMax(_ strength: Int) {
        let clamped = min(max(strength, MIN_HAPTIC_MAX), 255)
        hapticMax = clamped
        defaults.set(clamped, forKey: Key.hapticMax)
        host?.setHapticMax(clamped)
    }

    func setSpearcons(_ enabled: Bool) {
        spearcons = enabled
        defaults.set(enabled, forKey: Key.spearcons)
        host?.setSpearcons(enabled)
    }

    func setDescribeEnabled(_ enabled: Bool) {
        describeEnabled = enabled
        defaults.set(enabled, forKey: Key.describeEnabled)
        if !enabled { cancelDescription() }
    }

    func setDescribeUrl(_ url: String) {
        let trimmed = url.trimmingCharacters(in: .whitespacesAndNewlines)
        describeUrl = trimmed
        defaults.set(trimmed, forKey: Key.describeUrl)
    }

    /// An empty key removes the stored one.
    func setDescribeKey(_ key: String) {
        let trimmed = key.trimmingCharacters(in: .whitespacesAndNewlines)
        Keychain.set(trimmed.isEmpty ? nil : trimmed, account: Key.describeKeyAccount)
        describeKeySet = Keychain.get(Key.describeKeyAccount) != nil
    }

    func setDescribeUnredactedOptIn(_ optIn: Bool) {
        describeUnredactedOptIn = optIn
        defaults.set(optIn, forKey: Key.describeUnredactedOptIn)
        if !optIn { cancelDescription() }
    }

    // MARK: Tuning

    /// Smallest gap kept between two band thresholds, mm.
    static let bandGapMm = 50

    /// Applies `edit` to a copy of the tuning, validates it, persists it and sends it to the live engine.
    /// A value the engine would refuse is not applied and the reason is shown.
    func updateTuning(_ edit: (inout Tuning) -> Void) {
        var next = tuning
        edit(&next)
        guard next != tuning else { return }
        do {
            try next.validate()
        } catch {
            tuningMessage = "Not applied: \(error)"
            return
        }
        tuningMessage = nil
        tuning = next
        persistTuning()
        host?.setTuning(next)
    }

    func setTuningValue(_ path: WritableKeyPath<Tuning, Int>, _ value: Int) {
        updateTuning { $0[keyPath: path] = value }
    }

    func setTuningFlag(_ path: WritableKeyPath<Tuning, Bool>, _ value: Bool) {
        updateTuning { $0[keyPath: path] = value }
    }

    /// Band thresholds keep 0 < near < mid < far ≤ 8000 with `bandGapMm` between them: a slider cannot cross its
    /// neighbour.
    func setNear(_ mm: Int) {
        updateTuning { $0.nearMm = min(mm, $0.midMm - Self.bandGapMm) }
    }

    func setMid(_ mm: Int) {
        updateTuning { $0.midMm = min(max(mm, $0.nearMm + Self.bandGapMm), $0.farMm - Self.bandGapMm) }
    }

    func setFar(_ mm: Int) {
        updateTuning { $0.farMm = max(mm, $0.midMm + Self.bandGapMm) }
    }

    func resetTuning() {
        updateTuning { $0 = Tuning() }
    }

    /// Camera board vs iPhone camera. Changing it restarts a running session. The iPhone camera needs permission,
    /// asked here; if it is denied the choice is not applied and the reason stays visible.
    func selectFrameSource(_ source: FrameSourceKind) async {
        guard source != frameSource else { return }
        if source == .phoneCamera {
            let status = await PhoneCameraSource.requestPermission()
            if let problem = PhoneCameraSource.permissionDescription(status) {
                cameraMessage = "iPhone camera not selected: \(problem)."
                return
            }
        }
        cameraMessage = nil
        frameSource = source
        defaults.set(source == .phoneCamera ? "phoneCamera" : "camBoard", forKey: Key.frameSource)
        if running {
            restartAfterStop = true
            stop()
        }
    }

    // MARK: Debug

    func sendTestBuzz(pattern: Int, intensity: Int) {
        host?.sendTestHaptic(pattern: pattern, intensity: min(max(intensity, MIN_HAPTIC_MAX), 255))
    }

    // MARK: Read text

    /// Read text: OCR of the newest camera frame, spoken as read. Display-only: never read by
    /// hazard logic. Not started while a MID/NEAR warning is active or the system is off; a second tap while it is
    /// reading stops it.
    func readText() {
        guard running, let host else { return }
        if reader == .reading {
            readToken += 1
            alerts.stopReading()
            reader = .idle
            return
        }
        guard state != .unavailable, !hazardActive else {
            reader = .done("Not reading: a warning is active or the system is off.")
            return
        }
        readToken += 1
        let token = readToken
        let gen = generation
        reader = .reading
        host.readText { [weak self] result in
            DispatchQueue.main.async { MainActor.assumeIsolated { self?.finishReading(result, token: token, generation: gen) } }
        }
    }

    private func finishReading(_ result: ReadTextResult, token: Int, generation gen: Int) {
        guard token == readToken, gen == generation, running else { return }
        guard state != .unavailable, !hazardActive else {
            reader = .idle
            return
        }
        let spoken: String
        switch result {
        case let .text(text): spoken = text
        case .noText: spoken = "No text found"
        case .noFreshFrame: spoken = "No fresh camera image"
        case .failed: spoken = "Text reading failed"
        }
        if case let .failed(why) = result { trace.log("Read text failed: \(why)") }
        reader = .done(spoken)
        alerts.speakReading(spoken)
    }

    /// MID/NEAR alerts and system off cut a reading or a description in progress or being spoken. Only that speech
    /// is stopped: a hazard word or "System off" that replaced it is never cut.
    private func interruptReadingIfNeeded() {
        guard state == .unavailable || hazardActive else { return }
        if reader != .idle {
            readToken += 1
            alerts.stopReading()
            reader = .idle
        }
        if describeState != .idle {
            describeToken += 1
            alerts.stopReading()
            describeState = .idle
        }
    }

    // MARK: Describe

    private var describeEndpoint: DescribeEndpoint? {
        guard !describeUrl.isEmpty, let key = Keychain.get(Key.describeKeyAccount) else { return nil }
        return DescribeEndpoint(baseUrl: describeUrl, apiKey: key)
    }

    /// Cancels a request in flight or the description being spoken. The response of a cancelled request is dropped.
    private func cancelDescription() {
        guard describeState != .idle else { return }
        describeToken += 1
        alerts.stopReading()
        describeState = .idle
    }

    /// Describe: one camera frame to the describe-api on the wearer's explicit request, never automatically and
    /// never read by hazard logic. This build has no face redaction, so it needs the wearer's explicit opt-in to
    /// upload unblurred frames. A second tap while a request is in flight cancels it.
    func requestDescription(question: String? = nil) {
        guard running, let host else { return }
        if describeState == .requesting {
            cancelDescription()
            describeState = .done("Description cancelled.")
            return
        }
        guard describeUnredactedOptIn else {
            describeState = .done("Description unavailable: it uploads camera frames with faces unblurred, which is off. Turn it on in Settings first. Nothing was sent.")
            return
        }
        guard let endpoint = describeEndpoint else {
            describeState = .done("Description unavailable: set the server URL and key in Settings. Nothing was sent.")
            return
        }
        guard state != .unavailable, !hazardActive else {
            describeState = .done("Not describing: a warning is active or the system is off.")
            return
        }
        describeToken += 1
        let token = describeToken
        let gen = generation
        describeState = .requesting
        host.describeFrame { [weak self] frame in
            DispatchQueue.main.async {
                MainActor.assumeIsolated { self?.sendDescription(frame, to: endpoint, question: question, token: token, generation: gen) }
            }
        }
    }

    private func sendDescription(_ frame: AssembledFrame?, to endpoint: DescribeEndpoint, question: String?, token: Int, generation gen: Int) {
        guard token == describeToken, gen == generation, running else { return }
        guard let frame else {
            finishDescribing(.failed(code: "no_frame", retryable: true), token: token, generation: gen)
            return
        }
        trace.log("Describe: sending one unblurred \(frame.w)x\(frame.h) frame (\(frame.jpeg.count) bytes)")
        Task { [weak self] in
            let result = await DescribeClient.send(endpoint: endpoint, jpeg: frame.jpeg, width: frame.w, height: frame.h, question: question)
            self?.finishDescribing(result, token: token, generation: gen)
        }
    }

    private func finishDescribing(_ result: DescribeResult, token: Int, generation gen: Int) {
        guard token == describeToken, gen == generation, running else { return }
        guard state != .unavailable, !hazardActive else {
            describeState = .idle
            return
        }
        let spoken: String
        switch result {
        case let .text(text):
            spoken = text
            trace.log("Describe: answered")
        case let .failed(code, _):
            trace.log("Describe failed: \(code)")
            switch code {
            case "no_frame": spoken = "No fresh camera image"
            case "unauthorized": spoken = "Description failed: the server refused the key"
            case "bad_url": spoken = "Description unavailable: check the server URL and key in Settings"
            case "timeout", "network", "unavailable": spoken = "Description unavailable: the server did not answer"
            default: spoken = "Description failed"
            }
        }
        describeState = .done(spoken)
        alerts.speakReading(spoken)
    }

    // MARK: Published events

    func refreshAddresses() {
        phoneAddresses = LocalAddresses.ipv4()
        lastAddressRefresh = Date()
    }

    private func apply(_ event: HostEvent, generation gen: Int) {
        guard gen == generation, running else { return }
        switch event {
        case let .state(s, reason: r):
            let changed = s != state
            state = s
            reason = r
            if changed {
                trace.recordState(s, reason: r, atUs: EngineClock.nowUs())
            }
            if changed, s != .starting {
                UIAccessibility.post(notification: .announcement, argument: "Status: \(StateText.title(s))")
            }
            interruptReadingIfNeeded()
        case let .alert(a):
            currentAlert = a
            let band = a?.band ?? Band.none
            if band != lastBand {
                lastBand = band
                if let a, band != .none {
                    trace.log("Alert: \(describeAlert(a))")
                } else {
                    trace.log("Alert: none")
                }
            }
            interruptReadingIfNeeded()
        case let .diagnostics(d):
            diagnostics = d
            trace.addDiagnostics(d)
            if d.linkWeak != lastLinkWeak {
                lastLinkWeak = d.linkWeak
                let dbm = d.cue.rssiDbm.map { " (\($0) dBm)" } ?? ""
                trace.log(d.linkWeak ? "Link weak\(dbm)" : "Link recovered\(dbm)")
            }
        case let .slow(range: r, ranges: batch, link: l, camera: c, profile: p, sound: snd):
            if let r { range = r }
            link = l
            camera = c
            liveProfile = p
            sound = snd
            trace.addRanges(batch)
            if Date().timeIntervalSince(lastAddressRefresh) > 2 { refreshAddresses() }
        case let .tuningRejected(why):
            tuningMessage = "Engine refused the tuning: \(why)"
        case let .testBuzz(pattern: p, intensity: i, sent: sent):
            let name = Self.hapticName(p)
            trace.log(sent ? "Test buzz \(name) at \(i): sent" : "Test buzz \(name) at \(i): NOT sent (no clock sync or cue board not heard)")
        }
    }

    /// PROTOCOL.md haptic pattern ids a test buzz can use.
    static let testPatterns: [(id: Int, name: String)] = [
        (Haptic.pulse40, "PULSE40"), (Haptic.pulse60, "PULSE60"), (Haptic.buzz150, "BUZZ150"), (Haptic.lss, "LSS"),
    ]

    static func hapticName(_ id: Int) -> String {
        testPatterns.first { $0.id == id }?.name ?? "pattern \(id)"
    }
}
#endif
