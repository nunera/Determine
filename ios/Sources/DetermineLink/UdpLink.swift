import DetermineEngine
import Foundation
#if canImport(Glibc)
import Glibc
#elseif canImport(Darwin)
import Darwin
#endif

public enum UdpLinkError: Error, CustomStringConvertible {
    case alreadyStarted
    case badBindAddress(String)
    case socket(String, errno: Int32)

    public var description: String {
        switch self {
        case .alreadyStarted: return "link already started"
        case .badBindAddress(let a): return "cannot resolve bind address \(a)"
        case .socket(let what, let e): return "\(what) failed: \(String(cString: strerror(e))) (errno \(e))"
        }
    }
}

/// The phone end of the UDP link (PROTOCOL.md "Transport"): one socket bound to :47000 receives BEAT, RANGE,
/// FRAME, PONG and ACK from both boards and sends PING/CUE/PROFILE back to each board's learned address.
///
/// `onPacket` runs on the receive thread with a fresh copy of the datagram; hosts hop onto their single engine
/// queue/lock inside it. `send` is called from the engine thread. Both threads share only the socket fd, the
/// counters and the started/stopped flag, all guarded by `lock`.
public final class UdpLink: @unchecked Sendable { // @unchecked: mutable state is guarded by `lock`; closures are only invoked on the receive thread.
    public typealias PacketHandler = (_ board: BoardId, _ bytes: [UInt8], _ rxPhoneUs: Int64) -> Void

    private static let maxDatagram = 1500
    /// Receive timeout so the thread notices `stop()`; closing an fd does not wake a blocked `recvfrom` on Linux.
    private static let pollIntervalUs = 50_000

    public let addressBook: BoardAddressBook
    private let clockUs: () -> Int64
    private let onPacket: PacketHandler
    private let onUnattributed: () -> Void

    private let lock = NSLock()
    private var fd: Int32 = -1
    private var running = false
    private var stopped: DispatchSemaphore?
    private var _unattributed: UInt64 = 0
    private var _sendErrors: UInt64 = 0
    private var _sendsWithoutAddress: UInt64 = 0
    private var _boundPort: UInt16 = 0

    /// - Parameters:
    ///   - clockUs: monotonic phone clock in µs, read once per datagram right after `recvfrom` returns.
    ///   - onPacket: attributed datagram; called on the receive thread.
    ///   - onUnattributed: called on the receive thread for a datagram no board can be matched to (it is dropped).
    public init(
        addressBook: BoardAddressBook,
        clockUs: @escaping () -> Int64,
        onPacket: @escaping PacketHandler,
        onUnattributed: @escaping () -> Void = {}
    ) {
        self.addressBook = addressBook
        self.clockUs = clockUs
        self.onPacket = onPacket
        self.onUnattributed = onUnattributed
    }

    /// Datagrams dropped because no board could be attributed.
    public var unattributedCount: UInt64 { locked { _unattributed } }
    public var sendErrors: UInt64 { locked { _sendErrors } }
    /// Sends skipped because the board had not been heard from yet.
    public var sendsWithoutAddress: UInt64 { locked { _sendsWithoutAddress } }
    /// The port actually bound (differs from the requested one only when 0 was requested), 0 before `start`.
    public var boundPort: UInt16 { locked { _boundPort } }

    private func locked<T>(_ body: () -> T) -> T {
        lock.lock()
        defer { lock.unlock() }
        return body()
    }

    /// Binds `bind:port` and starts the receive thread. Throws if the address cannot be resolved or bound.
    public func start(bind: String = "0.0.0.0", port: UInt16 = BoardAddressBook.phonePort) throws {
        lock.lock()
        defer { lock.unlock() }
        guard fd < 0 else { throw UdpLinkError.alreadyStarted }
        guard let local = Endpoint.resolve(host: bind, port: port) else { throw UdpLinkError.badBindAddress(bind) }

        #if canImport(Glibc)
        let sock = socket(AF_INET, Int32(SOCK_DGRAM.rawValue), 0)
        #else
        let sock = socket(AF_INET, SOCK_DGRAM, 0)
        #endif
        guard sock >= 0 else { throw UdpLinkError.socket("socket", errno: errno) }

        func fail(_ what: String) -> UdpLinkError {
            let e = errno
            close(sock)
            return .socket(what, errno: e)
        }

        var on: Int32 = 1
        _ = setsockopt(sock, SOL_SOCKET, SO_REUSEADDR, &on, socklen_t(MemoryLayout<Int32>.size))
        // A camera burst is up to ~25 chunks of <=1438 B; leave room for several frames plus ToF/BEAT. Best effort:
        // the kernel clamps it.
        var rcvbuf: Int32 = 512 * 1024
        _ = setsockopt(sock, SOL_SOCKET, SO_RCVBUF, &rcvbuf, socklen_t(MemoryLayout<Int32>.size))
        var tv = timeval(tv_sec: 0, tv_usec: .init(Self.pollIntervalUs))
        guard setsockopt(sock, SOL_SOCKET, SO_RCVTIMEO, &tv, socklen_t(MemoryLayout<timeval>.size)) == 0 else {
            throw fail("setsockopt(SO_RCVTIMEO)")
        }

        var sin = local.sockAddrIn()
        let bound = withUnsafePointer(to: &sin) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                posixBind(sock, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        guard bound == 0 else { throw fail("bind \(local)") }

        var actual = sockaddr_in()
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &actual) {
            $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(sock, $0, &len) }
        }
        _boundPort = named == 0 ? Endpoint(actual).port : port

        fd = sock
        running = true
        let done = DispatchSemaphore(value: 0)
        stopped = done
        let thread = Thread { [self] in
            receiveLoop(sock)
            done.signal()
        }
        thread.name = "determine-udp-rx"
        thread.qualityOfService = .userInteractive
        thread.start()
    }

    private func isRunning() -> Bool { locked { running } }

    private func receiveLoop(_ sock: Int32) {
        var buf = [UInt8](repeating: 0, count: Self.maxDatagram)
        while isRunning() {
            var from = sockaddr_in()
            var fromLen = socklen_t(MemoryLayout<sockaddr_in>.size)
            let n = buf.withUnsafeMutableBytes { raw in
                withUnsafeMutablePointer(to: &from) {
                    $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                        recvfrom(sock, raw.baseAddress, raw.count, 0, $0, &fromLen)
                    }
                }
            }
            if n < 0 {
                let e = errno
                if e == EAGAIN || e == EWOULDBLOCK || e == EINTR || e == ECONNREFUSED { continue }
                // Socket closed under us or unrecoverable: end the loop; `stop()`/the host surfaces it.
                return
            }
            let rxUs = clockUs()
            let bytes = Array(buf[0..<n])
            let board = addressBook.attribute(declared: Wire.senderBoard(bytes), source: Endpoint(from))
            guard let board else {
                locked { _unattributed += 1 }
                onUnattributed()
                continue
            }
            onPacket(board, bytes, rxUs)
        }
    }

    /// Sends `packet` to the board's learned/pinned address. A board not heard from yet is counted and skipped;
    /// a failed send surfaces to the engine as a missing ACK (Arch §4.2).
    public func send(board: BoardId, packet: [UInt8]) {
        guard let dest = addressBook.destination(board) else {
            locked { _sendsWithoutAddress += 1 }
            return
        }
        let sock = locked { fd }
        guard sock >= 0 else { return }
        var sin = dest.sockAddrIn()
        let sent = packet.withUnsafeBytes { raw in
            withUnsafePointer(to: &sin) {
                $0.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                    sendto(sock, raw.baseAddress, raw.count, 0, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
                }
            }
        }
        if sent != packet.count { locked { _sendErrors += 1 } }
    }

    /// Stops the receive thread (waits for it) and closes the socket. Idempotent.
    public func stop() {
        let (sock, done): (Int32, DispatchSemaphore?) = locked {
            running = false
            let s = fd
            let d = stopped
            fd = -1
            stopped = nil
            return (s, d)
        }
        guard sock >= 0 else { return }
        _ = done?.wait(timeout: .now() + 2)
        close(sock)
    }
}

#if canImport(Glibc)
@inline(__always) private func posixBind(_ s: Int32, _ a: UnsafePointer<sockaddr>, _ l: socklen_t) -> Int32 { Glibc.bind(s, a, l) }
#else
@inline(__always) private func posixBind(_ s: Int32, _ a: UnsafePointer<sockaddr>, _ l: socklen_t) -> Int32 { Darwin.bind(s, a, l) }
#endif

extension UdpLink: Outbox {}
