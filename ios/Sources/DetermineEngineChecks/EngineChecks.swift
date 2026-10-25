import DetermineEngine

/// Port of EngineTest.kt: SystemMonitorTest, EngineTest, LatencyStatsTest.
func engineChecks(_ t: Harness) {
    // MARK: SystemMonitor

    func ok(_ now: Int64, _ mod: (inout MonitorInputs) -> Void = { _ in }) -> MonitorInputs {
        var i = MonitorInputs(
            nowUs: now, camBeatAgeUs: 50_000, cueBeatAgeUs: 50_000, frameAgeUs: 100_000, rangeAgeUs: 70_000,
            tofKnown: true, clocksSynced: true, cueLinkConfirmed: true,
            ackFailures: 0, batteryCritical: false, paused: false
        )
        mod(&i)
        return i
    }

    t.test("monitor: starting needs everything, then degraded and unavailable boundaries") {
        let m = SystemMonitor()
        t.isNil(m.evaluate(ok(0) { $0.cueLinkConfirmed = false }))
        t.isNil(m.evaluate(ok(1) { $0.rangeAgeUs = nil }))
        t.eq(SystemState.ready, m.evaluate(ok(2))?.to)
        t.eq("tof_unknown", m.evaluate(ok(3) { $0.tofKnown = false })?.reason)
        t.eq(SystemState.ready, m.evaluate(ok(4) { $0.frameAgeUs = 500_000 })?.to)  // bound inclusive
        t.eq("frame_stale", m.evaluate(ok(5) { $0.frameAgeUs = 500_001 })?.reason)
        t.eq(SystemState.ready, m.evaluate(ok(6))?.to)
        t.eq("cam_beat_missing", m.evaluate(ok(7) { $0.camBeatAgeUs = 500_001 })?.reason)
        t.eq("recovered", m.evaluate(ok(8))?.reason)
        t.isNil(m.evaluate(ok(9) { $0.ackFailures = 2 }))
        t.eq("cue_ack_missing", m.evaluate(ok(10) { $0.ackFailures = 3 })?.reason)
        _ = m.evaluate(ok(11))
        t.eq("battery_critical", m.evaluate(ok(12) { $0.batteryCritical = true })?.reason)
        _ = m.evaluate(ok(13))
        t.eq("range_stale", m.evaluate(ok(14) { $0.rangeAgeUs = nil })?.reason)
    }

    t.test("monitor: a fresh unknown reading starts degraded without an unavailable timeout") {
        let m = SystemMonitor()
        let tr = m.evaluate(ok(0) { $0.tofKnown = false })
        t.eq(SystemState.degraded, tr?.to)
        t.eq("tof_unknown", tr?.reason)
        t.isNil(m.evaluate(ok(11_000_000) { $0.tofKnown = false }))
        t.eq(SystemState.ready, m.evaluate(ok(11_000_001))?.to)
    }

    t.test("monitor: paused only explicitly and startup times out loudly") {
        let m = SystemMonitor()
        t.eq(SystemState.paused, m.evaluate(ok(0) { $0.paused = true })?.to)
        t.eq(SystemState.starting, m.evaluate(ok(10) { $0.cueLinkConfirmed = false })?.to)
        t.isNil(m.evaluate(ok(10_000_010) { $0.cueLinkConfirmed = false }))
        let tr = m.evaluate(ok(10_000_011) { $0.cueLinkConfirmed = false })
        t.eq(SystemState.unavailable, tr?.to)
        t.yes(tr?.reason.hasPrefix("startup_timeout") ?? false)
    }

    // MARK: Engine

    func readyRig(_ cfg: EngineConfig = EngineConfig(), _ sink: TestSink = TestSink()) -> Rig {
        let r = Rig(config: cfg, sink: sink)
        r.run(1_000_000)
        t.eq(SystemState.ready, r.engine.state, "\(r.states)")
        return r
    }
    let hapticOnlyProfile = HearingProfile.uncalibrated(ceilingDb: -30, mode: .hapticOnly)

    t.test("engine: camera reboot with restarted frame ids and a new clock recovers to ready") {
        let r = readyRig()
        r.run(20_000_000) // ~200 frames: fewer than the assembler's 1000-frame reboot jump
        r.camAlive = false
        r.run(1_000_000)
        t.eq(SystemState.unavailable, r.engine.state)
        r.rebootCam(newOffset: 50_000 - r.now) // board time restarts near zero, so offset changes
        r.camAlive = true
        r.run(2_000_000)
        t.eq(SystemState.ready, r.engine.state, "\(r.states.suffix(4))")
    }

    t.test("engine: boots to ready with one audible ready tick, profile acked, and no board audio") {
        let r = readyRig()
        t.eq([SystemState.ready], r.states.map { $0.state })
        t.eq(1, r.sink.audible().filter { $0.cmd.cueId == CueId.ready }.count)
        t.yes(r.cues.allSatisfy { $0.pkt.cueId != CueId.ready }, "a haptic-free tick never goes to the board")
        t.yes(!r.profiles.isEmpty)
        t.notNil(r.engine.latestJpeg())
        t.eq(2000, r.engine.latestJpeg()?.jpeg.count)
        t.notNil(r.engine.latestRange())
    }

    t.test("engine: not ready until the headset route plays, and the board haptic link is also required") {
        let broken = TestSink(status: AckStatus.badCue)
        let r = Rig(sink: broken)
        r.run(2_000_000)
        t.eq(SystemState.starting, r.engine.state)
        t.yes(broken.audible().isEmpty, "only muted probes before READY")
        t.yes((2...3).contains(broken.plays.count), "probe at the 1 s retry cadence: \(broken.plays.count)")

        let noBoardAcks = Rig()
        noBoardAcks.acksAlive = false
        noBoardAcks.run(2_000_000)
        t.eq(SystemState.starting, noBoardAcks.engine.state, "audio alone is not enough: haptics must be confirmed")
    }

    t.test("engine: obstacle produces alert, audio and haptics at band cadence, spearcon once, scalar distance only") {
        let r = readyRig()
        r.obstacle(700)
        let t0 = r.now
        r.run(1_000_000)
        t.eq(Alert(band: .mid, distanceMm: 700), r.lastAlert)
        let sounds = r.obstacleSounds(since: t0)
        t.yes(sounds.allSatisfy { $0.cmd.cueId == CueId.obstacleMid })
        t.yes((2...3).contains(sounds.count), "MID 2.5/s over ~0.8 s: \(sounds.count)")
        let cues = r.obstacleCues(since: t0)
        t.eq(sounds.count, cues.count, "every warning vibrates the temples as well as sounding in the headset")
        t.yes(cues.allSatisfy { $0.pkt.cueId == CueId.obstacleMid && $0.pkt.side == Side.both })
        let (sentAt, c) = cues[0]
        t.eq(1, c.flags)
        // deadline = board time at issue + 150 ms (offset estimate within the 1 ms symmetric delay error)
        let expected = sentAt + r.cueOffset + 150_000
        t.yes(abs(c.deadlineBoardUs - expected) < 2_000, "\(c.deadlineBoardUs) vs \(expected)")
        t.eq(["obstacle ahead, 0.7 metres"], r.spoken)
        // obstacle disappears: exit after bandExitMs (600 ms) of nothing, cues stop, never a "clear" sound
        r.obstacle(nil)
        r.run(300_000)
        t.notNil(r.lastAlert, "a short gap does not end the warning")
        r.run(500_000)
        t.isNil(r.lastAlert)
        let tEnd = r.now
        r.run(1_000_000)
        t.yes(r.cues.allSatisfy { $0.at < tEnd })
        t.yes(r.obstacleSounds(since: tEnd).isEmpty)
        let d = r.engine.diagnostics(nowPhoneUs: r.now)
        t.notNil(d.captureToCueP50Ms); t.notNil(d.ackP95Ms)
        t.yes((d.captureToCueP95Ms ?? 1e9) < 400.0)
        t.eq(RangeStatus.noTarget, d.rangeStatus)
    }

    t.test("engine: a target beyond 1200 mm reports nothing and diagnostics show the single forward measurement") {
        let r = readyRig()
        r.obstacle(2500)
        r.run(1_000_000)
        t.yes(r.obstacleCues().isEmpty)
        t.yes(r.obstacleSounds().isEmpty)
        let d = r.engine.diagnostics(nowPhoneUs: r.now)
        t.eq(2500, d.rangeMm); t.eq(RangeStatus.valid, d.rangeStatus)
        t.isNil(d.lastAlert)
    }

    t.test("engine: camera down is unavailable, loud on headset and haptics, suppresses obstacle cues, recovers once") {
        let r = readyRig()
        r.obstacle(400)
        r.run(500_000)
        t.yes(!r.obstacleCues().isEmpty)
        r.camAlive = false
        let tDown = r.now
        r.run(700_000)
        t.eq(SystemState.unavailable, r.engine.state)
        t.eq(1, r.systemOffs)
        t.isNil(r.lastAlert)
        let sysOffCue = r.cues.first { $0.pkt.cueId == CueId.systemOff }!
        let tUnavail = sysOffCue.at
        t.yes(tUnavail - tDown <= 700_000)
        t.eq(Haptic.lss, sysOffCue.pkt.hapticPattern)
        let sysOffSound = r.sink.plays.first { $0.cmd.cueId == CueId.systemOff }!.cmd
        t.eq(-20, sysOffSound.gainLDb) // min(ceiling -20, -20), on the phone's headset route
        t.yes(r.obstacleCues(since: tUnavail + 1).isEmpty)
        r.run(5_000_000)
        t.eq(2, r.systemOffs) // repeats every 5 s
        t.yes(r.obstacleCues(since: tUnavail + 1).isEmpty)
        r.camAlive = true
        r.run(1_000_000)
        t.eq(SystemState.ready, r.engine.state)
        t.eq(0, r.spoken.filter { $0 == "system ready" }.count, "the announcement waits for announceHoldMs (1.5 s)")
        r.run(1_000_000)
        t.eq(1, r.spoken.filter { $0 == "system ready" }.count)
        t.yes(!r.obstacleCues(since: r.now - 500_000).isEmpty)
    }

    t.test("engine: missing board acks escalate to unavailable") {
        let r = readyRig()
        r.obstacle(400) // NEAR: 5 cues/s, so three unacked cues time out well inside the 1 s window
        r.run(400_000)
        r.acksAlive = false
        r.run(1_000_000)
        t.eq(SystemState.unavailable, r.engine.state)
        t.eq("cue_ack_missing", r.states.last!.reason)
    }

    t.test("engine: no headset at startup is unavailable at once, announced, and diagnostics still show the forward range") {
        let sink = TestSink(status: AUDIO_ROUTE_LOST)
        let r = Rig(sink: sink)
        r.obstacle(450)
        r.run(1_000_000)
        t.eq(SystemState.unavailable, r.states.last?.state)
        t.eq("headset_route_lost", r.states.last?.reason)
        t.yes(r.systemOffs >= 1, "the phone's alarm path announces it; nothing is silent")
        t.yes(sink.audible().allSatisfy { $0.cmd.cueId == CueId.systemOff }, "only the failure cue is attempted")
        t.yes(r.obstacleCues().isEmpty && r.obstacleSounds().isEmpty)
        let d = r.engine.diagnostics(nowPhoneUs: r.now)
        t.eq(450, d.rangeMm); t.eq(RangeStatus.valid, d.rangeStatus) // scalar diagnostics stay inspectable
        // headset appears: the 1 s probe confirms the route and the system recovers
        sink.status = AckStatus.applied
        r.run(2_000_000)
        t.eq(SystemState.ready, r.engine.state, "\(r.states)")
    }

    t.test("engine: headset loss in idle space is announced without waiting for an obstacle") {
        let r = readyRig()
        r.sink.status = AUDIO_ROUTE_LOST
        r.run(1_100_000)
        t.eq(SystemState.unavailable, r.states.last?.state)
        t.eq("headset_route_lost", r.states.last?.reason)
        t.yes(r.systemOffs > 0)
        t.yes(r.obstacleCues().isEmpty)
    }

    t.test("engine: a headset reconnected while paused is rechecked before resume") {
        let r = readyRig()
        r.sink.status = AUDIO_ROUTE_LOST
        r.run(1_100_000)
        r.engine.setPaused(true)
        r.run(100_000)
        r.sink.status = AckStatus.applied
        r.run(1_100_000)
        let transitions = r.states.count
        r.engine.setPaused(false)
        r.run(100_000)
        t.eq(SystemState.ready, r.engine.state)
        t.yes(r.states.dropFirst(transitions).allSatisfy { $0.state != .unavailable })
    }

    t.test("engine: a failed write that is not a route loss is a missing ack") {
        let r = readyRig()
        r.sink.status = PHONE_ROUTE_FAILED
        r.obstacle(400)
        r.run(1_500_000)
        t.eq("cue_ack_missing", r.states.last?.reason)
    }

    t.test("engine: headset route failure is explicit - unavailable with system off, never a silent fallback") {
        let r = readyRig()
        r.obstacle(700)
        r.run(300_000)
        r.sink.status = AUDIO_ROUTE_LOST
        let tFail = r.now
        r.run(1_500_000)
        t.eq(SystemState.unavailable, r.states.last?.state)
        t.eq("headset_route_lost", r.states.last?.reason)
        t.eq(1, r.systemOffs)
        t.isNil(r.lastAlert)
        t.yes(r.sink.plays.contains { $0.at >= tFail && $0.cmd.cueId == CueId.systemOff })
        t.yes(r.cues.contains { $0.pkt.cueId == CueId.systemOff }, "the board's haptics still announce the failure")

        r.sink.status = AckStatus.applied
        r.run(1_500_000)
        t.eq(SystemState.ready, r.engine.state)
        r.run(2_000_000)
        t.yes(r.spoken.contains("system ready"))
    }

    t.test("engine: a throwing sink is a missing ack, busy is not a failure") {
        let r = readyRig()
        r.obstacle(400)
        r.sink.status = AckStatus.busyDropped
        r.run(1_000_000)
        t.eq(SystemState.ready, r.engine.state)
        t.yes(r.engine.diagnostics(nowPhoneUs: r.now).nacks > 0)
        r.sink.status = nil
        r.run(1_000_000)
        t.eq(SystemState.unavailable, r.engine.state)
        t.eq("cue_ack_missing", r.states.last?.reason)
        t.yes(r.engine.diagnostics(nowPhoneUs: r.now).missedAcks >= 3)
    }

    t.test("engine: unknown tof is degraded, never clear") {
        let r = readyRig()
        r.status = RangeStatus.unknown
        r.run(300_000)
        t.eq(SystemState.degraded, r.engine.state)
        t.eq("tof_unknown", r.states.last?.reason)
        t.yes(r.obstacleCues().isEmpty)
        t.no(r.spoken.contains("distance sensor limited"), "spoken only after the state held announceHoldMs")
        r.run(1_500_000)
        t.yes(r.spoken.contains("distance sensor limited"))
    }

    t.test("engine: stale range is dropped") {
        let r = readyRig()
        r.rangeDelayUs = 600_000
        r.obstacle(700)
        r.run(1_000_000)
        t.yes(r.obstacleCues().isEmpty)
        t.yes(r.engine.diagnostics(nowPhoneUs: r.now).staleDropped > 0)
        t.eq(SystemState.degraded, r.engine.state)
    }

    t.test("engine: an unknown reading after a warning stops the warning and its cues until valid readings resume") {
        let r = readyRig()
        r.obstacle(700)
        r.run(1_000_000)
        t.eq(Band.mid, r.lastAlert?.band)
        r.status = RangeStatus.unknown
        r.run(200_000)
        t.eq(SystemState.degraded, r.states.last?.state)
        t.eq("tof_unknown", r.states.last?.reason)
        let tt = r.now
        r.run(3_000_000) // the whole fault window: DEGRADED allows cues, so only the policy can keep them off
        t.eq(SystemState.degraded, r.engine.state)
        t.isNil(r.lastAlert)
        t.yes(r.obstacleCues(since: tt).isEmpty, "no stale MID haptics: \(r.obstacleCues(since: tt).count)")
        t.yes(r.obstacleSounds(since: tt).isEmpty, "no stale MID sounds")
        t.isNil(r.engine.diagnostics(nowPhoneUs: r.now).lastAlert)

        r.obstacle(700) // valid again: a fresh onset with the interrupt flag
        let t2 = r.now
        r.run(1_500_000)
        t.eq(SystemState.ready, r.engine.state)
        t.yes(!r.obstacleCues(since: t2).isEmpty)
        t.eq(1, r.obstacleCues(since: t2).first?.pkt.flags)
    }

    t.test("engine: range silence stops the warning and its cues, degraded range stale, and resumes with new readings") {
        let r = readyRig()
        r.obstacle(700)
        r.run(1_000_000)
        t.eq(Band.mid, r.lastAlert?.band)
        r.rangeAlive = false
        r.run(800_000) // past the 500 ms freshness bound
        t.eq(SystemState.degraded, r.states.last?.state)
        t.eq("range_stale", r.states.last?.reason)
        let tt = r.now
        r.run(3_000_000)
        t.isNil(r.lastAlert)
        t.yes(r.obstacleCues(since: tt).isEmpty, "no cues from a distance that is no longer measured")
        t.yes(r.obstacleSounds(since: tt).isEmpty)

        r.rangeAlive = true
        let t2 = r.now
        r.run(1_500_000)
        t.eq(SystemState.ready, r.engine.state)
        t.yes(!r.obstacleCues(since: t2).isEmpty)
    }

    t.test("engine: pause is explicit and silences obstacle cues, pings continue") {
        let r = readyRig()
        r.obstacle(700)
        r.engine.setPaused(true)
        r.run(10_000)
        let tt = r.now
        r.run(1_000_000)
        t.eq(SystemState.paused, r.engine.state)
        t.yes(r.obstacleCues(since: tt).isEmpty)
        t.yes(r.obstacleSounds(since: tt).isEmpty)
        t.eq(0, r.systemOffs)
        r.engine.setPaused(false)
        r.run(1_000_000)
        t.eq(SystemState.ready, r.engine.state)
        t.yes(r.spoken.contains("paused") && r.spoken.contains("resumed"))
    }

    t.test("engine: battery critical is unavailable") {
        let r = readyRig()
        r.engine.setBatteryCritical(true)
        r.run(20_000)
        t.eq(SystemState.unavailable, r.states.last?.state)
        t.eq("battery_critical", r.states.last?.reason)
    }

    t.test("engine: profile resent after cue board reboot and on change carries mode and haptic cap") {
        let r = readyRig()
        let n = r.profiles.count
        r.cueBeatT0 = 1_000_000_000 // board time jumps backwards = reboot
        r.run(200_000)
        t.yes(r.profiles.count > n)
        let m = r.profiles.count
        r.engine.setProfile(try HearingProfile.uncalibrated(ceilingDb: -30).copy(hapticMax: 120))
        r.run(20_000)
        t.eq(120, r.profiles.dropFirst(m).last?.hapticMax)
        t.eq(0, r.profiles.dropFirst(m).last?.mode) // STEREO
    }

    t.test("engine: a reordered late BEAT is not a reboot and keeps the clock synced") {
        let r = readyRig()
        let n = r.states.count
        let late = Beat(board: BoardId.cue.wire, resetReason: 1, faults: 0, tUs: r.now + r.cueOffset - 80_000, rssiDbm: -60, tempC: 40, heapKb: 200)
        let bytes = try Wire.encode(.beat(late), seq: 999_999)
        r.engine.onPacket(from: .cue, bytes: bytes, len: bytes.count, rxPhoneUs: r.now)
        r.run(300_000)
        t.eq(SystemState.ready, r.engine.state)
        t.yes(r.states.dropFirst(n).allSatisfy { $0.state != .unavailable }, "\(r.states.dropFirst(n))")
    }

    t.test("engine: calibration audio clamps to the phone ceiling") {
        let sink = TestSink()
        let (e, _) = silentEngine(EngineConfig(initialProfile: try HearingProfile.uncalibrated(ceilingDb: -20).copy(hapticMax: 100)), sink: sink)
        t.yes(e.sendCalibrationCue(CueCommand(cueId: CueId.calTone1k, side: 0, gainLDb: 0, gainRDb: GAIN_MUTE, hapticPattern: Haptic.none,
                                              hapticL: 0, hapticR: 0, flags: 1, ttlMs: 1000), nowPhoneUs: 5))
        let played = sink.plays.last!.cmd
        t.eq(-20, played.gainLDb) // audio clamped to the uncalibrated ceiling -20 on the phone
        t.eq(GAIN_MUTE, played.gainRDb)
    }

    t.test("engine: haptic only mode needs no headset - ready, haptics to the board, the sink is never called") {
        let sink = TestSink(status: AUDIO_ROUTE_LOST) // no headset at all
        let r = readyRig(EngineConfig(initialProfile: hapticOnlyProfile), sink)
        t.yes(sink.plays.isEmpty, "audio is intentionally off: no probe, no READY tick on the headset")
        r.obstacle(400)
        let t0 = r.now
        r.run(500_000)
        t.eq(SystemState.ready, r.engine.state)
        t.yes(!r.obstacleCues(since: t0).isEmpty)
        t.yes(r.obstacleCues(since: t0).allSatisfy { $0.pkt.hapticPattern != Haptic.none && $0.pkt.hapticL > 0 })
        t.yes(sink.plays.isEmpty)
        t.eq(2, r.profiles.last?.mode)
    }

    t.test("engine: haptic only still fails loud when the board stops acking, and announces on the phone and the board") {
        let r = readyRig(EngineConfig(initialProfile: hapticOnlyProfile), TestSink(status: AUDIO_ROUTE_LOST))
        r.obstacle(400)
        r.run(300_000)
        r.acksAlive = false
        r.run(1_000_000)
        t.eq(SystemState.unavailable, r.states.last?.state)
        t.eq("cue_ack_missing", r.states.last?.reason)
        t.yes(r.systemOffs >= 1, "the phone alarm path does not depend on the headset or the mode")
        t.yes(r.cues.contains { $0.pkt.cueId == CueId.systemOff && $0.pkt.hapticPattern == Haptic.lss })
    }

    t.test("engine: calibration tones still need and use the headset while warning sounds are off") {
        let sink = TestSink()
        let r = readyRig(EngineConfig(initialProfile: hapticOnlyProfile), sink)
        _ = r.engine.sendCalibrationCue(CueCommand(cueId: CueId.calTone1k, side: 0, gainLDb: -40, gainRDb: GAIN_MUTE, hapticPattern: Haptic.none,
                                                   hapticL: 0, hapticR: 0, flags: 1, ttlMs: 1000), nowPhoneUs: r.now)
        t.eq(1, sink.plays.count)
        t.eq(CueId.calTone1k, sink.plays.first?.cmd.cueId)
        // a calibration failure with no headset does not take a haptic-only system down
        sink.status = AUDIO_ROUTE_LOST
        _ = r.engine.sendCalibrationCue(CueCommand(cueId: CueId.calTone1k, side: 0, gainLDb: -40, gainRDb: GAIN_MUTE, hapticPattern: Haptic.none,
                                                   hapticL: 0, hapticR: 0, flags: 1, ttlMs: 1000), nowPhoneUs: r.now)
        r.run(500_000)
        t.eq(SystemState.ready, r.engine.state)
    }

    t.test("engine: switching haptic only to stereo re-proves the headset and fails loud without one") {
        let sink = TestSink(status: AUDIO_ROUTE_LOST)
        let r = readyRig(EngineConfig(initialProfile: hapticOnlyProfile), sink)
        r.engine.setProfile(try hapticOnlyProfile.copy(mode: .stereo))
        r.run(300_000)
        t.eq(SystemState.unavailable, r.states.last?.state)
        t.eq("headset_route_lost", r.states.last?.reason)
        r.engine.setProfile(hapticOnlyProfile) // back to haptic only: no headset needed again
        r.run(1_500_000)
        t.eq(SystemState.ready, r.engine.state, "\(r.states)")
    }

    t.test("engine: malformed and misattributed packets are counted, not trusted") {
        let r = readyRig()
        r.engine.onPacket(from: .cue, bytes: [1, 2, 3], len: 3, rxPhoneUs: r.now)
        let camBeat = try Wire.encode(.beat(Beat(board: 1, resetReason: 0, faults: 0, tUs: 1, rssiDbm: 0, tempC: 0, heapKb: 0)), seq: 1)
        r.engine.onPacket(from: .cue, bytes: camBeat, len: camBeat.count, rxPhoneUs: r.now) // claims CAM, arrived from CUE
        t.eq(2, r.engine.diagnostics(nowPhoneUs: r.now).malformed)
        t.yes(r.engine.state != .unavailable)
    }

    t.test("engine: no grid-era regional range can be set from a sensor packet - one scalar zone only") {
        let snap = RangeSnapshot(tBoardUs: 1, rxPhoneUs: 2, mm: 3, status: 0)
        t.eq(Set(["tBoardUs", "rxPhoneUs", "mm", "status"]), Set(Mirror(reflecting: snap).children.compactMap { $0.label }))
    }

    t.test("engine: a stale sequence of oversized/garbage board timestamps never traps") {
        let r = readyRig()
        let wild = Beat(board: 2, resetReason: 0, faults: 0, tUs: Int64.max, rssiDbm: 0, tempC: 0, heapKb: 0)
        let b = try Wire.encode(.beat(wild), seq: 5)
        r.engine.onPacket(from: .cue, bytes: b, len: b.count, rxPhoneUs: r.now)
        let pong = try Wire.encode(.pong(Pong(tPhoneUs: r.now, tBoardRxUs: Int64.min, tBoardTxUs: Int64.max)), seq: 6)
        r.engine.onPacket(from: .cue, bytes: pong, len: pong.count, rxPhoneUs: r.now)
        let rng = try Wire.encode(.range(RangePkt(tUs: Int64.min, mm: 100, status: 0)), seq: 7)
        r.engine.onPacket(from: .cue, bytes: rng, len: rng.count, rxPhoneUs: r.now)
        r.run(100_000)
        t.yes(true)
    }

    // MARK: LatencyStats

    t.test("latency: nearest rank p95 of twenty observations excludes the maximum") {
        let stats = LatencyStats()
        for ms in 1...20 { stats.add(Double(ms)) }
        t.eq(19.0, stats.percentile(95))
        t.eq(10.0, stats.percentile(50))
        t.eq(20.0, stats.percentile(100))
    }
}
