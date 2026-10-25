#if canImport(AVFoundation)
import AVFoundation
import DetermineLink
import Foundation

enum SpeechProvider: String, CaseIterable, Codable, Identifiable, Sendable {
    case device, openrouter, openai, elevenlabs
    var id: String { rawValue }
    var label: String {
        switch self {
        case .device: return "On this iPhone"
        case .openrouter: return "OpenRouter"
        case .openai: return "OpenAI (direct)"
        case .elevenlabs: return "ElevenLabs (direct)"
        }
    }
}

struct SpeechPreferences: Codable, Equatable, Sendable {
    var provider: SpeechProvider = .device
    var deviceVoice = ""
    var cloudVoice = "alloy"
    var cloudModel = "elevenlabs/eleven-flash-v2.5"
    var rate: Double = 1

    init() {}

    private enum CodingKeys: String, CodingKey { case provider, deviceVoice, cloudVoice, cloudModel, rate }
    init(from decoder: Decoder) throws {
        let values = try decoder.container(keyedBy: CodingKeys.self)
        provider = try values.decodeIfPresent(SpeechProvider.self, forKey: .provider) ?? .device
        deviceVoice = try values.decodeIfPresent(String.self, forKey: .deviceVoice) ?? ""
        cloudVoice = try values.decodeIfPresent(String.self, forKey: .cloudVoice) ?? "alloy"
        cloudModel = try values.decodeIfPresent(String.self, forKey: .cloudModel) ?? "elevenlabs/eleven-flash-v2.5"
        rate = try values.decodeIfPresent(Double.self, forKey: .rate) ?? 1
    }
}

struct CloudVoice: Codable, Identifiable, Sendable {
    let id: String
    let name: String
}
struct CloudSpeechModel: Codable, Identifiable, Sendable {
    let id: String
    let name: String
    let voices: [CloudVoice]
}
struct CloudSpeechProvider: Codable, Identifiable, Sendable {
    let id: String
    let name: String
    let available: Bool
    let reason: String?
    let voices: [CloudVoice]
    let models: [CloudSpeechModel]?
}
struct SpeechCatalog: Codable, Sendable {
    let providers: [CloudSpeechProvider]
}

struct DeviceVoice: Identifiable {
    let id: String
    let name: String
    let language: String
    let quality: String
    static var installed: [DeviceVoice] {
        AVSpeechSynthesisVoice.speechVoices().map {
            DeviceVoice(id: $0.identifier, name: $0.name, language: $0.language,
                        quality: $0.quality == .premium ? "Premium" : ($0.quality == .enhanced ? "Enhanced" : "Standard"))
        }.sorted { ($0.language, $0.name, $0.quality) < ($1.language, $1.name, $1.quality) }
    }
}
#endif
