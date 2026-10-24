# org.determine.core — public API (v1)

Maven coordinates (composite build): `org.determine:core` (pure Kotlin/JVM, target 17, Kotlin 2.0.21, no deps).
All times are **µs**: phone time = a monotonic clock supplied by the caller (`SystemClock.elapsedRealtimeNanos()/1000`
on Android, `System.nanoTime()/1000` in cli). The core never reads a clock itself and never does I/O.

## Facade (`org.determine.core`)

```kotlin
enum class BoardId { CAM, CUE }
interface Outbox { fun send(board: BoardId, packet: ByteArray) }          // caller does UDP (CAM :47002, CUE :47001)
interface PhoneAlerts { fun speak(text: String); fun vibrate(pattern: LongArray); fun systemOff() }
data class Detection(val label: String, val score: Float, val left: Float, val top: Float, val right: Float, val bottom: Float) // 0..1

class Engine(config: EngineConfig, outbox: Outbox, alerts: PhoneAlerts, listener: EngineListener,
             localCueSink: LocalCueSink? = null) {     // sink required (else IllegalArgumentException) when cueOutput = PHONE_AUDIO
  fun onPacket(from: BoardId, bytes: ByteArray, len: Int, rxPhoneUs: Long)
  fun onPhoneFrame(jpeg: ByteArray, w: Int, h: Int, capturePhoneUs: Long)  // Tier 0 only (else IllegalStateException); see Fallbacks
  fun onDetections(frameId: Long, capturePhoneUs: Long, detections: List<Detection>, nowPhoneUs: Long)
                                                // CHANGED (was captureBoardUs): pass AssembledFrame.capturePhoneUs;
                                                // dropped (staleDropped++) when nowPhoneUs − capturePhoneUs > 500 ms
  fun tick(nowPhoneUs: Long)                    // every ~10 ms: PINGs (10 Hz/board), cue cadence, ACK + liveness timeouts
  fun setProfile(profile: HearingProfile)       // GLASSES: sends PROFILE now (and after every cue-board reconnect); always the clamp source
  fun setPaused(paused: Boolean)                // PAUSED is explicit only; spoken confirmation
  fun setBatteryCritical(critical: Boolean)     // → UNAVAILABLE
  fun setHeadYawRate(degPerS: Float)            // scanning (> config.scanYawDegPerS) holds FAR cues only
  fun latestJpeg(): AssembledFrame?             // newest complete fresh frame for the detector (board or phone camera)
  fun sendCalibrationCue(cmd: CueCommand, nowPhoneUs: Long): Long  // returns CUE seq; ttl from cmd (1000 ms for CAL)
  val state: SystemState
  val profile: HearingProfile
}
interface EngineListener { fun onState(state: SystemState, reason: String); fun onAlert(alert: Alert?); fun onDiagnostics(d: Diagnostics) }

/** Board attribution helper: BEAT → its board byte, RANGE/ACK → CUE, FRAME → CAM, PONG/others/invalid → null
 *  (attribute those by the learned source address). */
object org.determine.core.wire.Wire { fun senderBoard(bytes: ByteArray, len: Int): BoardId? ; fun decode(...); fun encode(...) }
```

Types:
```kotlin
enum class SystemState { STARTING, READY, DEGRADED, UNAVAILABLE, PAUSED }
enum class Region { LEFT, CENTER, RIGHT }
enum class Band { NONE, FAR, MID, NEAR }                     // ordered by urgency
data class Alert(val region: Region, val band: Band, val label: String, val distanceMm: Int?)  // distanceMm only with valid ToF
data class AssembledFrame(val frameId: Long, val captureBoardUs: Long, val capturePhoneUs: Long, val w: Int, val h: Int, val jpeg: ByteArray)
data class BoardDiagnostics(val board: BoardId, val beatAgeMs: Long?, val offsetUs: Long?, val rttUs: Long?, val synced: Boolean, val faults: Int, val rssiDbm: Int?)
data class Diagnostics(
  val nowPhoneUs: Long, val state: SystemState, val cam: BoardDiagnostics, val cue: BoardDiagnostics,
  val frameAgeMs: Long?, val rangeAgeMs: Long?, val lastAlert: Alert?,
  val lastCueLatencyMs: Double?,                         // capture → cue issue, last onset
  val captureToCueP50Ms: Double?, val captureToCueP95Ms: Double?,
  val ackP50Ms: Double?, val ackP95Ms: Double?,          // cue issue → ACK
  val cuesSent: Long, val acks: Long, val nacks: Long, val missedAcks: Long, val malformed: Long, val staleDropped: Long,
  val frameSource: FrameSourceKind, val cueOutput: CueOutput,
  val ignoredCamPackets: Long)                          // camera-board packets received in Tier 0 (ignored)
data class CueCommand(val cueId: Int, val side: Int, val gainLDb: Int, val gainRDb: Int, val hapticPattern: Int,
                      val hapticL: Int, val hapticR: Int, val flags: Int, val ttlMs: Int)
data class EngineConfig(...)   // all thresholds; defaults = Arch §4.2/§7.2/§8 values. See Engine.kt.
                               // + frameSource: FrameSourceKind = CAM_BOARD, cueOutput: CueOutput = GLASSES
enum class FrameSourceKind { CAM_BOARD, PHONE_CAMERA }
enum class CueOutput { GLASSES, PHONE_AUDIO }
fun interface LocalCueSink { fun play(cmd: CueCommand, nowPhoneUs: Long): Int }  // returns wire.AckStatus
```

## Fallbacks (Arch §4.1 seams, §11.3 timeline)

Both are `EngineConfig` choices fixed at construction; they combine (phone camera + phone audio needs only the cue board).

**Tier 0 — `frameSource = PHONE_CAMERA`** (phone on the headband, its own camera).
- The host feeds `onPhoneFrame(jpeg, w, h, capturePhoneUs)` on the engine lock; the engine assigns `frameId` (0, 1, …) and
  `latestJpeg()` returns `AssembledFrame(frameId, captureBoardUs = 0, capturePhoneUs, w, h, jpeg)`. The array is kept, not copied.
- Dropped: empty JPEG or `w/h ≤ 0` (`malformed`), capture time not newer than the previous frame, or older than 500 ms
  at the last `tick` (`staleDropped`).
- No camera board: it is never PINGed, its BEAT/clock are not required for READY, and its absence never causes
  UNAVAILABLE. Packets that still arrive from `BoardId.CAM` are counted in `ignoredCamPackets` and ignored.
- Frame age > 500 ms is still UNAVAILABLE (`frame_stale`), measured on `capturePhoneUs`.

**Phone audio — `cueOutput = PHONE_AUDIO`** (transducers missing → phone audio to a headset).
- Every cue (obstacle, READY tick, SYSTEM_OFF, `sendCalibrationCue`) goes to `localCueSink.play(cmd, now)` instead of a
  CUE packet, synchronously on the caller's thread, gains already clamped to `profile.effectiveCeilingDb` (−128 = mute)
  and haptics to `hapticMax`. The sink plays now; there is no deadline.
- The return value is that cue's ACK: PLAYED/PLAYED_CLAMPED good; BUSY_DROPPED a nack without escalation; anything
  else (EXPIRED, NO_SYNC, BAD_CUE, negative, unknown) or a thrown exception counts toward the same 3-failure
  escalation to UNAVAILABLE (`cue_ack_missing`).
- PROFILE is never sent. The cue path is confirmed by the first good local play: until then (and every
  `profileRetryUs` = 1 s while UNAVAILABLE) the engine plays a **muted link probe**
  `CueCommand(READY, BOTH, −128, −128, haptic NONE, 0, 0, flags 0, ttl)`. The sink must play nothing audible for it
  and return PLAYED only if its output path is actually open.
- The cue board is still required: its BEAT, PINGs/clock sync and RANGE (ToF) work as before.
- UNAVAILABLE: system-off on the local sink plus `PhoneAlerts.systemOff()`/vibration, repeated every 5 s.

## Describe loop (`org.determine.core.describe`, Arch §7.3, cloud contract `cloud/API.md`)

```kotlin
data class DescribeRequest(val requestId: String, val jpeg: ByteArray, val width: Int, val height: Int)  // jpeg = redacted
sealed class DescribeResult { abstract val requestId: String
  data class Ok(requestId, text: String, backend: String, silicon: String, inferenceMs: Long?, totalMs: Long?)
  data class Failed(requestId, reason: String, retryable: Boolean) }
interface DescribeTransport { fun send(req: DescribeRequest, onResult: (DescribeResult) -> Unit); fun cancel(requestId: String) }
fun interface Redactor { fun redact(frame: AssembledFrame): ByteArray? }   // null = could not redact → no upload
interface DescribeSpeech { fun speakDescription(text: String); fun stopDescription(); fun say(text: String) }
enum class DescribeState { IDLE, REDACTING, WAITING, SPEAKING }
enum class DescribeOutcome { STARTED, DISABLED, SYSTEM_UNAVAILABLE, NO_FRESH_FRAME, BUSY, REDACTION_FAILED }
data class DescribeConfig(val timeoutUs: Long = 8_000_000, val maxFrameAgeUs: Long = 500_000, val prefix: String = "May be wrong.")
class DescribeController(engine, transport, redactor, speech, config = DescribeConfig()) {
  var enabled: Boolean          // user opt-in, default false; setting false cancels anything in progress
  val state: DescribeState
  fun request(nowPhoneUs: Long): DescribeOutcome
  fun onResult(result: DescribeResult, nowPhoneUs: Long)
  fun onAlert(alert: Alert?, nowPhoneUs: Long)    // forward every EngineListener.onAlert
  fun onState(state: SystemState, nowPhoneUs: Long) // forward every EngineListener.onState
  fun tick(nowPhoneUs: Long)                      // with Engine.tick: delivers queued results, timeout
  companion object { fun sanitize(text: String): String? }
}
```
- Not thread-safe: call every method under the Engine's lock. User-initiated only (long press / button); never automatic.
- `request` checks in order: `enabled` → engine not UNAVAILABLE → not REDACTING/WAITING (`BUSY`) → `latestJpeg()` captured
  ≤ `maxFrameAgeUs` ago → `redactor.redact(frame)` (synchronous; null, empty or throwing → `REDACTION_FAILED`, says
  "Description unavailable.", nothing uploaded) → `transport.send` (must not block) with a fresh
  `requestId = "dsc-<16 hex>-<n>"` → `STARTED`. Other rejections are silent; the host announces them.
  A request while SPEAKING stops the old description first.
- Results: the callback given to `send` may run on any thread; it only enqueues, and `tick` hands queued results to
  `onResult` (hosts may also call `onResult` directly). Only the current request's id is accepted — late, cancelled
  or duplicate results are ignored.
  - `Ok` → text re-filtered locally (`sanitize`: drops every sentence with `clear`, `clearly`, `safe`, `safely`,
    `nothing ahead`, `no obstacle(s)`, `empty path`, `all clear`, `free of obstacles`) → `speakDescription("May be wrong. " + text)`,
    state SPEAKING; nothing left → `say("Description unavailable.")`. If a MID/NEAR alert is active the answer is dropped silently.
  - `Failed` or no result within `timeoutUs` (8 s; `transport.cancel` is called) → `say("Description unavailable.")`.
- Interrupts (silent; the hazard owns the audio): MID or NEAR alert → `stopDescription()` (always) + cancel; system
  UNAVAILABLE → same. FAR alerts do not interrupt.
- SPEAKING ends at a generous estimate (1.5 s + 80 ms per character), on the next request, or on an interrupt.

## Hearing / calibration (`org.determine.core.hearing`)

```kotlin
enum class HearingMode(val wire: Int) { STEREO(0), SSD(1), HAPTIC_ONLY(2) }
enum class Ear { LEFT, RIGHT }
data class BandProfile(val hz: Int, val thrDbL: Int?, val thrDbR: Int?, val slDb: Int, val offsetDbL: Int, val offsetDbR: Int) // thr null = unusable side
data class HearingProfile(deviceId, createdAtMs, mode, ceilingDb, bands, hapticGainL, hapticGainR, hapticMax, verifiedTrials, verifiedCorrect, fitNote) {
  fun levelDb(band: Int, ear: Ear): Int?    // thr + sl + offset, clamped to ceiling; null = unusable
  companion object { fun uncalibrated(ceilingDb: Int = -20): HearingProfile }
}
class Staircase(startDb, ceilingDb, ...)   // 2-down-1-up; level, answer(heard), done, unusable, thresholdDb
class CalibrationSession(ceilingDb: Int, sink: (CueCommand) -> Unit, random: Random = Random.Default)
```
**Calibration sends cues through the Engine**: build the session with
`sink = { cmd -> engine.sendCalibrationCue(cmd, now()) }`, call `engine.setPaused(true)` during calibration
(obstacle cues off; PINGs continue), then `engine.setProfile(session.result()!!)`.
Session steps (`session.step: CalStep` = FIT, THRESHOLD, LEVEL, BALANCE, VERIFY, DONE):
`setFit(deviceId, weakerEar?, note)` → `answerHeard(Boolean)` repeatedly (current `ear`/`bandHz` exposed; `replay()` re-presents)
→ `setSensationLevel(10..20)` → `nudgeBalance(deltaDb)` / `replay()` / `confirmBalance()` (twice) → `answerSide(Ear)` ×5
→ `result()`. `chooseHapticOnly()` at any step finishes with HAPTIC_ONLY.

## Stable cli output (`phone/cli`) — the simulator parses this

One line per event, space-separated `key=value`, first token is the kind, second is ms since cli start:
```
STATE <ms> <STATE> reason=<token>
ALERT <ms> region=<LEFT|CENTER|RIGHT> band=<FAR|MID|NEAR> label=<token> dist_mm=<int|->
ALERT <ms> none
CUE-TX <ms> seq=<n> cue=<id> side=<0|1|2> gl=<db> gr=<db> hp=<pattern> hl=<0-255> hr=<0-255> flags=<n> ttl=<ms> deadline=<boardUs>
PROFILE-TX <ms> seq=<n> ceiling=<db> mode=<0|1|2> haptic_max=<n>
SPEAK <ms> text="<text>"
VIBRATE <ms> pattern=<a,b,c>
SYSTEM-OFF <ms>
DIAG <ms> state=.. cam_beat_ms=.. cam_offset_us=.. cam_rtt_us=.. cam_synced=.. cue_beat_ms=.. cue_offset_us=.. cue_rtt_us=.. cue_synced=.. frame_age_ms=.. range_age_ms=.. cap2cue_ms=.. cap2cue_p50=.. cap2cue_p95=.. ack_p50=.. ack_p95=.. cues=.. acks=.. nacks=.. missed=.. malformed=.. stale=..
```
Unknown values print `-`. Tokens never contain spaces (spaces → `_`). New keys may be appended at line end only.
