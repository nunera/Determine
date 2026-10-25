// Arch §4.2 runtime state machine (port of system/SystemMonitor.kt).
//  STARTING -> READY when frames fresh, ToF ok, cue link confirmed (and clocks synced).
//  READY <-> DEGRADED on ToF stale or UNKNOWN status.
//  READY/DEGRADED -> UNAVAILABLE on frame age > 500 ms, BEAT missing > 500 ms from either board, ACK failures,
//  battery critical. The camera being down is UNAVAILABLE, never ToF-only.
//  PAUSED only on explicit request; resuming re-enters STARTING.

public struct MonitorConfig: Equatable, Sendable {
    public var frameMaxAgeUs: Int64
    public var beatTimeoutUs: Int64
    public var rangeMaxAgeUs: Int64
    /// Consecutive cues/profiles without a good ACK (missing within 300 ms, or NACK) -> UNAVAILABLE.
    public var maxAckFailures: Int
    /// STARTING that never becomes ready is a failure too: fail loud rather than stay silent forever.
    public var startupTimeoutUs: Int64

    public init(frameMaxAgeUs: Int64 = 500_000, beatTimeoutUs: Int64 = 500_000, rangeMaxAgeUs: Int64 = 500_000,
                maxAckFailures: Int = 3, startupTimeoutUs: Int64 = 10_000_000) {
        self.frameMaxAgeUs = frameMaxAgeUs
        self.beatTimeoutUs = beatTimeoutUs
        self.rangeMaxAgeUs = rangeMaxAgeUs
        self.maxAckFailures = maxAckFailures
        self.startupTimeoutUs = startupTimeoutUs
    }
}

/// Snapshot of everything the state machine needs. Ages are nil when never received.
public struct MonitorInputs: Equatable, Sendable {
    public var nowUs: Int64
    public var camBeatAgeUs: Int64?
    public var cueBeatAgeUs: Int64?
    /// Age (offset-corrected capture -> now) of the newest complete frame; nil if none or no clock sync.
    public var frameAgeUs: Int64?
    public var rangeAgeUs: Int64?
    /// The forward ToF zone's last status was known (VALID or NO_TARGET), not UNKNOWN.
    public var tofKnown: Bool
    public var clocksSynced: Bool
    public var cueLinkConfirmed: Bool
    public var ackFailures: Int
    public var batteryCritical: Bool
    public var paused: Bool
    /// False in Tier 0 (phone camera): no camera board exists, so its BEAT is not required. Frames still are.
    public var camBoardRequired: Bool
    /// The headset sink reported AUDIO_ROUTE_LOST: definitive, no grace.
    public var audioRouteLost: Bool

    public init(
        nowUs: Int64, camBeatAgeUs: Int64?, cueBeatAgeUs: Int64?, frameAgeUs: Int64?, rangeAgeUs: Int64?,
        tofKnown: Bool, clocksSynced: Bool, cueLinkConfirmed: Bool, ackFailures: Int, batteryCritical: Bool,
        paused: Bool, camBoardRequired: Bool = true, audioRouteLost: Bool = false
    ) {
        self.nowUs = nowUs
        self.camBeatAgeUs = camBeatAgeUs
        self.cueBeatAgeUs = cueBeatAgeUs
        self.frameAgeUs = frameAgeUs
        self.rangeAgeUs = rangeAgeUs
        self.tofKnown = tofKnown
        self.clocksSynced = clocksSynced
        self.cueLinkConfirmed = cueLinkConfirmed
        self.ackFailures = ackFailures
        self.batteryCritical = batteryCritical
        self.paused = paused
        self.camBoardRequired = camBoardRequired
        self.audioRouteLost = audioRouteLost
    }
}

public final class SystemMonitor {
    public struct Transition: Equatable, Sendable {
        public let from: SystemState
        public let to: SystemState
        public let reason: String
    }

    public let config: MonitorConfig
    public private(set) var state = SystemState.starting
    public private(set) var reason = "boot"
    private var startingSinceUs: Int64?

    public init(config: MonitorConfig = MonitorConfig()) {
        self.config = config
    }

    /// First failure reason, or nil when every liveness condition holds.
    public func failure(_ i: MonitorInputs) -> String? {
        if i.batteryCritical { return "battery_critical" }
        if i.audioRouteLost { return "headset_route_lost" }
        if i.camBoardRequired, i.camBeatAgeUs == nil || i.camBeatAgeUs! > config.beatTimeoutUs { return "cam_beat_missing" }
        if i.cueBeatAgeUs == nil || i.cueBeatAgeUs! > config.beatTimeoutUs { return "cue_beat_missing" }
        if i.ackFailures >= config.maxAckFailures { return "cue_ack_missing" }
        if !i.clocksSynced { return "clock_unsynced" }
        if i.frameAgeUs == nil || i.frameAgeUs! > config.frameMaxAgeUs { return "frame_stale" }
        return nil
    }

    /// ToF problem reason (DEGRADED), or nil when ToF is usable.
    public func tofProblem(_ i: MonitorInputs) -> String? {
        if i.rangeAgeUs == nil || i.rangeAgeUs! > config.rangeMaxAgeUs { return "range_stale" }
        if !i.tofKnown { return "tof_unknown" }
        return nil
    }

    public func evaluate(_ i: MonitorInputs) -> Transition? {
        if startingSinceUs == nil { startingSinceUs = i.nowUs }
        if i.paused { return go(.paused, "user_pause") }
        let fail = failure(i)
        let tof = tofProblem(i)
        switch state {
        case .paused:
            startingSinceUs = i.nowUs
            return go(.starting, "resumed")
        case .starting:
            if fail == nil && i.cueLinkConfirmed && tof == nil { return go(.ready, "all_ok") }
            if fail == nil && i.cueLinkConfirmed && tof == "tof_unknown" { return go(.degraded, tof!) }
            if i.audioRouteLost { return go(.unavailable, "headset_route_lost") }
            if i.nowUs - startingSinceUs! > config.startupTimeoutUs {
                let why = fail ?? (!i.cueLinkConfirmed ? "cue_ack_missing" : tof)
                return go(.unavailable, "startup_timeout:" + (why ?? "null"))
            }
            return nil
        case .ready, .degraded:
            if let fail = fail { return go(.unavailable, fail) }
            if let tof = tof { return go(.degraded, tof) }
            return go(.ready, "all_ok")
        case .unavailable:
            if fail != nil || !i.cueLinkConfirmed { return nil }
            if let tof = tof { return go(.degraded, "recovered:\(tof)") }
            return go(.ready, "recovered")
        }
    }

    private func go(_ to: SystemState, _ why: String) -> Transition? {
        if to == state { return nil }
        let t = Transition(from: state, to: to, reason: why)
        state = to
        reason = why
        return t
    }
}
