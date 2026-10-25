// Per-board NTP-style clock estimate (PROTOCOL.md PING/PONG), port of clock/ClockSync.kt:
//   offset = ((rx - t1) + (tx - t4)) / 2   (board - phone)
//   rtt    = (t4 - t1) - (tx - rx)
// The offset comes from the lowest-RTT sample in a sliding window, which rejects samples inflated by queueing
// delay. Samples with negative or excessive RTT, or echoing a phone time we never sent, are dropped.
// Board-supplied u64 values can be arbitrary, so every subtraction wraps (as the JVM's Long does) instead of trapping.

public final class ClockSync {
    public struct Sample: Equatable, Sendable {
        public let offsetUs: Int64
        public let rttUs: Int64
        public let atPhoneUs: Int64
    }

    private let windowSize: Int
    private let maxRttUs: Int64
    /// No accepted sample for this long -> not synced (a dead link must not keep a stale offset forever).
    private let staleAfterUs: Int64

    private var window: [Sample] = []
    private var lastPingSentUs = Int64.min
    private var firstPingSentUs = Int64.min
    public private(set) var rejected: Int64 = 0

    public init(windowSize: Int = 32, maxRttUs: Int64 = 200_000, staleAfterUs: Int64 = 2_000_000) {
        self.windowSize = windowSize
        self.maxRttUs = maxRttUs
        self.staleAfterUs = staleAfterUs
    }

    /// Records an outgoing PING so stale or forged echoes can be rejected.
    public func onPingSent(_ tPhoneUs: Int64) {
        if firstPingSentUs == Int64.min { firstPingSentUs = tPhoneUs }
        lastPingSentUs = tPhoneUs
    }

    /// Returns the accepted sample or nil if rejected as an outlier.
    @discardableResult
    public func onPong(t1: Int64, rx: Int64, tx: Int64, t4: Int64) -> Sample? {
        let rtt = (t4 &- t1) &- (tx &- rx)
        let valid = firstPingSentUs != Int64.min && t1 >= firstPingSentUs && t1 <= lastPingSentUs &&
            t4 >= t1 && tx >= rx && rtt >= 0 && rtt <= maxRttUs
        if !valid { rejected += 1; return nil }
        let offset = ((rx &- t1) &+ (tx &- t4)) / 2
        let s = Sample(offsetUs: offset, rttUs: rtt, atPhoneUs: t4)
        window.append(s)
        while window.count > windowSize { window.removeFirst() }
        return s
    }

    /// Forget everything, e.g. after the board rebooted (its clock restarted).
    public func reset() {
        window.removeAll()
    }

    private func best() -> Sample? { window.min { $0.rttUs < $1.rttUs } }

    public func synced(_ nowPhoneUs: Int64) -> Bool {
        guard let last = window.last else { return false }
        return nowPhoneUs &- last.atPhoneUs <= staleAfterUs
    }

    public var offsetUs: Int64? { best()?.offsetUs }
    public var rttUs: Int64? { best()?.rttUs }

    public func toBoard(_ phoneUs: Int64) -> Int64? { best().map { phoneUs &+ $0.offsetUs } }
    public func toPhone(_ boardUs: Int64) -> Int64? { best().map { boardUs &- $0.offsetUs } }
}
