// The phone's single integrator (port of Engine.kt, Arch §4): ingests board packets, keeps clocks, runs the hazard
// policy and the state machine, plays every warning sound through `localCueSink` (phone -> headset) and sends the
// board's haptic commands. NOT thread-safe: serialise every call. Callbacks run synchronously.
//
// Outputs of one warning: audio on the phone (per-side calibrated levels under the profile ceiling) AND temple
// haptics on the cue board (CUE packet; the board never plays sound). The cue path counts as confirmed only when
// BOTH have answered well once - the board through a PROFILE/CUE ACK, the audio through a good local play
// (audio is not required in HAPTIC_ONLY).
//
// Fallback: with `EngineConfig.frameSource == .phoneCamera` there is no camera board (no BEAT/PING/clock for it)
// and the host feeds `onPhoneFrame`.

public struct EngineConfig {
    public var policy: PolicyConfig
    public var cue: CueConfig
    public var monitor: MonitorConfig
    public var initialProfile: HearingProfile
    /// PROTOCOL.md: PING 10 Hz per board (also the cue board's watchdog feed).
    public var pingIntervalUs: Int64
    /// Arch §4.2 freshness bound for FRAME/RANGE (capture age and receive age).
    public var freshnessUs: Int64
    public var ackTimeoutUs: Int64
    public var profileRetryUs: Int64
    /// Arch §8.1: SYSTEM OFF repeats every 5 s until resolved.
    public var systemOffRepeatUs: Int64
    /// Local audio level of the system-off cue: min(ceiling, -20 dBFS).
    public var systemOffGainDb: Int
    public var diagnosticsIntervalUs: Int64
    /// Arch §8.2: spearcons on by default during training.
    public var spearcons: Bool
    /// Head yaw rate above which the wearer is scanning (Arch §7.2: holds FAR only).
    public var scanYawDegPerS: Float
    /// Phone-side vibration for system unavailable (long-short-short, Arch §8.3).
    public var systemOffVibration: [Int64]
    /// Tier 0 fallback (Arch §11.3): `.phoneCamera` replaces the camera board; frames come from `Engine.onPhoneFrame`.
    public var frameSource: FrameSourceKind
    /// Runtime-tunable parameters (bands, smoothing, announcements, link, experimental modes). Must validate.
    public var tuning: Tuning

    public init(
        policy: PolicyConfig = PolicyConfig(),
        cue: CueConfig = CueConfig(),
        monitor: MonitorConfig = MonitorConfig(),
        initialProfile: HearingProfile = HearingProfile.uncalibrated(),
        pingIntervalUs: Int64 = 100_000,
        freshnessUs: Int64 = 500_000,
        ackTimeoutUs: Int64 = 300_000,
        profileRetryUs: Int64 = 1_000_000,
        systemOffRepeatUs: Int64 = 5_000_000,
        systemOffGainDb: Int = -20,
        diagnosticsIntervalUs: Int64 = 1_000_000,
        spearcons: Bool = true,
        scanYawDegPerS: Float = 60,
        systemOffVibration: [Int64] = [0, 400, 100, 120, 100, 120],
        frameSource: FrameSourceKind = .camBoard,
        tuning: Tuning = Tuning()
    ) {
        self.policy = policy
        self.cue = cue
        self.monitor = monitor
        self.initialProfile = initialProfile
        self.pingIntervalUs = pingIntervalUs
        self.freshnessUs = freshnessUs
        self.ackTimeoutUs = ackTimeoutUs
        self.profileRetryUs = profileRetryUs
        self.systemOffRepeatUs = systemOffRepeatUs
        self.systemOffGainDb = systemOffGainDb
        self.diagnosticsIntervalUs = diagnosticsIntervalUs
        self.spearcons = spearcons
        self.scanYawDegPerS = scanYawDegPerS
        self.systemOffVibration = systemOffVibration
        self.frameSource = frameSource
        self.tuning = tuning
    }
}

/// A BEAT whose board time is this much older than the newest is a reboot, not UDP reordering.
let REBOOT_JUMP_US: Int64 = 1_000_000

public final class Engine {
    public let config: EngineConfig
    private let outbox: any Outbox
    private let alerts: any PhoneAlerts
    private let listener: any EngineListener
    private let localCueSink: any LocalCueSink

    private let camBoardUsed: Bool

    private final class BoardLink {
        let id: BoardId
        let clock = ClockSync()
        var lastBeatRxUs: Int64?
        var lastBeatTUs: Int64?
        var faults = 0
        var rssi: Int?
        var pingSeq: Int64 = 0
        var nextPingUs = Int64.min
        init(_ id: BoardId) { self.id = id }
    }

    private let cam = BoardLink(.cam)
    private let cue = BoardLink(.cue)
    private func link(_ b: BoardId) -> BoardLink { b == .cam ? cam : cue }
    private let pingedLinks: [BoardLink]

    private let assembler = FrameAssembler()
    private let gate: FreshnessGate
    private let policy: HazardPolicy
    private let scheduler: CueScheduler
    private let monitor: SystemMonitor

    public private(set) var profile: HearingProfile
    public var state: SystemState { monitor.state }

    private var paused = false
    private var batteryCritical = false
    private var yawDegPerS: Float = 0
    private var spearcons: Bool

    private var latestFrame: AssembledFrame?
    private var latestRangeSnapshot: RangeSnapshot?
    private var latestRangeCapturePhoneUs: Int64?
    private var latestReading: ForwardRange?
    private var phoneFrameId: Int64 = 0
    private var lastTickUs: Int64?

    private var alert: Alert?
    private var onsetPending = false
    private var onsetCapturePhoneUs: Int64?

    public private(set) var tuning: Tuning
    private let approach = ApproachEstimator()
    private var approachNow = false
    private var nextExploreUs = Int64.min
    private var pendingAnnounce: (text: String, state: SystemState, sinceUs: Int64)?
    private var lastSpokenUs: [String: Int64] = [:]
    private var linkWeak = false
    private var weakSinceUs: Int64?
    private var recoverSinceUs: Int64?
    private var lastWeakSpokenUs: Int64?

    // CUE and PROFILE share one seq counter so every ACK.cue_seq is unambiguous.
    private var cmdSeq: Int64 = 0
    private struct Pending { let issuedUs: Int64 }
    private var pending: [Int64: Pending] = [:]
    // Failures are counted per output channel: a good answer from one channel must never reset the other's
    // streak, or a dead board (no ACKs) would hide behind a working headset, and the reverse.
    private var boardFailures = 0
    private var audioFailures = 0
    private var boardConfirmed = false
    private var audioConfirmed = false
    private var audioRouteLost = false

    /// Warning sounds are wanted unless the profile intentionally turns phone audio off (HAPTIC_ONLY).
    private var audioRequired: Bool { profile.mode != .hapticOnly }
    private var cueLinkConfirmed: Bool { boardConfirmed && (audioConfirmed || !audioRequired) }
    private var profileDirty = true
    private var nextProfileUs = Int64.min
    private var nextProbeUs = Int64.min
    private var nextSystemOffUs = Int64.max
    private var nextDiagUs = Int64.min

    private var cuesSent: Int64 = 0
    private var acks: Int64 = 0
    private var nacks: Int64 = 0
    private var missedAcks: Int64 = 0
    private var malformed: Int64 = 0
    private var staleDropped: Int64 = 0
    private var ignoredCamPackets: Int64 = 0
    private let ackLatency = LatencyStats()
    private let captureToCue = LatencyStats()
    private var lastCueLatencyMs: Double?

    public init(config: EngineConfig, outbox: any Outbox, alerts: any PhoneAlerts, listener: any EngineListener, localCueSink: any LocalCueSink) {
        do { try config.tuning.validate() } catch { preconditionFailure("EngineConfig.tuning is invalid: \(error)") }
        self.config = config
        self.outbox = outbox
        self.alerts = alerts
        self.listener = listener
        self.localCueSink = localCueSink
        self.camBoardUsed = config.frameSource == .camBoard
        self.pingedLinks = camBoardUsed ? [cam, cue] : [cue]
        self.gate = FreshnessGate(maxAgeUs: config.freshnessUs)
        self.policy = HazardPolicy(config: config.policy, tuning: config.tuning)
        self.scheduler = CueScheduler(config: config.cue, tuning: config.tuning)
        self.monitor = SystemMonitor(config: config.monitor)
        self.profile = config.initialProfile
        self.tuning = config.tuning
        self.spearcons = config.spearcons
    }

    // MARK: inputs

    /// `len` is the datagram length inside `bytes` (default: all of `bytes`).
    public func onPacket(from: BoardId, bytes: [UInt8], len: Int? = nil, rxPhoneUs: Int64) {
        // Tier 0: a camera board that is still powered is not part of the system; never trust its packets.
        if from == .cam && !camBoardUsed { ignoredCamPackets += 1; return }
        guard let d = try? Wire.decode(bytes, len: len) else { malformed += 1; return }
        handle(from, d.packet, rxPhoneUs)
    }

    private func handle(_ from: BoardId, _ p: Packet, _ rx: Int64) {
        switch p {
        case .beat(let b): onBeat(from, b, rx)
        case .pong(let q): link(from).clock.onPong(t1: q.tPhoneUs, rx: q.tBoardRxUs, tx: q.tBoardTxUs, t4: rx)
        case .range(let r): if from == .cue { onRange(r, rx) } else { malformed += 1 }
        case .frame(let f): if from == .cam { onFrame(f, rx) } else { malformed += 1 }
        case .ack(let a): if from == .cue { onAck(a, rx) } else { malformed += 1 }
        default: malformed += 1 // phone->board types are never valid inbound
        }
    }

    private func onBeat(_ from: BoardId, _ b: Beat, _ rx: Int64) {
        if BoardId.fromWire(b.board) != from { malformed += 1; return }
        let l = link(from)
        let prevRx = l.lastBeatRxUs
        let prevT = l.lastBeatTUs
        // UDP may reorder: a BEAT slightly older than the newest one is late, not a reboot. A reboot restarts
        // the board clock near zero, so its time jumps back by far more than any network delay.
        if let prevT = prevT, b.tUs < prevT, prevT &- b.tUs <= REBOOT_JUMP_US { return }
        let rebooted = prevT != nil && b.tUs < prevT!
        let reconnected = prevRx == nil || rebooted || rx &- prevRx! > config.monitor.beatTimeoutUs
        // After a reboot or a silence longer than the BEAT timeout, nothing from the old session can be trusted:
        // the board clock may have restarted (old offset meaningless) and the camera restarts frame_id at 0,
        // which the assembler would otherwise drop as "late" until it passed the old count.
        if reconnected && prevRx != nil {
            l.clock.reset()
            if from == .cam { assembler.reset() }
        }
        l.lastBeatRxUs = rx
        l.lastBeatTUs = b.tUs
        l.faults = b.faults
        l.rssi = b.rssiDbm
        if from == .cue {
            if reconnected {
                // PROFILE after every cue-board (re)connect; the haptic link is unconfirmed until it is ACKed.
                boardConfirmed = false
                profileDirty = true
                linkWeak = false; weakSinceUs = nil; recoverSinceUs = nil
            }
            updateLink(rssi: b.rssiDbm, rx)
            if reconnected { sendProfile(rx) }
        }
    }

    private func onFrame(_ f: FramePkt, _ rx: Int64) {
        guard let raw = assembler.onChunk(f, rxPhoneUs: rx) else { return }
        let capPhone = cam.clock.toPhone(raw.tCapBoardUs)
        if !gate.isFresh(capturePhoneUs: capPhone, rxPhoneUs: raw.firstRxPhoneUs, nowPhoneUs: rx) { staleDropped += 1; return }
        latestFrame = AssembledFrame(frameId: raw.frameId, captureBoardUs: raw.tCapBoardUs, capturePhoneUs: capPhone!, w: raw.w, h: raw.h, jpeg: raw.jpeg)
    }

    /// Tier 0 (`frameSource == .phoneCamera`): a JPEG from the phone's own camera, `capturePhoneUs` on the caller's
    /// phone clock. The engine assigns frameId. Out-of-order or already-stale frames are dropped; the 500 ms frame-age
    /// bound applies to phone frames exactly as to board frames. Throws `EngineStateError` outside Tier 0.
    public func onPhoneFrame(jpeg: [UInt8], w: Int, h: Int, capturePhoneUs: Int64) throws {
        if camBoardUsed { throw EngineStateError("onPhoneFrame needs EngineConfig.frameSource = .phoneCamera") }
        if jpeg.isEmpty || w <= 0 || h <= 0 { malformed += 1; return } // never an empty frame (Arch §4.1)
        let tooOld = lastTickUs.map { $0 &- capturePhoneUs > config.freshnessUs } ?? false
        if let prev = latestFrame, capturePhoneUs <= prev.capturePhoneUs { staleDropped += 1; return }
        if tooOld { staleDropped += 1; return }
        latestFrame = AssembledFrame(frameId: phoneFrameId, captureBoardUs: 0, capturePhoneUs: capturePhoneUs, w: w, h: h, jpeg: jpeg)
        phoneFrameId += 1
    }

    private func onRange(_ r: RangePkt, _ rx: Int64) {
        let capPhone = cue.clock.toPhone(r.tUs)
        if !gate.isFresh(capturePhoneUs: capPhone, rxPhoneUs: rx, nowPhoneUs: rx) { staleDropped += 1; return }
        // UDP may reorder: a RANGE slightly older than the newest one is late, not a reboot - it must not rewind the
        // smoother's time base. A jump back by more than REBOOT_JUMP_US is a board reboot and restarts it.
        if let last = latestRangeSnapshot?.tBoardUs, r.tUs < last, last &- r.tUs <= REBOOT_JUMP_US { staleDropped += 1; return }
        latestRangeSnapshot = RangeSnapshot(tBoardUs: r.tUs, rxPhoneUs: rx, mm: r.mm, status: r.status)
        latestRangeCapturePhoneUs = capPhone
        // The one honest distance source: the forward ToF zone. Nothing is fused from the camera.
        let reading = ForwardTo.reading(status: r.status, mm: r.mm)
        latestReading = reading
        if case .target(let mm) = reading { approach.add(tUs: r.tUs, mm: mm, windowMs: tuning.approachWindowMs) } else { approach.clear() }
        approachNow = approachFires()
        runPolicy(policy.observe(reading), capturePhoneUs: capPhone!, now: rx, boardUs: r.tUs)
    }

    /// Experimental approach rule: closing at >= `approachMinSpeedMmPerS` with time-to-contact < `approachTtcMs`.
    private func approachFires() -> Bool {
        guard tuning.approachEnabled, let v = approach.closingSpeedMmPerS(), let mm = approach.latestMm else { return false }
        return v >= Double(tuning.approachMinSpeedMmPerS) && Double(mm) / v * 1000 < Double(tuning.approachTtcMs)
    }

    /// Cue-board RSSI -> link quality (informational): weak after `weakLinkHoldMs` below the threshold, recovered
    /// after the same hold at threshold + 5 dB; in between resets both runs. Speaks once per weak episode.
    private func updateLink(rssi: Int, _ now: Int64) {
        let thr = tuning.weakLinkRssiDbm
        let hold = Int64(tuning.weakLinkHoldMs) * 1000
        if rssi < thr {
            recoverSinceUs = nil
            if linkWeak { return }
            let since = weakSinceUs ?? now
            weakSinceUs = since
            if now &- since >= hold {
                linkWeak = true
                weakSinceUs = nil
                let quiet = state != .unavailable && state != .paused
                if quiet, lastWeakSpokenUs.map({ now &- $0 >= Int64(tuning.weakLinkRepeatMs) * 1000 }) ?? true {
                    lastWeakSpokenUs = now
                    alerts.speak("weak connection")
                }
            }
        } else if rssi >= thr + 5 {
            weakSinceUs = nil
            guard linkWeak else { return }
            let since = recoverSinceUs ?? now
            recoverSinceUs = since
            if now &- since >= hold { linkWeak = false; recoverSinceUs = nil }
        } else {
            weakSinceUs = nil
            recoverSinceUs = nil
        }
    }

    // MARK: controls

    /// Sends PROFILE (mode, haptic_max) now and after every cue-board reconnect.
    public func setProfile(_ profile: HearingProfile) {
        let audioWasRequired = audioRequired
        self.profile = profile
        profileDirty = true
        nextProfileUs = Int64.min
        if audioWasRequired != audioRequired {
            // Audio turned on or off on purpose: forget the old route verdict; a turn-on re-proves the headset first.
            audioConfirmed = false
            audioRouteLost = false
            audioFailures = 0
            nextProbeUs = Int64.min
        }
    }

    public func setPaused(_ paused: Bool) { self.paused = paused }
    public func setBatteryCritical(_ critical: Bool) { batteryCritical = critical }
    public func setHeadYawRate(degPerS: Float) { yawDegPerS = abs(degPerS) }
    public func setSpearcons(_ enabled: Bool) { spearcons = enabled }
    public func latestJpeg() -> AssembledFrame? { latestFrame }
    public func latestRange() -> RangeSnapshot? { latestRangeSnapshot }

    /// Validates and applies `t` at once (no restart); the current band/alert state carries over. Throws
    /// `TuningError` and changes nothing when `t` is invalid.
    public func setTuning(_ t: Tuning) throws {
        try t.validate()
        let exploreChanged = t.exploreEnabled != tuning.exploreEnabled
        tuning = t
        policy.tuning = t
        scheduler.tuning = t
        if exploreChanged { scheduler.reset(); onsetPending = false }
        approachNow = approachFires()
    }

    /// Debug buzz through the normal CUE path (deadline, haptic_max clamp, ACK; no phone audio). False when the cue
    /// board has not been heard from, its clock is not synced, or `pattern` is not a haptic pattern (1...4).
    public func sendTestHaptic(pattern: Int, intensity: Int, nowPhoneUs: Int64) -> Bool {
        guard cue.lastBeatRxUs != nil, cue.clock.synced(nowPhoneUs), Haptic.pulse40...Haptic.lss ~= pattern else { return false }
        let v = max(0, min(255, intensity))
        let g = GAIN_MUTE
        sendCue(CueCommand(cueId: CueId.obstacleFar, side: Side.both, gainLDb: g, gainRDb: g, hapticPattern: pattern,
                           hapticL: v, hapticR: v, flags: 1, ttlMs: config.cue.ttlMs),
                now: nowPhoneUs, captureUs: nil, audio: false)
        return true
    }

    /// Calibration tones are local-only. Returns false when the stimulus was not accepted, so the session cannot
    /// mistake output failure for hearing loss. Even HAPTIC_ONLY profiles may run hearing calibration.
    public func sendCalibrationCue(_ cmd: CueCommand, nowPhoneUs: Int64) -> Bool {
        func clamp(_ gain: Int) -> Int { gain == GAIN_MUTE ? GAIN_MUTE : max(min(gain, profile.effectiveCeilingDb), -127) }
        var c = cmd
        c.gainLDb = clamp(cmd.gainLDb)
        c.gainRDb = clamp(cmd.gainRDb)
        c.hapticPattern = Haptic.none
        c.hapticL = 0
        c.hapticR = 0
        return playLocal(c, nowPhoneUs)
    }

    // MARK: tick

    /// Call every ~10 ms: PINGs (10 Hz/board), cue cadence, ACK + liveness timeouts.
    public func tick(nowPhoneUs: Int64) {
        let now = nowPhoneUs
        lastTickUs = now
        for l in pingedLinks where now >= l.nextPingUs {
            l.clock.onPingSent(now)
            let s = l.pingSeq
            l.pingSeq &+= 1
            send(l.id, .ping(Ping(tPhoneUs: now)), seq: s & 0xFFFF_FFFF)
            l.nextPingUs = now + config.pingIntervalUs
        }
        assembler.expire(now)
        expireAcks(now)
        if (profileDirty || !boardConfirmed || state == .unavailable) && now >= nextProfileUs { sendProfile(now) }
        if audioRequired && now >= nextProbeUs { probeLocalSink(now) }

        if let t = monitor.evaluate(inputs(now)) { onTransition(t, now) }
        serviceAnnounce(now)

        if state == .unavailable && now >= nextSystemOffUs { systemOff(now) }
        // RANGE silence: a distance that is no longer being measured must stop producing warnings. The monitor
        // reports DEGRADED (range_stale); the last band is not repeated and nothing is claimed in its place.
        if alert != nil && rangeStale(now) { clearAlert() }
        emitObstacleCue(now)
        emitExploreCue(now)

        if now >= nextDiagUs {
            nextDiagUs = now + config.diagnosticsIntervalUs
            listener.onDiagnostics(diagnostics(nowPhoneUs: now))
        }
    }

    private func rangeStale(_ now: Int64) -> Bool {
        guard let cap = latestRangeCapturePhoneUs else { return true }
        return now &- cap > config.monitor.rangeMaxAgeUs
    }

    private func inputs(_ now: Int64) -> MonitorInputs {
        MonitorInputs(
            nowUs: now,
            camBeatAgeUs: cam.lastBeatRxUs.map { now &- $0 },
            cueBeatAgeUs: cue.lastBeatRxUs.map { now &- $0 },
            frameAgeUs: latestFrame.map { now &- $0.capturePhoneUs },
            rangeAgeUs: latestRangeCapturePhoneUs.map { now &- $0 },
            tofKnown: latestReading.map { $0 != .unknown } ?? false,
            clocksSynced: cue.clock.synced(now) && (!camBoardUsed || cam.clock.synced(now)),
            cueLinkConfirmed: cueLinkConfirmed,
            ackFailures: max(boardFailures, audioRequired ? audioFailures : 0),
            batteryCritical: batteryCritical,
            paused: paused,
            camBoardRequired: camBoardUsed,
            audioRouteLost: audioRouteLost && audioRequired
        )
    }

    private func onTransition(_ t: SystemMonitor.Transition, _ now: Int64) {
        listener.onState(t.to, reason: t.reason)
        pendingAnnounce = nil // any state change cancels an announcement still waiting out its hold
        switch t.to {
        case .unavailable:
            // Failure is loud (invariant 2): suppress obstacle cues, system-off on every surviving output.
            clearAlert()
            systemOff(now)
        case .ready:
            if t.from == .unavailable { announce("system ready", in: .ready, now) }
            // Arch §8: READY is one soft tick, once - not on every DEGRADED->READY flap.
            if t.from == .starting || t.from == .unavailable { sendReadyTick(now) }
            if let a = alert { listener.onAlert(a) }
        case .degraded:
            // Announce once per entry; only supported claims.
            announce(t.from == .unavailable ? "system ready, distance sensor limited" : "distance sensor limited", in: .degraded, now)
            if let a = alert, t.from != .ready { listener.onAlert(a) }
        case .paused:
            clearAlert()
            alerts.speak("paused")
        case .starting:
            if t.from == .paused { alerts.speak("resumed") }
        }
        if t.to != .unavailable { nextSystemOffUs = Int64.max }
    }

    /// READY / DEGRADED speech waits until the new state held `announceHoldMs`; the visual state is already out.
    private func announce(_ text: String, in state: SystemState, _ now: Int64) {
        pendingAnnounce = (text, state, now)
    }

    private func serviceAnnounce(_ now: Int64) {
        guard let p = pendingAnnounce, state == p.state, now &- p.sinceUs >= Int64(tuning.announceHoldMs) * 1000 else { return }
        pendingAnnounce = nil
        if let last = lastSpokenUs[p.text], now &- last < Int64(tuning.announceRepeatMs) * 1000 { return } // dropped, not retried
        lastSpokenUs[p.text] = now
        alerts.speak(p.text)
    }

    private func clearAlert() {
        let had = alert != nil
        policy.reset()
        scheduler.reset()
        alert = nil
        onsetPending = false
        if had { listener.onAlert(nil) }
    }

    private func systemOff(_ now: Int64) {
        alerts.systemOff()
        alerts.vibrate(pattern: config.systemOffVibration)
        // Every surviving output (invariant 2): the board's temple haptics always, the headset when warning sounds
        // are on, and the phone's own alarm tone/speech/vibration above (not tied to the headset or the profile).
        let g = min(profile.effectiveCeilingDb, config.systemOffGainDb)
        let hm = profile.hapticMax
        sendCue(CueCommand(cueId: CueId.systemOff, side: Side.both, gainLDb: g, gainRDb: g, hapticPattern: Haptic.lss,
                           hapticL: hm, hapticR: hm, flags: 1, ttlMs: config.cue.ttlMs), now: now, captureUs: nil)
        nextSystemOffUs = now + config.systemOffRepeatUs
    }

    private func sendReadyTick(_ now: Int64) {
        let ceil = profile.effectiveCeilingDb
        let g = min(profile.levelDb(1, .left) ?? GAIN_MUTE, ceil)
        let gr = min(profile.levelDb(1, .right) ?? GAIN_MUTE, ceil)
        sendCue(CueCommand(cueId: CueId.ready, side: Side.both, gainLDb: g, gainRDb: gr, hapticPattern: Haptic.none,
                           hapticL: 0, hapticR: 0, flags: 0, ttlMs: config.cue.ttlMs), now: now, captureUs: nil)
    }

    private func emitObstacleCue(_ now: Int64) {
        if tuning.exploreEnabled { onsetPending = false; scheduler.reset(); return } // exploration replaces band cues
        if !obstacleCuesAllowed() { return }
        let onset = onsetPending
        guard let cmd = scheduler.update(alert, onset: onset, profile: profile, nowUs: now) else { return }
        onsetPending = false
        let cap = onset ? onsetCapturePhoneUs : nil
        sendCue(cmd, now: now, captureUs: cap)
        if onset {
            if let cap = cap {
                let ms = Double(now &- cap) / 1000.0
                lastCueLatencyMs = ms
                captureToCue.add(ms)
            }
            if spearcons, let a = alert { alerts.speak(CueScheduler.spearcon(a)) }
        }
    }

    private func obstacleCuesAllowed() -> Bool { state == .ready || state == .degraded }

    private func runPolicy(_ obs: ForwardObservation, capturePhoneUs: Int64, now: Int64, boardUs: Int64) {
        let next = policy.step(obs, scanning: yawDegPerS > config.scanYawDegPerS, approach: approachNow, nowUs: now, boardUs: boardUs)
        if policy.takeOnset() { onsetPending = true; onsetCapturePhoneUs = capturePhoneUs }
        if next != alert {
            alert = next
            if obstacleCuesAllowed() { listener.onAlert(next) }
        }
        // Urgent warnings never wait for the next tick.
        emitObstacleCue(now)
    }

    /// The Arch §4.2 liveness bound on RANGE applies to anything derived from it.
    private func exploreTargetMm(_ now: Int64) -> Int? {
        guard tuning.exploreEnabled, obstacleCuesAllowed(), !rangeStale(now),
              case .target(let mm)? = latestReading, mm <= tuning.exploreMaxMm else { return nil }
        return mm
    }

    /// Exploration mode (experimental): one haptic pulse per `explorePeriodMs`, stronger the closer the target.
    /// Phone audio stays silent; no spearcons, no band cues.
    private func emitExploreCue(_ now: Int64) {
        guard let mm = exploreTargetMm(now), now >= nextExploreUs else { return }
        let lo = tuning.exploreMinIntensity
        let hi = tuning.exploreMaxMm
        let d = max(0, min(mm, hi))
        let intensity = lo + ((255 - lo) * (hi - d) + hi / 2) / hi
        let g = GAIN_MUTE
        sendCue(CueCommand(cueId: CueId.obstacleFar, side: Side.both, gainLDb: g, gainRDb: g, hapticPattern: Haptic.pulse40,
                           hapticL: intensity, hapticR: intensity, flags: 0, ttlMs: config.cue.ttlMs),
                now: now, captureUs: nil, audio: false)
        nextExploreUs = now + Int64(tuning.explorePeriodMs) * 1000
    }

    /// One warning on both outputs: the phone plays the audio immediately (its return value is that cue's audio ACK),
    /// and when the command carries a haptic pattern the board gets a CUE packet whose ACK is awaited. Cues without
    /// haptics (READY tick, calibration tones) never touch the wire: the board has no audio to play. With warning
    /// sounds intentionally off (HAPTIC_ONLY) the sink is not called at all.
    @discardableResult
    private func sendCue(_ cmd: CueCommand, now: Int64, captureUs: Int64?, audio: Bool = true) -> Int64 {
        let ceil = profile.effectiveCeilingDb
        func clamp(_ g: Int) -> Int { g == GAIN_MUTE ? GAIN_MUTE : max(min(g, ceil), -127) } // invariant 4
        let gl = clamp(cmd.gainLDb)
        let gr = clamp(cmd.gainRDb)
        let hl = min(cmd.hapticL, profile.hapticMax)
        let hr = min(cmd.hapticR, profile.hapticMax)
        let seq = nextCmdSeq()
        cuesSent += 1
        if audio && audioRequired {
            var c = cmd
            c.gainLDb = gl
            c.gainRDb = gr
            c.hapticL = hl
            c.hapticR = hr
            playLocal(c, now)
        }
        if cmd.hapticPattern != Haptic.none {
            // Invariant 5: deadline 0 when unsynced -> the board drops the cue (never plays late).
            var deadline: Int64 = 0
            if cue.clock.synced(now), let b = cue.clock.toBoard(now) { deadline = b &+ Int64(cmd.ttlMs) * 1000 }
            let pkt = CuePkt(tIssuedPhoneUs: now, deadlineBoardUs: deadline, cueId: cmd.cueId, side: cmd.side,
                             hapticPattern: cmd.hapticPattern, hapticL: hl, hapticR: hr, flags: cmd.flags, ttlMs: cmd.ttlMs)
            pending[seq] = Pending(issuedUs: now)
            send(.cue, .cue(pkt), seq: seq)
        }
        return seq
    }

    /// The local sink's return value is this cue's audio ACK. Any good play confirms the audio path; a bad status or a
    /// thrown error counts towards the same ACK-failure escalation as the board.
    @discardableResult
    private func playLocal(_ cmd: CueCommand, _ now: Int64) -> Bool {
        let status: Int?
        do { status = try localCueSink.play(cmd, nowPhoneUs: now) } catch { status = nil }
        if status == AUDIO_ROUTE_LOST { nacks += 1; audioRouteLost = true; return false }
        guard let st = status else { missedAcks += 1; audioFailures += 1; return false }
        let applied = recordStatus(st, board: false)
        if applied { audioRouteLost = false }
        return applied
    }

    /// Audio-path stand-in for a PROFILE ACK: a muted, haptic-free READY command checks the phone -> headset route
    /// without making a sound, every `profileRetryUs` even while idle or paused.
    private func probeLocalSink(_ now: Int64) {
        playLocal(CueCommand(cueId: CueId.ready, side: Side.both, gainLDb: GAIN_MUTE, gainRDb: GAIN_MUTE, hapticPattern: Haptic.none,
                             hapticL: 0, hapticR: 0, flags: 0, ttlMs: config.cue.ttlMs), now)
        nextProbeUs = now + config.profileRetryUs
    }

    private func sendProfile(_ now: Int64) {
        let seq = nextCmdSeq()
        pending[seq] = Pending(issuedUs: now)
        send(.cue, .profile(ProfilePkt(mode: profile.mode.wire, hapticMax: profile.hapticMax)), seq: seq)
        profileDirty = false
        nextProfileUs = now + config.profileRetryUs
    }

    private func onAck(_ a: Ack, _ rx: Int64) {
        guard let p = pending.removeValue(forKey: a.cueSeq) else { return } // late (already counted missing) or unknown
        if recordStatus(a.status, board: true) { ackLatency.add(Double(rx - p.issuedUs) / 1000.0) }
    }

    /// Board ACK (`board` = true) or local-sink result -> counters and that channel's link state. Returns true for a
    /// good (applied) status. Each channel keeps its own failure streak.
    private func recordStatus(_ status: Int, board: Bool) -> Bool {
        switch status {
        case AckStatus.applied, AckStatus.appliedClamped:
            acks += 1
            if board { boardFailures = 0; boardConfirmed = true } else { audioFailures = 0; audioConfirmed = true }
            return true
        case AckStatus.busyDropped:
            nacks += 1 // busy with a higher-priority cue; path alive
            return false
        default: // EXPIRED, NO_SYNC, BAD_CUE, unknown/negative: that cue path is not working
            nacks += 1
            if board { boardFailures += 1 } else { audioFailures += 1 }
            return false
        }
    }

    private func nextCmdSeq() -> Int64 {
        let s = cmdSeq
        cmdSeq = (cmdSeq + 1) & 0xFFFF_FFFF
        return s
    }

    private func expireAcks(_ now: Int64) {
        for (seq, p) in pending where now - p.issuedUs > config.ackTimeoutUs {
            pending.removeValue(forKey: seq)
            missedAcks += 1
            boardFailures += 1
        }
    }

    /// Encoding PING/CUE/PROFILE cannot fail (only BEAT i8 fields and oversized FRAME chunks throw).
    private func send(_ board: BoardId, _ p: Packet, seq: Int64) {
        outbox.send(board: board, packet: try! Wire.encode(p, seq: seq))
    }

    private func boardDiag(_ l: BoardLink, _ now: Int64) -> BoardDiagnostics {
        BoardDiagnostics(
            board: l.id, beatAgeMs: l.lastBeatRxUs.map { (now &- $0) / 1000 }, offsetUs: l.clock.offsetUs, rttUs: l.clock.rttUs,
            synced: l.clock.synced(now), faults: l.faults, rssiDbm: l.rssi
        )
    }

    public func diagnostics(nowPhoneUs now: Int64) -> Diagnostics {
        let fresh = !rangeStale(now)
        return Diagnostics(
            nowPhoneUs: now, state: state, cam: boardDiag(cam, now), cue: boardDiag(cue, now),
            frameAgeMs: latestFrame.map { (now &- $0.capturePhoneUs) / 1000 },
            rangeAgeMs: latestRangeCapturePhoneUs.map { (now &- $0) / 1000 },
            rangeMm: latestRangeSnapshot?.mm, rangeStatus: latestRangeSnapshot?.status,
            lastAlert: alert, lastCueLatencyMs: lastCueLatencyMs,
            captureToCueP50Ms: captureToCue.percentile(50), captureToCueP95Ms: captureToCue.percentile(95),
            ackP50Ms: ackLatency.percentile(50), ackP95Ms: ackLatency.percentile(95),
            cuesSent: cuesSent, acks: acks, nacks: nacks, missedAcks: missedAcks, malformed: malformed, staleDropped: staleDropped,
            frameSource: config.frameSource, ignoredCamPackets: ignoredCamPackets,
            linkWeak: linkWeak,
            closingSpeedMmPerS: fresh ? approach.closingSpeedMmPerS() : nil,
            approachActive: fresh && approachNow,
            exploring: exploreTargetMm(now) != nil
        )
    }
}

/// Rolling latency window (last `capacity` samples) with nearest-rank percentiles.
public final class LatencyStats {
    private let capacity: Int
    private var samples: [Double] = []

    public init(capacity: Int = 200) { self.capacity = capacity }

    public func add(_ ms: Double) {
        samples.append(ms)
        if samples.count > capacity { samples.removeFirst() }
    }

    public func percentile(_ p: Double) -> Double? {
        if samples.isEmpty { return nil }
        let sorted = samples.sorted()
        let rank = Int((p / 100.0 * Double(sorted.count)).rounded(.up)) - 1
        return sorted[max(0, min(sorted.count - 1, rank))]
    }
}
