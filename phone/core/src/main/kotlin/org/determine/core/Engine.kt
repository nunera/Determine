package org.determine.core

import org.determine.core.clock.ClockSync
import org.determine.core.cue.CueConfig
import org.determine.core.cue.CueScheduler
import org.determine.core.hearing.HearingMode
import org.determine.core.hearing.HearingProfile
import org.determine.core.ingest.FrameAssembler
import org.determine.core.ingest.FreshnessGate
import org.determine.core.policy.HazardPolicy
import org.determine.core.policy.PolicyConfig
import org.determine.core.range.ForwardRange
import org.determine.core.range.ForwardTo
import org.determine.core.system.MonitorConfig
import org.determine.core.system.MonitorInputs
import org.determine.core.system.SystemMonitor
import org.determine.core.wire.Ack
import org.determine.core.wire.AckStatus
import org.determine.core.wire.Beat
import org.determine.core.wire.CueId
import org.determine.core.wire.CuePkt
import org.determine.core.wire.FramePkt
import org.determine.core.wire.Haptic
import org.determine.core.wire.Packet
import org.determine.core.wire.Ping
import org.determine.core.wire.Pong
import org.determine.core.wire.ProfilePkt
import org.determine.core.wire.RangePkt
import org.determine.core.wire.Side
import org.determine.core.wire.Wire
import org.determine.core.wire.WireException

data class EngineConfig(
    val policy: PolicyConfig = PolicyConfig(),
    val cue: CueConfig = CueConfig(),
    /** Initial runtime tuning (feature contract §7); [Engine.setTuning] changes it later. */
    val tuning: Tuning = Tuning(),
    val monitor: MonitorConfig = MonitorConfig(),
    val initialProfile: HearingProfile = HearingProfile.uncalibrated(),
    /** PROTOCOL.md: PING 10 Hz per board (also the cue board's watchdog feed). */
    val pingIntervalUs: Long = 100_000,
    /** Arch §4.2 freshness bound for FRAME/RANGE (capture age and receive age). */
    val freshnessUs: Long = 500_000,
    val ackTimeoutUs: Long = 300_000,
    val profileRetryUs: Long = 1_000_000,
    /** Arch §8.1: SYSTEM OFF repeats every 5 s until resolved. */
    val systemOffRepeatUs: Long = 5_000_000,
    /** Local audio level of the system-off cue: min(ceiling, −20 dBFS). */
    val systemOffGainDb: Int = -20,
    val diagnosticsIntervalUs: Long = 1_000_000,
    /** Arch §8.2: spearcons on by default during training. */
    val spearcons: Boolean = true,
    /** Head yaw rate above which the wearer is scanning (Arch §7.2: holds FAR only). */
    val scanYawDegPerS: Float = 60f,
    /** Phone-side vibration for system unavailable (long-short-short, Arch §8.3). */
    val systemOffVibration: LongArray = longArrayOf(0, 400, 100, 120, 100, 120),
    /** Tier 0 fallback (Arch §11.3): PHONE_CAMERA replaces the camera board; frames come from [Engine.onPhoneFrame]. */
    val frameSource: FrameSourceKind = FrameSourceKind.CAM_BOARD,
)

/** A BEAT whose board time is this much older than the newest is a reboot, not UDP reordering. */
internal const val REBOOT_JUMP_US = 1_000_000L

/**
 * The phone's single integrator (Arch §4): ingests board packets, keeps clocks, runs the hazard policy and the
 * state machine, plays every warning sound through [localCueSink] (the phone → bone-conduction headset path,
 * the primary audio output) and sends the board's haptic commands. Not thread-safe: serialise every call.
 * Callbacks run synchronously. The phone decides every hazard; the wearable decides nothing (invariant 3).
 *
 * Outputs of one warning: audio on the phone (per-side calibrated levels under the profile ceiling) AND temple
 * haptics on the cue board (CUE packet; the board never plays sound). The cue path counts as confirmed only
 * when BOTH have answered well once — the board through a PROFILE/CUE ACK, the audio through a good local play.
 *
 * Fallback: with [EngineConfig.frameSource] = PHONE_CAMERA there is no camera board (no BEAT/PING/clock for it)
 * and the host feeds [onPhoneFrame].
 */
class Engine(
    val config: EngineConfig,
    private val outbox: Outbox,
    private val alerts: PhoneAlerts,
    private val listener: EngineListener,
    private val localCueSink: LocalCueSink,
) {
    init { config.tuning.validate() }

    private val camBoardUsed = config.frameSource == FrameSourceKind.CAM_BOARD

    private inner class BoardLink(val id: BoardId) {
        val clock = ClockSync()
        var lastBeatRxUs: Long? = null
        var lastBeatTUs: Long? = null
        var faults = 0
        var rssi: Int? = null
        var pingSeq = 0L
        var nextPingUs = Long.MIN_VALUE
    }

    private val cam = BoardLink(BoardId.CAM)
    private val cue = BoardLink(BoardId.CUE)
    private fun link(b: BoardId) = if (b == BoardId.CAM) cam else cue
    private val pingedLinks = if (camBoardUsed) listOf(cam, cue) else listOf(cue)

    private val assembler = FrameAssembler()
    private val gate = FreshnessGate(config.freshnessUs)
    private val policy = HazardPolicy(config.tuning, config.policy)
    private val scheduler = CueScheduler(config.tuning, config.cue)
    private val monitor = SystemMonitor(config.monitor)

    var profile: HearingProfile = config.initialProfile; private set

    /** Runtime-adjustable knobs (feature contract §7); change them with [setTuning]. */
    var tuning: Tuning = config.tuning; private set
    val state: SystemState get() = monitor.state

    private var paused = false
    private var batteryCritical = false
    private var yawDegPerS = 0f
    private var spearcons = config.spearcons

    private var latestFrame: AssembledFrame? = null
    private var latestRange: RangeSnapshot? = null
    private var latestRangeCapturePhoneUs: Long? = null
    private var latestReading: ForwardRange? = null
    private var phoneFrameId = 0L
    private var lastTickUs: Long? = null

    private var alert: Alert? = null
    private var onsetPending = false
    private var onsetCapturePhoneUs: Long? = null

    // CUE and PROFILE share one seq counter so every ACK.cue_seq is unambiguous.
    private var cmdSeq = 0L
    private class Pending(val issuedUs: Long, val isProfile: Boolean, val captureUs: Long?)
    private val pending = LinkedHashMap<Long, Pending>()
    // Failures are counted per output channel: a good answer from one channel must never reset the other's
    // streak, or a dead board (no ACKs) would hide behind a working headset, and the reverse.
    private var boardFailures = 0
    private var audioFailures = 0
    private var boardConfirmed = false
    private var audioConfirmed = false
    private var audioRouteLost = false

    /** Warning sounds are wanted unless the profile intentionally turns phone audio off (HAPTIC_ONLY). */
    private val audioRequired: Boolean get() = profile.mode != HearingMode.HAPTIC_ONLY
    private val cueLinkConfirmed: Boolean get() = boardConfirmed && (audioConfirmed || !audioRequired)
    private var profileDirty = true
    private var nextProfileUs = Long.MIN_VALUE
    private var nextProbeUs = Long.MIN_VALUE
    private var nextSystemOffUs = Long.MAX_VALUE
    private var nextDiagUs = Long.MIN_VALUE
    private var exploring = false
    private var nextExploreUs = 0L

    private var announceState: SystemState? = null
    private var announcePhrase: String? = null
    private var announceSinceUs = 0L
    private val lastSpokenUs = HashMap<String, Long>()

    private var linkWeak = false
    private var weakBelowSinceUs: Long? = null
    private var weakAboveSinceUs: Long? = null
    private var lastWeakSpeechUs: Long? = null

    private var cuesSent = 0L
    private var acks = 0L
    private var nacks = 0L
    private var missedAcks = 0L
    private var malformed = 0L
    private var staleDropped = 0L
    private var ignoredCamPackets = 0L
    private val ackLatency = LatencyStats()
    private val captureToCue = LatencyStats()
    private var lastCueLatencyMs: Double? = null

    // ------------------------------------------------------------------ inputs

    fun onPacket(from: BoardId, bytes: ByteArray, len: Int, rxPhoneUs: Long) {
        // Tier 0: a camera board that is still powered is not part of the system; never trust its packets.
        if (from == BoardId.CAM && !camBoardUsed) { ignoredCamPackets++; return }
        val d = try { Wire.decode(bytes, len) } catch (_: WireException) { malformed++; return }
        handle(from, d.packet, rxPhoneUs)
    }

    private fun handle(from: BoardId, p: Packet, rx: Long) {
        when (p) {
            is Beat -> onBeat(from, p, rx)
            is Pong -> link(from).clock.onPong(p.tPhoneUs, p.tBoardRxUs, p.tBoardTxUs, rx)
            is RangePkt -> if (from == BoardId.CUE) onRange(p, rx) else malformed++
            is FramePkt -> if (from == BoardId.CAM) onFrame(p, rx) else malformed++
            is Ack -> if (from == BoardId.CUE) onAck(p, rx) else malformed++
            else -> malformed++ // phone→board types are never valid inbound
        }
    }

    private fun onBeat(from: BoardId, b: Beat, rx: Long) {
        if (BoardId.fromWire(b.board) != from) { malformed++; return }
        val l = link(from)
        val prevRx = l.lastBeatRxUs
        val prevT = l.lastBeatTUs
        // UDP may reorder: a BEAT slightly older than the newest one is late, not a reboot. A reboot restarts
        // the board clock near zero, so its time jumps back by far more than any network delay.
        if (prevT != null && b.tUs < prevT && prevT - b.tUs <= REBOOT_JUMP_US) return
        val rebooted = prevT != null && b.tUs < prevT
        val reconnected = prevRx == null || rebooted || rx - prevRx > config.monitor.beatTimeoutUs
        // After a reboot or a silence longer than the BEAT timeout, nothing from the old session can be trusted:
        // the board clock may have restarted (old offset meaningless) and the camera restarts frame_id at 0,
        // which the assembler would otherwise drop as "late" until it passed the old count.
        if (reconnected && prevRx != null) {
            l.clock.reset()
            if (from == BoardId.CAM) assembler.reset()
        }
        l.lastBeatRxUs = rx
        l.lastBeatTUs = b.tUs
        l.faults = b.faults
        l.rssi = b.rssiDbm
        if (from == BoardId.CUE) {
            if (reconnected) resetWeakLink()
            updateWeakLink(b.rssiDbm, rx)
        }
        if (from == BoardId.CUE && reconnected) {
            // PROFILE after every cue-board (re)connect; the haptic link is unconfirmed until it is ACKed.
            boardConfirmed = false
            profileDirty = true
            sendProfile(rx)
        }
    }

    private fun resetWeakLink() {
        linkWeak = false; weakBelowSinceUs = null; weakAboveSinceUs = null
    }

    /**
     * Link quality from the cue board's BEAT RSSI (phone receive time). Weak after [Tuning.weakLinkHoldMs] below
     * the threshold, recovered after the same time at threshold + 5 dB; in between, neither run advances.
     * Informational only: never changes [SystemState]. Spoken once per weak episode, not within
     * [Tuning.weakLinkRepeatMs] of the previous announcement, and never over a system-off or a pause.
     */
    private fun updateWeakLink(rssi: Int, now: Long) {
        val holdUs = tuning.weakLinkHoldMs * 1000L
        when {
            rssi < tuning.weakLinkRssiDbm -> {
                weakAboveSinceUs = null
                val since = weakBelowSinceUs ?: now.also { weakBelowSinceUs = it }
                if (!linkWeak && now - since >= holdUs) {
                    linkWeak = true
                    val last = lastWeakSpeechUs
                    val quiet = state == SystemState.UNAVAILABLE || state == SystemState.PAUSED
                    if (!quiet && (last == null || now - last >= tuning.weakLinkRepeatMs * 1000L)) {
                        lastWeakSpeechUs = now
                        alerts.speak("weak connection")
                    }
                }
            }
            rssi >= tuning.weakLinkRssiDbm + 5 -> {
                weakBelowSinceUs = null
                val since = weakAboveSinceUs ?: now.also { weakAboveSinceUs = it }
                if (linkWeak && now - since >= holdUs) { linkWeak = false; weakAboveSinceUs = null }
            }
            else -> { weakBelowSinceUs = null; weakAboveSinceUs = null }
        }
    }

    private fun onFrame(f: FramePkt, rx: Long) {
        val raw = assembler.onChunk(f, rx) ?: return
        val capPhone = cam.clock.toPhone(raw.tCapBoardUs)
        if (!gate.isFresh(capPhone, raw.firstRxPhoneUs, rx)) { staleDropped++; return }
        latestFrame = AssembledFrame(raw.frameId, raw.tCapBoardUs, capPhone!!, raw.w, raw.h, raw.jpeg)
    }

    /**
     * Tier 0 (frameSource = PHONE_CAMERA): a JPEG from the phone's own camera, [capturePhoneUs] on the caller's phone
     * clock. The engine assigns frameId and keeps [jpeg] (do not mutate it). Out-of-order or already-stale frames are
     * dropped; the 500 ms frame-age bound applies to phone frames exactly as to board frames.
     */
    fun onPhoneFrame(jpeg: ByteArray, w: Int, h: Int, capturePhoneUs: Long) {
        check(!camBoardUsed) { "onPhoneFrame needs EngineConfig.frameSource = PHONE_CAMERA" }
        if (jpeg.isEmpty() || w <= 0 || h <= 0) { malformed++; return } // never an empty frame (Arch §4.1)
        val prev = latestFrame
        val tooOld = lastTickUs?.let { it - capturePhoneUs > config.freshnessUs } ?: false
        if ((prev != null && capturePhoneUs <= prev.capturePhoneUs) || tooOld) { staleDropped++; return }
        latestFrame = AssembledFrame(phoneFrameId++, 0L, capturePhoneUs, w, h, jpeg)
    }

    private fun onRange(r: RangePkt, rx: Long) {
        // UDP may reorder: a RANGE slightly older than the newest accepted one is late, not a reboot (same rule as
        // BEAT). Only a jump back by more than REBOOT_JUMP_US restarts the smoother.
        val prevT = latestRange?.tBoardUs
        if (prevT != null && r.tUs < prevT && prevT - r.tUs <= REBOOT_JUMP_US) { staleDropped++; return }
        val capPhone = cue.clock.toPhone(r.tUs)
        if (!gate.isFresh(capPhone, rx, rx)) { staleDropped++; return }
        latestRange = RangeSnapshot(r.tUs, rx, r.mm, r.status)
        latestRangeCapturePhoneUs = capPhone
        // The one honest distance source: the forward ToF zone. Nothing is fused from the camera.
        val reading = ForwardTo.reading(r.status, r.mm)
        latestReading = reading
        runPolicy(reading, r.tUs, capPhone!!, rx)
    }

    // ------------------------------------------------------------------ controls

    fun setProfile(profile: HearingProfile) {
        val audioWasRequired = audioRequired
        this.profile = profile
        profileDirty = true
        nextProfileUs = Long.MIN_VALUE
        if (audioWasRequired != audioRequired) {
            // Audio turned on or off on purpose: forget the old route verdict; a turn-on re-proves the headset first.
            audioConfirmed = false
            audioRouteLost = false
            audioFailures = 0
            nextProbeUs = Long.MIN_VALUE
        }
    }

    fun setPaused(paused: Boolean) { this.paused = paused }
    fun setBatteryCritical(critical: Boolean) { batteryCritical = critical }
    fun setHeadYawRate(degPerS: Float) { yawDegPerS = kotlin.math.abs(degPerS) }
    fun setSpearcons(enabled: Boolean) { spearcons = enabled }

    /**
     * Applies [t] at once, without restarting anything. Band state, queued announcements and link tracking carry
     * over; the new thresholds/times are used from the next sample/tick on.
     * @throws IllegalArgumentException when [t] is invalid; the previous tuning stays in force.
     */
    fun setTuning(t: Tuning) {
        t.validate()
        if (t.exploreEnabled != tuning.exploreEnabled) { scheduler.reset(); exploring = false; nextExploreUs = 0L }
        tuning = t
        policy.tuning = t
        scheduler.tuning = t
    }

    /**
     * Debug buzz: one CUE (side BOTH, no phone audio) with [pattern] (a [Haptic] pattern, 1..4) at [intensity]
     * (clamped to 0..255, then to haptic_max), through the normal CUE path: deadline, ACK tracking. Returns false —
     * and sends nothing — when the cue board is not known yet, its clock is not synced, or [pattern] is not a
     * playable pattern.
     */
    fun sendTestHaptic(pattern: Int, intensity: Int, nowPhoneUs: Long): Boolean {
        if (pattern !in Haptic.PULSE40..Haptic.LSS) return false
        if (cue.lastBeatRxUs == null || !cue.clock.synced(nowPhoneUs)) return false
        val i = intensity.coerceIn(0, 255)
        sendCue(
            CueCommand(CueId.OBSTACLE_FAR, Side.BOTH, GAIN_MUTE, GAIN_MUTE, pattern, i, i, 1, config.cue.ttlMs),
            nowPhoneUs, null, localAudio = false,
        )
        return true
    }

    fun latestJpeg(): AssembledFrame? = latestFrame
    fun latestRange(): RangeSnapshot? = latestRange

    /**
     * Calibration tones are local-only. Return false when the stimulus was not accepted, so the session
     * cannot mistake output failure for hearing loss. Even HAPTIC_ONLY profiles may run hearing calibration.
     */
    fun sendCalibrationCue(cmd: CueCommand, nowPhoneUs: Long): Boolean {
        fun clamp(gain: Int) = if (gain == GAIN_MUTE) GAIN_MUTE else minOf(gain, profile.effectiveCeilingDb).coerceAtLeast(-127)
        return playLocal(localCueSink, cmd.copy(
            gainLDb = clamp(cmd.gainLDb), gainRDb = clamp(cmd.gainRDb),
            hapticPattern = Haptic.NONE, hapticL = 0, hapticR = 0,
        ), nowPhoneUs)
    }

    // ------------------------------------------------------------------ tick

    fun tick(nowPhoneUs: Long) {
        val now = nowPhoneUs
        lastTickUs = now
        for (l in pingedLinks) {
            if (now >= l.nextPingUs) {
                l.clock.onPingSent(now)
                outbox.send(l.id, Wire.encode(Ping(now), l.pingSeq++ and 0xFFFFFFFFL))
                l.nextPingUs = now + config.pingIntervalUs
            }
        }
        assembler.expire(now)
        expireAcks(now)
        if ((profileDirty || !boardConfirmed || state == SystemState.UNAVAILABLE) && now >= nextProfileUs) sendProfile(now)
        if (audioRequired && now >= nextProbeUs) probeLocalSink(now)

        monitor.evaluate(inputs(now))?.let { onTransition(it, now) }
        flushAnnouncement(now)

        if (state == SystemState.UNAVAILABLE && now >= nextSystemOffUs) systemOff(now)
        // RANGE silence: a distance that is no longer being measured must stop producing warnings. The monitor
        // reports DEGRADED (range_stale); the last band is not repeated and nothing is claimed in its place.
        if (alert != null && rangeStale(now)) clearAlert()
        emitObstacleCue(now)

        if (now >= nextDiagUs) {
            nextDiagUs = now + config.diagnosticsIntervalUs
            listener.onDiagnostics(diagnostics(now))
        }
    }

    private fun rangeStale(now: Long): Boolean {
        val cap = latestRangeCapturePhoneUs ?: return true
        return now - cap > config.monitor.rangeMaxAgeUs
    }

    private fun inputs(now: Long) = MonitorInputs(
        nowUs = now,
        camBeatAgeUs = cam.lastBeatRxUs?.let { now - it },
        cueBeatAgeUs = cue.lastBeatRxUs?.let { now - it },
        frameAgeUs = latestFrame?.let { now - it.capturePhoneUs },
        rangeAgeUs = latestRangeCapturePhoneUs?.let { now - it },
        tofKnown = latestReading?.let { it != ForwardRange.Unknown } ?: false,
        clocksSynced = cue.clock.synced(now) && (!camBoardUsed || cam.clock.synced(now)),
        cueLinkConfirmed = cueLinkConfirmed,
        ackFailures = maxOf(boardFailures, if (audioRequired) audioFailures else 0),
        batteryCritical = batteryCritical,
        paused = paused,
        camBoardRequired = camBoardUsed,
        audioRouteLost = audioRouteLost && audioRequired,
    )

    private fun onTransition(t: SystemMonitor.Transition, now: Long) {
        listener.onState(t.to, t.reason)
        announcePhrase = null // a state that did not hold is never announced
        when (t.to) {
            SystemState.UNAVAILABLE -> {
                // Failure is loud (invariant 2): suppress obstacle cues, system-off on every surviving output.
                clearAlert()
                systemOff(now)
            }
            SystemState.READY -> {
                if (t.from == SystemState.UNAVAILABLE) queueAnnouncement(t.to, "system ready", now)
                // Arch §8: READY is one soft tick, once — not on every DEGRADED→READY flap.
                if (t.from == SystemState.STARTING || t.from == SystemState.UNAVAILABLE) sendReadyTick(now)
                alert?.let { listener.onAlert(it) }
            }
            SystemState.DEGRADED -> {
                // Announce once per entry, after it held; only supported claims.
                queueAnnouncement(t.to, if (t.from == SystemState.UNAVAILABLE) "system ready, distance sensor limited" else "distance sensor limited", now)
                alert?.let { if (t.from != SystemState.READY) listener.onAlert(it) }
            }
            SystemState.PAUSED -> { clearAlert(); alerts.speak("paused") }
            SystemState.STARTING -> if (t.from == SystemState.PAUSED) alerts.speak("resumed")
        }
        if (t.to != SystemState.UNAVAILABLE) nextSystemOffUs = Long.MAX_VALUE
    }

    private fun queueAnnouncement(forState: SystemState, phrase: String, now: Long) {
        announceState = forState
        announcePhrase = phrase
        announceSinceUs = now
    }

    /** Speaks the queued READY/DEGRADED phrase once its state has held [Tuning.announceHoldMs] (and it is not a recent repeat). */
    private fun flushAnnouncement(now: Long) {
        val phrase = announcePhrase ?: return
        if (state != announceState) { announcePhrase = null; return }
        if (now - announceSinceUs < tuning.announceHoldMs * 1000L) return
        announcePhrase = null
        val last = lastSpokenUs[phrase]
        if (last != null && now - last < tuning.announceRepeatMs * 1000L) return
        lastSpokenUs[phrase] = now
        alerts.speak(phrase)
    }

    private fun clearAlert() {
        val had = alert != null
        policy.reset()
        scheduler.reset()
        alert = null
        onsetPending = false
        if (had) listener.onAlert(null)
    }

    private fun systemOff(now: Long) {
        alerts.systemOff()
        alerts.vibrate(config.systemOffVibration)
        // Every surviving output (invariant 2): the board's temple haptics always, the headset when warning sounds
        // are on, and the phone's own alarm tone/speech/vibration above (not tied to the headset or the profile).
        val g = minOf(profile.effectiveCeilingDb, config.systemOffGainDb)
        val hm = profile.hapticMax
        sendCue(CueCommand(CueId.SYSTEM_OFF, Side.BOTH, g, g, Haptic.LSS, hm, hm, 1, config.cue.ttlMs), now, null)
        nextSystemOffUs = now + config.systemOffRepeatUs
    }

    private fun sendReadyTick(now: Long) {
        val g = minOf(profile.levelDb(1, org.determine.core.hearing.Ear.LEFT) ?: GAIN_MUTE, profile.effectiveCeilingDb)
        val gr = minOf(profile.levelDb(1, org.determine.core.hearing.Ear.RIGHT) ?: GAIN_MUTE, profile.effectiveCeilingDb)
        sendCue(CueCommand(CueId.READY, Side.BOTH, g, gr, Haptic.NONE, 0, 0, 0, config.cue.ttlMs), now, null)
    }

    private fun emitObstacleCue(now: Long) {
        if (!obstacleCuesAllowed()) { exploring = false; return }
        if (tuning.exploreEnabled) { emitExploreCue(now); return }
        val onset = onsetPending
        val cmd = scheduler.update(alert, onset, profile, now) ?: return
        onsetPending = false
        val cap = if (onset) onsetCapturePhoneUs else null
        sendCue(cmd, now, cap)
        if (onset) {
            cap?.let { val ms = (now - it) / 1000.0; lastCueLatencyMs = ms; captureToCue.add(ms) }
            if (spearcons) alert?.let { alerts.speak(CueScheduler.spearcon(it)) }
        }
    }

    /**
     * EXPERIMENTAL exploration (Tuning.exploreEnabled): band cues, spearcons and phone audio are replaced by one
     * PULSE40 haptic per explorePeriodMs whose intensity grows linearly from exploreMinIntensity at exploreMaxMm to
     * 255 at 0 mm (the board clamps nothing: sendCue clamps to haptic_max). Beyond exploreMaxMm, with NO_TARGET,
     * UNKNOWN or a stale RANGE: silence — never an "all clear".
     */
    private fun emitExploreCue(now: Long) {
        onsetPending = false
        val mm = (latestReading as? ForwardRange.Target)?.mm?.takeIf { it <= tuning.exploreMaxMm && !rangeStale(now) }
        if (mm == null) { exploring = false; nextExploreUs = 0L; return }
        exploring = true
        if (now < nextExploreUs) return
        val periodUs = tuning.explorePeriodMs * 1000L
        nextExploreUs = if (now - nextExploreUs >= periodUs) now + periodUs else nextExploreUs + periodUs
        val max = tuning.exploreMaxMm
        val min = tuning.exploreMinIntensity
        val intensity = min + ((255 - min) * (max - mm.coerceIn(0, max)) + max / 2) / max
        sendCue(
            CueCommand(CueId.OBSTACLE_FAR, Side.BOTH, GAIN_MUTE, GAIN_MUTE, Haptic.PULSE40, intensity, intensity, 0, config.cue.ttlMs),
            now, null, localAudio = false,
        )
    }

    private fun obstacleCuesAllowed() = state == SystemState.READY || state == SystemState.DEGRADED

    private fun runPolicy(reading: ForwardRange, boardTimeUs: Long, capturePhoneUs: Long, now: Long) {
        val next = policy.step(reading, yawDegPerS > config.scanYawDegPerS, boardTimeUs)
        if (policy.takeOnset()) { onsetPending = true; onsetCapturePhoneUs = capturePhoneUs }
        if (next != alert) {
            alert = next
            if (obstacleCuesAllowed()) listener.onAlert(next)
        }
        // Urgent warnings never wait for the next tick.
        emitObstacleCue(now)
    }

    /**
     * One warning on both outputs: the phone plays the audio immediately (its return value is that cue's audio
     * ACK), and when the command carries a haptic pattern the board gets a CUE packet whose ACK is awaited.
     * Cues without haptics (READY tick, calibration tones) never touch the wire: the board has no audio to play.
     * With warning sounds intentionally off (HAPTIC_ONLY) the sink is not called at all, so a missing headset is
     * neither required nor a failure. Calibration has its own local-only playback path.
     */
    private fun sendCue(cmd: CueCommand, now: Long, captureUs: Long?, localAudio: Boolean = true): Long {
        val ceil = profile.effectiveCeilingDb
        fun clamp(g: Int) = if (g == GAIN_MUTE) GAIN_MUTE else minOf(g, ceil).coerceAtLeast(-127) // invariant 4
        val gl = clamp(cmd.gainLDb)
        val gr = clamp(cmd.gainRDb)
        val hl = minOf(cmd.hapticL, profile.hapticMax)
        val hr = minOf(cmd.hapticR, profile.hapticMax)
        val seq = nextCmdSeq()
        cuesSent++
        if (audioRequired && localAudio) {
            playLocal(localCueSink, cmd.copy(gainLDb = gl, gainRDb = gr, hapticL = hl, hapticR = hr), now)
        }
        if (cmd.hapticPattern != Haptic.NONE) {
            // Invariant 5: deadline 0 when unsynced → the board drops the cue (never plays late).
            val deadline = cue.clock.takeIf { it.synced(now) }?.toBoard(now)?.let { it + cmd.ttlMs * 1000L } ?: 0L
            val pkt = CuePkt(now, deadline, cmd.cueId, cmd.side, cmd.hapticPattern, hl, hr, cmd.flags, cmd.ttlMs)
            pending[seq] = Pending(now, false, captureUs)
            outbox.send(BoardId.CUE, Wire.encode(pkt, seq))
        }
        return seq
    }

    /**
     * The local sink's return value is this cue's audio ACK. Any good play confirms the audio path; a bad
     * status or an exception counts towards the same ACK-failure escalation as the board.
     */
    private fun playLocal(sink: LocalCueSink, cmd: CueCommand, now: Long): Boolean {
        val status = try { sink.play(cmd, now) } catch (_: RuntimeException) { null }
        if (status == AUDIO_ROUTE_LOST) { nacks++; audioRouteLost = true; return false }
        if (status == null) { missedAcks++; audioFailures++; return false }
        val applied = recordStatus(status, board = false)
        if (applied) audioRouteLost = false
        return applied
    }

    /**
     * Audio-path stand-in for a PROFILE ACK: a muted, haptic-free READY command checks the phone → headset
     * route without making a sound, every [EngineConfig.profileRetryUs] even while idle or paused.
     */
    private fun probeLocalSink(now: Long) {
        playLocal(
            localCueSink,
            CueCommand(CueId.READY, Side.BOTH, GAIN_MUTE, GAIN_MUTE, Haptic.NONE, 0, 0, 0, config.cue.ttlMs),
            now,
        )
        nextProbeUs = now + config.profileRetryUs
    }

    private fun sendProfile(now: Long) {
        val seq = nextCmdSeq()
        pending[seq] = Pending(now, true, null)
        outbox.send(BoardId.CUE, Wire.encode(ProfilePkt(profile.mode.wire, profile.hapticMax), seq))
        profileDirty = false
        nextProfileUs = now + config.profileRetryUs
    }

    private fun onAck(a: Ack, rx: Long) {
        val p = pending.remove(a.cueSeq) ?: return // late (already counted missing) or unknown
        if (recordStatus(a.status, board = true)) ackLatency.add((rx - p.issuedUs) / 1000.0)
    }

    /**
     * Board ACK ([board] = true) or local-sink result → counters and that channel's link state. Returns true for a
     * good (applied) status. Each channel keeps its own failure streak.
     */
    private fun recordStatus(status: Int, board: Boolean): Boolean {
        return when (status) {
            AckStatus.APPLIED, AckStatus.APPLIED_CLAMPED -> {
                acks++
                if (board) { boardFailures = 0; boardConfirmed = true } else { audioFailures = 0; audioConfirmed = true }
                true
            }
            AckStatus.BUSY_DROPPED -> { nacks++; false } // busy with a higher-priority cue; path alive
            else -> { // EXPIRED, NO_SYNC, BAD_CUE, unknown/negative: that cue path is not working
                nacks++
                if (board) boardFailures++ else audioFailures++
                false
            }
        }
    }

    private fun nextCmdSeq(): Long { val s = cmdSeq; cmdSeq = (cmdSeq + 1) and 0xFFFFFFFFL; return s }

    private fun expireAcks(now: Long) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.issuedUs > config.ackTimeoutUs) {
                it.remove()
                missedAcks++
                boardFailures++
            }
        }
    }

    private fun boardDiag(l: BoardLink, now: Long) = BoardDiagnostics(
        l.id, l.lastBeatRxUs?.let { (now - it) / 1000 }, l.clock.offsetUs, l.clock.rttUs, l.clock.synced(now), l.faults, l.rssi,
    )

    fun diagnostics(now: Long): Diagnostics {
        val rangeFresh = !rangeStale(now)
        return Diagnostics(
            nowPhoneUs = now, state = state, cam = boardDiag(cam, now), cue = boardDiag(cue, now),
            frameAgeMs = latestFrame?.let { (now - it.capturePhoneUs) / 1000 },
            rangeAgeMs = latestRangeCapturePhoneUs?.let { (now - it) / 1000 },
            rangeMm = latestRange?.mm, rangeStatus = latestRange?.status,
            lastAlert = alert, lastCueLatencyMs = lastCueLatencyMs,
            captureToCueP50Ms = captureToCue.percentile(50.0), captureToCueP95Ms = captureToCue.percentile(95.0),
            ackP50Ms = ackLatency.percentile(50.0), ackP95Ms = ackLatency.percentile(95.0),
            cuesSent = cuesSent, acks = acks, nacks = nacks, missedAcks = missedAcks, malformed = malformed, staleDropped = staleDropped,
            frameSource = config.frameSource, ignoredCamPackets = ignoredCamPackets,
            linkWeak = linkWeak,
            closingSpeedMmPerS = if (rangeFresh) policy.closingSpeedMmPerS else null,
            approachActive = rangeFresh && policy.approachActive,
            exploring = exploring,
        )
    }
}

/** Rolling latency window (last [capacity] samples) with nearest-rank percentiles. */
class LatencyStats(private val capacity: Int = 200) {
    private val samples = ArrayDeque<Double>()

    fun add(ms: Double) {
        synchronized(samples) {
            samples.addLast(ms)
            if (samples.size > capacity) samples.removeFirst()
        }
    }

    fun percentile(p: Double): Double? = synchronized(samples) {
        if (samples.isEmpty()) return null
        val sorted = samples.sorted()
        sorted[(kotlin.math.ceil(p / 100.0 * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]
    }
}
