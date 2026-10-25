import DetermineEngine
import Foundation

// The stable cli output format of phone/core/API.md ("Stable cli output"); tools/sim/e2e.py parses it. Kotlin
// prints enum constant names (READY, NEAR, ...), so the Swift enums are mapped to the same upper-case tokens.

extension SystemState {
    var token: String {
        switch self {
        case .starting: return "STARTING"
        case .ready: return "READY"
        case .degraded: return "DEGRADED"
        case .unavailable: return "UNAVAILABLE"
        case .paused: return "PAUSED"
        }
    }
}

extension Band {
    var token: String {
        switch self {
        case .none: return "NONE"
        case .far: return "FAR"
        case .mid: return "MID"
        case .near: return "NEAR"
        }
    }
}

/// Spaces → `_`, empty → `-` (tokens never contain spaces).
func tok(_ s: String) -> String {
    let t = s.replacingOccurrences(of: " ", with: "_")
    return t.isEmpty ? "-" : t
}

func opt(_ v: Int64?) -> String { v.map { String($0) } ?? "-" }
func opt(_ v: Int?) -> String { v.map { String($0) } ?? "-" }
func opt(_ v: Double?) -> String { v.map { String(format: "%.2f", $0) } ?? "-" }

func alertLine(_ a: Alert?) -> String {
    guard let a else { return "none" }
    return "band=\(a.band.token) dist_mm=\(opt(a.distanceMm))"
}

func diagLine(_ d: Diagnostics) -> String {
    [
        "state=\(d.state.token)",
        "cam_beat_ms=\(opt(d.cam.beatAgeMs))", "cam_offset_us=\(opt(d.cam.offsetUs))",
        "cam_rtt_us=\(opt(d.cam.rttUs))", "cam_synced=\(d.cam.synced)",
        "cue_beat_ms=\(opt(d.cue.beatAgeMs))", "cue_offset_us=\(opt(d.cue.offsetUs))",
        "cue_rtt_us=\(opt(d.cue.rttUs))", "cue_synced=\(d.cue.synced)",
        "frame_age_ms=\(opt(d.frameAgeMs))", "range_age_ms=\(opt(d.rangeAgeMs))",
        "cap2cue_ms=\(opt(d.lastCueLatencyMs))", "cap2cue_p50=\(opt(d.captureToCueP50Ms))",
        "cap2cue_p95=\(opt(d.captureToCueP95Ms))",
        "ack_p50=\(opt(d.ackP50Ms))", "ack_p95=\(opt(d.ackP95Ms))",
        "cues=\(d.cuesSent)", "acks=\(d.acks)", "nacks=\(d.nacks)", "missed=\(d.missedAcks)",
        "malformed=\(d.malformed)", "stale=\(d.staleDropped)",
        rangeLine(d.rangeMm, d.rangeStatus), "range_left=unavailable", "range_right=unavailable",
        "link_weak=\(d.linkWeak)", "closing_mm_s=\(opt(d.closingSpeedMmPerS))", "approach=\(d.approachActive)",
        "exploring=\(d.exploring)",
    ].joined(separator: " ")
}

/// The forward ToF zone as one diagnostic pair: `range_mm=870 range_status=VALID` (canonical v2 status).
func rangeLine(_ mm: Int?, _ status: Int?) -> String {
    let st: String
    switch status {
    case nil: st = "-"
    case RangeStatus.valid: st = "VALID"
    case RangeStatus.noTarget: st = "NO_TARGET"
    case RangeStatus.unknown: st = "UNKNOWN"
    case let s?: st = "INVALID_\(s)"
    }
    let mmText = status == RangeStatus.valid ? (mm.map { String($0) } ?? "-") : "-"
    return "range_mm=\(mmText) range_status=\(st)"
}

/// Text line for an outgoing command, or nil for packets that are not logged (PING).
func txLine(_ packet: [UInt8]) -> (kind: String, rest: String)? {
    guard let d = try? Wire.decode(packet) else { return nil }
    switch d.packet {
    case .cue(let p):
        return ("CUE-TX", "seq=\(d.seq) cue=\(p.cueId) side=\(p.side) "
            + "hp=\(p.hapticPattern) hl=\(p.hapticL) hr=\(p.hapticR) flags=\(p.flags) ttl=\(p.ttlMs) deadline=\(p.deadlineBoardUs)")
    case .profile(let p):
        return ("PROFILE-TX", "seq=\(d.seq) mode=\(p.mode) haptic_max=\(p.hapticMax)")
    default:
        return nil
    }
}

/// Text line for a cue played by the local (headless) audio sink.
func audioLine(_ c: CueCommand, status: Int) -> String {
    "cue=\(c.cueId) side=\(c.side) gl=\(c.gainLDb) gr=\(c.gainRDb) status=\(status)"
}
