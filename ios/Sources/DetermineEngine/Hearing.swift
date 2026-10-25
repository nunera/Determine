// Port of hearing/Hearing.kt: only what the Engine and cue scheduler need (mode, profile, levels, validation).
// Calibration (Staircase, CalibrationSession) is intentionally not ported.

/// Firmware hard ceiling (determine_wire.h DT_FW_HARD_CEILING_DB). The phone never asks for more.
public let HARD_CEILING_DB = -10

/// Lowest allowed board haptic cap (PROTOCOL.md v2: PROFILE.haptic_max is 1..255, the board rejects 0 as BAD_CUE).
/// The temple haptics are the only failure channel that survives phone loss, so the user can never configure them
/// off; a cap of 1 is the weakest setting and is NOT a measured perceptibility guarantee.
public let MIN_HAPTIC_MAX = 1

/// Phone audio semantics; the board is always haptic-only.
public enum HearingMode: Int, Sendable {
    case stereo = 0
    case ssd = 1
    case hapticOnly = 2

    public var wire: Int { rawValue }
}

public enum Ear: Sendable { case left, right }

/// Calibration bands matching the cue timbres (Arch §9). Index used by `HearingProfile.bands`.
public enum CalBand {
    public static let b500 = 0
    public static let b1k = 1
    public static let b2k = 2
    public static let hz = [500, 1000, 2000]
}

public struct HearingProfileError: Error, Equatable, CustomStringConvertible {
    public let description: String
    public init(_ description: String) { self.description = description }
}

/// Per band; a nil threshold means that side could not detect the tone at the ceiling (unusable).
public struct BandProfile: Equatable, Sendable {
    public let hz: Int
    public let thrDbL: Int?
    public let thrDbR: Int?
    public let slDb: Int
    public let offsetDbL: Int
    public let offsetDbR: Int

    /// Throws when the sensation level is outside 10...20 dB (Arch §9).
    public init(hz: Int, thrDbL: Int?, thrDbR: Int?, slDb: Int, offsetDbL: Int, offsetDbR: Int) throws {
        guard (10...20).contains(slDb) else { throw HearingProfileError("sensation level must be 10..20 dB (Arch §9)") }
        self.hz = hz
        self.thrDbL = thrDbL
        self.thrDbR = thrDbR
        self.slDb = slDb
        self.offsetDbL = offsetDbL
        self.offsetDbR = offsetDbR
    }
}

/// Arch §9.1 HearingProfile. Every level it produces is <= `effectiveCeilingDb` ("ceiling first").
public struct HearingProfile: Equatable, Sendable {
    public let deviceId: String
    public let createdAtMs: Int64
    public let mode: HearingMode
    public let ceilingDb: Int
    public let bands: [BandProfile]
    public let hapticGainL: Int
    public let hapticGainR: Int
    public let hapticMax: Int
    public let verifiedTrials: Int
    public let verifiedCorrect: Int
    public let fitNote: String

    /// Throws `HearingProfileError` unless: ceiling <= 0 dBFS, one band per `CalBand.hz`, haptic gains 0...255 and
    /// `hapticMax` in `MIN_HAPTIC_MAX...255` (the watchdog failure channel cannot be disabled).
    public init(
        deviceId: String, createdAtMs: Int64, mode: HearingMode, ceilingDb: Int, bands: [BandProfile],
        hapticGainL: Int, hapticGainR: Int, hapticMax: Int, verifiedTrials: Int, verifiedCorrect: Int,
        fitNote: String = ""
    ) throws {
        guard ceilingDb <= 0 else { throw HearingProfileError("ceiling is dBFS <= 0") }
        guard bands.count == CalBand.hz.count else { throw HearingProfileError("bands must have \(CalBand.hz.count) entries") }
        guard (0...255).contains(hapticGainL), (0...255).contains(hapticGainR) else {
            throw HearingProfileError("haptic gains are 0..255")
        }
        guard (MIN_HAPTIC_MAX...255).contains(hapticMax) else {
            throw HearingProfileError("haptic_max is \(MIN_HAPTIC_MAX)..255: the watchdog failure channel cannot be disabled")
        }
        self.deviceId = deviceId
        self.createdAtMs = createdAtMs
        self.mode = mode
        self.ceilingDb = ceilingDb
        self.bands = bands
        self.hapticGainL = hapticGainL
        self.hapticGainR = hapticGainR
        self.hapticMax = hapticMax
        self.verifiedTrials = verifiedTrials
        self.verifiedCorrect = verifiedCorrect
        self.fitNote = fitNote
    }

    /// Kotlin `copy(...)`: a revalidated copy with the given fields replaced.
    public func copy(
        deviceId: String? = nil, createdAtMs: Int64? = nil, mode: HearingMode? = nil, ceilingDb: Int? = nil,
        bands: [BandProfile]? = nil, hapticGainL: Int? = nil, hapticGainR: Int? = nil, hapticMax: Int? = nil,
        verifiedTrials: Int? = nil, verifiedCorrect: Int? = nil, fitNote: String? = nil
    ) throws -> HearingProfile {
        try HearingProfile(
            deviceId: deviceId ?? self.deviceId, createdAtMs: createdAtMs ?? self.createdAtMs,
            mode: mode ?? self.mode, ceilingDb: ceilingDb ?? self.ceilingDb, bands: bands ?? self.bands,
            hapticGainL: hapticGainL ?? self.hapticGainL, hapticGainR: hapticGainR ?? self.hapticGainR,
            hapticMax: hapticMax ?? self.hapticMax, verifiedTrials: verifiedTrials ?? self.verifiedTrials,
            verifiedCorrect: verifiedCorrect ?? self.verifiedCorrect, fitNote: fitNote ?? self.fitNote
        )
    }

    public var effectiveCeilingDb: Int { min(ceilingDb, HARD_CEILING_DB) }

    /// thr + sensation level + balance offset, clamped to the ceiling; nil = side unusable in this band.
    public func levelDb(_ band: Int, _ ear: Ear) -> Int? {
        let b = bands[band]
        guard let thr = (ear == .left) ? b.thrDbL : b.thrDbR else { return nil }
        let off = (ear == .left) ? b.offsetDbL : b.offsetDbR
        return min(thr + b.slDb + off, effectiveCeilingDb)
    }

    public func usable(_ ear: Ear) -> Bool {
        bands.allSatisfy { (ear == .left ? $0.thrDbL : $0.thrDbR) != nil }
    }

    /// Before calibration: a conservative quiet level 20 dB below the ceiling on both sides.
    /// Precondition: `ceilingDb <= 0` (a positive dBFS ceiling is a programming error).
    public static func uncalibrated(ceilingDb: Int = -20, mode: HearingMode = .stereo, nowMs: Int64 = 0) -> HearingProfile {
        let c = min(ceilingDb, HARD_CEILING_DB)
        let thr = c - 35
        do {
            return try HearingProfile(
                deviceId: "uncalibrated", createdAtMs: nowMs, mode: mode, ceilingDb: ceilingDb,
                bands: try CalBand.hz.map { try BandProfile(hz: $0, thrDbL: thr, thrDbR: thr, slDb: 15, offsetDbL: 0, offsetDbR: 0) },
                hapticGainL: 200, hapticGainR: 200, hapticMax: 255, verifiedTrials: 0, verifiedCorrect: 0
            )
        } catch {
            preconditionFailure("uncalibrated(ceilingDb: \(ceilingDb)): \(error)")
        }
    }
}
