import DetermineEngine

/// The observation the engine would hand the policy for a reading (nil = NO_TARGET).
private func obs(_ p: HazardPolicy, _ mm: Int? = nil, unknown: Bool = false) -> ForwardObservation {
    if unknown { return p.observe(.unknown) }
    guard let mm = mm else { return p.observe(.nothingSeen) }
    return p.observe(.target(mm: mm))
}

/// Port of PolicyCueTest.kt: HazardPolicyTest and CueSchedulerTest, on the time-based smoother (feature contract §2).
func policyCueChecks(_ t: Harness) {
    let dt: Int64 = 66_667 // 15 Hz

    /// Feeds one sample per `dt` of board time (= phone time here) and returns the presented alert.
    final class Feeder {
        let p: HazardPolicy
        var now: Int64 = 0
        let dt: Int64
        init(_ p: HazardPolicy, dt: Int64) { self.p = p; self.dt = dt }
        @discardableResult
        func feed(_ mm: Int?, unknown: Bool = false, scanning: Bool = false, approach: Bool = false) -> Alert? {
            let o = unknown ? p.observe(.unknown) : mm.map { p.observe(.target(mm: $0)) } ?? p.observe(.nothingSeen)
            let a = p.step(o, scanning: scanning, approach: approach, nowUs: now, boardUs: now)
            now += dt
            return a
        }
        /// Feeds for `us` of time; returns the last alert.
        @discardableResult
        func hold(_ mm: Int?, forUs us: Int64, scanning: Bool = false) -> Alert? {
            let end = now + us
            var a: Alert?
            while now < end { a = feed(mm, scanning: scanning) }
            return a
        }
    }

    t.test("policy: band boundaries are the conservative near-field edges") {
        let p = HazardPolicy()
        t.eq(Band.near, p.band(0)); t.eq(Band.near, p.band(499))
        t.eq(Band.mid, p.band(500)); t.eq(Band.mid, p.band(899))
        t.eq(Band.far, p.band(900)); t.eq(Band.far, p.band(1200))
        t.eq(Band.none, p.band(1201)) // nothing beyond 1.2 m: the sensor cannot honestly vouch for it
        t.eq(Band.none, p.band(4000))
    }

    t.test("tuning: defaults are the contract's, valid, and the thresholds never exceed 1200 mm") {
        let d = Tuning()
        t.eq(Tuning(nearMm: 500, midMm: 900, farMm: 1200, bandEnterMs: 150, bandExitMs: 600, bandHysteresisMm: 50,
                    farPeriodMs: 1000, midPeriodMs: 400, nearPeriodMs: 200, announceHoldMs: 1500, announceRepeatMs: 10_000,
                    weakLinkRssiDbm: -70, weakLinkHoldMs: 2000, weakLinkRepeatMs: 30_000,
                    approachEnabled: false, approachWindowMs: 300, approachTtcMs: 1500, approachMinSpeedMmPerS: 300,
                    exploreEnabled: false, explorePeriodMs: 120, exploreMaxMm: 1200, exploreMinIntensity: 30), d)
        try d.validate()
        t.eq(Tuning().farMm, 1200)
        t.eq(EngineConfig().tuning, d)
    }

    t.test("policy: enter needs bandEnterMs of consistent readings, exit needs bandExitMs") {
        let f = Feeder(HazardPolicy(), dt: dt)
        t.isNil(f.feed(700)) // 0 ms
        t.isNil(f.feed(700)) // 67
        t.isNil(f.feed(700)) // 133
        let a = f.feed(700) // 200 >= 150
        t.eq(Alert(band: .mid, distanceMm: 700), a)
        t.yes(f.p.takeOnset()); t.no(f.p.takeOnset())
        // nothing seen: alert stays until 600 ms of it (first none sample opens the run)
        for _ in 0..<9 { t.notNil(f.feed(nil)) } // run age 0..533 ms
        t.isNil(f.feed(nil)) // 600 ms
    }

    t.test("policy: a valid reading beyond far + hysteresis exits like no target, one inside the hysteresis zone does not") {
        let f = Feeder(HazardPolicy(), dt: dt)
        f.hold(1100, forUs: 300_000)
        t.notNil(f.hold(1100, forUs: 100_000))
        t.notNil(f.hold(1250, forUs: 3_000_000)) // 1250 = farMm + 50, not strictly beyond: FAR holds forever
        t.isNil(f.hold(1251, forUs: 700_000))
        let g = Feeder(HazardPolicy(), dt: dt)
        g.hold(700, forUs: 300_000)
        t.isNil(g.hold(2500, forUs: 700_000))
    }

    t.test("policy: an interrupted enter run restarts and a single none sample does not exit") {
        let f = Feeder(HazardPolicy(), dt: dt)
        f.feed(700); f.feed(700); f.feed(nil) // run broken at 133 ms
        f.feed(700) // new run starts at 200 ms
        t.isNil(f.feed(700)); t.isNil(f.feed(700)) // 267, 333 ms: 133 ms into the run
        t.notNil(f.feed(700)) // 400 ms: 200 ms into the run
        f.feed(nil)
        t.notNil(f.feed(700)); t.notNil(f.feed(700)) // exit run broken, still presenting
        for _ in 0..<5 { t.notNil(f.feed(nil)) }
    }

    t.test("policy: hysteresis, leaving NEAR needs the distance above nearMm + 50") {
        let f = Feeder(HazardPolicy(), dt: dt)
        f.hold(300, forUs: 300_000)
        t.eq(Band.near, f.hold(540, forUs: 3_000_000)?.band) // MID by raw band but inside 500 + 50: stays NEAR
        t.eq(Band.near, f.hold(550, forUs: 3_000_000)?.band) // 550 is not strictly above 550
        t.eq(Band.near, f.hold(560, forUs: 500_000)?.band)    // above, but not for 600 ms yet
        t.eq(Band.mid, f.hold(560, forUs: 200_000)?.band)     // 600 ms of it
    }

    t.test("policy: leaving a band to a less urgent one needs bandExitMs; the run keeps the most urgent band seen") {
        let f = Feeder(HazardPolicy(), dt: dt)
        f.hold(700, forUs: 300_000)
        // MID -> FAR readings (>950) interleaved with nothing seen: after 600 ms the most urgent of them wins: FAR
        var last: Alert?
        let end = f.now + 700_000
        var i = 0
        while f.now < end { last = i % 2 == 0 ? f.feed(1000) : f.feed(nil); i += 1 }
        t.eq(Band.far, last?.band)
        t.eq(1000, last?.distanceMm == nil ? 1000 : last?.distanceMm)
    }

    t.test("policy: an enter run keeps the least urgent band seen and enters at once on a sustained band") {
        let f = Feeder(HazardPolicy(), dt: dt)
        var last: Alert?
        for i in 0..<5 { last = i % 2 == 0 ? f.feed(300) : f.feed(700) } // NEAR/MID alternating for 267 ms
        t.eq(Band.mid, last?.band)
        // more urgent band: enters after bandEnterMs, no new onset
        t.yes(f.p.takeOnset())
        t.eq(Band.mid, f.hold(300, forUs: 100_000)?.band)
        t.eq(Band.near, f.hold(300, forUs: 200_000)?.band)
        t.no(f.p.takeOnset())
    }

    t.test("policy: unknown drops the active alert at once and presents nothing until valid readings re-enter") {
        let f = Feeder(HazardPolicy(), dt: dt)
        f.hold(700, forUs: 300_000) // MID alert active
        t.yes(f.p.takeOnset())
        // The measurement no longer exists: no stale MID, no distance, no onset, no all-clear.
        for _ in 0..<20 { t.isNil(f.feed(nil, unknown: true)) }
        t.no(f.p.takeOnset())
        // VALID readings resume through the normal enter rules: bandEnterMs of them, then a fresh onset.
        t.isNil(f.feed(700)); t.isNil(f.feed(700)); t.isNil(f.feed(700))
        t.eq(Alert(band: .mid, distanceMm: 700), f.feed(700))
        t.yes(f.p.takeOnset())
    }

    t.test("policy: unknown never produces an alert from nothing and never counts as clear") {
        let q = Feeder(HazardPolicy(), dt: dt)
        for _ in 0..<20 { t.isNil(q.feed(nil, unknown: true)) }
        // an unknown sample between valid ones restarts the enter run; it is not a none sample either
        let f = Feeder(HazardPolicy(), dt: dt)
        f.feed(700); f.feed(700)
        t.isNil(f.feed(nil, unknown: true))
        t.isNil(f.feed(700)); t.isNil(f.feed(700)); t.isNil(f.feed(700))
        t.notNil(f.feed(700))
    }

    t.test("policy: onset is rate limited to 700 ms") {
        let f = Feeder(HazardPolicy(), dt: dt)
        f.hold(700, forUs: 300_000)
        t.yes(f.p.takeOnset())
        let onsetT = f.now - dt
        f.hold(nil, forUs: 700_000) // exit
        // re-enter quickly: the alert only shows once 700 ms have passed since the last onset
        var shownAt: Int64?
        while f.now < onsetT + 2_000_000 {
            let at = f.now
            if f.feed(700) != nil { shownAt = at; break }
        }
        t.yes((shownAt ?? 0) - onsetT >= 700_000, "shown at \(shownAt ?? -1), onset at \(onsetT)")
    }

    t.test("policy: band updates within an active alert without a new onset") {
        let f = Feeder(HazardPolicy(), dt: dt)
        f.hold(1100, forUs: 300_000)
        _ = f.p.takeOnset()
        t.eq(Band.near, f.hold(300, forUs: 300_000)?.band)
        t.no(f.p.takeOnset())
    }

    t.test("policy: scanning holds FAR only") {
        let f = Feeder(HazardPolicy(), dt: dt)
        t.isNil(f.hold(1100, forUs: 500_000, scanning: true))
        t.eq(Band.far, f.hold(1100, forUs: 100_000)?.band) // FAR resumes once scanning stops, with its onset
        t.yes(f.p.takeOnset())
        let g = Feeder(HazardPolicy(), dt: dt)
        t.eq(Band.near, g.hold(300, forUs: 300_000, scanning: true)?.band)
    }

    t.test("policy: board time going backwards (reboot) restarts the smoother") {
        let f = Feeder(HazardPolicy(), dt: dt)
        f.hold(700, forUs: 300_000)
        f.now = 0 // the board restarted its clock
        t.isNil(f.feed(700)) // not presented until it has held bandEnterMs again (and the onset gap has passed)
        t.notNil(f.hold(700, forUs: 900_000))
    }

    t.test("policy: tuning applies at once; bandEnterMs/bandExitMs 0 act on the first sample") {
        var tn = Tuning(); tn.bandEnterMs = 0; tn.bandExitMs = 0
        let f = Feeder(HazardPolicy(tuning: tn), dt: dt)
        t.eq(Band.mid, f.feed(700)?.band)
        t.isNil(f.feed(nil))
        f.p.tuning.nearMm = 600; f.p.tuning.midMm = 1000
        f.now += 1_000_000
        t.eq(Band.near, f.feed(550)?.band)
        t.eq(Band.mid, f.feed(950)?.band)
    }

    t.test("policy: approach escalates the presented band by one, transiently") {
        let f = Feeder(HazardPolicy(), dt: dt)
        f.hold(1100, forUs: 300_000)
        t.eq(Band.mid, f.feed(1100, approach: true)?.band) // FAR -> MID
        t.eq(Band.far, f.feed(1100)?.band)                  // active band untouched
        f.hold(700, forUs: 300_000)
        t.eq(Band.near, f.feed(700, approach: true)?.band)  // MID -> NEAR
        f.hold(300, forUs: 300_000)
        t.eq(Band.near, f.feed(300, approach: true)?.band)  // NEAR stays NEAR
        // nothing active, a target inside farMm: NONE -> FAR at once (after the 700 ms onset gap)
        let g = Feeder(HazardPolicy(), dt: dt)
        t.eq(Band.far, g.feed(1100, approach: true)?.band)
        t.yes(g.p.takeOnset())
        let h = Feeder(HazardPolicy(), dt: dt)
        t.isNil(h.feed(2000, approach: true)) // beyond farMm there is nothing to escalate
    }

    t.test("policy: the alert carries only band and forward distance - no side or label") {
        // Alert has exactly (band, distanceMm): nothing to lateralize and no object label to infer.
        let a = Alert(band: .near, distanceMm: 320)
        t.eq(Set(["band", "distanceMm"]), Set(Mirror(reflecting: a).children.compactMap { $0.label }))
        t.eq(320, a.distanceMm)
    }

    // MARK: CueScheduler

    func makeProfile(ceilingDb: Int = -14, mode: HearingMode = .stereo, thrL: Int? = -40, thrR: Int? = -30) throws -> HearingProfile {
        try HearingProfile(
            deviceId: "t", createdAtMs: 0, mode: mode, ceilingDb: ceilingDb,
            bands: try CalBand.hz.map { try BandProfile(hz: $0, thrDbL: thrL, thrDbR: thrR, slDb: 15, offsetDbL: 0, offsetDbR: 0) },
            hapticGainL: 200, hapticGainR: 100, hapticMax: 180, verifiedTrials: 5, verifiedCorrect: 5
        )
    }

    t.test("cue: cadence per band and onset interrupts") {
        let profile = try makeProfile()
        let s = CueScheduler()
        let far = Alert(band: .far, distanceMm: 1100)
        let first = s.update(far, onset: true, profile: profile, nowUs: 0)
        t.eq(1, first?.flags)
        t.isNil(s.update(far, onset: false, profile: profile, nowUs: 999_999))
        t.eq(0, s.update(far, onset: false, profile: profile, nowUs: 1_000_000)?.flags)
        let mid = Alert(band: .mid, distanceMm: 700)
        t.eq(1, s.update(mid, onset: false, profile: profile, nowUs: 1_010_000)?.flags) // band change plays now
        t.isNil(s.update(mid, onset: false, profile: profile, nowUs: 1_409_999))
        t.notNil(s.update(mid, onset: false, profile: profile, nowUs: 1_410_000))
        let near = Alert(band: .near, distanceMm: 300)
        var n = 0
        for now in stride(from: Int64(2_000_000), to: 3_000_000, by: 10_000) where s.update(near, onset: false, profile: profile, nowUs: now) != nil { n += 1 }
        t.eq(5, n) // 5/s
        t.isNil(s.update(nil, onset: false, profile: profile, nowUs: 3_000_000))
    }

    t.test("cue: cue family, both sides always, and haptic patterns") {
        let profile = try makeProfile()
        let s = CueScheduler()
        let far = s.build(Alert(band: .far, distanceMm: 1100), profile: profile, interrupt: false)
        t.eq(CueId.obstacleFar, far.cueId); t.eq(Side.both, far.side)
        t.eq(-25, far.gainLDb); t.eq(-15, far.gainRDb)
        t.eq(Haptic.pulse40, far.hapticPattern); t.eq(120, far.hapticL); t.eq(60, far.hapticR)
        t.eq(150, far.ttlMs)
        let mid = s.build(Alert(band: .mid, distanceMm: 700), profile: profile, interrupt: false)
        t.eq(CueId.obstacleMid, mid.cueId); t.eq(Side.both, mid.side)
        t.eq(Haptic.pulse60, mid.hapticPattern); t.eq(160, mid.hapticL); t.eq(80, mid.hapticR)
        let near = s.build(Alert(band: .near, distanceMm: 300), profile: profile, interrupt: false)
        t.eq(CueId.obstacleNear, near.cueId); t.eq(Side.both, near.side)
        t.eq(Haptic.buzz150, near.hapticPattern)
        t.eq(180, near.hapticL) // capped at haptic_max
        t.eq(100, near.hapticR)
    }

    t.test("cue: no person cue is ever selected from the forward zone") {
        let profile = try makeProfile()
        let s = CueScheduler()
        for b in [Band.far, .mid, .near] {
            let id = s.build(Alert(band: b, distanceMm: 800), profile: profile, interrupt: false).cueId
            t.yes((CueId.obstacleFar...CueId.obstacleNear).contains(id), "band \(b) -> cue \(id)")
        }
    }

    t.test("cue: near adds 6 dB but never exceeds ceiling") {
        let profile = try makeProfile()
        let s = CueScheduler()
        let c = s.build(Alert(band: .near, distanceMm: 300), profile: profile, interrupt: false)
        t.eq(-19, c.gainLDb)       // -40+15+6
        t.eq(-14, c.gainRDb)       // -30+15+6 = -9 -> ceiling -14
        let loud = try profile.copy(ceilingDb: 0)
        let c2 = s.build(Alert(band: .near, distanceMm: 300), profile: loud, interrupt: false)
        t.eq(-10, c2.gainRDb)      // hard ceiling -10 applies on the phone too
    }

    t.test("cue: per-side ceiling, ssd with an unusable side, and haptic only mutes the phone audio") {
        let profile = try makeProfile()
        let s = CueScheduler()
        let ssd = try makeProfile(mode: .ssd, thrL: -40, thrR: nil)
        let c = s.build(Alert(band: .mid, distanceMm: 700), profile: ssd, interrupt: false)
        t.eq(Side.both, c.side); t.eq(-25, c.gainLDb); t.eq(GAIN_MUTE, c.gainRDb) // unusable side stays muted
        t.yes(c.hapticL > 0 && c.hapticR > 0) // board haptics unaffected, both temples
        let h = s.build(Alert(band: .near, distanceMm: 300), profile: try profile.copy(mode: .hapticOnly), interrupt: false)
        t.eq(GAIN_MUTE, h.gainLDb); t.eq(GAIN_MUTE, h.gainRDb)
        t.yes(h.hapticL > 0 && h.hapticR > 0) // haptics still go to the board
    }

    t.test("cue: spearcon never states distance without tof and never names an object") {
        t.eq("obstacle ahead, 0.7 metres", CueScheduler.spearcon(Alert(band: .mid, distanceMm: 700)))
        t.eq("obstacle ahead", CueScheduler.spearcon(Alert(band: .far, distanceMm: nil)))
        // Java %.1f rounds the decimal value half-up: 0.25 -> 0.3, 1.15 -> 1.2, 0.35 -> 0.4
        t.eq("obstacle ahead, 0.3 metres", CueScheduler.spearcon(Alert(band: .mid, distanceMm: 250)))
        t.eq("obstacle ahead, 1.2 metres", CueScheduler.spearcon(Alert(band: .far, distanceMm: 1150)))
        t.eq("obstacle ahead, 0.4 metres", CueScheduler.spearcon(Alert(band: .mid, distanceMm: 350)))
        t.eq("obstacle ahead, 1.2 metres", CueScheduler.spearcon(Alert(band: .far, distanceMm: 1187)))
    }

    t.test("hearing: profile validation and levels") {
        let profile = try makeProfile()
        t.eq(-25, profile.levelDb(CalBand.b1k, .left))
        t.eq(-15, profile.levelDb(CalBand.b1k, .right))
        t.eq(-14, profile.effectiveCeilingDb)
        t.eq(-10, try profile.copy(ceilingDb: 0).effectiveCeilingDb)
        t.throwsError { _ = try profile.copy(hapticMax: 0) }
        t.throwsError { _ = try profile.copy(hapticMax: 256) }
        t.eq(1, try profile.copy(hapticMax: MIN_HAPTIC_MAX).hapticMax)
        t.throwsError { _ = try profile.copy(ceilingDb: 1) }
        t.throwsError { _ = try profile.copy(bands: []) }
        t.throwsError { _ = try BandProfile(hz: 500, thrDbL: 0, thrDbR: 0, slDb: 9, offsetDbL: 0, offsetDbR: 0) }
        let u = HearingProfile.uncalibrated(ceilingDb: -30, mode: .hapticOnly)
        t.eq(HearingMode.hapticOnly, u.mode); t.eq(2, u.mode.wire); t.eq(255, u.hapticMax)
        t.eq(-30 - 35 + 15, u.levelDb(1, .left)) // thr = ceiling - 35, +15 dB sensation level
    }
}
