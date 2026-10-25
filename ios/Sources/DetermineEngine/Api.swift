// Public surface of the Swift port of org.determine.core (phone/core/API.md). Foundation only: no I/O, no clock.
// All times are Int64 microseconds on a monotonic clock the caller supplies. The Engine is NOT thread-safe:
// hosts call every method from one serial queue or under one lock.

/// The two wearable boards (PROTOCOL.md BEAT.board: 1 = CAM, 2 = CUE).
public enum BoardId: Int, Sendable, CaseIterable {
    case cam = 1
    case cue = 2

    public var wire: Int { rawValue }

    public static func fromWire(_ v: Int) -> BoardId? { BoardId(rawValue: v) }
}

/// Sends an encoded packet to a board. The app/cli owns the UDP socket and the learned board addresses.
public protocol Outbox {
    func send(board: BoardId, packet: [UInt8])
}

/// Where frames come from (Arch §4.1 FrameSource). `phoneCamera` is Tier 0: no camera board, the host calls
/// `Engine.onPhoneFrame`.
public enum FrameSourceKind: Sendable {
    case camBoard
    case phoneCamera
}

/// Audio gain sentinel: this channel is silent (dBFS levels are <= 0).
public let GAIN_MUTE = -128

/// `LocalCueSink.play` result meaning "the required headset route is absent or was lost". The engine goes
/// UNAVAILABLE (reason `headset_route_lost`) at once; the sink must never fall back to the phone speaker.
public let AUDIO_ROUTE_LOST = -2

/// Plays a cue's AUDIO on the phone (the headset route). Called synchronously from the Engine with gains already
/// clamped to the profile ceiling (-128 = mute). Returns a `AckStatus` value; any other value (e.g. negative) or a
/// thrown error counts as a failed cue, exactly like a missing/negative board ACK.
public protocol LocalCueSink {
    func play(_ cmd: CueCommand, nowPhoneUs: Int64) throws -> Int
}

/// Phone-side fallback outputs (used when the wearable is down, and for spearcons).
public protocol PhoneAlerts {
    func speak(_ text: String)
    func vibrate(pattern: [Int64])
    func systemOff()
}

/// Arch §4.2 runtime states.
public enum SystemState: Sendable {
    case starting, ready, degraded, unavailable, paused
}

/// Proximity bands, ordered by urgency, for the single forward zone. There is deliberately no CLEAR.
public enum Band: Int, Sendable, Comparable {
    case none = 0, far, mid, near

    public static func < (a: Band, b: Band) -> Bool { a.rawValue < b.rawValue }
}

/// The single alert the phone presents, from the forward ToF zone only. `distanceMm` is non-nil only when backed by a
/// VALID ToF status; nothing is ever inferred from camera imagery.
public struct Alert: Equatable, Hashable, Sendable {
    public let band: Band
    public let distanceMm: Int?

    public init(band: Band, distanceMm: Int?) {
        self.band = band
        self.distanceMm = distanceMm
    }
}

public struct AssembledFrame: Equatable, Sendable {
    public let frameId: Int64
    public let captureBoardUs: Int64
    public let capturePhoneUs: Int64
    public let w: Int
    public let h: Int
    public let jpeg: [UInt8]

    public init(frameId: Int64, captureBoardUs: Int64, capturePhoneUs: Int64, w: Int, h: Int, jpeg: [UInt8]) {
        self.frameId = frameId
        self.captureBoardUs = captureBoardUs
        self.capturePhoneUs = capturePhoneUs
        self.w = w
        self.h = h
        self.jpeg = jpeg
    }
}

public struct BoardDiagnostics: Equatable, Sendable {
    public let board: BoardId
    public let beatAgeMs: Int64?
    public let offsetUs: Int64?
    public let rttUs: Int64?
    public let synced: Bool
    public let faults: Int
    public let rssiDbm: Int?

    public init(board: BoardId, beatAgeMs: Int64?, offsetUs: Int64?, rttUs: Int64?, synced: Bool, faults: Int, rssiDbm: Int?) {
        self.board = board
        self.beatAgeMs = beatAgeMs
        self.offsetUs = offsetUs
        self.rttUs = rttUs
        self.synced = synced
        self.faults = faults
        self.rssiDbm = rssiDbm
    }
}

public struct Diagnostics: Equatable, Sendable {
    public let nowPhoneUs: Int64
    public let state: SystemState
    public let cam: BoardDiagnostics
    public let cue: BoardDiagnostics
    public let frameAgeMs: Int64?
    public let rangeAgeMs: Int64?
    /// Last accepted forward ToF measurement (one zone). `rangeMm` is meaningful only when `rangeStatus` is VALID.
    public let rangeMm: Int?
    public let rangeStatus: Int?
    public let lastAlert: Alert?
    public let lastCueLatencyMs: Double?
    public let captureToCueP50Ms: Double?
    public let captureToCueP95Ms: Double?
    public let ackP50Ms: Double?
    public let ackP95Ms: Double?
    public let cuesSent: Int64
    public let acks: Int64
    public let nacks: Int64
    public let missedAcks: Int64
    public let malformed: Int64
    public let staleDropped: Int64
    public let frameSource: FrameSourceKind
    /// Camera-board packets received while frameSource = phoneCamera (ignored, never trusted).
    public let ignoredCamPackets: Int64
    /// Cue-board RSSI stayed below `Tuning.weakLinkRssiDbm` for `weakLinkHoldMs` (informational, no state change).
    public let linkWeak: Bool
    /// Least-squares closing speed (mm/s, positive = approaching) over the last `approachWindowMs` of VALID samples;
    /// nil with fewer than 3 samples or no fresh VALID reading. Always computed, even with approach warnings off.
    public let closingSpeedMmPerS: Double?
    /// The reported band is currently escalated by the (experimental) approach-speed rule.
    public let approachActive: Bool
    /// Exploration mode is currently pulsing the temples.
    public let exploring: Bool

    public init(
        nowPhoneUs: Int64, state: SystemState, cam: BoardDiagnostics, cue: BoardDiagnostics,
        frameAgeMs: Int64?, rangeAgeMs: Int64?, rangeMm: Int?, rangeStatus: Int?, lastAlert: Alert?,
        lastCueLatencyMs: Double?, captureToCueP50Ms: Double?, captureToCueP95Ms: Double?,
        ackP50Ms: Double?, ackP95Ms: Double?,
        cuesSent: Int64, acks: Int64, nacks: Int64, missedAcks: Int64, malformed: Int64, staleDropped: Int64,
        frameSource: FrameSourceKind, ignoredCamPackets: Int64,
        linkWeak: Bool = false, closingSpeedMmPerS: Double? = nil, approachActive: Bool = false, exploring: Bool = false
    ) {
        self.nowPhoneUs = nowPhoneUs
        self.state = state
        self.cam = cam
        self.cue = cue
        self.frameAgeMs = frameAgeMs
        self.rangeAgeMs = rangeAgeMs
        self.rangeMm = rangeMm
        self.rangeStatus = rangeStatus
        self.lastAlert = lastAlert
        self.lastCueLatencyMs = lastCueLatencyMs
        self.captureToCueP50Ms = captureToCueP50Ms
        self.captureToCueP95Ms = captureToCueP95Ms
        self.ackP50Ms = ackP50Ms
        self.ackP95Ms = ackP95Ms
        self.cuesSent = cuesSent
        self.acks = acks
        self.nacks = nacks
        self.missedAcks = missedAcks
        self.malformed = malformed
        self.staleDropped = staleDropped
        self.frameSource = frameSource
        self.ignoredCamPackets = ignoredCamPackets
        self.linkWeak = linkWeak
        self.closingSpeedMmPerS = closingSpeedMmPerS
        self.approachActive = approachActive
        self.exploring = exploring
    }
}

/// A cue to play. Audio gains (dBFS <= 0, -128 = mute) go to the `LocalCueSink` only; the haptic fields go to the
/// cue board in the CUE packet (the board never plays sound). The engine adds seq, issue time and deadline.
public struct CueCommand: Equatable, Sendable {
    public var cueId: Int
    public var side: Int
    public var gainLDb: Int
    public var gainRDb: Int
    public var hapticPattern: Int
    public var hapticL: Int
    public var hapticR: Int
    public var flags: Int
    public var ttlMs: Int

    public init(cueId: Int, side: Int, gainLDb: Int, gainRDb: Int, hapticPattern: Int, hapticL: Int, hapticR: Int, flags: Int, ttlMs: Int) {
        self.cueId = cueId
        self.side = side
        self.gainLDb = gainLDb
        self.gainRDb = gainRDb
        self.hapticPattern = hapticPattern
        self.hapticL = hapticL
        self.hapticR = hapticR
        self.flags = flags
        self.ttlMs = ttlMs
    }
}

/// Callbacks run synchronously inside Engine calls.
public protocol EngineListener {
    func onState(_ state: SystemState, reason: String)
    func onAlert(_ alert: Alert?)
    func onDiagnostics(_ d: Diagnostics)
}

/// Latest accepted forward ToF measurement, for display (PROTOCOL.md v2 RANGE: one zone, canonical status).
public struct RangeSnapshot: Equatable, Sendable {
    public let tBoardUs: Int64
    public let rxPhoneUs: Int64
    public let mm: Int
    public let status: Int

    public init(tBoardUs: Int64, rxPhoneUs: Int64, mm: Int, status: Int) {
        self.tBoardUs = tBoardUs
        self.rxPhoneUs = rxPhoneUs
        self.mm = mm
        self.status = status
    }
}

/// Thrown by `Engine.onPhoneFrame` when the engine is not configured for Tier 0.
public struct EngineStateError: Error, Equatable, CustomStringConvertible {
    public let description: String
    public init(_ description: String) { self.description = description }
}
