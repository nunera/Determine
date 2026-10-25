import Foundation
import DetermineEngine

/// Feature contract §2-§8 at engine level: smoother, less chatter, link quality, approach speed, exploration mode,
/// runtime tuning and the debug haptic. (Policy-level smoother checks live in PolicyCueChecks.)
func tuningChecks(_ t: Harness) {
    func readyRig(_ cfg: EngineConfig = EngineConfig(), _ sink: TestSink = TestSink()) -> Rig {
        let r = Rig(config: cfg, sink: sink)
        r.run(1_000_000)
        t.eq(SystemState.ready, r.engine.state, "\(r.states)")
        return r
    }
    func tuned(_ edit: (inout Tuning) -> Void) -> EngineConfig {
        var tn = Tuning()
        edit(&tn)
        return EngineConfig(tuning: tn)
    }
    func count(_ r: Rig, _ phrase: String) -> Int { r.spoken.filter { $0 == phrase }.count }
    /// Moves the target by `perStepMm` every 10 ms for `ms`, with VALID readings.
    func sweep(_ r: Rig, from: Int, perStepMm: Int, ms: Int) {
        r.mm = from
        r.status = RangeStatus.valid
        for _ in 0..<(ms / 10) { r.step(); r.mm += perStepMm }
    }

    // MARK: §2 smoother

    t.test("smoother: a target hovering +-30 mm around the MID/FAR edge does not make the band chatter") {
        let r = readyRig()
        var changes = 0
        var lastBand: Band?
        r.status = RangeStatus.valid
        for i in 0..<600 { // 6 s, 3 Hz triangle-ish wave, 870...930 mm
            let phase = Double(i) / 100.0 * 3.0 * 2.0 * Double.pi
            r.mm = 900 + Int(30.0 * sin(phase))
            r.step()
            if let a = r.lastAlert, a.band != lastBand { changes += 1; lastBand = a.band }
        }
        t.yes(changes >= 1 && changes <= 2, "band changes: \(changes)")
        t.yes(r.obstacleCues(since: 0).allSatisfy { $0.pkt.cueId != CueId.obstacleNear })
    }

    t.test("smoother: a one-sample glitch neither starts nor ends a warning") {
        let r = readyRig()
        r.status = RangeStatus.valid; r.mm = 400
        r.step(); r.step(); // 1-2 samples at most
        r.obstacle(nil)
        r.run(1_000_000)
        t.isNil(r.lastAlert, "no warning from a blink")
        r.obstacle(400)
        r.run(500_000)
        t.eq(Band.near, r.lastAlert?.band)
        r.obstacle(nil)
        r.run(200_000)
        r.obstacle(400)
        r.run(300_000)
        t.eq(Band.near, r.lastAlert?.band, "200 ms of nothing does not end the warning")
    }

    t.test("smoother: unknown still cancels at once and missing RANGE still cancels after 500 ms") {
        let r = readyRig()
        r.obstacle(400)
        r.run(600_000)
        t.eq(Band.near, r.lastAlert?.band)
        r.status = RangeStatus.unknown
        r.run(150_000)
        t.isNil(r.lastAlert, "UNKNOWN cancels the warning immediately")
        r.obstacle(400)
        r.run(600_000)
        t.eq(Band.near, r.lastAlert?.band)
        r.rangeAlive = false
        r.run(700_000)
        t.isNil(r.lastAlert)
    }

    t.test("smoother: a reordered (late) RANGE is dropped, it neither rewinds the smoother nor ends the warning") {
        let r = readyRig()
        r.obstacle(700)
        r.run(800_000)
        t.eq(Band.mid, r.lastAlert?.band)
        let alerts = r.bus.alertsOut.count
        let dropped = r.engine.diagnostics(nowPhoneUs: r.now).staleDropped
        for _ in 0..<150 { // 1.5 s of late NO_TARGET samples interleaved with the live VALID ones
            r.injectLateRange(boardAgeUs: 300_000, mm: 0, status: RangeStatus.noTarget)
            r.step()
        }
        t.yes(r.engine.diagnostics(nowPhoneUs: r.now).staleDropped >= dropped + 100)
        t.eq(Band.mid, r.lastAlert?.band)
        t.eq(alerts, r.bus.alertsOut.count, "no alert churn")
    }

    // MARK: §3 less chatter

    t.test("announce: DEGRADED speech waits announceHoldMs, a flap says nothing, the same phrase is not repeated within announceRepeatMs") {
        let r = readyRig()
        // flap: 600 ms unknown / 600 ms valid, five times
        for _ in 0..<5 {
            r.status = RangeStatus.unknown; r.run(600_000)
            r.obstacle(nil); r.run(600_000)
        }
        t.eq(0, count(r, "distance sensor limited"), "\(r.states.map { $0.state })")
        t.yes(r.states.contains { $0.state == .degraded }, "the visual state is immediate")
        // a real fault: spoken once after the hold
        r.status = RangeStatus.unknown
        r.run(1_000_000)
        t.eq(0, count(r, "distance sensor limited"))
        r.run(800_000)
        t.eq(1, count(r, "distance sensor limited"))
        // recover and fail again inside the repeat window: visual state yes, speech no
        r.obstacle(nil); r.run(500_000)
        r.status = RangeStatus.unknown; r.run(2_500_000)
        t.eq(SystemState.degraded, r.engine.state)
        t.eq(1, count(r, "distance sensor limited"), "same phrase within 10 s")
        // after the window it may be spoken again
        r.obstacle(nil); r.run(500_000)
        r.run(7_000_000)
        r.status = RangeStatus.unknown; r.run(2_500_000)
        t.eq(2, count(r, "distance sensor limited"))
    }

    t.test("announce: paused/resumed and system off stay immediate, announceHoldMs is tunable") {
        let r = readyRig()
        r.engine.setPaused(true)
        r.run(50_000)
        t.yes(r.spoken.contains("paused"))
        r.engine.setPaused(false)
        r.run(50_000)
        t.yes(r.spoken.contains("resumed"))
        let q = readyRig()
        q.camAlive = false
        q.run(900_000)
        t.eq(SystemState.unavailable, q.engine.state)
        t.yes(q.systemOffs >= 1, "system off is never delayed")

        let z = readyRig(tuned { $0.announceHoldMs = 0 })
        z.status = RangeStatus.unknown
        z.run(300_000)
        t.eq(1, count(z, "distance sensor limited"))
    }

    // MARK: §4 link quality

    t.test("link: weak RSSI for 2 s warns once and flags diagnostics without changing the state; recovers at +5 dB for 2 s") {
        let r = readyRig()
        t.no(r.engine.diagnostics(nowPhoneUs: r.now).linkWeak)
        r.cueRssi = -80
        r.run(1_500_000)
        t.no(r.engine.diagnostics(nowPhoneUs: r.now).linkWeak, "not weak before weakLinkHoldMs")
        r.run(1_000_000)
        t.yes(r.engine.diagnostics(nowPhoneUs: r.now).linkWeak)
        t.eq(1, count(r, "weak connection"))
        t.eq(SystemState.ready, r.engine.state)
        r.run(5_000_000)
        t.eq(1, count(r, "weak connection"), "not repeated while weak")
        // -66 is better than the threshold but not by 5 dB: still weak
        r.cueRssi = -66
        r.run(4_000_000)
        t.yes(r.engine.diagnostics(nowPhoneUs: r.now).linkWeak)
        r.cueRssi = -64
        r.run(1_500_000)
        t.yes(r.engine.diagnostics(nowPhoneUs: r.now).linkWeak, "recovery needs 2 s")
        r.run(1_000_000)
        t.no(r.engine.diagnostics(nowPhoneUs: r.now).linkWeak)
        t.eq(SystemState.ready, r.engine.state)
        // weak again within weakLinkRepeatMs of the first warning: flagged, not spoken again
        r.cueRssi = -85
        r.run(3_000_000)
        t.yes(r.engine.diagnostics(nowPhoneUs: r.now).linkWeak)
        t.eq(1, count(r, "weak connection"))
    }

    t.test("link: a short dip does not warn, and the threshold and hold are tunable") {
        let r = readyRig()
        r.cueRssi = -90
        r.run(1_000_000)
        r.cueRssi = -50
        r.run(3_000_000)
        t.eq(0, count(r, "weak connection"))
        let q = readyRig(tuned { $0.weakLinkRssiDbm = -40; $0.weakLinkHoldMs = 500 }) // -50 dBm is weak now
        q.run(1_000_000)
        t.yes(q.engine.diagnostics(nowPhoneUs: q.now).linkWeak)
        t.eq(1, count(q, "weak connection"))
    }

    // MARK: §5 approach speed

    t.test("approach: closing speed is always computed; the escalation is off by default") {
        let r = readyRig()
        t.isNil(r.engine.diagnostics(nowPhoneUs: r.now).closingSpeedMmPerS)
        sweep(r, from: 1400, perStepMm: -5, ms: 700) // 500 mm/s
        let d = r.engine.diagnostics(nowPhoneUs: r.now)
        t.yes(abs((d.closingSpeedMmPerS ?? 0) - 500) < 40, "closing \(String(describing: d.closingSpeedMmPerS))")
        t.no(d.approachActive)
        t.eq(Band.far, r.lastAlert?.band, "mm \(r.mm)") // raw FAR is reported as FAR
        r.obstacle(nil)
        r.run(150_000)
        t.isNil(r.engine.diagnostics(nowPhoneUs: r.now).closingSpeedMmPerS)
    }

    t.test("approach: enabled, a fast closing target is reported one band more urgent; a still target is not") {
        let r = readyRig(tuned { $0.approachEnabled = true; $0.approachTtcMs = 3000 })
        sweep(r, from: 1400, perStepMm: -5, ms: 700)
        let d = r.engine.diagnostics(nowPhoneUs: r.now)
        t.yes(d.approachActive)
        t.eq(Band.mid, r.lastAlert?.band, "FAR at \(r.mm) mm escalated to MID")
        r.mm = 1100
        r.run(600_000)
        t.no(r.engine.diagnostics(nowPhoneUs: r.now).approachActive)
        t.eq(Band.far, r.lastAlert?.band)
        // slow approach below approachMinSpeedMmPerS (100 mm/s) is not escalated
        sweep(r, from: 1190, perStepMm: -1, ms: 800)
        t.no(r.engine.diagnostics(nowPhoneUs: r.now).approachActive)
        t.eq(Band.far, r.lastAlert?.band)
    }

    t.test("approach: the rule can be switched on and off at runtime") {
        let r = readyRig()
        sweep(r, from: 1400, perStepMm: -5, ms: 700)
        t.no(r.engine.diagnostics(nowPhoneUs: r.now).approachActive)
        var on = Tuning(); on.approachEnabled = true; on.approachTtcMs = 3000
        try r.engine.setTuning(on)
        t.yes(r.engine.diagnostics(nowPhoneUs: r.now).approachActive, "applies without waiting for the next sample")
        // time-to-contact above approachTtcMs: not active
        on.approachTtcMs = 200
        try r.engine.setTuning(on)
        t.no(r.engine.diagnostics(nowPhoneUs: r.now).approachActive)
        try r.engine.setTuning(Tuning())
        t.no(r.engine.diagnostics(nowPhoneUs: r.now).approachActive)
    }

    // MARK: §6 exploration

    t.test("explore: pulses every explorePeriodMs, stronger when closer, silent beyond exploreMaxMm / without a target") {
        let sink = TestSink()
        let r = readyRig(tuned { $0.exploreEnabled = true }, sink)
        let audibleBefore = sink.audible().count
        r.obstacle(600)
        let t0 = r.now
        r.run(1_200_000)
        let cues = r.cues.filter { $0.at >= t0 }
        t.yes((9...11).contains(cues.count), "~10 pulses/1.2 s, got \(cues.count)")
        t.yes(cues.allSatisfy { $0.pkt.hapticPattern == Haptic.pulse40 && $0.pkt.side == Side.both && $0.pkt.cueId == CueId.obstacleFar })
        t.yes(cues.allSatisfy { $0.pkt.hapticL == 143 && $0.pkt.hapticR == 143 }, "\(cues.map { $0.pkt.hapticL })") // 30 + (225*600 + 600)/1200
        for i in 1..<cues.count { t.yes(cues[i].at - cues[i - 1].at >= 119_000, "period") }
        t.eq(audibleBefore, sink.audible().count, "no phone audio for exploration")
        t.yes(r.spoken.isEmpty, "no spearcons")
        t.yes(r.engine.diagnostics(nowPhoneUs: r.now).exploring)
        r.obstacle(100)
        r.run(300_000)
        t.yes(r.cues.last!.pkt.hapticL >= 230, "near = strong: \(r.cues.last!.pkt.hapticL)")
        r.obstacle(1300)
        r.run(300_000)
        let t1 = r.now
        r.run(500_000)
        t.yes(r.cues.allSatisfy { $0.at < t1 }, "silence beyond exploreMaxMm")
        t.no(r.engine.diagnostics(nowPhoneUs: r.now).exploring)
        r.obstacle(nil)
        r.run(500_000)
        t.yes(r.cues.allSatisfy { $0.at < t1 }, "silence without a target")
        r.status = RangeStatus.unknown
        r.run(500_000)
        t.eq(SystemState.degraded, r.engine.state)
        t.yes(r.cues.allSatisfy { $0.at < t1 })
        t.no(r.engine.diagnostics(nowPhoneUs: r.now).exploring)
    }

    t.test("explore: no band cues while on, the band cues return when switched off, intensity is clamped to haptic_max") {
        let hm = HearingProfile.uncalibrated(ceilingDb: -14, mode: .stereo)
        var tn = Tuning(); tn.exploreEnabled = true
        let r = readyRig(EngineConfig(initialProfile: hm, tuning: tn))
        r.obstacle(400)
        r.run(800_000)
        t.yes(r.sink.plays.allSatisfy { !($0.cmd.cueId >= CueId.obstacleFar && $0.cmd.cueId <= CueId.obstacleNear) }, "no band audio")
        try r.engine.setTuning(Tuning())
        let t0 = r.now
        r.run(1_000_000)
        t.yes(!r.obstacleSounds(since: t0).isEmpty, "band cues are back")
        // haptic_max clamp: the board never gets an intensity above the profile cap
        let capped = try HearingProfile.uncalibrated(ceilingDb: -14, mode: .stereo).copy(hapticMax: 60)
        let q = readyRig(EngineConfig(initialProfile: capped, tuning: tn))
        q.obstacle(50)
        let t1 = q.now
        q.run(500_000)
        t.yes(q.cues.filter { $0.at >= t1 }.allSatisfy { $0.pkt.hapticL <= 60 && $0.pkt.hapticL > 0 })
    }

    // MARK: §7 runtime tuning

    t.test("tuning: setTuning validates (TuningError) and changes nothing on failure") {
        let r = readyRig()
        let bad: [(String, (inout Tuning) -> Void)] = [
            ("near 0", { $0.nearMm = 0 }), ("near >= mid", { $0.nearMm = 900 }), ("mid >= far", { $0.midMm = 1200 }),
            ("far > 8000", { $0.farMm = 8001 }), ("enter < 0", { $0.bandEnterMs = -1 }), ("exit < 0", { $0.bandExitMs = -1 }),
            ("hysteresis < 0", { $0.bandHysteresisMm = -1 }), ("period 0", { $0.nearPeriodMs = 0 }),
            ("hold < 0", { $0.announceHoldMs = -1 }), ("repeat < 0", { $0.announceRepeatMs = -1 }),
            ("rssi > 0", { $0.weakLinkRssiDbm = 1 }), ("rssi < -127", { $0.weakLinkRssiDbm = -128 }),
            ("window 0", { $0.approachWindowMs = 0 }), ("ttc 0", { $0.approachTtcMs = 0 }), ("speed 0", { $0.approachMinSpeedMmPerS = 0 }),
            ("explore period 0", { $0.explorePeriodMs = 0 }), ("explore max 0", { $0.exploreMaxMm = 0 }),
            ("explore max 8001", { $0.exploreMaxMm = 8001 }), ("min intensity 256", { $0.exploreMinIntensity = 256 }),
            ("min intensity -1", { $0.exploreMinIntensity = -1 }),
        ]
        for (name, edit) in bad {
            var x = Tuning()
            edit(&x)
            t.throwsError(name) { try r.engine.setTuning(x) }
            t.eq(Tuning(), r.engine.tuning, name)
        }
        var ok = Tuning(); ok.farMm = 8000; ok.exploreMaxMm = 8000
        try r.engine.setTuning(ok)
        t.eq(ok, r.engine.tuning)
    }

    t.test("tuning: thresholds and periods apply immediately without a restart") {
        let r = readyRig()
        r.obstacle(700)
        r.run(800_000)
        t.eq(Band.mid, r.lastAlert?.band)
        // 700 mm becomes NEAR when nearMm moves to 800
        var x = Tuning(); x.nearMm = 800; x.midMm = 1000; x.farMm = 1500
        try r.engine.setTuning(x)
        r.run(400_000)
        t.eq(Band.near, r.lastAlert?.band)
        // faster NEAR cadence: 100 ms instead of 200 ms
        let t0 = r.now
        r.run(1_000_000)
        let slow = r.obstacleCues(since: t0).count
        x.nearPeriodMs = 100
        try r.engine.setTuning(x)
        let t1 = r.now
        r.run(1_000_000)
        let fast = r.obstacleCues(since: t1).count
        t.yes(fast >= slow + 3, "cues per second \(slow) -> \(fast)")
        t.eq(x, r.engine.tuning)
        // the farMm of the new tuning: 1400 mm is now reported
        r.obstacle(1400)
        r.run(1_500_000)
        t.eq(Band.far, r.lastAlert?.band)
    }

    t.test("tuning: EngineConfig carries the initial Tuning") {
        let r = Rig(config: tuned { $0.nearMm = 300; $0.bandEnterMs = 0 })
        t.eq(300, r.engine.tuning.nearMm)
    }

    t.test("tuning: sendTestHaptic goes through the normal CUE path, false without sync or for a bad pattern") {
        let r = Rig()
        t.no(r.engine.sendTestHaptic(pattern: Haptic.pulse60, intensity: 120, nowPhoneUs: r.now), "no beat, no sync yet")
        r.run(1_500_000)
        t.no(r.engine.sendTestHaptic(pattern: 0, intensity: 120, nowPhoneUs: r.now))
        t.no(r.engine.sendTestHaptic(pattern: 5, intensity: 120, nowPhoneUs: r.now))
        let plays = r.sink.audible().count
        let n = r.cues.count
        t.yes(r.engine.sendTestHaptic(pattern: Haptic.buzz150, intensity: 300, nowPhoneUs: r.now))
        r.run(50_000)
        t.eq(n + 1, r.cues.count)
        let c = r.cues.last!.pkt
        t.eq(Haptic.buzz150, c.hapticPattern)
        t.eq(Side.both, c.side)
        t.eq(255, c.hapticL); t.eq(255, c.hapticR) // clamped to 255 (profile haptic_max 255)
        t.yes(c.deadlineBoardUs > 0, "deadline set once synced")
        t.eq(plays, r.sink.audible().count, "no phone audio")
        t.yes(r.engine.sendTestHaptic(pattern: Haptic.pulse40, intensity: -5, nowPhoneUs: r.now))
        r.run(50_000)
        t.eq(0, r.cues.last!.pkt.hapticL)
        let capped = Rig(config: EngineConfig(initialProfile: try HearingProfile.uncalibrated(ceilingDb: -14, mode: .stereo).copy(hapticMax: 90)))
        capped.run(1_500_000)
        t.yes(capped.engine.sendTestHaptic(pattern: Haptic.pulse60, intensity: 200, nowPhoneUs: capped.now))
        capped.run(50_000)
        t.eq(90, capped.cues.last!.pkt.hapticL, "haptic_max clamp")
    }

    // MARK: §8 diagnostics defaults

    t.test("diagnostics: the new fields are neutral on a quiet system") {
        let r = readyRig()
        let d = r.engine.diagnostics(nowPhoneUs: r.now)
        t.no(d.linkWeak); t.no(d.approachActive); t.no(d.exploring)
        t.isNil(d.closingSpeedMmPerS)
    }
}
