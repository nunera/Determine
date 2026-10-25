import Foundation

/// Describe loop wire format (cloud/API.md §1, contract v1): the request the phone builds and the answer or error it
/// parses. Pure: no I/O, no clock. Describe is on request only and never feeds the hazard decision.
public enum DescribeSettings {
    /// API.md §1: decoded JPEG size limit.
    public static let maxJpegBytes = 512 * 1024
    /// A frame older than this shows a scene the wearer has already left.
    public static let maxFrameAgeUs: Int64 = 500_000
    /// Phone timeout end to end (API.md §1).
    public static let timeoutSeconds: TimeInterval = 8
    public static let locale = "en"
}

public enum DescribeResult: Equatable, Sendable {
    /// The API's safety-filtered text (at most 2 sentences), spoken as returned.
    case text(String)
    /// The API's `error` code (or a local one: `bad_url`, `too_large`, `network`, `timeout`, `bad_response`).
    case failed(code: String, retryable: Bool)
}

/// What `POST /v1/voice` decided the wearer meant (cloud/API.md §1b).
public enum VoiceDecision: Equatable, Sendable {
    case command(VoiceCommand)
    /// A specific question about the scene: the phone uploads one frame with it.
    case ask(String)
    /// Not addressed to the assistant, unclear or unsupported: do nothing.
    case none
    /// The router could not be reached or answered badly: do nothing, say so.
    case failed(code: String, retryable: Bool)
}

public enum DescribeWire {
    /// Request body for a frame that was NOT face-redacted: this app has no redaction, so it states
    /// `redacted: false` and relies on the wearer's explicit `unredacted_opt_in` (the API refuses it otherwise).
    /// `question` is a specific question about the scene (the API cleans it and caps it at 300 characters).
    public static func requestBody(requestId: String, jpeg: [UInt8], width: Int, height: Int, question: String? = nil) throws -> Data {
        var object: [String: Any] = [
            "request_id": requestId,
            "image_jpeg_b64": Data(jpeg).base64EncodedString(),
            "width": width,
            "height": height,
            "redacted": false,
            "unredacted_opt_in": true,
            "locale": DescribeSettings.locale,
        ]
        if let question, !question.isEmpty { object["question"] = String(question.prefix(300)) }
        return try JSONSerialization.data(withJSONObject: object)
    }

    /// Body for `POST /v1/voice`: only the words that were said, never audio or an image.
    public static func voiceRequestBody(requestId: String, transcript: String) throws -> Data {
        let object: [String: Any] = [
            "request_id": requestId,
            "transcript": String(transcript.prefix(300)),
            "locale": DescribeSettings.locale,
        ]
        return try JSONSerialization.data(withJSONObject: object)
    }

    /// The router's answer. Anything that is not exactly a known command or a non-empty question is `.none`.
    public static func parseVoice(status: Int, body: Data) -> VoiceDecision {
        let object = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
        if status == 200 {
            switch object?["action"] as? String {
            case "command":
                if let raw = object?["command"] as? String, let command = VoiceCommand(rawValue: raw) { return .command(command) }
                return .none
            case "ask":
                let question = (object?["question"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
                return question.isEmpty ? .none : .ask(question)
            default:
                return .none
            }
        }
        if let code = object?["error"] as? String, !code.isEmpty {
            return .failed(code: code, retryable: object?["retryable"] as? Bool ?? false)
        }
        return .failed(code: "http_\(status)", retryable: status >= 500)
    }

    /// 200 → the text; anything else → the API's error code and retryable flag, or a transport-level fallback.
    public static func parse(status: Int, body: Data) -> DescribeResult {
        let object = (try? JSONSerialization.jsonObject(with: body)) as? [String: Any]
        if status == 200 {
            guard let text = (object?["text"] as? String)?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else {
                return .failed(code: "bad_response", retryable: false)
            }
            return .text(text)
        }
        if let code = object?["error"] as? String, !code.isEmpty {
            return .failed(code: code, retryable: object?["retryable"] as? Bool ?? false)
        }
        return .failed(code: "http_\(status)", retryable: status >= 500)
    }

    /// 8-64 characters of `[A-Za-z0-9_-]` (API.md §1), chosen by the phone.
    public static func makeRequestId() -> String {
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-")
        var rng = SystemRandomNumberGenerator()
        return String((0..<16).map { _ in alphabet[Int.random(in: 0..<alphabet.count, using: &rng)] })
    }
}
