#if canImport(AVFoundation)
import AVFoundation
import DetermineLink
import Foundation

/// Only optional reading/description speech. Hazard speech remains local in IosPhoneAlerts.
@MainActor
final class ReadingSpeech {
    var preferences = SpeechPreferences()
    var endpoint: DescribeEndpoint?
    var onStatus: @Sendable (String) -> Void = { _ in }
    private let synth = AVSpeechSynthesizer()
    private let engine = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private lazy var timePitch = AVAudioUnitTimePitch()
    private let format = AVAudioFormat(standardFormatWithSampleRate: 24_000, channels: 1)!
    private var attached = false
    private var task: Task<Void, Never>?
    private var continuation: AsyncStream<String>.Continuation?
    private var generation = 0
    private var pendingBuffers = 0
    private var drain: CheckedContinuation<Void, Never>?
    private var accepting = false

    func begin() {
        cancel()
        accepting = true
        let gen = generation
        let stream = AsyncStream<String> { continuation = $0 }
        let settings = preferences
        let destination = endpoint
        task = Task { [weak self] in
            guard let self else { return }
            do {
                for await sentence in stream {
                    try Task.checkCancellation()
                    guard gen == generation else { return }
                    if settings.provider == .device {
                        let utterance = AVSpeechUtterance(string: sentence)
                        utterance.rate = min(0.65, max(0.35, AVSpeechUtteranceDefaultSpeechRate * Float(settings.rate)))
                        utterance.voice = settings.deviceVoice.isEmpty ? preferredVoice() : AVSpeechSynthesisVoice(identifier: settings.deviceVoice)
                        utterance.volume = 1
                        synth.speak(utterance)
                    } else {
                        guard let destination else { throw URLError(.badURL) }
                        try await cloud(sentence, settings: settings, endpoint: destination, generation: gen)
                    }
                }
            } catch {
                guard !Task.isCancelled, gen == generation else { return }
                accepting = false
                continuation?.finish()
                onStatus("Speech unavailable. The answer is still in the transcript. Check the voice provider and server connection.")
                player.stop()
                engine.stop()
            }
        }
    }

    private func preferredVoice() -> AVSpeechSynthesisVoice? {
        let language = AVSpeechSynthesisVoice.currentLanguageCode()
        return AVSpeechSynthesisVoice.speechVoices().filter { $0.language == language }
            .max { $0.quality.rawValue < $1.quality.rawValue } ?? AVSpeechSynthesisVoice(language: language)
    }

    func append(_ text: String) {
        guard accepting, !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        continuation?.yield(text)
    }
    func finish() { accepting = false; continuation?.finish(); continuation = nil }
    func speak(_ text: String) {
        begin()
        var remaining = text.trimmingCharacters(in: .whitespacesAndNewlines)[...]
        // OCR can exceed a provider's per-request limit; keep chunks at word boundaries.
        while remaining.count > 1500 {
            let end = remaining.index(remaining.startIndex, offsetBy: 1500)
            let split = remaining[..<end].lastIndex(where: { $0.isWhitespace }) ?? end
            append(String(remaining[..<split]))
            remaining = remaining[split...].drop(while: { $0.isWhitespace })
        }
        append(String(remaining))
        finish()
    }

    func cancel() {
        generation += 1
        accepting = false
        continuation?.finish()
        continuation = nil
        task?.cancel()
        task = nil
        synth.stopSpeaking(at: .immediate)
        player.stop()
        engine.stop()
        pendingBuffers = 0
        drain?.resume()
        drain = nil
    }

    private func cloud(_ text: String, settings: SpeechPreferences, endpoint: DescribeEndpoint, generation gen: Int) async throws {
        var base = endpoint.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        while base.hasSuffix("/") { base.removeLast() }
        guard let url = URL(string: base + "/v1/speech"), ["https", "http"].contains(url.scheme ?? ""), !endpoint.apiKey.isEmpty else { throw URLError(.badURL) }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("Bearer \(endpoint.apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["text": text, "provider": settings.provider.rawValue,
                                                                 "voice": settings.cloudVoice, "model": settings.cloudModel, "speed": 1.0])
        request.timeoutInterval = 25
        if !attached {
            engine.attach(player)
            engine.attach(timePitch)
            engine.connect(player, to: timePitch, format: format)
            engine.connect(timePitch, to: engine.mainMixerNode, format: format)
            attached = true
        }
        timePitch.rate = Float(settings.rate)
        timePitch.bypass = settings.rate == 1
        PhoneAudioSession.shared.activate()
        try engine.start()
        player.play()
        var bytes = Data()
        var validated = false
        var receivedAudio = false
        for try await event in HTTPStream.events(for: request) {
            try Task.checkCancellation()
            guard gen == generation else { throw CancellationError() }
            switch event {
            case let .response(response):
                guard response.statusCode == 200, response.value(forHTTPHeaderField: "Content-Type")?.contains("audio/pcm") == true else { throw URLError(.badServerResponse) }
                validated = true
            case let .data(data):
                guard validated else { throw URLError(.badServerResponse) }
                bytes.append(data)
                let samples = bytes.count / 2
                guard samples > 0 else { continue }
                guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples)),
                      let channel = buffer.floatChannelData?[0] else { throw URLError(.cannotDecodeRawData) }
                buffer.frameLength = AVAudioFrameCount(samples)
                bytes.withUnsafeBytes { raw in
                    let input = raw.bindMemory(to: UInt8.self)
                    for index in 0..<samples {
                        let value = UInt16(input[index * 2]) | (UInt16(input[index * 2 + 1]) << 8)
                        channel[index] = Float(Int16(bitPattern: value)) / 32768
                    }
                }
                bytes.removeFirst(samples * 2)
                receivedAudio = true
                pendingBuffers += 1
                player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { [weak self] _ in
                    Task { @MainActor in
                        guard let self, gen == self.generation else { return }
                        self.pendingBuffers -= 1
                        if self.pendingBuffers == 0 { self.drain?.resume(); self.drain = nil }
                    }
                }
            }
        }
        guard validated, receivedAudio, bytes.isEmpty else { throw URLError(.cannotDecodeRawData) }
        if pendingBuffers > 0 { await withCheckedContinuation { drain = $0 } }
        try Task.checkCancellation()
    }
}
#endif
