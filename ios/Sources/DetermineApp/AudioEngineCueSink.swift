#if canImport(AVFoundation)
import AVFoundation
import DetermineEngine
import Foundation

/// Where the phone's warning sounds (the `CueBank` cue language) go. Haptic only is the default and the shipped
/// behaviour: the temples vibrate and the phone only speaks. The other two are explicit choices.
enum SoundOutput: String, CaseIterable, Sendable {
    /// No warning sounds: haptic only (engine mode HAPTIC_ONLY, the sink is never asked to play audio).
    case off
    /// Bluetooth headset only (A2DP, LE Audio or HFP). If the current route is anything else the sink answers
    /// `AUDIO_ROUTE_LOST`: sound never silently goes to the phone speaker.
    case headset
    /// The iPhone's own speaker. Debug aid for working on the cues without the headset; not the product.
    case speaker

    var label: String {
        switch self {
        case .off: return "Off (haptic only)"
        case .headset: return "Bluetooth headset"
        case .speaker: return "iPhone speaker (debug)"
        }
    }
}

/// The one shared `AVAudioSession` configuration for speech, spearcons and cue sounds.
final class PhoneAudioSession: @unchecked Sendable {
    static let shared = PhoneAudioSession()

    private let lock = NSLock()
    private var ready = false

    /// Category `.playback` ignores the silent switch: a hazard or failure announcement must be heard. Idempotent.
    @discardableResult
    func activate() -> Bool {
        lock.lock()
        defer { lock.unlock() }
        if ready { return true }
        do {
            let session = AVAudioSession.sharedInstance()
            try session.setCategory(.playback, mode: .voicePrompt, options: [.interruptSpokenAudioAndMixWithOthers])
            try session.setActive(true)
            ready = true
        } catch {
            // Speech still plays through the default session; vibration is unaffected.
            NSLog("Determine: audio session setup failed: \(error)")
        }
        return ready
    }

    /// After an interruption the session must be activated again.
    @discardableResult
    func reactivate() -> Bool {
        lock.lock()
        ready = false
        lock.unlock()
        return activate()
    }
}

/// Facts about the current audio output route.
enum OutputRoute {
    private static func outputs() -> [AVAudioSessionPortDescription] { AVAudioSession.sharedInstance().currentRoute.outputs }

    static func isBluetooth(_ port: AVAudioSession.Port) -> Bool {
        port == .bluetoothA2DP || port == .bluetoothLE || port == .bluetoothHFP
    }

    /// Every active output is a Bluetooth headset (and there is at least one).
    static func bluetoothOnly() -> Bool {
        let o = outputs()
        return !o.isEmpty && o.allSatisfy { isBluetooth($0.portType) }
    }

    /// Every active output is the built-in speaker.
    static func speakerOnly() -> Bool {
        let o = outputs()
        return !o.isEmpty && o.allSatisfy { $0.portType == .builtInSpeaker }
    }

    static func matches(_ output: SoundOutput) -> Bool {
        switch output {
        case .off: return false
        case .headset: return bluetoothOnly()
        case .speaker: return speakerOnly()
        }
    }

    static func describe() -> String {
        let o = outputs()
        return o.isEmpty ? "none" : o.map { "\($0.portName) (\($0.portType.rawValue))" }.joined(separator: ", ")
    }
}

/// Counters and route facts for the Judge debug panel.
struct SoundStats: Equatable, Sendable {
    var output: SoundOutput = .off
    var route = "none"
    var routeOk = false
    var engineRunning = false
    var played: UInt64 = 0
    var dropped: UInt64 = 0
    var routeLost: UInt64 = 0
    var failures: UInt64 = 0
    var error: String?
}

/// The iPhone's `LocalCueSink` (Arch §4.1): the `CueBank` cue language rendered to PCM and played through an
/// `AVAudioEngine`, the counterpart of Android `PhoneCueSink`.
///
/// There is no silent fallback:
///  - `.off` answers `AUDIO_ROUTE_LOST` to everything (it is only reached when the engine mode is not HAPTIC_ONLY);
///  - `.headset` plays only while the current route is Bluetooth (A2DP / LE Audio / HFP), otherwise `AUDIO_ROUTE_LOST`
///    and a cue in flight is cut: sound never reaches the speaker;
///  - `.speaker` plays only while the route is the built-in speaker (a connected Bluetooth or wired output keeps
///    iOS from using it; the sink then answers `AUDIO_ROUTE_LOST` instead of playing into someone's headset at the
///    debug level) and answers `APPLIED` for it;
///  - a muted, haptic-free READY command (the engine's link probe) plays nothing and answers per route;
///  - an engine that cannot start or a failed schedule answers `FAILED` (a missing ACK to the engine).
/// Like the board it replaces, it decides BAD_CUE / BUSY_DROPPED / APPLIED / APPLIED_CLAMPED itself, clamping each
/// gain to min(profile ceiling, −10 dBFS) independently of the engine.
///
/// Threading: `play` runs on the engine queue and only decides; a private serial queue owns the `AVAudioEngine`,
/// renders PCM and schedules it, so the engine never waits for audio. `@unchecked Sendable`: shared state is behind
/// `lock`, audio objects are confined to `audioQueue`.
final class AudioEngineCueSink: LocalCueSink, @unchecked Sendable {
    /// Returned for an engine/schedule failure that is not a route loss (counted by the engine as a missing ACK).
    static let failed = -1
    private static let sampleRate = 48_000

    /// The ceiling of the live profile, read inside `play` (i.e. on the engine queue).
    private let ceilingDb: @Sendable () -> Int
    private let audioQueue = DispatchQueue(label: "org.determine.cue-audio", qos: .userInteractive)
    private let lock = NSLock()

    // Shared state (lock).
    private var output: SoundOutput
    private var busyUntilUs: Int64 = 0
    private var engineRunning = false
    private var startError: String?
    private var stats = SoundStats()
    private var closed = false

    // audioQueue-confined.
    private var engine: AVAudioEngine?
    private var player: AVAudioPlayerNode?
    private var format: AVAudioFormat?
    private var tokens: [NSObjectProtocol] = []

    init(output: SoundOutput, ceilingDb: @escaping @Sendable () -> Int) {
        self.output = output
        self.ceilingDb = ceilingDb
        stats.output = output
        let nc = NotificationCenter.default
        tokens = [
            nc.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: nil) { [weak self] _ in
                guard let self else { return }
                audioQueue.async { self.routeChanged() }
            },
            nc.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: nil) { [weak self] note in
                let began = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt)
                    .flatMap(AVAudioSession.InterruptionType.init(rawValue:)) == .began
                guard let self else { return }
                audioQueue.async { self.interrupted(began: began) }
            },
            nc.addObserver(forName: .AVAudioEngineConfigurationChange, object: nil, queue: nil) { [weak self] _ in
                guard let self else { return }
                audioQueue.async { self.configurationChanged() }
            },
        ]
    }

    /// Activates the audio session now (so the first muted probe sees the real route) and warms the engine up.
    func prepare() {
        if currentOutput() != .off { PhoneAudioSession.shared.activate() }
        audioQueue.async { [self] in
            if currentOutput() == .off { stopEngine() } else { _ = ensureStarted() }
        }
    }

    func setOutput(_ new: SoundOutput) {
        lock.lock()
        output = new
        busyUntilUs = 0
        lock.unlock()
        prepare()
    }

    func snapshot() -> SoundStats {
        lock.lock()
        var s = stats
        s.output = output
        s.engineRunning = engineRunning
        s.error = startError
        lock.unlock()
        s.route = OutputRoute.describe()
        s.routeOk = OutputRoute.matches(s.output)
        return s
    }

    /// Stops all sound and releases the audio engine; the sink is unusable afterwards.
    func close() {
        lock.lock()
        closed = true
        lock.unlock()
        let nc = NotificationCenter.default
        for t in tokens { nc.removeObserver(t) }
        audioQueue.sync { stopEngine() }
    }

    // MARK: LocalCueSink

    func play(_ cmd: CueCommand, nowPhoneUs: Int64) throws -> Int {
        lock.lock()
        let out = output
        let busy = nowPhoneUs < busyUntilUs
        let running = engineRunning
        let startFailed = startError != nil
        lock.unlock()

        // No sound output chosen but an audio mode requested: there is no route, never use the speaker.
        guard out != .off else { return countRouteLost() }
        let d = CueBank.decide(cmd, ceilingDb: ceilingDb(), outputBusy: busy)
        if !d.play {
            if d.status == AckStatus.busyDropped { bump { $0.dropped += 1 } }
            return d.status
        }
        // Wrong route: answer "lost", never queue sound for whatever output iOS picked instead.
        guard OutputRoute.matches(out) else {
            audioQueue.async { [self] in player?.stop() }
            return countRouteLost()
        }
        // A muted command (the engine's link probe) only proves the route is open.
        if !d.audible {
            if startFailed { bump { $0.failures += 1 }; return Self.failed }
            return d.status
        }
        guard running else {
            // Not started (or lost to an interruption): restart for the next cue, this one cannot be played.
            audioQueue.async { [self] in _ = ensureStarted() }
            bump { $0.failures += 1 }
            return Self.failed
        }
        if let cue = CueBank.cue(cmd.cueId) {
            lock.lock()
            let lengthUs = Int64(cue.notes.enumerated().reduce(0) { $0 + $1.element.durMs + ($1.offset + 1 < cue.notes.count ? $1.element.gapMs : 0) }) * 1000
            busyUntilUs = nowPhoneUs + lengthUs
            lock.unlock()
        }
        let gl = d.gainLDb, gr = d.gainRDb, id = cmd.cueId
        audioQueue.async { [self] in schedule(cueId: id, gainLDb: gl, gainRDb: gr, output: out) }
        bump { $0.played += 1 }
        return d.status
    }

    // MARK: Bookkeeping

    private func bump(_ change: (inout SoundStats) -> Void) {
        lock.lock()
        change(&stats)
        lock.unlock()
    }

    private func countRouteLost() -> Int {
        bump { $0.routeLost += 1 }
        return AUDIO_ROUTE_LOST
    }

    private func currentOutput() -> SoundOutput {
        lock.lock()
        defer { lock.unlock() }
        return output
    }

    private func setRunning(_ running: Bool, error: String?) {
        lock.lock()
        engineRunning = running
        startError = error
        lock.unlock()
    }

    // MARK: Audio queue

    /// Builds the graph on first use and starts the engine. Returns whether it is running.
    private func ensureStarted() -> Bool {
        lock.lock()
        let isClosed = closed
        lock.unlock()
        if isClosed { return false }
        if let engine, engine.isRunning { return true }
        PhoneAudioSession.shared.activate()
        let engine = self.engine ?? AVAudioEngine()
        if player == nil {
            guard let fmt = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Double(Self.sampleRate), channels: 2, interleaved: false) else {
                setRunning(false, error: "cannot create audio format")
                return false
            }
            let node = AVAudioPlayerNode()
            engine.attach(node)
            engine.connect(node, to: engine.mainMixerNode, format: fmt)
            player = node
            format = fmt
        }
        self.engine = engine
        do {
            try engine.start()
            setRunning(true, error: nil)
            return true
        } catch {
            setRunning(false, error: "audio engine did not start: \(error.localizedDescription)")
            return false
        }
    }

    private func stopEngine() {
        player?.stop()
        engine?.stop()
        setRunning(false, error: nil)
    }

    private func schedule(cueId: Int, gainLDb: Int, gainRDb: Int, output: SoundOutput) {
        // The route may have changed between the engine queue's decision and now.
        guard OutputRoute.matches(output) else {
            player?.stop()
            return
        }
        guard let cue = CueBank.cue(cueId), ensureStarted(), let player, let format else {
            bump { $0.failures += 1 }
            return
        }
        let pcm = CueBank.render(cue, rateHz: Self.sampleRate, gainLQ15: CueBank.dbToQ15(gainLDb), gainRQ15: CueBank.dbToQ15(gainRDb))
        let frames = pcm.count / 2
        guard frames > 0,
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(frames)),
              let channels = buffer.floatChannelData
        else {
            bump { $0.failures += 1 }
            return
        }
        buffer.frameLength = AVAudioFrameCount(frames)
        let left = channels[0], right = channels[1]
        for i in 0..<frames {
            left[i] = Float(pcm[2 * i]) / 32768
            right[i] = Float(pcm[2 * i + 1]) / 32768
        }
        // A new cue replaces one in flight (the engine only sends while idle or with the interrupt flag).
        player.scheduleBuffer(buffer, at: nil, options: .interrupts, completionHandler: nil)
        if !player.isPlaying { player.play() }
    }

    private func routeChanged() {
        let out = currentOutput()
        if out == .off { return }
        if !OutputRoute.matches(out) {
            // The route vanished under a cue: cut it, never let it reach another output.
            player?.stop()
            lock.lock()
            busyUntilUs = 0
            lock.unlock()
        } else if engine?.isRunning != true {
            _ = ensureStarted()
        }
    }

    private func interrupted(began: Bool) {
        if began {
            player?.stop()
            setRunning(false, error: nil)
        } else if currentOutput() != .off {
            PhoneAudioSession.shared.reactivate()
            _ = ensureStarted()
        }
    }

    private func configurationChanged() {
        setRunning(false, error: nil)
        player?.stop()
        if currentOutput() != .off { _ = ensureStarted() }
    }
}
#endif
