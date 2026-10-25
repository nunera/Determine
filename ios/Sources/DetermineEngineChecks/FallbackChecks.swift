import DetermineEngine

/// Port of FallbackTest.kt (Tier 0 PHONE_CAMERA).
func fallbackChecks(_ t: Harness) {
    let tier0 = EngineConfig(initialProfile: HearingProfile.uncalibrated(ceilingDb: -30), frameSource: .phoneCamera)

    func ready(_ r: FallbackRig) -> FallbackRig {
        r.run(1_000_000)
        t.eq(SystemState.ready, r.engine.state, "\(r.states)")
        return r
    }

    t.test("fallback: tier 0 reaches ready without a camera board and fails loud when phone frames stop") {
        let r = ready(FallbackRig(tier0))
        t.eq(0, r.pings[.cam], "no camera board: never pinged")
        let f = r.engine.latestJpeg()
        t.eq(0, f?.captureBoardUs)
        t.eq(r.lastPhoneCaptureUs, f?.capturePhoneUs)
        t.eq(640, f?.w)
        t.yes((f?.frameId ?? 0) > 0)
        let d = r.engine.diagnostics(nowPhoneUs: r.now)
        t.eq(FrameSourceKind.phoneCamera, d.frameSource)
        t.isNil(d.cam.beatAgeMs)

        r.phoneFrames = false
        let lastCapture = r.lastPhoneCaptureUs
        r.run(400_000)
        t.eq(SystemState.ready, r.engine.state, "within the 500 ms bound")
        r.run(300_000)
        let last = r.states.last!
        t.eq(SystemState.unavailable, last.state)
        t.eq("frame_stale", last.reason)
        t.yes((500_001...510_000).contains(last.at - lastCapture), "\(last.at - lastCapture)")
        t.eq(1, r.systemOffs)
        t.yes(r.boardCues.contains { $0.cueId == CueId.systemOff }, "board haptics announce the failure")
        t.yes(r.sink.plays.contains { $0.cmd.cueId == CueId.systemOff }, "so does the headset")

        r.phoneFrames = true
        r.run(200_000)
        t.eq(SystemState.ready, r.engine.state)
    }

    t.test("fallback: tier 0 counts and ignores a powered camera board") {
        let r = ready(FallbackRig(tier0, camBoard: true))
        let d = r.engine.diagnostics(nowPhoneUs: r.now)
        t.yes(d.ignoredCamPackets >= 20, "\(d.ignoredCamPackets)")
        t.eq(0, d.malformed)
        t.eq(640, r.engine.latestJpeg()?.w) // the phone frame, never the board's
        t.isNil(d.cam.beatAgeMs)
        t.eq(0, r.pings[.cam])
    }

    t.test("fallback: camera board mode still requires the camera board") {
        let r = FallbackRig(EngineConfig(), camBoard: false, phoneFrames: false)
        r.run(1_000_000)
        t.ne(SystemState.ready, r.engine.state)
        t.yes((r.pings[.cam] ?? 0) > 0)
    }

    t.test("fallback: phone frames only in tier 0, ordered, fresh and non-empty") {
        t.throwsError { try silentEngine(EngineConfig()).0.onPhoneFrame(jpeg: [UInt8](repeating: 0, count: 10), w: 640, h: 480, capturePhoneUs: 0) }
        let (e, _) = silentEngine(tier0)
        e.tick(nowPhoneUs: 10_000_000)
        try e.onPhoneFrame(jpeg: [UInt8](repeating: 1, count: 10), w: 640, h: 480, capturePhoneUs: 9_990_000)
        let first = e.latestJpeg()
        t.notNil(first)
        try e.onPhoneFrame(jpeg: [UInt8](repeating: 2, count: 10), w: 640, h: 480, capturePhoneUs: 9_980_000) // older than the newest: out of order
        try e.onPhoneFrame(jpeg: [UInt8](repeating: 3, count: 10), w: 640, h: 480, capturePhoneUs: 9_990_000) // same capture time: duplicate
        try e.onPhoneFrame(jpeg: [], w: 640, h: 480, capturePhoneUs: 9_995_000)                              // empty is never a frame
        t.eq(first, e.latestJpeg())
        e.tick(nowPhoneUs: 10_600_000)
        try e.onPhoneFrame(jpeg: [UInt8](repeating: 4, count: 10), w: 640, h: 480, capturePhoneUs: 10_099_999) // 500.001 ms old at the last tick
        t.eq(first, e.latestJpeg())
        try e.onPhoneFrame(jpeg: [UInt8](repeating: 5, count: 10), w: 640, h: 480, capturePhoneUs: 10_100_000) // exactly 500 ms: still fresh
        t.eq((first?.frameId ?? -1) + 1, e.latestJpeg()?.frameId)
        let d = e.diagnostics(nowPhoneUs: 10_600_000)
        t.eq(3, d.staleDropped)
        t.eq(1, d.malformed)
    }

    t.test("fallback: the camera never contributes a distance or a person label - frames carry no range") {
        // Hazard distance has exactly one source: the forward ToF RANGE packet. A phone camera frame, however
        // fresh, produces no alert on its own.
        let r = ready(FallbackRig(tier0))
        r.run(1_000_000)
        t.yes(r.alertsOut.allSatisfy { $0 == nil })
        t.yes(r.sink.audible().allSatisfy { $0.cmd.cueId == CueId.ready })
        r.obstacle(300)
        r.run(500_000)
        let a = r.alertsOut.last!
        t.eq(Band.near, a?.band)
        t.eq(300, a?.distanceMm)
    }

    t.test("fallback: tier 0 sends every warning to the headset sink within the ceiling and to the board as haptics") {
        let r = ready(FallbackRig(tier0))
        r.obstacle(400)
        let t0 = r.now
        r.run(1_000_000)
        let sounds = r.sink.plays.filter { $0.at >= t0 && $0.cmd.cueId == CueId.obstacleNear }
        t.yes(sounds.count >= 3, "NEAR at 5/s: \(sounds.count)")
        t.yes(r.sink.plays.allSatisfy { p in [p.cmd.gainLDb, p.cmd.gainRDb].allSatisfy { $0 == GAIN_MUTE || $0 <= -30 } })
        t.yes(r.boardCues.filter { $0.cueId == CueId.obstacleNear }.count >= 3)
        t.yes(!r.profiles.isEmpty)
        t.yes((r.pings[.cue] ?? 0) > 0, "cue board still pinged: clock sync for RANGE")
        let d = r.engine.diagnostics(nowPhoneUs: r.now)
        t.eq(0, d.missedAcks)
        t.notNil(r.engine.latestJpeg())
    }
}
