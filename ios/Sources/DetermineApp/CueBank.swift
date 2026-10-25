import DetermineEngine
import Foundation

/// The phone's cue sound bank (Arch §8): note table, waveforms, dBFS gain maths and the play/clamp decision for every
/// cue id in PROTOCOL.md. A port of the Android `CueBank` (`android/.../audio/CueBank.kt`) so both phones speak the
/// same cue language. The cue board is haptic-only since protocol v2; these are the warning sounds the phone plays
/// itself. Pure: no audio framework, so it compiles and can be reasoned about off-device.
enum CueBank {
    enum Wave { case woodblock, marimba, tone, tick }

    struct Note {
        let wave: Wave
        let freqHz: Int
        let durMs: Int
        let gapMs: Int
    }

    struct Cue {
        let name: String
        let notes: [Note]
        let periodMs: Int
    }

    /// The sink never plays louder than this, whatever the profile says (= engine `HARD_CEILING_DB`).
    static let hardCeilingDb = HARD_CEILING_DB

    /// Index = cue id (PROTOCOL.md "Cue IDs"), identical to the Android bank.
    static let cues: [Cue] = [
        Cue(name: "READY", notes: [Note(wave: .tick, freqHz: 2000, durMs: 85, gapMs: 0)], periodMs: 1000),
        Cue(name: "OBSTACLE_FAR", notes: [Note(wave: .woodblock, freqHz: 800, durMs: 90, gapMs: 30), Note(wave: .woodblock, freqHz: 800, durMs: 90, gapMs: 0)], periodMs: 1000),
        Cue(name: "OBSTACLE_MID", notes: [Note(wave: .woodblock, freqHz: 1000, durMs: 85, gapMs: 25), Note(wave: .woodblock, freqHz: 1000, durMs: 85, gapMs: 0)], periodMs: 400),
        Cue(name: "OBSTACLE_NEAR", notes: [Note(wave: .woodblock, freqHz: 1600, durMs: 90, gapMs: 0)], periodMs: 200),
        Cue(name: "PERSON_FAR", notes: [Note(wave: .marimba, freqHz: 523, durMs: 100, gapMs: 20), Note(wave: .marimba, freqHz: 784, durMs: 100, gapMs: 0)], periodMs: 1000),
        Cue(name: "PERSON_MID", notes: [Note(wave: .marimba, freqHz: 659, durMs: 90, gapMs: 20), Note(wave: .marimba, freqHz: 988, durMs: 90, gapMs: 0)], periodMs: 400),
        Cue(name: "PERSON_NEAR", notes: [Note(wave: .marimba, freqHz: 1319, durMs: 90, gapMs: 0)], periodMs: 200),
        Cue(name: "SYSTEM_OFF", notes: [Note(wave: .marimba, freqHz: 880, durMs: 120, gapMs: 40), Note(wave: .marimba, freqHz: 659, durMs: 120, gapMs: 40), Note(wave: .marimba, freqHz: 440, durMs: 120, gapMs: 0)], periodMs: 5000),
        Cue(name: "CAL_TONE_500", notes: [Note(wave: .tone, freqHz: 500, durMs: 300, gapMs: 0)], periodMs: 1000),
        Cue(name: "CAL_TONE_1K", notes: [Note(wave: .tone, freqHz: 1000, durMs: 300, gapMs: 0)], periodMs: 1000),
        Cue(name: "CAL_TONE_2K", notes: [Note(wave: .tone, freqHz: 2000, durMs: 300, gapMs: 0)], periodMs: 1000),
        Cue(name: "CAL_OBSTACLE", notes: [Note(wave: .woodblock, freqHz: 800, durMs: 90, gapMs: 30), Note(wave: .woodblock, freqHz: 800, durMs: 90, gapMs: 0)], periodMs: 1000),
    ]

    static func cue(_ id: Int) -> Cue? { cues.indices.contains(id) ? cues[id] : nil }

    // MARK: Gain

    /// dBFS → Q15 linear amplitude (32768 = 0 dBFS); −128 mutes; positive input is treated as 0 dBFS (never boost).
    static func dbToQ15(_ db: Int) -> Int {
        if db == GAIN_MUTE { return 0 }
        if db >= 0 { return 32768 }
        return Int((32768.0 * pow(10.0, Double(db) / 20.0)).rounded())
    }

    /// Cap at min(profile ceiling, hard ceiling); mute stays mute.
    static func clampGain(_ requestedDb: Int, profileCeilingDb: Int) -> Int {
        let cap = min(profileCeilingDb, hardCeilingDb)
        if requestedDb == GAIN_MUTE { return GAIN_MUTE }
        return requestedDb > cap ? cap : requestedDb
    }

    // MARK: Synth

    private static let sineN = 1 << 10
    private static let sine: [Float] = (0..<sineN).map { Float(sin(2.0 * Double.pi * Double($0) / Double(sineN))) }

    /// Phase from the absolute sample index, so there is no accumulator drift.
    private static func sineAt(_ n: Int, _ freqHz: Int, _ rateHz: Int) -> Float {
        sine[(n * freqHz * sineN / rateHz) & (sineN - 1)]
    }

    private static func powi(_ b: Float, _ e: Int) -> Float {
        var r: Float = 1
        for _ in 0..<e { r *= b }
        return r
    }

    /// Samples in a note window of `durMs` at `rateHz`.
    static func windowSamples(durMs: Int, rateHz: Int) -> Int { durMs * rateHz / 1000 }

    /// One normalised sample (|x| ≤ 1) at index `n` of the note window; 0 outside it.
    static func noteSample(_ note: Note, _ n: Int, rateHz: Int) -> Float {
        let total = windowSamples(durMs: note.durMs, rateHz: rateHz)
        if total == 0 || n < 0 || n >= total { return 0 }
        let x = Float(n) / Float(total)
        let rest = 1 - x
        let tMs = Float(n) * 1000 / Float(rateHz)
        let f = note.freqHz
        switch note.wave {
        case .woodblock:
            let att: Float = tMs < 1 ? tMs : 1
            let f2 = Int(Float(f) * 2.76)
            return att * (0.7 * powi(rest, 4) * sineAt(n, f, rateHz) + 0.3 * powi(rest, 8) * sineAt(n, f2, rateHz))
        case .marimba:
            let att: Float = tMs < 2 ? tMs / 2 : 1
            return att * (0.75 * powi(rest, 3) * sineAt(n, f, rateHz) + 0.25 * powi(rest, 8) * sineAt(n, f * 4, rateHz))
        case .tone:
            let dur = Float(note.durMs)
            var ramp: Float
            if tMs < 5 {
                ramp = tMs / 5
            } else if dur - tMs < 5 {
                ramp = (dur - tMs) / 5
            } else {
                ramp = 1
            }
            if ramp < 0 { ramp = 0 }
            return 0.9 * ramp * sineAt(n, f, rateHz)
        case .tick:
            let att: Float = tMs < 0.5 ? tMs / 0.5 : 1
            return 0.6 * att * powi(rest, 12) * sineAt(n, f, rateHz)
        }
    }

    /// Normalised sample × Q15 gain → int16 with saturation.
    static func scaleSample(_ x: Float, gainQ15: Int) -> Int16 {
        let v = x * Float(gainQ15) * (32767.0 / 32768.0)
        if v > 32767 { return Int16.max }
        if v < -32768 { return Int16.min }
        return Int16(v.rounded())
    }

    /// Renders one whole cue as interleaved stereo int16 frames (L, R, L, R …).
    static func render(_ cue: Cue, rateHz: Int, gainLQ15: Int, gainRQ15: Int) -> [Int16] {
        var out: [Int16] = []
        for (idx, note) in cue.notes.enumerated() {
            let last = idx + 1 == cue.notes.count
            let win = windowSamples(durMs: note.durMs, rateHz: rateHz)
            let gap = last ? 0 : windowSamples(durMs: note.gapMs, rateHz: rateHz)
            out.reserveCapacity(out.count + 2 * (win + gap))
            for pos in 0..<(win + gap) {
                let s = noteSample(note, pos, rateHz: rateHz) // 0 in the gap
                out.append(scaleSample(s, gainQ15: gainLQ15))
                out.append(scaleSample(s, gainQ15: gainRQ15))
            }
        }
        return out
    }

    // MARK: Play decision

    /// What the sink does with one command; gains are the applied values (−128 = silent channel).
    struct Decision {
        let status: Int
        let play: Bool
        let gainLDb: Int
        let gainRDb: Int

        var audible: Bool { play && (gainLDb != GAIN_MUTE || gainRDb != GAIN_MUTE) }
    }

    /// BAD_CUE (unknown id/side) → BUSY_DROPPED (output busy and no interrupt flag) → APPLIED / APPLIED_CLAMPED
    /// (clamped iff the per-side ceiling lowered a requested gain). The commanded gains are authoritative for the
    /// mode: the engine already mutes both sides for HAPTIC_ONLY. Haptics are not decided here: they go to the cue
    /// board.
    static func decide(_ cmd: CueCommand, ceilingDb: Int, outputBusy: Bool) -> Decision {
        if cmd.cueId < 0 || cmd.cueId >= cues.count || !(Side.l...Side.both).contains(cmd.side) {
            return Decision(status: AckStatus.badCue, play: false, gainLDb: GAIN_MUTE, gainRDb: GAIN_MUTE)
        }
        if outputBusy && (cmd.flags & 1) == 0 {
            return Decision(status: AckStatus.busyDropped, play: false, gainLDb: GAIN_MUTE, gainRDb: GAIN_MUTE)
        }
        let gl = clampGain(cmd.gainLDb, profileCeilingDb: ceilingDb)
        let gr = clampGain(cmd.gainRDb, profileCeilingDb: ceilingDb)
        let status = (gl != cmd.gainLDb || gr != cmd.gainRDb) ? AckStatus.appliedClamped : AckStatus.applied
        let left = cmd.side != Side.r
        let right = cmd.side != Side.l
        return Decision(status: status, play: true, gainLDb: left ? gl : GAIN_MUTE, gainRDb: right ? gr : GAIN_MUTE)
    }
}
