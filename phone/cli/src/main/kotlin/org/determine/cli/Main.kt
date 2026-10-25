package org.determine.cli

import org.determine.core.Alert
import org.determine.core.BoardId
import org.determine.core.CueCommand
import org.determine.core.Diagnostics
import org.determine.core.Engine
import org.determine.core.EngineConfig
import org.determine.core.EngineListener
import org.determine.core.Outbox
import org.determine.core.PhoneAlerts
import org.determine.core.SystemState
import org.determine.core.hearing.HearingMode
import org.determine.core.hearing.HearingProfile
import org.determine.core.wire.AckStatus
import org.determine.core.wire.Beat
import org.determine.core.wire.CuePkt
import org.determine.core.wire.PacketType
import org.determine.core.wire.ProfilePkt
import org.determine.core.wire.RangeStatus
import org.determine.core.wire.Wire
import org.determine.core.wire.WireException
import java.io.PrintStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.system.exitProcess

/**
 * Headless phone: the real [Engine] over UDP, no camera detector. Output format is the stable one in
 * phone/core/API.md ("Stable cli output"); tools/sim/e2e.py parses it.
 *
 * Audio: this process has no audio device. Every cue's audio half is LOGGED as an AUDIO line by
 * [LogCueSink] and acknowledged as APPLIED so the engine's audio-path state machine runs exactly as it does
 * on the phone; the sounds themselves only exist on a real phone with its headset. The board half (haptics)
 * goes on the wire as real CUE packets.
 */
data class CliOptions(
    val port: Int = 47000,
    val bind: String = "0.0.0.0",
    val cam: InetSocketAddress? = null,
    val cue: InetSocketAddress? = null,
    val durationS: Double? = null,
    val ceilingDb: Int = -20,
    val mode: HearingMode = HearingMode.STEREO,
    val spearcons: Boolean = true,
)

const val USAGE = """determine-phone [options]
  --port N            UDP port to bind (default 47000)
  --bind ADDR         bind address (default 0.0.0.0)
  --cam HOST:PORT     camera board address (default: learn from its first BEAT)
  --cue HOST:PORT     cue board address (default: learn from its first BEAT)
  --duration S        exit after S seconds (default: run until killed)
  --ceiling DB        local audio ceiling in dBFS (default -20; never above the -10 hard cap)
  --mode M            STEREO | SSD | HAPTIC_ONLY (default STEREO)
  --no-spearcons      disable spoken words after cues
  --help"""

fun parseArgs(args: Array<String>): CliOptions {
    var o = CliOptions()
    var i = 0
    fun value(): String = args.getOrNull(++i) ?: throw IllegalArgumentException("missing value for ${args[i - 1]}")
    fun addr(s: String): InetSocketAddress {
        val idx = s.lastIndexOf(':')
        require(idx > 0) { "expected HOST:PORT, got $s" }
        return InetSocketAddress(InetAddress.getByName(s.substring(0, idx)), s.substring(idx + 1).toInt())
    }
    while (i < args.size) {
        when (val a = args[i]) {
            "--port" -> o = o.copy(port = value().toInt())
            "--bind" -> o = o.copy(bind = value())
            "--cam" -> o = o.copy(cam = addr(value()))
            "--cue" -> o = o.copy(cue = addr(value()))
            "--duration" -> o = o.copy(durationS = value().toDouble())
            "--ceiling" -> o = o.copy(ceilingDb = value().toInt())
            "--mode" -> o = o.copy(mode = HearingMode.valueOf(value().uppercase()))
            "--no-spearcons" -> o = o.copy(spearcons = false)
            else -> throw IllegalArgumentException("unknown option $a")
        }
        i++
    }
    require(o.ceilingDb <= 0) { "ceiling is dBFS and must be <= 0" }
    return o
}

/** Formats events as `KIND <ms> ...`; [nowUs] is the phone monotonic clock. Thread-safe. */
class EventLog(private val out: PrintStream, private val startUs: Long) {
    private val lock = ReentrantLock()
    fun emit(nowUs: Long, kind: String, rest: String) = lock.withLock {
        out.println("$kind ${"%.1f".format((nowUs - startUs) / 1000.0)}${if (rest.isEmpty()) "" else " $rest"}")
        out.flush()
    }
}

private fun tok(s: String) = s.replace(' ', '_').ifEmpty { "-" }
private fun opt(v: Any?) = when (v) { null -> "-"; is Double -> String.format(java.util.Locale.ROOT, "%.2f", v); else -> v.toString() }

fun alertLine(a: Alert?): String =
    if (a == null) "none" else "band=${a.band} dist_mm=${opt(a.distanceMm)}"

fun diagLine(d: Diagnostics): String = listOf(
    "state=${d.state}",
    "cam_beat_ms=${opt(d.cam.beatAgeMs)}", "cam_offset_us=${opt(d.cam.offsetUs)}", "cam_rtt_us=${opt(d.cam.rttUs)}", "cam_synced=${d.cam.synced}",
    "cue_beat_ms=${opt(d.cue.beatAgeMs)}", "cue_offset_us=${opt(d.cue.offsetUs)}", "cue_rtt_us=${opt(d.cue.rttUs)}", "cue_synced=${d.cue.synced}",
    "frame_age_ms=${opt(d.frameAgeMs)}", "range_age_ms=${opt(d.rangeAgeMs)}",
    "cap2cue_ms=${opt(d.lastCueLatencyMs)}", "cap2cue_p50=${opt(d.captureToCueP50Ms)}", "cap2cue_p95=${opt(d.captureToCueP95Ms)}",
    "ack_p50=${opt(d.ackP50Ms)}", "ack_p95=${opt(d.ackP95Ms)}",
    "cues=${d.cuesSent}", "acks=${d.acks}", "nacks=${d.nacks}", "missed=${d.missedAcks}",
    "malformed=${d.malformed}", "stale=${d.staleDropped}",
    rangeLine(d.rangeMm, d.rangeStatus), "range_left=unavailable", "range_right=unavailable",
    "link_weak=${d.linkWeak}", "closing_mm_s=${opt(d.closingSpeedMmPerS)}", "approach=${d.approachActive}", "exploring=${d.exploring}",
).joinToString(" ")

/** The forward ToF zone as one diagnostic pair: `range_mm=870 range_status=VALID` (canonical v2 status). */
fun rangeLine(mm: Int?, status: Int?): String {
    val st = when (status) {
        null -> "-"
        RangeStatus.VALID -> "VALID"
        RangeStatus.NO_TARGET -> "NO_TARGET"
        RangeStatus.UNKNOWN -> "UNKNOWN"
        else -> "INVALID_${status}"
    }
    return "range_mm=${if (status == RangeStatus.VALID) mm?.toString() ?: "-" else "-"} range_status=$st"
}

/** Text line for an outgoing command, or null for packets that are not logged (PING). */
fun txLine(packet: ByteArray): Pair<String, String>? {
    val d = try { Wire.decode(packet) } catch (_: WireException) { return null }
    return when (val p = d.packet) {
        is CuePkt -> "CUE-TX" to "seq=${d.seq} cue=${p.cueId} side=${p.side} " +
            "hp=${p.hapticPattern} hl=${p.hapticL} hr=${p.hapticR} flags=${p.flags} ttl=${p.ttlMs} deadline=${p.deadlineBoardUs}"
        is ProfilePkt -> "PROFILE-TX" to "seq=${d.seq} mode=${p.mode} haptic_max=${p.hapticMax}"
        else -> null
    }
}

/** Text line for a cue played by the local (headless) audio sink. */
fun audioLine(c: CueCommand, status: Int): String =
    "cue=${c.cueId} side=${c.side} gl=${c.gainLDb} gr=${c.gainRDb} status=${status}"

/**
 * Board addressing: a packet's sender is identified by the board field of its BEAT (PROTOCOL.md: the phone
 * learns each board's IP from its first packet). Other packet types are attributed by source address
 * once learned; RANGE can only come from the cue board and FRAME from the camera board.
 */
class BoardDirectory(cam: InetSocketAddress?, cue: InetSocketAddress?) {
    private val addr = HashMap<BoardId, InetSocketAddress>().apply {
        cam?.let { put(BoardId.CAM, it) }; cue?.let { put(BoardId.CUE, it) }
    }
    @Synchronized fun get(b: BoardId): InetSocketAddress? = addr[b]

    @Synchronized fun classify(src: InetSocketAddress, bytes: ByteArray, len: Int): BoardId? {
        val byType = if (len >= 4) when (bytes[3].toInt() and 0xFF) {
            PacketType.RANGE, PacketType.ACK -> BoardId.CUE
            PacketType.FRAME -> BoardId.CAM
            PacketType.BEAT -> try { (Wire.decode(bytes, len).packet as? Beat)?.let { BoardId.fromWire(it.board) } } catch (_: WireException) { null }
            else -> null
        } else null
        if (byType != null) { addr[byType] = src; return byType }
        return addr.entries.firstOrNull { it.value == src }?.key
    }
}

fun nowUs(): Long = System.nanoTime() / 1000

/**
 * The headless stand-in for the phone's headset audio ([org.determine.core.LocalCueSink]): logs every cue and
 * acknowledges APPLIED. There is no speaker or headset in this process — the AUDIO line IS the audio path.
 */
class LogCueSink(private val log: EventLog) : org.determine.core.LocalCueSink {
    override fun play(cmd: CueCommand, nowPhoneUs: Long): Int {
        log.emit(nowUs(), "AUDIO", audioLine(cmd, AckStatus.APPLIED))
        return AckStatus.APPLIED
    }
}

fun run(o: CliOptions, out: PrintStream = System.out): Int {
    val start = nowUs()
    val log = EventLog(out, start)
    val socket = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(o.bind, o.port)); soTimeout = 5 }
    val dir = BoardDirectory(o.cam, o.cue)
    val lock = ReentrantLock() // Engine is single-threaded: every call holds this lock.

    val outbox = Outbox { board, packet ->
        val to = dir.get(board) ?: return@Outbox // board not heard from yet
        txLine(packet)?.let { (k, rest) -> log.emit(nowUs(), k, rest) }
        socket.send(DatagramPacket(packet, packet.size, to))
    }
    val alerts = object : PhoneAlerts {
        override fun speak(text: String) = log.emit(nowUs(), "SPEAK", "text=\"${text.replace('"', '\'')}\"")
        override fun vibrate(pattern: LongArray) = log.emit(nowUs(), "VIBRATE", "pattern=${pattern.joinToString(",")}")
        override fun systemOff() = log.emit(nowUs(), "SYSTEM-OFF", "")
    }
    val listener = object : EngineListener {
        override fun onState(state: SystemState, reason: String) = log.emit(nowUs(), "STATE", "$state reason=${tok(reason)}")
        override fun onAlert(alert: Alert?) = log.emit(nowUs(), "ALERT", alertLine(alert))
        override fun onDiagnostics(d: Diagnostics) = log.emit(nowUs(), "DIAG", diagLine(d))
    }
    val engine = Engine(
        EngineConfig(initialProfile = HearingProfile.uncalibrated(o.ceilingDb, o.mode), spearcons = o.spearcons),
        outbox, alerts, listener, LogCueSink(log),
    )
    out.println("determine-phone listening on ${o.bind}:${o.port} (headless: AUDIO lines stand in for headset playback)"); out.flush()

    val running = java.util.concurrent.atomic.AtomicBoolean(true)
    Runtime.getRuntime().addShutdownHook(Thread { running.set(false) })
    val ticker = Thread({
        while (running.get()) {
            lock.withLock { engine.tick(nowUs()) }
            Thread.sleep(10)
        }
    }, "tick").apply { isDaemon = true; start() }

    val buf = ByteArray(2048)
    val pkt = DatagramPacket(buf, buf.size)
    val endUs = o.durationS?.let { start + (it * 1e6).toLong() }
    while (running.get() && (endUs == null || nowUs() < endUs)) {
        try {
            socket.receive(pkt)
        } catch (_: SocketTimeoutException) {
            continue
        }
        val rx = nowUs()
        val src = pkt.socketAddress as InetSocketAddress
        val board = dir.classify(src, buf, pkt.length) ?: continue
        lock.withLock { engine.onPacket(board, buf, pkt.length, rx) }
    }
    running.set(false)
    ticker.join(200)
    socket.close()
    return 0
}

fun main(args: Array<String>) {
    if ("--help" in args || "-h" in args) { println(USAGE); return }
    val o = try { parseArgs(args) } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}\n$USAGE"); exitProcess(2)
    }
    exitProcess(run(o))
}
