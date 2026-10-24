package org.determine.core

import org.determine.core.hearing.HearingProfile
import org.determine.core.wire.Ack
import org.determine.core.wire.AckStatus
import org.determine.core.wire.Beat
import org.determine.core.wire.CueId
import org.determine.core.wire.CuePkt
import org.determine.core.wire.FramePkt
import org.determine.core.wire.Packet
import org.determine.core.wire.Ping
import org.determine.core.wire.Pong
import org.determine.core.wire.ProfilePkt
import org.determine.core.wire.RangePkt
import org.determine.core.wire.Wire
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** A phone-side cue player for PHONE_AUDIO; [status] null = the player throws. */
class TestSink(var status: Int? = AckStatus.PLAYED) : LocalCueSink {
    val plays = mutableListOf<Pair<Long, CueCommand>>()
    override fun play(cmd: CueCommand, nowPhoneUs: Long): Int {
        plays += nowPhoneUs to cmd
        return status ?: throw IllegalStateException("audio output gone")
    }

    fun audible() = plays.filter { it.second.gainLDb != -128 || it.second.gainRDb != -128 || it.second.hapticL > 0 || it.second.hapticR > 0 }
}

/** Boards (optional camera board, cue board) + phone camera, all in virtual time, for the fallback modes. */
class FallbackRig(
    config: EngineConfig,
    val sink: TestSink? = null,
    var camBoard: Boolean = false,
    var phoneFrames: Boolean = true,
) {
    var now = 1_000_000_000L
    private val camOffset = 7_000_000L - now
    private val cueOffset = 3_000_000L - now
    var cueAlive = true
    var grid = IntArray(64)
    var status = IntArray(64) { 255 }

    val states = mutableListOf<Triple<SystemState, String, Long>>()
    val alertsOut = mutableListOf<Alert?>()
    val spoken = mutableListOf<String>()
    var systemOffs = 0
    val boardCues = mutableListOf<CuePkt>()
    val profiles = mutableListOf<ProfilePkt>()
    val pings = mutableMapOf(BoardId.CAM to 0, BoardId.CUE to 0)
    var lastPhoneCaptureUs = 0L
    private val inbox = ArrayDeque<Pair<BoardId, ByteArray>>()
    private var seq = 0L
    private var nextBeat = 0L
    private var nextRange = 0L
    private var nextPhoneFrame = 0L
    private var camFrameId = 0L

    val engine = Engine(
        config,
        { b, p -> inbox.addLast(b to p) },
        object : PhoneAlerts {
            override fun speak(text: String) { spoken += text }
            override fun vibrate(pattern: LongArray) {}
            override fun systemOff() { systemOffs++ }
        },
        object : EngineListener {
            override fun onState(state: SystemState, reason: String) { states += Triple(state, reason, now) }
            override fun onAlert(alert: Alert?) { alertsOut += alert }
            override fun onDiagnostics(d: Diagnostics) {}
        },
        sink,
    )

    private fun deliver(b: BoardId, p: Packet) {
        val bytes = Wire.encode(p, seq++)
        engine.onPacket(b, bytes, bytes.size, now + 1_000)
    }

    fun step() {
        while (inbox.isNotEmpty()) {
            val (b, bytes) = inbox.removeFirst()
            val d = Wire.decode(bytes)
            val alive = if (b == BoardId.CAM) camBoard else cueAlive
            val off = if (b == BoardId.CAM) camOffset else cueOffset
            when (val p = d.packet) {
                is Ping -> { pings[b] = pings.getValue(b) + 1; if (alive) deliver(b, Pong(p.tPhoneUs, p.tPhoneUs + off + 500, now + off + 500)) }
                is CuePkt -> { boardCues += p; if (alive) deliver(b, Ack(d.seq, AckStatus.PLAYED, p.gainLDb, p.gainRDb, now + off)) }
                is ProfilePkt -> { profiles += p; if (alive) deliver(b, Ack(d.seq, AckStatus.PLAYED, 0, 0, now + off)) }
                else -> Unit
            }
        }
        if (now >= nextBeat) {
            nextBeat = now + 100_000
            if (camBoard) {
                deliver(BoardId.CAM, Beat(1, 1, 0, now + camOffset, -50, 40, 100))
                deliver(BoardId.CAM, FramePkt(camFrameId++, now + camOffset - 30_000, 320, 240, 100, 0, 0, 1, ByteArray(100) { 7 }))
            }
            if (cueAlive) deliver(BoardId.CUE, Beat(2, 1, 0, now + cueOffset, -50, 40, 100))
        }
        if (now >= nextRange) {
            nextRange = now + 66_667
            if (cueAlive) deliver(BoardId.CUE, RangePkt(now + cueOffset - 20_000, grid.copyOf(), status.copyOf()))
        }
        if (phoneFrames && now >= nextPhoneFrame) {
            nextPhoneFrame = now + 33_333
            lastPhoneCaptureUs = now - 20_000
            engine.onPhoneFrame(ByteArray(500) { 3 }, 640, 480, lastPhoneCaptureUs)
        }
        engine.tick(now)
        now += 10_000
    }

    fun run(us: Long) { val end = now + us; while (now < end) step() }

    fun obstacle(mm: Int?) {
        status = IntArray(64) { 255 }; grid = IntArray(64)
        if (mm != null) { val z = 2 * 8 + 3; grid[z] = mm; status[z] = 5 }
    }

    fun ready(): FallbackRig { run(1_000_000); assertEquals(SystemState.READY, engine.state, states.toString()); return this }
}

private fun silentEngine(config: EngineConfig, sink: LocalCueSink? = null) = Engine(
    config, { _, _ -> },
    object : PhoneAlerts {
        override fun speak(text: String) {}; override fun vibrate(pattern: LongArray) {}; override fun systemOff() {}
    },
    object : EngineListener {
        override fun onState(state: SystemState, reason: String) {}; override fun onAlert(alert: Alert?) {}
        override fun onDiagnostics(d: Diagnostics) {}
    },
    sink,
)

class FallbackTest {
    private val tier0 = EngineConfig(frameSource = FrameSourceKind.PHONE_CAMERA)
    private val phoneAudio = EngineConfig(cueOutput = CueOutput.PHONE_AUDIO, initialProfile = HearingProfile.uncalibrated(-30))

    // ------------------------------------------------------------------ Tier 0: phone camera

    @Test
    fun `tier 0 reaches ready without a camera board and fails loud when phone frames stop`() {
        val r = FallbackRig(tier0).ready()
        assertEquals(0, r.pings.getValue(BoardId.CAM), "no camera board: never pinged")
        val f = r.engine.latestJpeg()!!
        assertEquals(0L, f.captureBoardUs)
        assertEquals(r.lastPhoneCaptureUs, f.capturePhoneUs)
        assertEquals(640, f.w)
        assertTrue(f.frameId > 0)
        val d = r.engine.diagnostics(r.now)
        assertEquals(FrameSourceKind.PHONE_CAMERA, d.frameSource)
        assertEquals(CueOutput.GLASSES, d.cueOutput)
        assertNull(d.cam.beatAgeMs)

        r.phoneFrames = false
        val lastCapture = r.lastPhoneCaptureUs
        r.run(400_000)
        assertEquals(SystemState.READY, r.engine.state, "within the 500 ms bound")
        r.run(300_000)
        val (state, reason, at) = r.states.last()
        assertEquals(SystemState.UNAVAILABLE, state)
        assertEquals("frame_stale", reason)
        assertTrue(at - lastCapture in 500_001..510_000, "${at - lastCapture}")
        assertEquals(1, r.systemOffs)
        assertTrue(r.boardCues.any { it.cueId == CueId.SYSTEM_OFF })

        r.phoneFrames = true
        r.run(200_000)
        assertEquals(SystemState.READY, r.engine.state)
    }

    @Test
    fun `tier 0 counts and ignores a powered camera board`() {
        val r = FallbackRig(tier0, camBoard = true).ready()
        val d = r.engine.diagnostics(r.now)
        assertTrue(d.ignoredCamPackets >= 20, "${d.ignoredCamPackets}")
        assertEquals(0L, d.malformed)
        assertEquals(640, r.engine.latestJpeg()!!.w) // the phone frame, never the board's
        assertNull(d.cam.beatAgeMs)
        assertEquals(0, r.pings.getValue(BoardId.CAM))
    }

    @Test
    fun `camera board mode still requires the camera board`() {
        val r = FallbackRig(EngineConfig(), camBoard = false, phoneFrames = false)
        r.run(1_000_000)
        assertNotEquals(SystemState.READY, r.engine.state)
        assertTrue(r.pings.getValue(BoardId.CAM) > 0)
    }

    @Test
    fun `phone frames only in tier 0, ordered, fresh and non-empty`() {
        assertThrows(IllegalStateException::class.java) {
            silentEngine(EngineConfig()).onPhoneFrame(ByteArray(10), 640, 480, 0)
        }
        val e = silentEngine(tier0)
        e.tick(10_000_000)
        e.onPhoneFrame(ByteArray(10) { 1 }, 640, 480, 9_990_000)
        val first = e.latestJpeg()!!
        e.onPhoneFrame(ByteArray(10) { 2 }, 640, 480, 9_980_000) // older than the newest: out of order
        e.onPhoneFrame(ByteArray(10) { 3 }, 640, 480, 9_990_000) // same capture time: duplicate
        e.onPhoneFrame(ByteArray(0), 640, 480, 9_995_000)        // empty is never a frame
        assertSame(first, e.latestJpeg())
        e.tick(10_600_000)
        e.onPhoneFrame(ByteArray(10) { 4 }, 640, 480, 10_099_999) // 500.001 ms old at the last tick
        assertSame(first, e.latestJpeg())
        e.onPhoneFrame(ByteArray(10) { 5 }, 640, 480, 10_100_000) // exactly 500 ms: still fresh
        assertEquals(first.frameId + 1, e.latestJpeg()!!.frameId)
        val d = e.diagnostics(10_600_000)
        assertEquals(3L, d.staleDropped)
        assertEquals(1L, d.malformed)
    }

    @Test
    fun `detections are gated on the phone capture time`() {
        val e = silentEngine(tier0)
        val det = listOf(Detection("person", 0.9f, 0.4f, 0.3f, 0.6f, 0.9f))
        e.onDetections(1, 1_000_000, det, 1_500_000)  // exactly 500 ms: accepted
        assertEquals(0L, e.diagnostics(1_500_000).staleDropped)
        e.onDetections(2, 1_000_000, det, 1_500_001)
        assertEquals(1L, e.diagnostics(1_500_001).staleDropped)
    }

    // ------------------------------------------------------------------ phone audio output

    @Test
    fun `phone audio needs a sink`() {
        assertThrows(IllegalArgumentException::class.java) { silentEngine(EngineConfig(cueOutput = CueOutput.PHONE_AUDIO)) }
    }

    @Test
    fun `phone audio routes every cue to the sink within the ceiling and never sends PROFILE or CUE`() {
        val sink = TestSink()
        val r = FallbackRig(phoneAudio, sink, camBoard = true, phoneFrames = false).ready()
        val probe = sink.plays.first().second
        assertEquals(-128, probe.gainLDb); assertEquals(-128, probe.gainRDb); assertEquals(0, probe.hapticL + probe.hapticR)
        assertEquals(1, sink.audible().count { it.second.cueId == CueId.READY })

        r.obstacle(800)
        val t0 = r.now
        r.run(1_000_000)
        val obstacle = sink.plays.filter { it.first >= t0 && it.second.cueId in CueId.OBSTACLE_FAR..CueId.PERSON_NEAR }
        assertTrue(obstacle.size >= 3, "NEAR at 5/s: ${obstacle.size}")
        assertTrue(sink.plays.all { (_, c) -> listOf(c.gainLDb, c.gainRDb).all { it == -128 || it <= -30 } })

        r.engine.sendCalibrationCue(CueCommand(CueId.CAL_TONE_1K, 0, 0, -128, 0, 0, 0, 1, 1000), r.now)
        assertEquals(-30, sink.plays.last().second.gainLDb)

        assertTrue(r.profiles.isEmpty())
        assertTrue(r.boardCues.isEmpty())
        assertTrue(r.pings.getValue(BoardId.CUE) > 0, "cue board still pinged: clock sync for RANGE")
        val d = r.engine.diagnostics(r.now)
        assertEquals(CueOutput.PHONE_AUDIO, d.cueOutput)
        assertEquals(0L, d.missedAcks)
        assertTrue(d.acks >= obstacle.size)
    }

    @Test
    fun `phone audio sink failures escalate to unavailable with system off on the sink and recover`() {
        val sink = TestSink()
        val r = FallbackRig(phoneAudio, sink, camBoard = true, phoneFrames = false).ready()
        r.obstacle(800)
        r.run(300_000)
        sink.status = -1
        val tFail = r.now
        r.run(1_500_000)
        assertEquals(SystemState.UNAVAILABLE to "cue_ack_missing", r.states.last().first to r.states.last().second)
        assertTrue(r.states.last().third - tFail < 1_000_000)
        assertEquals(1, r.systemOffs)
        assertTrue(sink.plays.any { it.first >= tFail && it.second.cueId == CueId.SYSTEM_OFF })
        assertNull(r.alertsOut.last())
        assertTrue(r.boardCues.isEmpty())

        sink.status = AckStatus.PLAYED
        r.run(1_500_000)
        assertEquals(SystemState.READY, r.engine.state)
        assertTrue(r.spoken.contains("system ready"))
    }

    @Test
    fun `a throwing sink is a missing ack, busy is not a failure`() {
        val sink = TestSink()
        val r = FallbackRig(phoneAudio, sink, camBoard = true, phoneFrames = false).ready()
        r.obstacle(800)
        sink.status = AckStatus.BUSY_DROPPED
        r.run(1_000_000)
        assertEquals(SystemState.READY, r.engine.state)
        assertTrue(r.engine.diagnostics(r.now).nacks > 0)
        sink.status = null
        r.run(1_000_000)
        assertEquals(SystemState.UNAVAILABLE, r.engine.state)
        assertEquals("cue_ack_missing", r.states.last().second)
        assertTrue(r.engine.diagnostics(r.now).missedAcks >= 3)
    }

    @Test
    fun `phone audio is not ready until the sink plays and still needs the cue board`() {
        val broken = TestSink(status = AckStatus.BAD_CUE)
        val r = FallbackRig(phoneAudio, broken, camBoard = true, phoneFrames = false)
        r.run(2_000_000)
        assertEquals(SystemState.STARTING, r.engine.state)
        assertTrue(broken.audible().isEmpty(), "only muted probes before READY")
        assertTrue(broken.plays.size in 2..3, "probe at the 1 s PROFILE cadence: ${broken.plays.size}")

        val ok = FallbackRig(phoneAudio, TestSink(), camBoard = true, phoneFrames = false).ready()
        ok.cueAlive = false
        ok.run(700_000)
        assertEquals(SystemState.UNAVAILABLE to "cue_beat_missing", ok.states.last().first to ok.states.last().second)
    }

    @Test
    fun `tier 0 with phone audio needs only the cue board`() {
        val sink = TestSink()
        val r = FallbackRig(tier0.copy(cueOutput = CueOutput.PHONE_AUDIO), sink).ready()
        assertTrue(r.profiles.isEmpty() && r.boardCues.isEmpty())
        assertEquals(0, r.pings.getValue(BoardId.CAM))
        assertNotNull(r.engine.latestJpeg())
        assertEquals(1, sink.audible().count { it.second.cueId == CueId.READY })
    }
}
