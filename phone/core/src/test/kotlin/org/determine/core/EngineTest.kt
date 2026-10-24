package org.determine.core

import org.determine.core.system.MonitorInputs
import org.determine.core.system.SystemMonitor
import org.determine.core.wire.Ack
import org.determine.core.wire.AckStatus
import org.determine.core.wire.Beat
import org.determine.core.wire.CueId
import org.determine.core.wire.CuePkt
import org.determine.core.wire.FramePkt
import org.determine.core.wire.Ping
import org.determine.core.wire.Pong
import org.determine.core.wire.ProfilePkt
import org.determine.core.wire.RangePkt
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
        corridorKnown = true, corridorUnknownFraction = 0f, clocksSynced = true, cueLinkConfirmed = true,
        ackFailures = 0, batteryCritical = false, paused = false,
    )

    @Test
    fun `starting needs everything, then degraded and unavailable boundaries`() {
        val m = SystemMonitor()
        assertNull(m.evaluate(ok(0).copy(cueLinkConfirmed = false)))
        assertNull(m.evaluate(ok(1).copy(corridorKnown = false)))
        assertEquals(SystemState.READY, m.evaluate(ok(2))!!.to)
        assertEquals(SystemState.DEGRADED, m.evaluate(ok(3).copy(corridorUnknownFraction = 0.51f))!!.to)
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

/** Two simulated boards wired to an Engine, all in virtual time. */
class Rig(config: EngineConfig = EngineConfig()) {
    var now = 1_000_000_000L
    var camOffset = 7_000_000L - now
    val cueOffset = 3_000_000L - now
    var camAlive = true
    var cueAlive = true
    var acksAlive = true
    var cueBeatT0 = 0L
    var grid: IntArray = IntArray(64) { 0 }
    var status: IntArray = IntArray(64) { 255 }
    var rangeDelayUs = 0L

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
                is CuePkt -> { cues += p.tIssuedPhoneUs to p; if (alive && acksAlive) deliver(b, Ack(Wire.decode(bytes).seq, AckStatus.PLAYED, p.gainLDb, p.gainRDb, now + off)) }
                is ProfilePkt -> { profiles += p; if (alive && acksAlive) deliver(b, Ack(Wire.decode(bytes).seq, AckStatus.PLAYED, 0, 0, now + off)) }
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
            if (cueAlive) deliver(BoardId.CUE, Beat(2, 1, 0, now + cueOffset - cueBeatT0, -50, 40, 100))
        }
        if (now >= nextRange) {
            nextRange = now + 66_667
            if (cueAlive) deliver(BoardId.CUE, RangePkt(now + cueOffset - 20_000 - rangeDelayUs, grid.copyOf(), status.copyOf()))
        }
        engine.tick(now)
        now += 10_000
    }

    fun run(us: Long) { val end = now + us; while (now < end) step() }

    /** Camera board reboots: its clock restarts with a new offset and frame_id counts from 0 again. */
    fun rebootCam(newOffset: Long) { camOffset = newOffset; frameId = 0 }

    fun obstacle(mm: Int?) {
        status = IntArray(64) { 255 }; grid = IntArray(64)
        if (mm != null) { val z = 2 * 8 + 3; grid[z] = mm; status[z] = 5 }
    }

    fun obstacleCues(since: Long = 0) = cues.filter { it.first >= since && it.second.cueId in CueId.OBSTACLE_FAR..CueId.PERSON_NEAR }
}

class EngineTest {
    private fun readyRig(cfg: EngineConfig = EngineConfig()) = Rig(cfg).also { it.run(1_000_000); assertEquals(SystemState.READY, it.engine.state, it.states.toString()) }

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
    fun `boots to ready with one ready tick and profile acked`() {
        val r = readyRig()
        assertEquals(listOf(SystemState.READY), r.states.map { it.first })
        assertEquals(1, r.cues.count { it.second.cueId == CueId.READY })
        assertTrue(r.profiles.isNotEmpty())
        assertNotNull(r.engine.latestJpeg())
        assertEquals(2000, r.engine.latestJpeg()!!.jpeg.size)
        assertNotNull(r.engine.latestRange())
    }

    @Test
    fun `obstacle produces alert, cues at band cadence with synced deadline, spearcon once`() {
        val r = readyRig()
        r.obstacle(1500)
        val t0 = r.now
        r.run(1_000_000)
        assertEquals(Alert(Region.CENTER, Band.MID, "obstacle", 1500), r.alertsOut.last())
        val cues = r.obstacleCues(t0)
        assertTrue(cues.all { it.second.cueId == CueId.OBSTACLE_MID })
        assertTrue(cues.size in 2..3, "MID 2.5/s over ~0.8 s: ${cues.size}")
        val (sentAt, c) = cues.first()
        assertEquals(1, c.flags)
        // deadline = board time at issue + 150 ms (offset estimate within the 1 ms symmetric delay error)
        val expected = sentAt + r.cueOffset + 150_000
        assertTrue(kotlin.math.abs(c.deadlineBoardUs - expected) < 2_000, "${c.deadlineBoardUs} vs $expected")
        assertEquals(listOf("obstacle ahead, 1.5 metres"), r.spoken)
        // obstacle disappears: exit after 5 none samples, cues stop, never a "clear" sound
        r.obstacle(null)
        r.run(500_000)
        assertNull(r.alertsOut.last())
        val tEnd = r.now
        r.run(1_000_000)
        assertTrue(r.cues.none { it.first >= tEnd })
        val d = r.engine.diagnostics(r.now)
        assertNotNull(d.captureToCueP50Ms); assertNotNull(d.ackP95Ms)
        assertTrue(d.captureToCueP95Ms!! < 400.0)
    }

    @Test
    fun `camera down is unavailable, loud, suppresses obstacle cues, recovers once`() {
        val r = readyRig()
        r.obstacle(800)
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
        assertEquals(-20, sysOff.gainLDb) // min(ceiling -20, -20)
        assertTrue(r.obstacleCues(tUnavail + 1).isEmpty())
        r.run(5_000_000)
        assertEquals(2, r.systemOffs) // repeats every 5 s
        assertTrue(r.obstacleCues(tUnavail + 1).isEmpty())
        r.camAlive = true
        r.run(1_000_000)
        assertEquals(SystemState.READY, r.engine.state)
        assertEquals(1, r.spoken.count { it == "system ready" })
        assertTrue(r.obstacleCues(r.now - 500_000).isNotEmpty())
    }

    @Test
    fun `missing acks escalate to unavailable`() {
        val r = readyRig()
        r.obstacle(800)
        r.run(400_000)
        r.acksAlive = false
        r.run(1_000_000)
        assertEquals(SystemState.UNAVAILABLE, r.engine.state)
        assertEquals("cue_ack_missing", r.states.last().second)
    }

    @Test
    fun `unknown tof is degraded, never clear`() {
        val r = readyRig()
        r.status = IntArray(64) { 3 }
        r.run(300_000)
        assertEquals(SystemState.DEGRADED, r.engine.state)
        assertEquals("tof_unknown", r.states.last().second)
        assertTrue(r.obstacleCues().isEmpty())
        assertTrue(r.spoken.contains("distance sensor limited"))
    }

    @Test
    fun `stale range is dropped`() {
        val r = readyRig()
        r.rangeDelayUs = 600_000
        r.obstacle(800)
        r.run(1_000_000)
        assertTrue(r.obstacleCues().isEmpty())
        assertTrue(r.engine.diagnostics(r.now).staleDropped > 0)
        assertEquals(SystemState.DEGRADED, r.engine.state)
    }

    @Test
    fun `pause is explicit and silences obstacle cues, pings continue`() {
        val r = readyRig()
        r.obstacle(800)
        r.engine.setPaused(true)
        r.run(10_000)
        val t = r.now
        r.run(1_000_000)
        assertEquals(SystemState.PAUSED, r.engine.state)
        assertTrue(r.obstacleCues(t).isEmpty())
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
    fun `profile resent after cue board reboot and on change`() {
        val r = readyRig()
        val n = r.profiles.size
        r.cueBeatT0 = 1_000_000_000 // board time jumps backwards = reboot
        r.run(200_000)
        assertTrue(r.profiles.size > n)
        val m = r.profiles.size
        r.engine.setProfile(org.determine.core.hearing.HearingProfile.uncalibrated(-30))
        r.run(20_000)
        assertEquals(-30, r.profiles.drop(m).last().ceilingDb)
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
    fun `cue deadline is zero without clock sync and gains clamp to ceiling`() {
        val sent = mutableListOf<ByteArray>()
        val e = Engine(EngineConfig(), { _, p -> sent += p }, object : PhoneAlerts {
            override fun speak(text: String) {}; override fun vibrate(pattern: LongArray) {}; override fun systemOff() {}
        }, object : EngineListener {
            override fun onState(state: SystemState, reason: String) {}; override fun onAlert(alert: Alert?) {}
            override fun onDiagnostics(d: Diagnostics) {}
        })
        e.sendCalibrationCue(CueCommand(CueId.CAL_TONE_1K, 0, 0, -128, 0, 0, 0, 1, 1000), 5)
        val c = Wire.decode(sent.last()).packet as CuePkt
        assertEquals(0L, c.deadlineBoardUs)
        assertEquals(-20, c.gainLDb) // clamped to uncalibrated ceiling −20
        assertEquals(-128, c.gainRDb)
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
}
