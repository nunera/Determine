// Runtime-tunable engine parameters (feature contract §7; mirror of org.determine.core.Tuning). Every field has a
// documented default; `Engine.setTuning` validates and applies a whole value immediately, no restart.

/// Thrown by `Tuning.validated()` / `Engine.setTuning` for a value the engine cannot run with.
public struct TuningError: Error, Equatable, CustomStringConvertible {
    public let description: String
    public init(_ description: String) { self.description = description }
}

public struct Tuning: Equatable, Sendable {
    // Bands: NEAR < nearMm, MID < midMm, FAR <= farMm; anything beyond farMm is not reported.
    public var nearMm: Int
    public var midMm: Int
    public var farMm: Int
    /// A band (or a more urgent one) is entered after readings consistently indicated it for this long (board time).
    public var bandEnterMs: Int
    /// Leaving a band (to a less urgent one, or to "nothing to report") needs this much consistent evidence.
    public var bandExitMs: Int
    /// Moving out of a band toward less urgent needs the distance to exceed the band's upper bound by this much.
    public var bandHysteresisMm: Int
    /// Cue repetition period per band.
    public var farPeriodMs: Int
    public var midPeriodMs: Int
    public var nearPeriodMs: Int
    /// A spoken READY / DEGRADED announcement waits until the new state held this long...
    public var announceHoldMs: Int
    /// ...and the same phrase is not spoken again within this window.
    public var announceRepeatMs: Int
    /// Cue-board RSSI below this for `weakLinkHoldMs` = weak link (recovers at +5 dB for the same hold).
    public var weakLinkRssiDbm: Int
    public var weakLinkHoldMs: Int
    public var weakLinkRepeatMs: Int
    /// EXPERIMENTAL approach-speed escalation (default off).
    public var approachEnabled: Bool
    public var approachWindowMs: Int
    public var approachTtcMs: Int
    public var approachMinSpeedMmPerS: Int
    /// EXPERIMENTAL exploration mode (default off): haptic pulses whose strength follows the distance.
    public var exploreEnabled: Bool
    public var explorePeriodMs: Int
    public var exploreMaxMm: Int
    public var exploreMinIntensity: Int

    public init(
        nearMm: Int = 500, midMm: Int = 900, farMm: Int = 1200,
        bandEnterMs: Int = 150, bandExitMs: Int = 600, bandHysteresisMm: Int = 50,
        farPeriodMs: Int = 1000, midPeriodMs: Int = 400, nearPeriodMs: Int = 200,
        announceHoldMs: Int = 1500, announceRepeatMs: Int = 10_000,
        weakLinkRssiDbm: Int = -70, weakLinkHoldMs: Int = 2000, weakLinkRepeatMs: Int = 30_000,
        approachEnabled: Bool = false, approachWindowMs: Int = 300, approachTtcMs: Int = 1500,
        approachMinSpeedMmPerS: Int = 300,
        exploreEnabled: Bool = false, explorePeriodMs: Int = 120, exploreMaxMm: Int = 1200,
        exploreMinIntensity: Int = 30
    ) {
        self.nearMm = nearMm
        self.midMm = midMm
        self.farMm = farMm
        self.bandEnterMs = bandEnterMs
        self.bandExitMs = bandExitMs
        self.bandHysteresisMm = bandHysteresisMm
        self.farPeriodMs = farPeriodMs
        self.midPeriodMs = midPeriodMs
        self.nearPeriodMs = nearPeriodMs
        self.announceHoldMs = announceHoldMs
        self.announceRepeatMs = announceRepeatMs
        self.weakLinkRssiDbm = weakLinkRssiDbm
        self.weakLinkHoldMs = weakLinkHoldMs
        self.weakLinkRepeatMs = weakLinkRepeatMs
        self.approachEnabled = approachEnabled
        self.approachWindowMs = approachWindowMs
        self.approachTtcMs = approachTtcMs
        self.approachMinSpeedMmPerS = approachMinSpeedMmPerS
        self.exploreEnabled = exploreEnabled
        self.explorePeriodMs = explorePeriodMs
        self.exploreMaxMm = exploreMaxMm
        self.exploreMinIntensity = exploreMinIntensity
    }

    /// Throws `TuningError` unless: 0 < nearMm < midMm < farMm <= 8000; bandEnterMs, bandExitMs, bandHysteresisMm,
    /// announceHoldMs, announceRepeatMs, weakLinkHoldMs, weakLinkRepeatMs >= 0; the three band periods,
    /// approachWindowMs, approachTtcMs, approachMinSpeedMmPerS, explorePeriodMs > 0; 0 < exploreMaxMm <= 8000;
    /// exploreMinIntensity in 0...255; weakLinkRssiDbm in -127...0.
    public func validate() throws {
        func need(_ ok: Bool, _ what: String) throws { if !ok { throw TuningError(what) } }
        try need(0 < nearMm && nearMm < midMm && midMm < farMm && farMm <= 8000,
                 "bands must satisfy 0 < nearMm < midMm < farMm <= 8000 (got \(nearMm)/\(midMm)/\(farMm))")
        try need(bandEnterMs >= 0 && bandExitMs >= 0 && bandHysteresisMm >= 0,
                 "bandEnterMs, bandExitMs and bandHysteresisMm must be >= 0")
        try need(farPeriodMs > 0 && midPeriodMs > 0 && nearPeriodMs > 0, "band periods must be > 0")
        try need(announceHoldMs >= 0 && announceRepeatMs >= 0, "announceHoldMs and announceRepeatMs must be >= 0")
        try need(-127...0 ~= weakLinkRssiDbm, "weakLinkRssiDbm must be in -127...0")
        try need(weakLinkHoldMs >= 0 && weakLinkRepeatMs >= 0, "weakLinkHoldMs and weakLinkRepeatMs must be >= 0")
        try need(approachWindowMs > 0 && approachTtcMs > 0 && approachMinSpeedMmPerS > 0,
                 "approachWindowMs, approachTtcMs and approachMinSpeedMmPerS must be > 0")
        try need(explorePeriodMs > 0, "explorePeriodMs must be > 0")
        try need(0 < exploreMaxMm && exploreMaxMm <= 8000, "exploreMaxMm must be in 1...8000")
        try need(0...255 ~= exploreMinIntensity, "exploreMinIntensity must be in 0...255")
    }
}
