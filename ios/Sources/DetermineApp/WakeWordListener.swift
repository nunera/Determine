#if canImport(SwiftUI)
import AVFoundation
import DetermineEngine
import Foundation
import Speech

enum WakeWordState: Equatable, Sendable {
    case off
    case starting
    case listening
    case unavailable(String)

    var label: String {
        switch self {
        case .off: "off"
        case .starting: "starting"
        case .listening: "listening"
        case let .unavailable(why): "unavailable (\(why))"
        }
    }
}

enum WakeWordEvent: Sendable {
    case state(WakeWordState)
    /// The wake phrase was heard; a command may follow.
    case wake
    case command(VoiceCommand)
    case log(String)
}

/// "Hey Determine": always-on listening for a wake phrase and one command, on the iPhone's built-in microphone, with
/// Apple's on-device speech recognizer (`SpeechAnalyzer`, iOS 26). No audio or text leaves the phone. Experimental:
/// battery, heat and false triggers while walking are not measured. It only ever asks the app to run a command that
/// is already on Main (`VoiceCommand`); it never reaches the hazard decision.
final class WakeWordListener: @unchecked Sendable {
    private let emit: @Sendable (WakeWordEvent) -> Void
    private var task: Task<Void, Never>?
    private var core: AnyObject?

    init(emit: @escaping @Sendable (WakeWordEvent) -> Void) {
        self.emit = emit
    }

    func start() {
        guard #available(iOS 26.0, *) else {
            emit(.state(.unavailable("needs iOS 26")))
            return
        }
        let core = SpeechWakeCore(emit: emit)
        self.core = core
        task = Task { await core.run() }
    }

    func stop() {
        task?.cancel()
        task = nil
        if #available(iOS 26.0, *) { (core as? SpeechWakeCore)?.shutdown() }
        core = nil
        PhoneAudioSession.shared.setRecording(false)
    }
}

private enum WakeError: Error {
    case unsupportedLocale
    case noAudioFormat
}

/// Hands microphone buffers from the audio thread to whichever recognizer session is current, converted to the
/// format that session asked for.
@available(iOS 26.0, *)
private final class AudioInputBox: @unchecked Sendable {
    private let lock = NSLock()
    private var target: AVAudioFormat?
    private var converter: AVAudioConverter?
    private var continuation: AsyncStream<AnalyzerInput>.Continuation?

    func attach(target: AVAudioFormat, continuation: AsyncStream<AnalyzerInput>.Continuation) {
        lock.lock()
        self.target = target
        self.converter = nil
        self.continuation = continuation
        lock.unlock()
    }

    func detach() {
        lock.lock()
        continuation?.finish()
        continuation = nil
        converter = nil
        target = nil
        lock.unlock()
    }

    func push(_ buffer: AVAudioPCMBuffer) {
        lock.lock()
        guard let target, let continuation else {
            lock.unlock()
            return
        }
        if converter == nil || converter?.inputFormat != buffer.format {
            converter = AVAudioConverter(from: buffer.format, to: target)
        }
        let converter = converter
        lock.unlock()
        guard let converter else { return }
        let ratio = target.sampleRate / buffer.format.sampleRate
        let capacity = AVAudioFrameCount(Double(buffer.frameLength) * ratio) + 64
        guard let out = AVAudioPCMBuffer(pcmFormat: target, frameCapacity: capacity) else { return }
        var error: NSError?
        nonisolated(unsafe) var supplied = false
        nonisolated(unsafe) let source = buffer
        converter.convert(to: out, error: &error) { _, status in
            if supplied {
                status.pointee = .noDataNow
                return nil
            }
            supplied = true
            status.pointee = .haveData
            return source
        }
        if error == nil, out.frameLength > 0 { continuation.yield(AnalyzerInput(buffer: out)) }
    }
}

@available(iOS 26.0, *)
private final class SpeechWakeCore: @unchecked Sendable {
    /// A command heard but not yet final must stay the same this long before it is acted on.
    private static let stableFor: Duration = .milliseconds(700)
    /// After the wake phrase on its own, a command in the next segment still counts for this long.
    private static let commandWindow: Duration = .seconds(6)
    private static let maxFailures = 5

    private let emit: @Sendable (WakeWordEvent) -> Void
    private let engine = AVAudioEngine()
    private let input = AudioInputBox()
    private let lock = NSLock()
    private var stopped = false
    private var engineRunning = false
    private var observers: [NSObjectProtocol] = []

    init(emit: @escaping @Sendable (WakeWordEvent) -> Void) {
        self.emit = emit
    }

    private var isStopped: Bool {
        lock.lock()
        defer { lock.unlock() }
        return stopped
    }

    func shutdown() {
        lock.lock()
        stopped = true
        lock.unlock()
        for observer in observers { NotificationCenter.default.removeObserver(observer) }
        observers = []
        stopEngine()
        input.detach()
    }

    func run() async {
        emit(.state(.starting))
        guard await AVAudioApplication.requestRecordPermission() else {
            emit(.state(.unavailable("microphone access denied")))
            return
        }
        let locale = Locale(identifier: "en-US")
        do {
            try await ensureModel(for: locale)
        } catch {
            emit(.state(.unavailable("speech model: \(error.localizedDescription)")))
            return
        }
        guard PhoneAudioSession.shared.setRecording(true) else {
            emit(.state(.unavailable("audio session refused recording")))
            return
        }
        observe()

        var failures = 0
        while !isStopped, !Task.isCancelled {
            do {
                if let command = try await listenOnce(locale: locale) {
                    emit(.command(command))
                    // A fresh recognizer next time: the words just handled are not in its context.
                    try? await Task.sleep(for: .milliseconds(800))
                }
                failures = 0
            } catch is CancellationError {
                break
            } catch {
                failures += 1
                emit(.log("recognizer error: \(error.localizedDescription)"))
                if failures >= Self.maxFailures {
                    emit(.state(.unavailable("recognizer keeps failing")))
                    break
                }
                try? await Task.sleep(for: .seconds(2))
            }
        }
        shutdown()
    }

    private func ensureModel(for locale: Locale) async throws {
        let supported = await SpeechTranscriber.supportedLocales
        guard supported.contains(where: { $0.identifier(.bcp47) == locale.identifier(.bcp47) }) else {
            throw WakeError.unsupportedLocale
        }
        let installed = await SpeechTranscriber.installedLocales
        if installed.contains(where: { $0.identifier(.bcp47) == locale.identifier(.bcp47) }) { return }
        let transcriber = SpeechTranscriber(locale: locale, transcriptionOptions: [], reportingOptions: [], attributeOptions: [])
        if let request = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
            emit(.log("downloading the on-device speech model (needs a connection once)"))
            try await request.downloadAndInstall()
        }
    }

    /// One recognizer session: ends with the command that was heard, or nil if the result stream ended without one.
    private func listenOnce(locale: Locale) async throws -> VoiceCommand? {
        let transcriber = SpeechTranscriber(
            locale: locale, transcriptionOptions: [], reportingOptions: [.volatileResults], attributeOptions: []
        )
        let analyzer = SpeechAnalyzer(modules: [transcriber])
        guard let format = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [transcriber]) else {
            throw WakeError.noAudioFormat
        }
        let (stream, continuation) = AsyncStream<AnalyzerInput>.makeStream()
        try await analyzer.start(inputSequence: stream)
        input.attach(target: format, continuation: continuation)
        try startEngineIfNeeded()
        emit(.state(.listening))

        var command: VoiceCommand?
        var failure: Error?
        do {
            command = try await consume(transcriber)
        } catch {
            failure = error
        }
        input.detach()
        await analyzer.cancelAndFinishNow()
        if let failure { throw failure }
        return command
    }

    private func consume(_ transcriber: SpeechTranscriber) async throws -> VoiceCommand? {
        var candidate: VoiceCommand?
        var candidateSince = ContinuousClock.now
        var windowUntil: ContinuousClock.Instant?
        var wakeAnnounced = false
        for try await result in transcriber.results {
            if isStopped { return nil }
            let text = String(result.text.characters)
            let now = ContinuousClock.now
            if let until = windowUntil, now >= until {
                windowUntil = nil
                wakeAnnounced = false
            }
            var command: VoiceCommand?
            let parsed = VoicePhrases.parse(text)
            if parsed.wakeHeard {
                if !wakeAnnounced {
                    wakeAnnounced = true
                    windowUntil = now + Self.commandWindow
                    emit(.wake)
                }
                command = parsed.command
            } else if windowUntil != nil {
                command = VoicePhrases.command(in: text)
            }
            if command != candidate {
                candidate = command
                candidateSince = now
            }
            if let candidate, result.isFinal || now - candidateSince >= Self.stableFor {
                return candidate
            }
        }
        return nil
    }

    // MARK: Microphone

    private func startEngineIfNeeded() throws {
        lock.lock()
        defer { lock.unlock() }
        guard !engineRunning else { return }
        let node = engine.inputNode
        node.removeTap(onBus: 0)
        let hardware = node.outputFormat(forBus: 0)
        let box = input
        node.installTap(onBus: 0, bufferSize: 4096, format: hardware) { buffer, _ in box.push(buffer) }
        engine.prepare()
        try engine.start()
        engineRunning = true
    }

    private func stopEngine() {
        lock.lock()
        defer { lock.unlock() }
        guard engineRunning else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        engineRunning = false
    }

    /// The route or hardware format changed, or a call or Siri interrupted capture: rebuild the microphone tap.
    private func restartEngine() {
        guard !isStopped else { return }
        stopEngine()
        PhoneAudioSession.shared.reactivate()
        do {
            try startEngineIfNeeded()
            emit(.log("microphone restarted"))
        } catch {
            emit(.log("microphone restart failed: \(error.localizedDescription)"))
        }
    }

    private func observe() {
        let center = NotificationCenter.default
        observers.append(center.addObserver(forName: .AVAudioEngineConfigurationChange, object: engine, queue: nil) { [weak self] _ in
            self?.restartEngine()
        })
        observers.append(center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: nil) { [weak self] note in
            let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
            if raw == AVAudioSession.InterruptionType.ended.rawValue { self?.restartEngine() }
        })
    }
}
#endif
