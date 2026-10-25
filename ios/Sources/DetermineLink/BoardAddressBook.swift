import DetermineEngine
import Foundation

/// Learns each board's IP from its packets (PROTOCOL.md "Transport": the phone learns each board's IP from its
/// first packet) and attributes packets that carry no board identity (PONG) by source address.
///
/// Boards send from an ephemeral source port but listen on a fixed port (CUE 47001, CAM 47002), so a learned
/// destination is `(source IP, protocol listen port)`. A pinned board (`--cam` / `--cue`) keeps its given
/// `HOST:PORT` and is never re-learned; it is still attributed whenever a packet names it.
///
/// The receive thread writes and the engine thread reads, so every access takes `lock`.
public final class BoardAddressBook: @unchecked Sendable { // @unchecked: all mutable state guarded by `lock`.
    public static let phonePort: UInt16 = 47000
    public static let cuePort: UInt16 = 47001
    public static let camPort: UInt16 = 47002

    private let lock = NSLock()
    private let cuePortDefault: UInt16
    private let camPortDefault: UInt16
    private let pinnedCam: Endpoint?
    private let pinnedCue: Endpoint?
    private var cam: Endpoint?
    private var cue: Endpoint?

    public init(
        cam: Endpoint? = nil,
        cue: Endpoint? = nil,
        cuePort: UInt16 = BoardAddressBook.cuePort,
        camPort: UInt16 = BoardAddressBook.camPort
    ) {
        self.pinnedCam = cam
        self.pinnedCue = cue
        self.cam = cam
        self.cue = cue
        self.cuePortDefault = cuePort
        self.camPortDefault = camPort
    }

    /// Which board sent this datagram, or nil if it cannot be attributed. `declared` is the board named by the
    /// packet itself (`Wire.senderBoard`): nil for PONG and invalid packets, which are then attributed by the
    /// learned source address — an exact `(IP, port)` match first (both sim boards share 127.0.0.1), else a
    /// match on IP alone if that is unambiguous.
    public func attribute(declared: BoardId?, source: Endpoint) -> BoardId? {
        lock.lock()
        defer { lock.unlock() }
        if let declared {
            learn(declared, source)
            return declared
        }
        if cue == source { return .cue }
        if cam == source { return .cam }
        let cueIp = cue?.ip == source.ip
        let camIp = cam?.ip == source.ip
        if cueIp && !camIp { return .cue }
        if camIp && !cueIp { return .cam }
        return nil
    }

    private func learn(_ board: BoardId, _ source: Endpoint) {
        switch board {
        case .cam:
            guard pinnedCam == nil else { return }
            if cam?.ip != source.ip { cam = Endpoint(ip: source.ip, port: camPortDefault) }
            // A board that rebooted onto an address the other board used to hold takes it over; otherwise PONGs
            // from that IP would feed the wrong board's clock estimate. Two boards deliberately co-hosted on one
            // IP (the simulator) send from their own listen ports and keep both entries.
            if pinnedCue == nil, cue?.ip == source.ip, source.port != camPortDefault { cue = nil }
        case .cue:
            guard pinnedCue == nil else { return }
            if cue?.ip != source.ip { cue = Endpoint(ip: source.ip, port: cuePortDefault) }
            if pinnedCam == nil, cam?.ip == source.ip, source.port != cuePortDefault { cam = nil }
        }
    }

    /// Where to send packets for `board`, or nil while the board has not announced itself.
    public func destination(_ board: BoardId) -> Endpoint? {
        lock.lock()
        defer { lock.unlock() }
        switch board {
        case .cam: return cam
        case .cue: return cue
        }
    }

    /// Forgets every learned board; pinned boards stay.
    public func clear() {
        lock.lock()
        defer { lock.unlock() }
        cam = pinnedCam
        cue = pinnedCue
    }
}
