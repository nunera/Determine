// Closing speed of the forward target (feature contract §5): least-squares slope of VALID distance against board
// time over the last `windowMs`. Always computed (Diagnostics), acted on only when `Tuning.approachEnabled`.

final class ApproachEstimator {
    private var samples: [(tUs: Int64, mm: Int)] = []

    /// A VALID sample. Board time going backwards (board reboot) restarts the window.
    func add(tUs: Int64, mm: Int, windowMs: Int) {
        if let last = samples.last, tUs < last.tUs { samples.removeAll() }
        samples.append((tUs, mm))
        let window = Int64(windowMs) * 1000
        samples.removeAll { tUs &- $0.tUs > window }
    }

    /// NO_TARGET / UNKNOWN: the target is gone, so is its motion history.
    func clear() { samples.removeAll() }

    var latestMm: Int? { samples.last?.mm }

    /// mm/s, positive = approaching. nil with fewer than 3 samples or no spread in time.
    func closingSpeedMmPerS() -> Double? {
        let n = samples.count
        if n < 3 { return nil }
        let t0 = samples[0].tUs
        var sx = 0.0, sy = 0.0, sxx = 0.0, sxy = 0.0
        for s in samples {
            let x = Double(s.tUs &- t0) / 1e6
            let y = Double(s.mm)
            sx += x; sy += y; sxx += x * x; sxy += x * y
        }
        let nn = Double(n)
        let den = nn * sxx - sx * sx
        if den <= 0 { return nil }
        return -(nn * sxy - sx * sy) / den
    }
}
