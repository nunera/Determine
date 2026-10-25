// Port of cue/CueScheduler.kt. Turns the current alert into one repetition of the warning: the phone plays the
// sound (CueCommand gains -> LocalCueSink) and the cue board vibrates the temples (haptic fields -> CUE packet).
// The single forward zone never lateralizes: audio and haptics are always side BOTH, and PERSON_* cue ids are
// never selected.

public struct CueConfig: Equatable, Sendable {
    /// Arch §8: NEAR is +6 dB, always within the ceiling.
    public var nearStepDb: Int
    public var ttlMs: Int
    /// Haptic intensity scale per band (Arch §8.3 "intensity is user-tunable").
    public var hapticScaleFar: Float
    public var hapticScaleMid: Float
    public var hapticScaleNear: Float

    public init(
        nearStepDb: Int = 6, ttlMs: Int = 150,
        hapticScaleFar: Float = 0.6, hapticScaleMid: Float = 0.8, hapticScaleNear: Float = 1.0
    ) {
        self.nearStepDb = nearStepDb
        self.ttlMs = ttlMs
        self.hapticScaleFar = hapticScaleFar
        self.hapticScaleMid = hapticScaleMid
        self.hapticScaleNear = hapticScaleNear
    }
}

public final class CueScheduler {
    public let config: CueConfig
    public var tuning: Tuning
    private var current: Alert?
    private var nextDueUs: Int64 = 0

    public init(config: CueConfig = CueConfig(), tuning: Tuning = Tuning()) {
        self.config = config
        self.tuning = tuning
    }

    public func period(_ b: Band) -> Int64 {
        switch b {
        case .far: return Int64(tuning.farPeriodMs) * 1000
        case .mid: return Int64(tuning.midPeriodMs) * 1000
        case .near: return Int64(tuning.nearPeriodMs) * 1000
        case .none: return Int64.max
        }
    }

    /// Returns a cue to play now, or nil. Call on every tick and whenever the alert changes.
    public func update(_ alert: Alert?, onset: Bool, profile: HearingProfile, nowUs: Int64) -> CueCommand? {
        guard let alert = alert, alert.band != .none else { current = nil; return nil }
        let prev = current
        current = alert
        let changed = onset || prev == nil || prev!.band != alert.band
        if !changed && nowUs < nextDueUs { return nil }
        let p = period(alert.band)
        nextDueUs = (changed || nowUs - nextDueUs >= p) ? nowUs + p : nextDueUs + p
        return build(alert, profile: profile, interrupt: changed)
    }

    public func reset() { current = nil }

    public func build(_ alert: Alert, profile: HearingProfile, interrupt: Bool) -> CueCommand {
        let cueId: Int
        switch alert.band {
        case .near: cueId = CueId.obstacleNear
        case .mid: cueId = CueId.obstacleMid
        default: cueId = CueId.obstacleFar
        }
        // Calibration band matching the timbre: urgent note ~2 kHz, wood block ~1 kHz.
        let calBand = alert.band == .near ? CalBand.b2k : CalBand.b1k
        let step = alert.band == .near ? config.nearStepDb : 0
        let ceil = profile.effectiveCeilingDb
        func level(_ e: Ear) -> Int {
            profile.levelDb(calBand, e).map { min($0 + step, ceil) } ?? GAIN_MUTE
        }
        let l = level(.left)
        let r = level(.right)

        // SSD and STEREO both play every usable side at its own calibrated level: the forward zone never
        // lateralizes. HAPTIC_ONLY mutes the phone audio; the board haptics are unaffected.
        let gl: Int
        let gr: Int
        switch profile.mode {
        case .stereo, .ssd: gl = l; gr = r
        case .hapticOnly: gl = GAIN_MUTE; gr = GAIN_MUTE
        }

        let pattern: Int
        switch alert.band {
        case .near: pattern = Haptic.buzz150
        case .mid: pattern = Haptic.pulse60
        default: pattern = Haptic.pulse40
        }
        let scale: Float
        switch alert.band {
        case .near: scale = config.hapticScaleNear
        case .mid: scale = config.hapticScaleMid
        default: scale = config.hapticScaleFar
        }
        // Forward zone: both temples, never a left/right claim.
        func hap(_ gain: Int) -> Int { max(0, min(255, min(Int(Float(gain) * scale), profile.hapticMax))) }
        let hl = hap(profile.hapticGainL)
        let hr = hap(profile.hapticGainR)

        return CueCommand(cueId: cueId, side: Side.both, gainLDb: gl, gainRDb: gr, hapticPattern: pattern,
                          hapticL: hl, hapticR: hr, flags: interrupt ? 1 : 0, ttlMs: config.ttlMs)
    }

    /// Spearcon text (Arch §8.2); distance only when backed by valid ToF. The distance is metres to one decimal,
    /// rounded half-up exactly as Java's `%.1f` does for the decimal value mm/1000 (integer arithmetic, no float).
    public static func spearcon(_ a: Alert) -> String {
        var dist = ""
        if let mm = a.distanceMm {
            let tenths = mm >= 0 ? (mm + 50) / 100 : -((-mm + 50) / 100)
            dist = ", \(tenths / 10).\(abs(tenths % 10)) metres"
        }
        return "obstacle ahead\(dist)"
    }
}
