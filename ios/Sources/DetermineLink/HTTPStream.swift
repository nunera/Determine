import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif

public enum HTTPStreamEvent: @unchecked Sendable {
    case response(HTTPURLResponse)
    case data(Data)
}

/// Delegate delivery works on iOS and Linux; unlike data(for:), it never waits for the full body.
public enum HTTPStream {
    public static func events(for request: URLRequest) -> AsyncThrowingStream<HTTPStreamEvent, Error> {
        AsyncThrowingStream { continuation in
            let delegate = Receiver(continuation)
            let config = URLSessionConfiguration.ephemeral
            config.timeoutIntervalForRequest = 25
            config.timeoutIntervalForResource = 45
            let session = URLSession(configuration: config, delegate: delegate, delegateQueue: nil)
            continuation.onTermination = { @Sendable _ in session.invalidateAndCancel() }
            session.dataTask(with: request).resume()
        }
    }

    private final class Receiver: NSObject, URLSessionDataDelegate, @unchecked Sendable {
        let continuation: AsyncThrowingStream<HTTPStreamEvent, Error>.Continuation
        var received = 0
        init(_ continuation: AsyncThrowingStream<HTTPStreamEvent, Error>.Continuation) {
            self.continuation = continuation
        }
        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask,
                        didReceive response: URLResponse,
                        completionHandler: @escaping @Sendable (URLSession.ResponseDisposition) -> Void) {
            guard let response = response as? HTTPURLResponse else {
                completionHandler(.cancel)
                continuation.finish(throwing: URLError(.badServerResponse))
                return
            }
            continuation.yield(.response(response))
            completionHandler(.allow)
        }
        func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
            received += data.count
            guard received <= 8 * 1024 * 1024 else {
                dataTask.cancel()
                continuation.finish(throwing: URLError(.dataLengthExceedsMaximum))
                return
            }
            continuation.yield(.data(data))
        }
        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
            if let error { continuation.finish(throwing: error) }
            else { continuation.finish() }
            session.finishTasksAndInvalidate()
        }
    }
}
