package org.determine.core

import org.determine.core.clock.ClockSync
import org.determine.core.cue.CueConfig
import org.determine.core.cue.CueScheduler
import org.determine.core.hearing.HearingProfile
import org.determine.core.ingest.FrameAssembler
import org.determine.core.ingest.FreshnessGate
import org.determine.core.policy.HazardPolicy
import org.determine.core.policy.PolicyConfig
import org.determine.core.range.RangeCorridor
import org.determine.core.range.RangeReading
import org.determine.core.range.RegionMasks
import org.determine.core.range.RegionRange
import org.determine.core.system.MonitorConfig
import org.determine.core.system.MonitorInputs
import org.determine.core.system.SystemMonitor
import org.determine.core.wire.Ack
import org.determine.core.wire.AckStatus
import org.determine.core.wire.Beat
import org.determine.core.wire.CueId
import org.determine.core.wire.CuePkt
import org.determine.core.wire.FramePkt
import org.determine.core.wire.GAIN_MUTE
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
    val monitor: MonitorConfig = MonitorConfig(),
    val masks: RegionMasks = RegionMasks.DEFAULT,
    val initialProfile: HearingProfile = HearingProfile.uncalibrated(),
    /** PROTOCOL.md: PING 10 Hz per board (also the cue board's watchdog feed). */
    val pingIntervalUs: Long = 100_000,
    /** Arch §4.2 freshness bound for FRAME/RANGE (capture age and receive age). */
    val freshnessUs: Long = 500_000,
    val ackTimeoutUs: Long = 300_000,
    val profileRetryUs: Long = 1_000_000,
    /** Arch §8.1: SYSTEM OFF repeats every 5 s until resolved. */
    val systemOffRepeatUs: Long = 5_000_000,
    /** PROTOCOL.md watchdog level: min(ceiling, −20 dBFS). */
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
    /** Transducers-missing fallback: PHONE_AUDIO plays every cue through the [LocalCueSink]; the cue board stays for ToF. */
    val cueOutput: CueOutput = CueOutput.GLASSES,
)

/** A BEAT whose board time is this much older than the newest is a reboot, not UDP reordering. */
internal const val REBOOT_JUMP_US = 1_000_000L

/**
 * The phone's single integrator (Arch §4): ingests board packets, keeps clocks, runs the hazard policy and the state
 * machine, and sends CUE/PROFILE/PING. Not thread-safe: serialise every call. Callbacks run synchronously.
 * The phone decides every hazard; the wearable decides nothing (invariant 3).
 *
 * Fallbacks: with [EngineConfig.frameSource] = PHONE_CAMERA there is no camera board (no BEAT/PING/clock for it) and
 * the host feeds [onPhoneFrame]; with [EngineConfig.cueOutput] = PHONE_AUDIO every cue goes to [localCueSink]
 * (required then) and its return value stands in for the board ACK.
 */
class Engine(
    val config: EngineConfig,
    private val outbox: Outbox,
    private val alerts: PhoneAlerts,
    private val listener: EngineListener,
    localCueSink: LocalCueSink? = null,
) {
    /** Non-null exactly when cues are played on the phone. */
    private val localSink: LocalCueSink? = if (config.cueOutput == CueOutput.PHONE_AUDIO) {
        requireNotNull(localCueSink) { "cueOutput = PHONE_AUDIO needs a LocalCueSink" }
    } else {
        null
    }
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
    private val corridor = RangeCorridor(config.masks)
    private val policy = HazardPolicy(config.policy)
    private val scheduler = CueScheduler(config.cue)
    private val monitor = SystemMonitor(config.monitor)

    var profile: HearingProfile = config.initialProfile; private set
    val state: SystemState get() = monitor.state

    private var paused = false
    private var batteryCritical = false
    private var yawDegPerS = 0f
    private var spearcons = config.spearcons

    private var latestFrame: AssembledFrame? = null
    private var latestRange: RangeSnapshot? = null
    private var latestRangeCapturePhoneUs: Long? = null
    private var latestReading: RangeReading? = null
    private var detections: List<Detection> = emptyList()
    private var detectionsCapturePhoneUs: Long? = null
    private var phoneFrameId = 0L
    private var lastTickUs: Long? = null

    private var alert: Alert? = null
    private var onsetPending = false
    private var onsetCapturePhoneUs: Long? = null

    // CUE and PROFILE share one seq counter so every ACK.cue_seq is unambiguous.
    private var cmdSeq = 0L
    private class Pending(val issuedUs: Long, val isProfile: Boolean, val captureUs: Long?)
    private val pending = LinkedHashMap<Long, Pending>()
    private var ackFailures = 0
    private var cueLinkConfirmed = false
    private var profileDirty = true
    private var nextProfileUs = Long.MIN_VALUE
    private var nextSystemOffUs = Long.MAX_VALUE
    private var nextDiagUs = Long.MIN_VALUE

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
        if (from == BoardId.CUE && reconnected && localSink == null) {
            // PROFILE after every cue-board (re)connect; the link is unconfirmed until it is ACKed.
            // PHONE_AUDIO: the board plays nothing, so it gets no PROFILE; the local sink confirms the cue path.
            cueLinkConfirmed = false
            profileDirty = true
            sendProfile(rx)
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
        val capPhone = cue.clock.toPhone(r.tUs)
        if (!gate.isFresh(capPhone, rx, rx)) { staleDropped++; return }
        latestRange = RangeSnapshot(r.tUs, rx, r.mm, r.status)
        latestRangeCapturePhoneUs = capPhone
        val reading = corridor.evaluate(r.mm, r.status)
        latestReading = reading
        runPolicy(policy.observe(reading), capPhone!!, rx)
    }

    /** [capturePhoneUs] is the frame's [AssembledFrame.capturePhoneUs]; detections older than the freshness bound are dropped. */
    fun onDetections(frameId: Long, capturePhoneUs: Long, detections: List<Detection>, nowPhoneUs: Long) {
        if (nowPhoneUs - capturePhoneUs > config.freshnessUs) { staleDropped++; return }
        this.detections = detections
        detectionsCapturePhoneUs = capturePhoneUs
        // Without usable ToF, the camera may only give relative cues (bbox growth), never distances (Arch §7.1).
        if (!tofUsable(nowPhoneUs)) runPolicy(policy.relativeObservations(detections, capturePhoneUs), capturePhoneUs, nowPhoneUs)
    }

    private fun tofUsable(now: Long): Boolean {
        val cap = latestRangeCapturePhoneUs ?: return false
        val reading = latestReading ?: return false
        return now - cap <= config.freshnessUs && reading.of(Region.CENTER) != RegionRange.Unknown
    }

    private fun freshDetections(now: Long): List<Detection> {
        val cap = detectionsCapturePhoneUs ?: return emptyList()
        return if (now - cap <= config.freshnessUs) detections else emptyList()
    }

    private fun runPolicy(obs: Map<Region, org.determine.core.policy.RegionObservation>, capturePhoneUs: Long, now: Long) {
        val next = policy.step(obs, freshDetections(now), yawDegPerS > config.scanYawDegPerS, now)
        if (policy.takeOnset()) { onsetPending = true; onsetCapturePhoneUs = capturePhoneUs }
        if (next != alert) {
            alert = next
            if (obstacleCuesAllowed()) listener.onAlert(next)
        }
        // Urgent warnings never wait for the next tick.
        emitObstacleCue(now)
    }

    private fun obstacleCuesAllowed() = state == SystemState.READY || state == SystemState.DEGRADED

    private fun onAck(a: Ack, rx: Long) {
        val p = pending.remove(a.cueSeq) ?: return // late (already counted missing) or unknown
        if (recordStatus(a.status, confirmsLink = p.isProfile)) ackLatency.add((rx - p.issuedUs) / 1000.0)
    }

    /** Board ACK or local-sink result → counters and link state. Returns true for a good (played) status. */
    private fun recordStatus(status: Int, confirmsLink: Boolean): Boolean {
        return when (status) {
            AckStatus.PLAYED, AckStatus.PLAYED_CLAMPED -> {
                acks++
                ackFailures = 0
                if (confirmsLink) cueLinkConfirmed = true
                true
            }
            AckStatus.BUSY_DROPPED -> { nacks++; false } // busy with a higher-priority sound; cue path alive
            else -> { nacks++; ackFailures++; false } // EXPIRED, NO_SYNC, BAD_CUE, unknown/negative: cue path not working
        }
    }

    // ------------------------------------------------------------------ controls

    fun setProfile(profile: HearingProfile) {
        this.profile = profile
        profileDirty = true
        nextProfileUs = Long.MIN_VALUE
    }

    fun setPaused(paused: Boolean) { this.paused = paused }
    fun setBatteryCritical(critical: Boolean) { batteryCritical = critical }
    fun setHeadYawRate(degPerS: Float) { yawDegPerS = kotlin.math.abs(degPerS) }
    fun setSpearcons(enabled: Boolean) { spearcons = enabled }
    fun latestJpeg(): AssembledFrame? = latestFrame
    fun latestRange(): RangeSnapshot? = latestRange

    /** Calibration (Arch §9): sends [cmd] as-is apart from gains clamped to the ceiling; returns the CUE seq. */
    fun sendCalibrationCue(cmd: CueCommand, nowPhoneUs: Long): Long = sendCue(cmd, nowPhoneUs, null)

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
        if (localSink == null) {
            if ((profileDirty || !cueLinkConfirmed || state == SystemState.UNAVAILABLE) && now >= nextProfileUs) sendProfile(now)
        } else if ((!cueLinkConfirmed || state == SystemState.UNAVAILABLE) && now >= nextProfileUs) {
            probeLocalSink(now)
        }

        monitor.evaluate(inputs(now))?.let { onTransition(it, now) }

        if (state == SystemState.UNAVAILABLE && now >= nextSystemOffUs) systemOff(now)
        emitObstacleCue(now)

        if (now >= nextDiagUs) {
            nextDiagUs = now + config.diagnosticsIntervalUs
            listener.onDiagnostics(diagnostics(now))
        }
    }

    private fun inputs(now: Long) = MonitorInputs(
        nowUs = now,
        camBeatAgeUs = cam.lastBeatRxUs?.let { now - it },
        cueBeatAgeUs = cue.lastBeatRxUs?.let { now - it },
        frameAgeUs = latestFrame?.let { now - it.capturePhoneUs },
        rangeAgeUs = latestRangeCapturePhoneUs?.let { now - it },
        corridorKnown = latestReading?.of(Region.CENTER)?.let { it != RegionRange.Unknown } ?: false,
        corridorUnknownFraction = latestReading?.corridorUnknownFraction ?: 1f,
        clocksSynced = cue.clock.synced(now) && (!camBoardUsed || cam.clock.synced(now)),
        cueLinkConfirmed = cueLinkConfirmed,
        ackFailures = ackFailures,
        batteryCritical = batteryCritical,
        paused = paused,
        camBoardRequired = camBoardUsed,
    )

    private fun onTransition(t: SystemMonitor.Transition, now: Long) {
        listener.onState(t.to, t.reason)
        when (t.to) {
            SystemState.UNAVAILABLE -> {
                // Failure is loud (invariant 2): suppress obstacle cues, system-off on every surviving output.
                clearAlert()
                systemOff(now)
            }
            SystemState.READY -> {
                if (t.from == SystemState.UNAVAILABLE) alerts.speak("system ready")
                // Arch §8: READY is one soft tick, once — not on every DEGRADED→READY flap.
                if (t.from == SystemState.STARTING || t.from == SystemState.UNAVAILABLE) sendReadyTick(now)
                alert?.let { listener.onAlert(it) }
            }
            SystemState.DEGRADED -> {
                // Announce once per entry; only supported claims.
                alerts.speak(if (t.from == SystemState.UNAVAILABLE) "system ready, distance sensor limited" else "distance sensor limited")
                alert?.let { if (t.from != SystemState.READY) listener.onAlert(it) }
            }
            SystemState.PAUSED -> { clearAlert(); alerts.speak("paused") }
            SystemState.STARTING -> if (t.from == SystemState.PAUSED) alerts.speak("resumed")
        }
        if (t.to != SystemState.UNAVAILABLE) nextSystemOffUs = Long.MAX_VALUE
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
        // Cue output too, if it may still be alive (any surviving output, Arch §4.2): the cue board, or the local sink.
        val ceil = profile.effectiveCeilingDb
        val g = minOf(ceil, config.systemOffGainDb)
        val hm = profile.hapticMax
        sendCue(CueCommand(CueId.SYSTEM_OFF, Side.BOTH, g, g, Haptic.LSS, hm, hm, 1, config.cue.ttlMs), now, null)
        nextSystemOffUs = now + config.systemOffRepeatUs
    }

    private fun sendReadyTick(now: Long) {
        val g = minOf(profile.levelDb(1, org.determine.core.hearing.Ear.LEFT) ?: -128, profile.effectiveCeilingDb)
        val gr = minOf(profile.levelDb(1, org.determine.core.hearing.Ear.RIGHT) ?: -128, profile.effectiveCeilingDb)
        sendCue(CueCommand(CueId.READY, Side.BOTH, g, gr, Haptic.NONE, 0, 0, 0, config.cue.ttlMs), now, null)
    }

    private fun emitObstacleCue(now: Long) {
        if (!obstacleCuesAllowed()) return
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

    private fun sendCue(cmd: CueCommand, now: Long, captureUs: Long?): Long {
        val ceil = profile.effectiveCeilingDb
        fun clamp(g: Int) = if (g == -128) -128 else minOf(g, ceil).coerceAtLeast(-127) // invariant 4
        val gl = clamp(cmd.gainLDb)
        val gr = clamp(cmd.gainRDb)
        val hl = minOf(cmd.hapticL, profile.hapticMax)
        val hr = minOf(cmd.hapticR, profile.hapticMax)
        val seq = nextCmdSeq()
        cuesSent++
        val sink = localSink
        if (sink != null) {
            playLocal(sink, cmd.copy(gainLDb = gl, gainRDb = gr, hapticL = hl, hapticR = hr), now)
            return seq
        }
        // Invariant 5: deadline 0 when unsynced → the board drops the cue (never plays late).
        val deadline = cue.clock.takeIf { it.synced(now) }?.toBoard(now)?.let { it + cmd.ttlMs * 1000L } ?: 0L
        val pkt = CuePkt(now, deadline, cmd.cueId, cmd.side, gl, gr, cmd.hapticPattern, hl, hr, cmd.flags, cmd.ttlMs)
        pending[seq] = Pending(now, false, captureUs)
        outbox.send(BoardId.CUE, Wire.encode(pkt, seq))
        return seq
    }

    /**
     * PHONE_AUDIO: the sink's return value is this cue's ACK. Any good play confirms the cue path (it replaces the
     * PROFILE ACK); a bad status or an exception counts towards the same ACK-failure escalation as the board.
     */
    private fun playLocal(sink: LocalCueSink, cmd: CueCommand, now: Long) {
        val status = try { sink.play(cmd, now) } catch (_: RuntimeException) { null }
        if (status == null) { missedAcks++; ackFailures++; return }
        recordStatus(status, confirmsLink = true)
    }

    /**
     * PHONE_AUDIO stand-in for PROFILE: a muted, haptic-free READY command proves the phone output path is open
     * without making a sound. Sent until the first good play and every [EngineConfig.profileRetryUs] while UNAVAILABLE.
     */
    private fun probeLocalSink(now: Long) {
        playLocal(localSink!!, CueCommand(CueId.READY, Side.BOTH, GAIN_MUTE, GAIN_MUTE, Haptic.NONE, 0, 0, 0, config.cue.ttlMs), now)
        nextProfileUs = now + config.profileRetryUs
    }

    private fun sendProfile(now: Long) {
        val seq = nextCmdSeq()
        pending[seq] = Pending(now, true, null)
        outbox.send(BoardId.CUE, Wire.encode(ProfilePkt(profile.effectiveCeilingDb, profile.mode.wire, profile.hapticMax), seq))
        profileDirty = false
        nextProfileUs = now + config.profileRetryUs
    }

    private fun nextCmdSeq(): Long { val s = cmdSeq; cmdSeq = (cmdSeq + 1) and 0xFFFFFFFFL; return s }

    private fun expireAcks(now: Long) {
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.issuedUs > config.ackTimeoutUs) {
                it.remove()
                missedAcks++
                ackFailures++
            }
        }
    }

    private fun boardDiag(l: BoardLink, now: Long) = BoardDiagnostics(
        l.id, l.lastBeatRxUs?.let { (now - it) / 1000 }, l.clock.offsetUs, l.clock.rttUs, l.clock.synced(now), l.faults, l.rssi,
    )

    fun diagnostics(now: Long) = Diagnostics(
        nowPhoneUs = now, state = state, cam = boardDiag(cam, now), cue = boardDiag(cue, now),
        frameAgeMs = latestFrame?.let { (now - it.capturePhoneUs) / 1000 },
        rangeAgeMs = latestRangeCapturePhoneUs?.let { (now - it) / 1000 },
        lastAlert = alert, lastCueLatencyMs = lastCueLatencyMs,
        captureToCueP50Ms = captureToCue.percentile(50.0), captureToCueP95Ms = captureToCue.percentile(95.0),
        ackP50Ms = ackLatency.percentile(50.0), ackP95Ms = ackLatency.percentile(95.0),
        cuesSent = cuesSent, acks = acks, nacks = nacks, missedAcks = missedAcks, malformed = malformed, staleDropped = staleDropped,
        frameSource = config.frameSource, cueOutput = config.cueOutput, ignoredCamPackets = ignoredCamPackets,
    )
}

/** Rolling latency window (last [capacity] samples) with nearest-rank percentiles. */
class LatencyStats(private val capacity: Int = 200) {
    private val buf = ArrayDeque<Double>()
    fun add(ms: Double) { buf.addLast(ms); if (buf.size > capacity) buf.removeFirst() }
    val count: Int get() = buf.size
    fun percentile(p: Double): Double? {
        if (buf.isEmpty()) return null
        val s = buf.sorted()
        val rank = Math.ceil(p / 100.0 * s.size).toInt().coerceIn(1, s.size)
        return s[rank - 1]
    }
}
