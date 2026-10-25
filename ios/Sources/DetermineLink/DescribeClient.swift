import DetermineEngine
import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

/// Where the describe-api lives: base URL without the `/v1/describe` path, and the bearer key (cloud/API.md §1).
public struct DescribeEndpoint: Equatable, Sendable {
    public let baseUrl: String
    public let apiKey: String

    public init(baseUrl: String, apiKey: String) {
        self.baseUrl = baseUrl
        self.apiKey = apiKey
    }
}

/// Phone → describe-api client: `POST {baseUrl}/v1/describe` with a bearer key, 8 s end to end. Every error maps to
/// `DescribeResult.failed`; nothing is retried here (Describe is user-initiated: the user asks again).
public enum DescribeClient {
    static func url(for endpoint: DescribeEndpoint) -> URL? {
        var base = endpoint.baseUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        while base.hasSuffix("/") { base.removeLast() }
        guard let url = URL(string: base + "/v1/describe"), let scheme = url.scheme?.lowercased(),
              scheme == "https" || scheme == "http", url.host?.isEmpty == false
        else { return nil }
        return url
    }

    public static func send(
        endpoint: DescribeEndpoint, jpeg: [UInt8], width: Int, height: Int,
        requestId: String = DescribeWire.makeRequestId()
    ) async -> DescribeResult {
        guard jpeg.count <= DescribeSettings.maxJpegBytes else { return .failed(code: "too_large", retryable: false) }
        guard let url = url(for: endpoint), !endpoint.apiKey.isEmpty else { return .failed(code: "bad_url", retryable: false) }
        let body: Data
        do {
            body = try DescribeWire.requestBody(requestId: requestId, jpeg: jpeg, width: width, height: height)
        } catch {
            return .failed(code: "bad_request", retryable: false)
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
            guard let http = response as? HTTPURLResponse else { return .failed(code: "bad_response", retryable: true) }
            return DescribeWire.parse(status: http.statusCode, body: data)
        } catch let error as URLError where error.code == .timedOut {
            return .failed(code: "timeout", retryable: true)
        } catch {
            return .failed(code: "network", retryable: true)
        }
    }
}
