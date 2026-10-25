// Arch §7.2 hazard policy, forward-zone edition (port of policy/HazardPolicy.kt). The distance comes only from the
// forward ToF measurement - never from camera detections, and no left/right is ever inferred.

/// One forward-ToF sample for the policy. `known` = false means UNKNOWN status (never treated as nothing-there);
/// `distanceMm` is non-nil only for a VALID target inside the reporting range; `validMm` is the VALID distance even
/// beyond the reporting range (nil for NO_TARGET/UNKNOWN) - the hysteresis and approach rules need it.
public struct ForwardObservation: Equatable, Sendable {
    public let band: Band
    public let distanceMm: Int?
    public let known: Bool
    public let validMm: Int?

    public init(band: Band, distanceMm: Int?, known: Bool, validMm: Int? = nil) {
        self.band = band
        self.distanceMm = distanceMm
        self.known = known
        self.validMm = validMm ?? distanceMm
    }
}

public struct PolicyConfig: Equatable, Sendable {
    /// At most one onset (a band appearing from nothing) per this interval.
    public var minOnsetIntervalUs: Int64

    public init(minOnsetIntervalUs: Int64 = 700_000) {
        self.minOnsetIntervalUs = minOnsetIntervalUs
    }
}

/// Band thresholds, enter/exit timing and hysteresis come from `Tuning`:
/// - band(d): NEAR < nearMm, MID < midMm, FAR <= farMm, else NONE (no report);
/// - smoother (board time of the RANGE samples): a band, or a more urgent one, is entered after the readings
///   consistently indicated it for >= bandEnterMs (the run keeps the LEAST urgent band seen); leaving to a less
///   urgent band / to nothing needs >= bandExitMs (the run keeps the MOST urgent band seen) and the distance must
///   exceed the band's upper bound by bandHysteresisMm (strictly) to count; NO_TARGET counts as infinitely far;
/// - presentation: <= 1 onset per minOnsetIntervalUs; head scanning hides FAR; approach escalation is transient;
/// - never CLEAR: the output is an alert or nil. An UNKNOWN sample drops everything at once.
public final class HazardPolicy {
    public let config: PolicyConfig
    public var tuning: Tuning

    private var active: Band = .none
    private var upCand: Band?
    private var upSinceUs: Int64 = 0
    private var downCand: Band?
    private var downSinceUs: Int64 = 0
    private var lastSampleUs: Int64?
    private var presenting = false
    private var lastOnsetUs = Int64.min / 2

    /// True once per onset (consumed by `takeOnset`); the cue scheduler interrupts and the spearcon fires.
    private var onsetPending = false

    public init(config: PolicyConfig = PolicyConfig(), tuning: Tuning = Tuning()) {
        self.config = config
        self.tuning = tuning
    }

    public func band(_ mm: Int) -> Band {
        if mm < tuning.nearMm { return .near }
        if mm < tuning.midMm { return .mid }
        if mm <= tuning.farMm { return .far }
        return .none
    }

    public func observe(_ reading: ForwardRange) -> ForwardObservation {
        switch reading {
        case .target(let mm):
            let b = band(mm)
            return ForwardObservation(band: b, distanceMm: b == .none ? nil : mm, known: true, validMm: mm)
        case .nothingSeen:
            return ForwardObservation(band: .none, distanceMm: nil, known: true)
        case .unknown:
            return ForwardObservation(band: .none, distanceMm: nil, known: false)
        }
    }

    private func upper(_ b: Band) -> Int {
        switch b {
        case .near: return tuning.nearMm
        case .mid: return tuning.midMm
        default: return tuning.farMm
        }
    }

    private func lessUrgent(_ b: Band) -> Band { Band(rawValue: b.rawValue - 1) ?? .none }

    /// Where a reading that indicates the less urgent band `raw` lets the active band `a` go: each step out of a
    /// band needs the distance strictly beyond its upper bound + hysteresis (NO_TARGET = beyond everything).
    private func downTarget(from a: Band, raw: Band, mm: Int?) -> Band {
        var b = a
        while b > raw {
            if let mm, mm <= upper(b) + tuning.bandHysteresisMm { break }
            b = lessUrgent(b)
        }
        return b
    }

    /// One sample (a fresh forward ToF measurement; `boardUs` = its board time). `approach` = the experimental
    /// approach-speed rule fires for this sample. Returns the alert to present now, or nil for nothing to report.
    public func step(_ obs: ForwardObservation, scanning: Bool, approach: Bool = false, nowUs: Int64, boardUs: Int64) -> Alert? {
        if !obs.known {
            // Unknown is never clear and never a distance: stop presenting the last band, say nothing instead.
            resetSmoother()
            return nil
        }
        if let last = lastSampleUs, boardUs < last { resetSmoother() } // board reboot: its clock restarted
        lastSampleUs = boardUs
        smooth(obs, boardUs: boardUs)
        return present(obs, scanning: scanning, approach: approach, nowUs: nowUs)
    }

    private func smooth(_ obs: ForwardObservation, boardUs t: Int64) {
        let raw = obs.band
        if raw > active {
            downCand = nil
            if let c = upCand { if raw < c { upCand = raw } } else { upCand = raw; upSinceUs = t }
            if let c = upCand, t &- upSinceUs >= Int64(tuning.bandEnterMs) * 1000 {
                active = c
                upCand = nil
            }
            return
        }
        upCand = nil
        let target = downTarget(from: active, raw: raw, mm: obs.validMm)
        if target == active { downCand = nil; return }
        if let c = downCand { if target > c { downCand = target } } else { downCand = target; downSinceUs = t }
        if let c = downCand, t &- downSinceUs >= Int64(tuning.bandExitMs) * 1000 {
            active = c
            downCand = nil
        }
    }

    private func escalated(_ b: Band, mm: Int?) -> Band {
        switch b {
        case .far: return .mid
        case .mid: return .near
        case .near: return .near
        case .none: return (mm.map { $0 <= tuning.farMm } ?? false) ? .far : .none
        }
    }

    private func present(_ obs: ForwardObservation, scanning: Bool, approach: Bool, nowUs: Int64) -> Alert? {
        let shown = approach ? escalated(active, mm: obs.validMm) : active
        if shown == .none || (scanning && shown == .far) { presenting = false; return nil }
        if !presenting {
            if nowUs &- lastOnsetUs < config.minOnsetIntervalUs { return nil }
            presenting = true
            lastOnsetUs = nowUs
            onsetPending = true
        }
        return Alert(band: shown, distanceMm: obs.distanceMm)
    }

    public func takeOnset() -> Bool {
        let v = onsetPending
        onsetPending = false
        return v
    }

    private func resetSmoother() {
        active = .none; upCand = nil; downCand = nil; lastSampleUs = nil; presenting = false
    }

    public func reset() {
        resetSmoother()
        onsetPending = false
    }
}
