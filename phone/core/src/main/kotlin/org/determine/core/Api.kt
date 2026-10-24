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

/** Where cues are played (Arch §4.1 CueSink). PHONE_AUDIO: transducers missing, phone audio → headset via [LocalCueSink]. */
enum class CueOutput { GLASSES, PHONE_AUDIO }

/**
 * Plays a cue on the phone (PHONE_AUDIO). Called synchronously from the Engine with gains already clamped to the
 * profile ceiling (−128 = mute) and haptics to hapticMax. Returns an AckStatus (wire.AckStatus); any other value
 * (e.g. negative) or a thrown exception counts as a failed cue, exactly like a missing/negative board ACK.
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

/** One detector result, box normalised to 0..1 in the camera image (x grows to the wearer's right). */
data class Detection(
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val area: Float get() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
}

/** Arch §4.2 runtime states. */
enum class SystemState { STARTING, READY, DEGRADED, UNAVAILABLE, PAUSED }

enum class Region { LEFT, CENTER, RIGHT }

/** Proximity bands, ordered by urgency (Arch §7.2). There is deliberately no CLEAR. */
enum class Band { NONE, FAR, MID, NEAR }

/** The single alert the phone presents. [distanceMm] is non-null only when backed by valid ToF (Arch §7.2 "never"). */
data class Alert(val region: Region, val band: Band, val label: String, val distanceMm: Int?)

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
    val cueOutput: CueOutput,
    /** Camera-board packets received while frameSource = PHONE_CAMERA (ignored, never trusted). */
    val ignoredCamPackets: Long,
)

/** A cue to put on the wire; the engine adds seq, issue time and deadline. Gains in dBFS (≤ 0, −128 = mute). */
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

/** Latest accepted ToF grid, for display (PROTOCOL.md RANGE layout: index = row*8+col). */
class RangeSnapshot(val tBoardUs: Long, val rxPhoneUs: Long, val mm: IntArray, val status: IntArray)
