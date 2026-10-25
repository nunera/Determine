// The cue board's single forward ToF zone (port of range/ForwardRange.kt). No grid, no left/right split and no
// mounting transform: anything to the sides is simply not measured and must be presented as unavailable, never far.

public enum ForwardRange: Equatable, Sendable {
    /// Forward distance in mm from a VALID status.
    case target(mm: Int)
    /// The sensor positively saw nothing (NO_TARGET). Not "clear" - just nothing to report.
    case nothingSeen
    /// Invalid/error status: we do not know. Never treated as clear, never as far.
    case unknown
}

public enum ForwardTo {
    /// Canonical status (PROTOCOL.md v2 RANGE) -> reading. Unknown statuses stay `.unknown`.
    public static func reading(status: Int, mm: Int) -> ForwardRange {
        switch status {
        case RangeStatus.valid: return .target(mm: mm)
        case RangeStatus.noTarget: return .nothingSeen
        default: return .unknown
        }
    }
}
