package org.determine.core

/** The two wearable boards (PROTOCOL.md BEAT.board: 1 = CAM, 2 = CUE). */
enum class BoardId(val wire: Int) {
    CAM(1), CUE(2);

    companion object {
        fun fromWire(v: Int): BoardId? = entries.firstOrNull { it.wire == v }
    }
}

/** Sends an encoded packet to a board. The app/cli owns the UDP socket and the learned board addresses. */
fun interface Outbox {
    fun send(board: BoardId, packet: ByteArray)
}

/** Where frames come from (Arch §4.1 FrameSource). PHONE_CAMERA is Tier 0: no camera board, host calls [Engine.onPhoneFrame]. */
enum class FrameSourceKind { CAM_BOARD, PHONE_CAMERA }

/** Audio gain sentinel: this channel is silent (dBFS levels are ≤ 0). */
const val GAIN_MUTE = -128

/**
 * [LocalCueSink.play] result meaning "the required headset route is absent or was lost". The engine goes
 * UNAVAILABLE (reason `headset_route_lost`) at once and announces it; the sink must never fall back to the
 * phone speaker for warning audio. Probed every [EngineConfig.profileRetryUs] so a reconnected headset recovers.
 */
const val AUDIO_ROUTE_LOST = -2

/**
 * Plays a cue's AUDIO on the phone — the primary warning path to the finished bone-conduction Bluetooth
 * headset. Called synchronously from the Engine with gains already clamped to the profile ceiling
 * (−128 = mute). Returns a wire.AckStatus; any other value (e.g. negative) or a thrown exception counts as a
 * failed cue, exactly like a missing/negative board ACK. The sink must fail explicitly (never route to the
 * phone speaker) when no headset is connected.
 */
fun interface LocalCueSink {
    fun play(cmd: CueCommand, nowPhoneUs: Long): Int
}

/** Phone-side fallback outputs (Arch §4.2: used when the wearable is down, and for spearcons). */
interface PhoneAlerts {
    fun speak(text: String)
    fun vibrate(pattern: LongArray)
    fun systemOff()
}

/** Arch §4.2 runtime states. */
enum class SystemState { STARTING, READY, DEGRADED, UNAVAILABLE, PAUSED }

/** Proximity bands, ordered by urgency (Arch §7.2), for the single forward zone. There is deliberately no CLEAR. */
enum class Band { NONE, FAR, MID, NEAR }

/**
 * The single alert the phone presents, from the forward ToF zone only. [distanceMm] is non-null only when
 * backed by a VALID ToF status; nothing is ever inferred from camera imagery (no person/range fusion).
 */
data class Alert(val band: Band, val distanceMm: Int?)

data class AssembledFrame(
    val frameId: Long,
    val captureBoardUs: Long,
    val capturePhoneUs: Long,
    val w: Int,
    val h: Int,
    val jpeg: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is AssembledFrame && frameId == other.frameId && captureBoardUs == other.captureBoardUs &&
            w == other.w && h == other.h && jpeg.contentEquals(other.jpeg)

    override fun hashCode(): Int = (frameId * 31 + captureBoardUs).hashCode() * 31 + jpeg.contentHashCode()
}

data class BoardDiagnostics(
    val board: BoardId,
    val beatAgeMs: Long?,
    val offsetUs: Long?,
    val rttUs: Long?,
    val synced: Boolean,
    val faults: Int,
    val rssiDbm: Int?,
)

data class Diagnostics(
    val nowPhoneUs: Long,
    val state: SystemState,
    val cam: BoardDiagnostics,
    val cue: BoardDiagnostics,
    val frameAgeMs: Long?,
    val rangeAgeMs: Long?,
    /** Last accepted forward ToF measurement (one zone). [rangeMm] is meaningful only when [rangeStatus] is VALID. */
    val rangeMm: Int?,
    val rangeStatus: Int?,
    val lastAlert: Alert?,
    val lastCueLatencyMs: Double?,
    val captureToCueP50Ms: Double?,
    val captureToCueP95Ms: Double?,
    val ackP50Ms: Double?,
    val ackP95Ms: Double?,
    val cuesSent: Long,
    val acks: Long,
    val nacks: Long,
    val missedAcks: Long,
    val malformed: Long,
    val staleDropped: Long,
    val frameSource: FrameSourceKind,
    /** Camera-board packets received while frameSource = PHONE_CAMERA (ignored, never trusted). */
    val ignoredCamPackets: Long,
    /** True while the cue board's RSSI has stayed below [Tuning.weakLinkRssiDbm] (informational; never changes [state]). */
    val linkWeak: Boolean,
    /** Least-squares closing speed in mm/s over [Tuning.approachWindowMs] (positive = getting closer); null with < 3 VALID samples. */
    val closingSpeedMmPerS: Double?,
    /** EXPERIMENTAL approach escalation is enabled and its speed / time-to-contact criteria currently hold. */
    val approachActive: Boolean,
    /** EXPERIMENTAL exploration mode is enabled and currently driving haptic pulses from a fresh VALID reading. */
    val exploring: Boolean,
)

/**
 * A cue to play. Audio gains (dBFS ≤ 0, −128 = mute) go to the [LocalCueSink] only; the haptic fields go to
 * the cue board in the CUE packet (the board never plays sound). The engine adds seq, issue time and deadline.
 */
data class CueCommand(
    val cueId: Int,
    val side: Int,
    val gainLDb: Int,
    val gainRDb: Int,
    val hapticPattern: Int,
    val hapticL: Int,
    val hapticR: Int,
    val flags: Int,
    val ttlMs: Int,
)

interface EngineListener {
    fun onState(state: SystemState, reason: String)
    fun onAlert(alert: Alert?)
    fun onDiagnostics(d: Diagnostics)
}

/** Latest accepted forward ToF measurement, for display (PROTOCOL.md v2 RANGE: one zone, canonical status). */
data class RangeSnapshot(val tBoardUs: Long, val rxPhoneUs: Long, val mm: Int, val status: Int)
