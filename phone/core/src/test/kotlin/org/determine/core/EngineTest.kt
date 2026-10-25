package org.determine.core

import org.determine.core.hearing.HearingProfile
import org.determine.core.system.MonitorInputs
import org.determine.core.system.SystemMonitor
import org.determine.core.wire.Ack
import org.determine.core.wire.AckStatus
import org.determine.core.wire.Beat
import org.determine.core.wire.CueId
import org.determine.core.wire.CuePkt
import org.determine.core.wire.FramePkt
import org.determine.core.wire.Haptic
import org.determine.core.wire.Ping
import org.determine.core.wire.Pong
import org.determine.core.wire.ProfilePkt
import org.determine.core.wire.RangePkt
import org.determine.core.wire.RangeStatus
import org.determine.core.wire.Wire
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SystemMonitorTest {
    private fun ok(now: Long) = MonitorInputs(
        nowUs = now, camBeatAgeUs = 50_000, cueBeatAgeUs = 50_000, frameAgeUs = 100_000, rangeAgeUs = 70_000,
        tofKnown = true, clocksSynced = true, cueLinkConfirmed = true,
        ackFailures = 0, batteryCritical = false, paused = false,
    )

    @Test
    fun `starting needs everything, then degraded and unavailable boundaries`() {
        val m = SystemMonitor()
        assertNull(m.evaluate(ok(0).copy(cueLinkConfirmed = false)))
        assertNull(m.evaluate(ok(1).copy(rangeAgeUs = null)))
        assertEquals(SystemState.READY, m.evaluate(ok(2))!!.to)
        assertEquals("tof_unknown", m.evaluate(ok(3).copy(tofKnown = false))!!.reason)
        assertEquals(SystemState.READY, m.evaluate(ok(4).copy(frameAgeUs = 500_000))!!.to)  // bound inclusive
        assertEquals("frame_stale", m.evaluate(ok(5).copy(frameAgeUs = 500_001))!!.reason)
        assertEquals(SystemState.READY, m.evaluate(ok(6))!!.to)
        assertEquals("cam_beat_missing", m.evaluate(ok(7).copy(camBeatAgeUs = 500_001))!!.reason)
        assertEquals("recovered", m.evaluate(ok(8))!!.reason)
        assertNull(m.evaluate(ok(9).copy(ackFailures = 2)))
        assertEquals("cue_ack_missing", m.evaluate(ok(10).copy(ackFailures = 3))!!.reason)
        m.evaluate(ok(11))
        assertEquals("battery_critical", m.evaluate(ok(12).copy(batteryCritical = true))!!.reason)
        m.evaluate(ok(13))
        assertEquals("range_stale", m.evaluate(ok(14).copy(rangeAgeUs = null))!!.reason)
    }

    @Test
    fun `a fresh unknown reading starts degraded without an unavailable timeout`() {
        val m = SystemMonitor()
        val t = m.evaluate(ok(0).copy(tofKnown = false))!!
        assertEquals(SystemState.DEGRADED, t.to)
        assertEquals("tof_unknown", t.reason)
        assertNull(m.evaluate(ok(11_000_000).copy(tofKnown = false)))
        assertEquals(SystemState.READY, m.evaluate(ok(11_000_001))!!.to)
    }

    @Test
    fun `paused only explicitly and startup times out loudly`() {
        val m = SystemMonitor()
        assertEquals(SystemState.PAUSED, m.evaluate(ok(0).copy(paused = true))!!.to)
        assertEquals(SystemState.STARTING, m.evaluate(ok(10).copy(cueLinkConfirmed = false))!!.to)
        assertNull(m.evaluate(ok(10_000_010).copy(cueLinkConfirmed = false)))
        val t = m.evaluate(ok(10_000_011).copy(cueLinkConfirmed = false))!!
        assertEquals(SystemState.UNAVAILABLE, t.to)
        assertTrue(t.reason.startsWith("startup_timeout"))
    }
}

/**
 * Two simulated boards wired to an Engine, all in virtual time. The cue board is haptic-only: it answers PING,
 * PROFILE and CUE (APPLIED) and streams the single forward RANGE zone. Every sound goes to [sink], the stand-in
 * for the phone → headset audio route.
 */
class Rig(config: EngineConfig = EngineConfig(), val sink: TestSink = TestSink()) {
    var now = 1_000_000_000L
    var camOffset = 7_000_000L - now
    val cueOffset = 3_000_000L - now
    var camAlive = true
    var cueAlive = true
    var acksAlive = true
    var cueBeatT0 = 0L
    var mm = 0
    var status = RangeStatus.NO_TARGET
    var rangeDelayUs = 0L
    /** False = the cue board keeps beating and answering but sends no RANGE packets (sensor silence). */
    var rangeAlive = true
    /** RSSI the cue board reports in its BEATs. */
    var cueRssi = -50

    val states = mutableListOf<Pair<SystemState, String>>()
    val alertsOut = mutableListOf<Alert?>()
    val spoken = mutableListOf<String>()
    var systemOffs = 0
    val cues = mutableListOf<Pair<Long, CuePkt>>()
    val profiles = mutableListOf<ProfilePkt>()
    private val inbox = ArrayDeque<Pair<BoardId, ByteArray>>()
    private var seq = 0L
    private var nextBeat = 0L
    private var nextRange = 0L
    private var frameId = 0L

    val engine = Engine(
        config,
        { b, p -> inbox.addLast(b to p) },
        object : PhoneAlerts {
            override fun speak(text: String) { spoken += text }
            override fun vibrate(pattern: LongArray) {}
            override fun systemOff() { systemOffs++ }
        },
        object : EngineListener {
            override fun onState(state: SystemState, reason: String) { states += state to reason }
            override fun onAlert(alert: Alert?) { alertsOut += alert }
            override fun onDiagnostics(d: Diagnostics) {}
        },
        sink,
    )

    private fun deliver(b: BoardId, p: org.determine.core.wire.Packet, delay: Long = 1_000) {
        val bytes = Wire.encode(p, seq++)
        engine.onPacket(b, bytes, bytes.size, now + delay)
    }

    fun step() {
        // boards answer what the phone sent
        while (inbox.isNotEmpty()) {
            val (b, bytes) = inbox.removeFirst()
            val alive = if (b == BoardId.CAM) camAlive else cueAlive
            val off = if (b == BoardId.CAM) camOffset else cueOffset
            when (val p = Wire.decode(bytes).let { it.seq to it.packet }.second) {
                // board receives 0.5 ms after send and replies 0.5 ms before phone receive: symmetric, offset exact
                is Ping -> if (alive) deliver(b, Pong(p.tPhoneUs, p.tPhoneUs + off + 500, now + off + 500))
                // record the phone's real issue time: the rig drains the outbox one step (10 ms) after tick()
                is CuePkt -> { cues += p.tIssuedPhoneUs to p; if (alive && acksAlive) deliver(b, Ack(Wire.decode(bytes).seq, AckStatus.APPLIED, now + off)) }
                is ProfilePkt -> { profiles += p; if (alive && acksAlive) deliver(b, Ack(Wire.decode(bytes).seq, AckStatus.APPLIED, now + off)) }
                else -> Unit
            }
        }
        if (now >= nextBeat) {
            nextBeat = now + 100_000
            if (camAlive) {
                deliver(BoardId.CAM, Beat(1, 1, 0, now + camOffset, -50, 40, 100))
                val jpeg = ByteArray(2000) { it.toByte() }
                deliver(BoardId.CAM, FramePkt(frameId, now + camOffset - 30_000, 320, 240, 2000, 0, 0, 2, jpeg.copyOfRange(0, 1400)))
                deliver(BoardId.CAM, FramePkt(frameId++, now + camOffset - 30_000, 320, 240, 2000, 1400, 1, 2, jpeg.copyOfRange(1400, 2000)))
            }
            if (cueAlive) deliver(BoardId.CUE, Beat(2, 1, 0, now + cueOffset - cueBeatT0, cueRssi, 40, 100))
        }
        if (now >= nextRange) {
            nextRange = now + 66_667
            if (cueAlive && rangeAlive) deliver(BoardId.CUE, RangePkt(now + cueOffset - 20_000 - rangeDelayUs, mm, status))
        }
        engine.tick(now)
        now += 10_000
    }

    fun run(us: Long) { val end = now + us; while (now < end) step() }

    /** Camera board reboots: its clock restarts with a new offset and frame_id counts from 0 again. */
    fun rebootCam(newOffset: Long) { camOffset = newOffset; frameId = 0 }

    /** One valid forward reading at [distanceMm], or NO_TARGET for null. */
    fun obstacle(distanceMm: Int?) {
        if (distanceMm != null) { mm = distanceMm; status = RangeStatus.VALID } else { mm = 0; status = RangeStatus.NO_TARGET }
    }

    fun obstacleCues(since: Long = 0) = cues.filter { it.first >= since && it.second.cueId in CueId.OBSTACLE_FAR..CueId.OBSTACLE_NEAR }
    fun obstacleSounds(since: Long = 0) = sink.plays.filter { it.first >= since && it.second.cueId in CueId.OBSTACLE_FAR..CueId.OBSTACLE_NEAR }
}

class EngineTest {
    private fun readyRig(cfg: EngineConfig = EngineConfig(), sink: TestSink = TestSink()) =
        Rig(cfg, sink).also { it.run(1_000_000); assertEquals(SystemState.READY, it.engine.state, it.states.toString()) }

    @Test
    fun `camera reboot with restarted frame ids and a new clock recovers to ready`() {
        val r = readyRig()
        r.run(20_000_000) // ~200 frames: fewer than the assembler's 1000-frame reboot jump
        r.camAlive = false
        r.run(1_000_000)
        assertEquals(SystemState.UNAVAILABLE, r.engine.state)
        r.rebootCam(newOffset = 50_000L - r.now) // board time restarts near zero, so offset changes
        r.camAlive = true
        r.run(2_000_000)
        assertEquals(SystemState.READY, r.engine.state, r.states.takeLast(4).toString())
    }

    @Test
    fun `boots to ready with one audible ready tick, profile acked, and no board audio`() {
        val r = readyRig()
        assertEquals(listOf(SystemState.READY), r.states.map { it.first })
        assertEquals(1, r.sink.audible().count { it.second.cueId == CueId.READY })
        assertTrue(r.cues.none { it.second.cueId == CueId.READY }, "a haptic-free tick never goes to the board")
        assertTrue(r.profiles.isNotEmpty())
        assertNotNull(r.engine.latestJpeg())
        assertEquals(2000, r.engine.latestJpeg()!!.jpeg.size)
        assertNotNull(r.engine.latestRange())
    }

    @Test
    fun `not ready until the headset route plays, and the board haptic link is also required`() {
        val broken = TestSink(status = AckStatus.BAD_CUE)
        val r = Rig(sink = broken)
        r.run(2_000_000)
        assertEquals(SystemState.STARTING, r.engine.state)
        assertTrue(broken.audible().isEmpty(), "only muted probes before READY")
        assertTrue(broken.plays.size in 2..3, "probe at the 1 s retry cadence: ${broken.plays.size}")

        val noBoardAcks = Rig()
        noBoardAcks.acksAlive = false
        noBoardAcks.run(2_000_000)
        assertEquals(SystemState.STARTING, noBoardAcks.engine.state, "audio alone is not enough: haptics must be confirmed")
    }

    @Test
    fun `obstacle produces alert, audio and haptics at band cadence, spearcon once, scalar distance only`() {
        val r = readyRig()
        r.obstacle(700)
        val t0 = r.now
        r.run(1_000_000)
        assertEquals(Alert(Band.MID, 700), r.alertsOut.last())
        val sounds = r.obstacleSounds(t0)
        assertTrue(sounds.all { it.second.cueId == CueId.OBSTACLE_MID })
        assertTrue(sounds.size in 2..3, "MID 2.5/s over ~0.8 s: ${sounds.size}")
        val cues = r.obstacleCues(t0)
        assertEquals(sounds.size, cues.size, "every warning vibrates the temples as well as sounding in the headset")
        assertTrue(cues.all { it.second.cueId == CueId.OBSTACLE_MID && it.second.side == org.determine.core.wire.Side.BOTH })
        val (sentAt, c) = cues.first()
        assertEquals(1, c.flags)
        // deadline = board time at issue + 150 ms (offset estimate within the 1 ms symmetric delay error)
        val expected = sentAt + r.cueOffset + 150_000
        assertTrue(kotlin.math.abs(c.deadlineBoardUs - expected) < 2_000, "${c.deadlineBoardUs} vs $expected")
        assertEquals(listOf("obstacle ahead, 0.7 metres"), r.spoken)
        // obstacle disappears: exit after 600 ms of no-target, cues stop, never a "clear" sound
        r.obstacle(null)
        r.run(900_000)
        assertNull(r.alertsOut.last())
        val tEnd = r.now
        r.run(1_000_000)
        assertTrue(r.cues.none { it.first >= tEnd })
        assertTrue(r.obstacleSounds(tEnd).isEmpty())
        val d = r.engine.diagnostics(r.now)
        assertNotNull(d.captureToCueP50Ms); assertNotNull(d.ackP95Ms)
        assertTrue(d.captureToCueP95Ms!! < 400.0)
        assertEquals(RangeStatus.NO_TARGET, d.rangeStatus)
    }

    @Test
    fun `a target beyond 1200 mm reports nothing and diagnostics show the single forward measurement`() {
        val r = readyRig()
        r.obstacle(2500)
        r.run(1_000_000)
        assertTrue(r.obstacleCues().isEmpty())
        assertTrue(r.obstacleSounds().isEmpty())
        val d = r.engine.diagnostics(r.now)
        assertEquals(2500, d.rangeMm); assertEquals(RangeStatus.VALID, d.rangeStatus)
        assertNull(d.lastAlert)
    }

    @Test
    fun `camera down is unavailable, loud on headset and haptics, suppresses obstacle cues, recovers once`() {
        val r = readyRig()
        r.obstacle(400)
        r.run(500_000)
        assertTrue(r.obstacleCues().isNotEmpty())
        r.camAlive = false
        val tDown = r.now
        r.run(700_000)
        assertEquals(SystemState.UNAVAILABLE, r.engine.state)
        assertEquals(1, r.systemOffs)
        assertNull(r.alertsOut.last())
        val tUnavail = r.cues.first { it.second.cueId == CueId.SYSTEM_OFF }.first
        assertTrue(tUnavail - tDown <= 700_000)
        val sysOff = r.cues.first { it.second.cueId == CueId.SYSTEM_OFF }.second
        assertEquals(Haptic.LSS, sysOff.hapticPattern)
        val sysOffSound = r.sink.plays.first { it.second.cueId == CueId.SYSTEM_OFF }.second
        assertEquals(-20, sysOffSound.gainLDb) // min(ceiling -20, -20), on the phone's headset route
        assertTrue(r.obstacleCues(tUnavail + 1).isEmpty())
        r.run(5_000_000)
        assertEquals(2, r.systemOffs) // repeats every 5 s
        assertTrue(r.obstacleCues(tUnavail + 1).isEmpty())
        r.camAlive = true
        r.run(2_500_000)
        assertEquals(SystemState.READY, r.engine.state)
        assertEquals(1, r.spoken.count { it == "system ready" })
        assertTrue(r.obstacleCues(r.now - 500_000).isNotEmpty())
    }

    @Test
    fun `missing board acks escalate to unavailable`() {
        val r = readyRig()
        r.obstacle(400) // NEAR: 5 cues/s, so three unacked cues time out well inside the 1 s window
        r.run(400_000)
        r.acksAlive = false
        r.run(1_000_000)
        assertEquals(SystemState.UNAVAILABLE, r.engine.state)
        assertEquals("cue_ack_missing", r.states.last().second)
    }

    @Test
    fun `no headset at startup is unavailable at once, announced, and diagnostics still show the forward range`() {
        val sink = TestSink(status = AUDIO_ROUTE_LOST)
        val r = Rig(sink = sink)
        r.obstacle(450)
        r.run(1_000_000)
        assertEquals(SystemState.UNAVAILABLE to "headset_route_lost", r.states.last())
        assertTrue(r.systemOffs >= 1, "the phone's alarm path announces it; nothing is silent")
        assertTrue(sink.audible().all { it.second.cueId == CueId.SYSTEM_OFF }, "only the failure cue is attempted")
        assertTrue(r.obstacleCues().isEmpty() && r.obstacleSounds().isEmpty())
        val d = r.engine.diagnostics(r.now)
        assertEquals(450, d.rangeMm); assertEquals(RangeStatus.VALID, d.rangeStatus) // scalar diagnostics stay inspectable
        // headset appears: the 1 s probe confirms the route and the system recovers
        sink.status = AckStatus.APPLIED
        r.run(2_000_000)
        assertEquals(SystemState.READY, r.engine.state, r.states.toString())
    }

    @Test
    fun `headset loss in idle space is announced without waiting for an obstacle`() {
        val r = readyRig()
        r.sink.status = AUDIO_ROUTE_LOST
        r.run(1_100_000)
        assertEquals(SystemState.UNAVAILABLE to "headset_route_lost", r.states.last())
        assertTrue(r.systemOffs > 0)
        assertTrue(r.obstacleCues().isEmpty())
    }

    @Test
    fun `a headset reconnected while paused is rechecked before resume`() {
        val r = readyRig()
        r.sink.status = AUDIO_ROUTE_LOST
        r.run(1_100_000)
        r.engine.setPaused(true)
        r.run(100_000)
        r.sink.status = AckStatus.APPLIED
        r.run(1_100_000)
        val transitions = r.states.size
        r.engine.setPaused(false)
        r.run(100_000)
        assertEquals(SystemState.READY, r.engine.state)
        assertTrue(r.states.drop(transitions).none { it.first == SystemState.UNAVAILABLE })
    }

    @Test
    fun `a failed write that is not a route loss is a missing ack`() {
        val r = readyRig()
        r.sink.status = PHONE_ROUTE_FAILED
        r.obstacle(400)
        r.run(1_500_000)
        assertEquals("cue_ack_missing", r.states.last().second)
    }

    @Test
    fun `headset route failure is explicit - unavailable with system off, never a silent fallback`() {
        val r = readyRig()
        r.obstacle(700)
        r.run(300_000)
        r.sink.status = AUDIO_ROUTE_LOST
        val tFail = r.now
        r.run(1_500_000)
        val (state, reason) = r.states.last()
        assertEquals(SystemState.UNAVAILABLE, state)
        assertEquals("headset_route_lost", reason)
        assertEquals(1, r.systemOffs)
        assertNull(r.alertsOut.last())
        assertTrue(r.sink.plays.any { it.first >= tFail && it.second.cueId == CueId.SYSTEM_OFF })
        assertTrue(r.cues.any { it.second.cueId == CueId.SYSTEM_OFF }, "the board's haptics still announce the failure")

        r.sink.status = AckStatus.APPLIED
        r.run(3_000_000)
        assertEquals(SystemState.READY, r.engine.state)
        assertTrue(r.spoken.contains("system ready"))
    }

    @Test
    fun `a throwing sink is a missing ack, busy is not a failure`() {
        val r = readyRig()
        r.obstacle(400)
        r.sink.status = AckStatus.BUSY_DROPPED
        r.run(1_000_000)
        assertEquals(SystemState.READY, r.engine.state)
        assertTrue(r.engine.diagnostics(r.now).nacks > 0)
        r.sink.status = null
        r.run(1_000_000)
        assertEquals(SystemState.UNAVAILABLE, r.engine.state)
        assertEquals("cue_ack_missing", r.states.last().second)
        assertTrue(r.engine.diagnostics(r.now).missedAcks >= 3)
    }

    @Test
    fun `unknown tof is degraded, never clear`() {
        val r = readyRig()
        r.status = RangeStatus.UNKNOWN
        r.run(300_000)
        assertEquals(SystemState.DEGRADED, r.engine.state)
        assertEquals("tof_unknown", r.states.last().second)
        assertTrue(r.obstacleCues().isEmpty())
        assertFalse(r.spoken.contains("distance sensor limited"), "spoken only after the state held 1.5 s")
        r.run(1_500_000)
        assertTrue(r.spoken.contains("distance sensor limited"))
    }

    @Test
    fun `stale range is dropped`() {
        val r = readyRig()
        r.rangeDelayUs = 600_000
        r.obstacle(700)
        r.run(1_000_000)
        assertTrue(r.obstacleCues().isEmpty())
        assertTrue(r.engine.diagnostics(r.now).staleDropped > 0)
        assertEquals(SystemState.DEGRADED, r.engine.state)
    }

    @Test
    fun `an unknown reading after a warning stops the warning and its cues until valid readings resume`() {
        val r = readyRig()
        r.obstacle(700)
        r.run(1_000_000)
        assertEquals(Band.MID, r.alertsOut.last()!!.band)
        r.status = RangeStatus.UNKNOWN
        r.run(200_000)
        assertEquals(SystemState.DEGRADED to "tof_unknown", r.states.last())
        val t = r.now
        r.run(3_000_000) // the whole fault window: DEGRADED allows cues, so only the policy can keep them off
        assertEquals(SystemState.DEGRADED, r.engine.state)
        assertNull(r.alertsOut.last())
        assertTrue(r.obstacleCues(t).isEmpty(), "no stale MID haptics: ${r.obstacleCues(t).size}")
        assertTrue(r.obstacleSounds(t).isEmpty(), "no stale MID sounds")
        assertNull(r.engine.diagnostics(r.now).lastAlert)

        r.obstacle(700) // valid again: a fresh onset with the interrupt flag
        val t2 = r.now
        r.run(1_500_000)
        assertEquals(SystemState.READY, r.engine.state)
        assertTrue(r.obstacleCues(t2).isNotEmpty())
        assertEquals(1, r.obstacleCues(t2).first().second.flags)
    }

    @Test
    fun `range silence stops the warning and its cues, degraded range stale, and resumes with new readings`() {
        val r = readyRig()
        r.obstacle(700)
        r.run(1_000_000)
        assertEquals(Band.MID, r.alertsOut.last()!!.band)
        r.rangeAlive = false
        r.run(800_000) // past the 500 ms freshness bound
        assertEquals(SystemState.DEGRADED to "range_stale", r.states.last())
        val t = r.now
        r.run(3_000_000)
        assertNull(r.alertsOut.last())
        assertTrue(r.obstacleCues(t).isEmpty(), "no cues from a distance that is no longer measured")
        assertTrue(r.obstacleSounds(t).isEmpty())

        r.rangeAlive = true
        val t2 = r.now
        r.run(1_500_000)
        assertEquals(SystemState.READY, r.engine.state)
        assertTrue(r.obstacleCues(t2).isNotEmpty())
    }

    @Test
    fun `pause is explicit and silences obstacle cues, pings continue`() {
        val r = readyRig()
        r.obstacle(700)
        r.engine.setPaused(true)
        r.run(10_000)
        val t = r.now
        r.run(1_000_000)
        assertEquals(SystemState.PAUSED, r.engine.state)
        assertTrue(r.obstacleCues(t).isEmpty())
        assertTrue(r.obstacleSounds(t).isEmpty())
        assertEquals(0, r.systemOffs)
        r.engine.setPaused(false)
        r.run(1_000_000)
        assertEquals(SystemState.READY, r.engine.state)
        assertTrue(r.spoken.containsAll(listOf("paused", "resumed")))
    }

    @Test
    fun `battery critical is unavailable`() {
        val r = readyRig()
        r.engine.setBatteryCritical(true)
        r.run(20_000)
        assertEquals(SystemState.UNAVAILABLE to "battery_critical", r.states.last())
    }

    @Test
    fun `profile resent after cue board reboot and on change carries mode and haptic cap`() {
        val r = readyRig()
        val n = r.profiles.size
        r.cueBeatT0 = 1_000_000_000 // board time jumps backwards = reboot
        r.run(200_000)
        assertTrue(r.profiles.size > n)
        val m = r.profiles.size
        r.engine.setProfile(HearingProfile.uncalibrated(-30).copy(hapticMax = 120))
        r.run(20_000)
        assertEquals(120, r.profiles.drop(m).last().hapticMax)
        assertEquals(0, r.profiles.drop(m).last().mode) // STEREO
    }

    @Test
    fun `a reordered late BEAT is not a reboot and keeps the clock synced`() {
        val r = readyRig()
        val n = r.states.size
        val late = org.determine.core.wire.Beat(BoardId.CUE.wire, 1, 0, r.now + r.cueOffset - 80_000, -60, 40, 200)
        val bytes = Wire.encode(late, 999_999)
        r.engine.onPacket(BoardId.CUE, bytes, bytes.size, r.now)
        r.run(300_000)
        assertEquals(SystemState.READY, r.engine.state)
        assertTrue(r.states.drop(n).none { it.first == SystemState.UNAVAILABLE }, "${r.states.drop(n)}")
    }

    @Test
    fun `calibration audio clamps to the phone ceiling`() {
        val sent = mutableListOf<ByteArray>()
        val sink = TestSink()
        val e = Engine(EngineConfig(initialProfile = HearingProfile.uncalibrated(-20).copy(hapticMax = 100)), { _, p -> sent += p },
            object : PhoneAlerts {
                override fun speak(text: String) {}; override fun vibrate(pattern: LongArray) {}; override fun systemOff() {}
            }, object : EngineListener {
                override fun onState(state: SystemState, reason: String) {}; override fun onAlert(alert: Alert?) {}
                override fun onDiagnostics(d: Diagnostics) {}
            }, sink,
        )
        assertTrue(e.sendCalibrationCue(CueCommand(CueId.CAL_TONE_1K, 0, 0, GAIN_MUTE, Haptic.NONE, 0, 0, 1, 1000), 5))
        val played = sink.plays.last().second
        assertEquals(-20, played.gainLDb) // audio clamped to the uncalibrated ceiling −20 on the phone
        assertEquals(GAIN_MUTE, played.gainRDb)
    }


    @Test
    fun `haptic only mode needs no headset - ready, haptics to the board, the sink is never called`() {
        val p = HearingProfile.uncalibrated(-30, org.determine.core.hearing.HearingMode.HAPTIC_ONLY)
        val sink = TestSink(status = AUDIO_ROUTE_LOST) // no headset at all
        val r = readyRig(EngineConfig(initialProfile = p), sink)
        assertTrue(sink.plays.isEmpty(), "audio is intentionally off: no probe, no READY tick on the headset")
        r.obstacle(400)
        val t0 = r.now
        r.run(500_000)
        assertEquals(SystemState.READY, r.engine.state)
        assertTrue(r.obstacleCues(t0).isNotEmpty())
        assertTrue(r.obstacleCues(t0).all { it.second.hapticPattern != Haptic.NONE && it.second.hapticL > 0 })
        assertTrue(sink.plays.isEmpty())
        assertEquals(2, r.profiles.last().mode)
    }

    @Test
    fun `haptic only still fails loud when the board stops acking, and announces on the phone and the board`() {
        val p = HearingProfile.uncalibrated(-30, org.determine.core.hearing.HearingMode.HAPTIC_ONLY)
        val r = readyRig(EngineConfig(initialProfile = p), TestSink(status = AUDIO_ROUTE_LOST))
        r.obstacle(400)
        r.run(300_000)
        r.acksAlive = false
        r.run(1_000_000)
        assertEquals(SystemState.UNAVAILABLE to "cue_ack_missing", r.states.last())
        assertTrue(r.systemOffs >= 1, "the phone alarm path does not depend on the headset or the mode")
        assertTrue(r.cues.any { it.second.cueId == CueId.SYSTEM_OFF && it.second.hapticPattern == Haptic.LSS })
    }

    @Test
    fun `calibration tones still need and use the headset while warning sounds are off`() {
        val p = HearingProfile.uncalibrated(-30, org.determine.core.hearing.HearingMode.HAPTIC_ONLY)
        val sink = TestSink()
        val r = readyRig(EngineConfig(initialProfile = p), sink)
        r.engine.sendCalibrationCue(CueCommand(CueId.CAL_TONE_1K, 0, -40, GAIN_MUTE, Haptic.NONE, 0, 0, 1, 1000), r.now)
        assertEquals(CueId.CAL_TONE_1K, sink.plays.single().second.cueId)
        // a calibration failure with no headset does not take a haptic-only system down
        sink.status = AUDIO_ROUTE_LOST
        r.engine.sendCalibrationCue(CueCommand(CueId.CAL_TONE_1K, 0, -40, GAIN_MUTE, Haptic.NONE, 0, 0, 1, 1000), r.now)
        r.run(500_000)
        assertEquals(SystemState.READY, r.engine.state)
    }

    @Test
    fun `switching haptic only to stereo re-proves the headset and fails loud without one`() {
        val p = HearingProfile.uncalibrated(-30, org.determine.core.hearing.HearingMode.HAPTIC_ONLY)
        val sink = TestSink(status = AUDIO_ROUTE_LOST)
        val r = readyRig(EngineConfig(initialProfile = p), sink)
        r.engine.setProfile(p.copy(mode = org.determine.core.hearing.HearingMode.STEREO))
        r.run(300_000)
        assertEquals(SystemState.UNAVAILABLE to "headset_route_lost", r.states.last())
        r.engine.setProfile(p) // back to haptic only: no headset needed again
        r.run(1_500_000)
        assertEquals(SystemState.READY, r.engine.state, r.states.toString())
    }

    @Test
    fun `malformed and misattributed packets are counted, not trusted`() {
        val r = readyRig()
        r.engine.onPacket(BoardId.CUE, byteArrayOf(1, 2, 3), 3, r.now)
        val camBeat = Wire.encode(Beat(1, 0, 0, 1, 0, 0, 0), 1)
        r.engine.onPacket(BoardId.CUE, camBeat, camBeat.size, r.now) // claims CAM, arrived from CUE
        assertEquals(2L, r.engine.diagnostics(r.now).malformed)
        assertFalse(r.engine.state == SystemState.UNAVAILABLE)
    }

    @Test
    fun `no grid-era regional range can be set from a sensor packet - one scalar zone only`() {
        val fields = RangeSnapshot::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("tBoardUs", "rxPhoneUs", "mm", "status"), fields)
    }
}

class LatencyStatsTest {
    @Test
    fun `nearest rank p95 of twenty observations excludes the maximum`() {
        val stats = LatencyStats()
        for (ms in 1..20) stats.add(ms.toDouble())
        assertEquals(19.0, stats.percentile(95.0))
        assertEquals(10.0, stats.percentile(50.0))
        assertEquals(20.0, stats.percentile(100.0))
    }
}
