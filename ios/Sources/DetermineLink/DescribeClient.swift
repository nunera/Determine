import DetermineEngine
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Where the describe-api lives: base URL without the `/v1/...` path, and the bearer key (cloud/API.md §1).
public struct DescribeEndpoint: Equatable, Sendable {
    public let baseUrl: String
    public let apiKey: String

    public init(baseUrl: String, apiKey: String) {
        self.baseUrl = baseUrl
        self.apiKey = apiKey
    }
}

/// Phone → describe-api client, 8 s end to end. `send` is `POST {baseUrl}/v1/describe` (one frame, optionally with a
/// question); `route` is `POST {baseUrl}/v1/voice` (only the words that were said). Every error maps to a `.failed`
/// result; nothing is retried here (both are user-initiated: the user asks again).
public enum DescribeClient {
    static func url(for endpoint: DescribeEndpoint, path: String = "/v1/describe") -> URL? {
        var base = endpoint.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        while base.hasSuffix("/") { base.removeLast() }
        guard let url = URL(string: base + path), let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http", url.host?.isEmpty == false
        else { return nil }
        return url
    }

    public static func send(
        endpoint: DescribeEndpoint, jpeg: [UInt8], width: Int, height: Int, question: String? = nil,
        requestId: String = DescribeWire.makeRequestId()
    ) async -> DescribeResult {
        guard jpeg.count <= DescribeSettings.maxJpegBytes else { return .failed(code: "too_large", retryable: false) }
        guard let body = try? DescribeWire.requestBody(requestId: requestId, jpeg: jpeg, width: width, height: height, question: question)
        else { return .failed(code: "bad_request", retryable: false) }
        switch await post(endpoint: endpoint, path: "/v1/describe", body: body) {
        case let .success((status, data)): return DescribeWire.parse(status: status, body: data)
        case let .failure(failure): return .failed(code: failure.code, retryable: failure.retryable)
        }
    }

    /// What the wearer said after the wake phrase, as text. The phone calls this only when none of its own fixed
    /// phrases matched.
    public static func route(
        endpoint: DescribeEndpoint, transcript: String, requestId: String = DescribeWire.makeRequestId()
    ) async -> VoiceDecision {
        guard let body = try? DescribeWire.voiceRequestBody(requestId: requestId, transcript: transcript)
        else { return .failed(code: "bad_request", retryable: false) }
        switch await post(endpoint: endpoint, path: "/v1/voice", body: body) {
        case let .success((status, data)): return DescribeWire.parseVoice(status: status, body: data)
        case let .failure(failure): return .failed(code: failure.code, retryable: failure.retryable)
        }
    }

    private struct TransportFailure: Error {
        let code: String
        let retryable: Bool
    }

    private static func post(endpoint: DescribeEndpoint, path: String, body: Data) async -> Result<(Int, Data), TransportFailure> {
        guard let url = url(for: endpoint, path: path), !endpoint.apiKey.isEmpty else {
            return .failure(TransportFailure(code: "bad_url", retryable: false))
        }
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.httpBody = body
        request.setValue("Bearer \(endpoint.apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = DescribeSettings.timeoutSeconds

        let config = URLSessionConfiguration.ephemeral
        config.timeoutIntervalForRequest = DescribeSettings.timeoutSeconds
        config.timeoutIntervalForResource = DescribeSettings.timeoutSeconds
        let session = URLSession(configuration: config)
        defer { session.finishTasksAndInvalidate() }
        do {
            let (data, response) = try await session.data(for: request)
            guard let http = response as? HTTPURLResponse else {
                return .failure(TransportFailure(code: "bad_response", retryable: true))
            }
            return .success((http.statusCode, data))
        } catch let error as URLError where error.code == .timedOut {
            return .failure(TransportFailure(code: "timeout", retryable: true))
        } catch {
            return .failure(TransportFailure(code: "network", retryable: true))
        }
    }
}
