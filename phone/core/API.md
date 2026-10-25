# org.determine.core — public API (v2)

Maven coordinates (composite build): `org.determine:core` (pure Kotlin/JVM, target 17, Kotlin 2.0.21, no deps).
All times are **µs**: phone time = a monotonic clock supplied by the caller (`SystemClock.elapsedRealtimeNanos()/1000`
on Android, `System.nanoTime()/1000` in cli). The core never reads a clock itself and never does I/O.

Wire protocol v2 (`protocol/PROTOCOL.md`): the cue board is **haptic-only**, the distance source is **one forward
VL53L4CD zone**, and the **phone plays every sound** through a Bluetooth bone-conduction headset.

## Facade (`org.determine.core`)

```kotlin
enum class BoardId { CAM, CUE }
interface Outbox { fun send(board: BoardId, packet: ByteArray) }          // caller does UDP (CAM :47002, CUE :47001)
interface PhoneAlerts { fun speak(text: String); fun vibrate(pattern: LongArray); fun systemOff() }

class Engine(config: EngineConfig, outbox: Outbox, alerts: PhoneAlerts, listener: EngineListener,
             localCueSink: LocalCueSink) {                 // REQUIRED: the headset audio route
  fun onPacket(from: BoardId, bytes: ByteArray, len: Int, rxPhoneUs: Long)
  fun onPhoneFrame(jpeg: ByteArray, w: Int, h: Int, capturePhoneUs: Long)  // Tier 0 only (else IllegalStateException)
  fun tick(nowPhoneUs: Long)                    // every ~10 ms: PINGs (10 Hz/board), cue cadence, ACK + liveness timeouts
  fun setProfile(profile: HearingProfile)       // sends PROFILE (mode, haptic_max) now and after every cue-board reconnect
  fun setPaused(paused: Boolean)                // PAUSED is explicit only; spoken confirmation
  fun setBatteryCritical(critical: Boolean)     // → UNAVAILABLE
  fun setHeadYawRate(degPerS: Float)            // scanning (> config.scanYawDegPerS) holds FAR cues only
  fun latestJpeg(): AssembledFrame?             // newest complete fresh frame (Describe only; never a distance source)
  fun latestRange(): RangeSnapshot?             // newest accepted forward ToF measurement
  fun sendCalibrationCue(cmd: CueCommand, nowPhoneUs: Long): Boolean  // true only for accepted headset playback; never board haptics
  fun setSpearcons(enabled: Boolean)
  fun setTuning(t: Tuning)                      // validates (IllegalArgumentException, previous tuning kept) and applies at once
  fun sendTestHaptic(pattern: Int, intensity: Int, nowPhoneUs: Long): Boolean  // debug buzz; false w/o cue clock sync / cue board / valid pattern 1..4
  val tuning: Tuning
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
enum class Band { NONE, FAR, MID, NEAR }                     // ordered by urgency; there is no CLEAR
data class Alert(val band: Band, val distanceMm: Int?)       // forward zone only: no side, no object label
data class RangeSnapshot(val tBoardUs: Long, val rxPhoneUs: Long, val mm: Int, val status: Int)   // one zone; status = wire.RangeStatus
data class AssembledFrame(val frameId: Long, val captureBoardUs: Long, val capturePhoneUs: Long, val w: Int, val h: Int, val jpeg: ByteArray)
data class BoardDiagnostics(val board: BoardId, val beatAgeMs: Long?, val offsetUs: Long?, val rttUs: Long?, val synced: Boolean, val faults: Int, val rssiDbm: Int?)
data class Diagnostics(
  val nowPhoneUs: Long, val state: SystemState, val cam: BoardDiagnostics, val cue: BoardDiagnostics,
  val frameAgeMs: Long?, val rangeAgeMs: Long?,
  val rangeMm: Int?, val rangeStatus: Int?,              // last forward measurement; mm only meaningful when VALID
  val lastAlert: Alert?,
  val lastCueLatencyMs: Double?,                         // capture → cue issue, last onset
  val captureToCueP50Ms: Double?, val captureToCueP95Ms: Double?,
  val ackP50Ms: Double?, val ackP95Ms: Double?,          // cue issue → ACK
  val cuesSent: Long, val acks: Long, val nacks: Long, val missedAcks: Long, val malformed: Long, val staleDropped: Long,
  val frameSource: FrameSourceKind,
  val ignoredCamPackets: Long,                          // camera-board packets received in Tier 0 (ignored)
  val linkWeak: Boolean,                                // cue-board RSSI weak (informational only)
  val closingSpeedMmPerS: Double?,                      // LS slope of VALID distance; + = approaching; null with < 3 samples / stale RANGE
  val approachActive: Boolean,                          // experimental approach escalation enabled AND criteria hold
  val exploring: Boolean)                               // experimental exploration enabled AND driving pulses now
/** Audio gains stay on the phone (dBFS ≤ 0, −128 = mute); haptic fields go to the board in the CUE packet. */
data class CueCommand(val cueId: Int, val side: Int, val gainLDb: Int, val gainRDb: Int, val hapticPattern: Int,
                      val hapticL: Int, val hapticR: Int, val flags: Int, val ttlMs: Int)
data class EngineConfig(...)   // thresholds; defaults = Arch §4.2/§7.2/§8 values. See Engine.kt. frameSource: FrameSourceKind = CAM_BOARD,
                               // tuning: Tuning = Tuning(), policy: PolicyConfig(minOnsetIntervalUs), cue: CueConfig(nearStepDb, ttlMs, hapticScale*)
enum class FrameSourceKind { CAM_BOARD, PHONE_CAMERA }
fun interface LocalCueSink { fun play(cmd: CueCommand, nowPhoneUs: Long): Int }  // wire.AckStatus, or AUDIO_ROUTE_LOST / failure
const val GAIN_MUTE = -128
const val AUDIO_ROUTE_LOST = -2
```

## Hazard model (single forward zone)

- Distance comes from exactly one place: the `RANGE` packet's `(mm, status)` from the board's one forward ToF zone.
  Status `VALID` → `Target(mm)`, `NO_TARGET` → nothing seen (not "clear"), `UNKNOWN` or any other value → unknown
  (never far, never no-target). There is no grid, no regions, no mounting transform and no left/right claim.
- Bands (PROPOSED, measured/tunable via `Tuning`): **NEAR < 500 mm, MID 500–899 mm, FAR 900–1200 mm**; beyond
  1200 mm nothing is reported (the VL53L4CD's honest range, not a validated warning distance).
  `Alert.distanceMm` is the VALID distance when it is within `farMm`, else null.
  UNKNOWN immediately cancels the current metric warning; a missing RANGE cancels it after 500 ms. Re-entry runs
  through the enter rules; ≤ 1 onset per 700 ms (`PolicyConfig.minOnsetIntervalUs`), head scanning holds FAR only. Startup
  with a fresh UNKNOWN reading is announced DEGRADED, not a
  false system-off timeout. NO_TARGET (no return / beyond the sensor's range) is "nothing seen": no speech, no cue,
  no DEGRADED, and never an "all clear".
- Smoothing (time-based, on the board time of the RANGE samples; `Tuning.bandEnterMs/bandExitMs/bandHysteresisMm`,
  defaults 150 / 600 / 50): a band — or a more urgent one — is entered once the readings have consistently
  indicated it for ≥ `bandEnterMs` (the run keeps the *least* urgent band seen); leaving it (to a less urgent band, or
  to nothing from VALID-beyond-far / NO_TARGET) takes ≥ `bandExitMs` of consistent evidence **and** a distance
  strictly beyond the band's upper bound + `bandHysteresisMm` (NEAR leaves above `nearMm + 50`, MID above `midMm + 50`,
  FAR above `farMm + 50`). Hysteresis is exit-only. A sample going the other way restarts the run.
- Camera frames and any image model have **no** role in range, band, side or label. Alerts are always
  "obstacle ahead", cues are always `side = BOTH`, and `PERSON_*` cue ids exist only in the phone's sound bank.

## Tuning (`org.determine.core.Tuning`)

`Engine.tuning` (read) / `Engine.setTuning(t)`; `EngineConfig.tuning` is the initial value. `setTuning` validates
(`IllegalArgumentException`, previous tuning kept) and applies immediately: band state, queued announcements and link
tracking carry over. Times in ms, distances in mm. Defaults and validation:

| field | default | valid |
|---|---|---|
| `nearMm` / `midMm` / `farMm` | 500 / 900 / 1200 | `0 < nearMm < midMm < farMm ≤ 8000` |
| `bandEnterMs` / `bandExitMs` / `bandHysteresisMm` | 150 / 600 / 50 | ≥ 0 |
| `farPeriodMs` / `midPeriodMs` / `nearPeriodMs` | 1000 / 400 / 200 | > 0 |
| `announceHoldMs` / `announceRepeatMs` | 1500 / 10000 | ≥ 0 |
| `weakLinkRssiDbm` / `weakLinkHoldMs` / `weakLinkRepeatMs` | -70 / 2000 / 30000 | rssi −127..0, times ≥ 0 |
| `approachEnabled` / `approachWindowMs` / `approachTtcMs` / `approachMinSpeedMmPerS` | false / 300 / 1500 / 300 | > 0 |
| `exploreEnabled` / `explorePeriodMs` / `exploreMaxMm` / `exploreMinIntensity` | false / 120 / 1200 / 30 | period > 0, max 1..8000, intensity 0..255 |

The earlier `PolicyConfig`/`CueConfig` band, period and enter/exit-sample fields were folded into `Tuning`
(`enterSamples`/`exitSamples` no longer exist); `PolicyConfig` keeps `minOnsetIntervalUs`, `CueConfig` keeps
`nearStepDb`, `ttlMs`, `hapticScale*`. `Engine.sendTestHaptic(pattern, intensity, nowPhoneUs)` sends one debug CUE
(`OBSTACLE_FAR` id, side BOTH, interrupt flag, no phone audio; intensity clamped 0..255 then to haptic_max) through the
normal CUE path (deadline, ACK tracking). It returns false, sending nothing, without a cue BEAT, without cue-board
clock sync, or for a pattern outside 1..4.

### Less chatter
Spoken state announcements — `system ready` (after UNAVAILABLE), `distance sensor limited`, `system ready, distance
sensor limited` — are said only after the new state held `announceHoldMs` and the same phrase is not repeated within
`announceRepeatMs` (a suppressed phrase is dropped, not retried). `onState` stays immediate; `paused`/`resumed`
and the system-off cue are never delayed.

### Link quality
From the cue board's BEAT RSSI (phone receive time): below `weakLinkRssiDbm` for `weakLinkHoldMs` ⇒
`Diagnostics.linkWeak = true` and `weak connection` is spoken once per weak episode (not within `weakLinkRepeatMs` of
the previous announcement, and not while UNAVAILABLE/PAUSED). It recovers after RSSI ≥ threshold + 5 dB for
`weakLinkHoldMs`; in between neither run advances. Informational: never changes `SystemState`. Tracking resets on a
cue-board reconnect.

### Approach speed (EXPERIMENTAL, off by default)
Closing speed = −(least-squares slope of VALID distance vs board time over the last `approachWindowMs`, ≥ 3 samples),
positive when approaching; NO_TARGET and UNKNOWN clear the window. `Diagnostics.closingSpeedMmPerS` is always computed
(null when fewer than 3 samples or RANGE is stale). With `approachEnabled`, speed ≥ `approachMinSpeedMmPerS` and
distance / speed < `approachTtcMs` (`Diagnostics.approachActive`) escalate the reported band by one at the output
stage: FAR→MID, MID→NEAR, NEAR stays, nothing-yet within `farMm` → FAR. The smoothed band is unchanged, so the
escalation ends as soon as the criteria stop holding; onset rate limit and the scan hold still apply.

### Exploration (EXPERIMENTAL, off by default)
With `exploreEnabled` band cues, spearcons and band audio are replaced by one haptic CUE (PULSE40, side BOTH, flags 0,
no phone audio, id `OBSTACLE_FAR`) every `explorePeriodMs` while a fresh VALID reading is ≤ `exploreMaxMm`; intensity
= `min + ((255 − min)·(max − mm) + max/2) / max` (integer, mm clamped to 0..max), clamped to haptic_max. NO_TARGET,
beyond `exploreMaxMm`, UNKNOWN or stale RANGE: silence. State handling (DEGRADED, UNAVAILABLE, system-off) is
unchanged; `Diagnostics.exploring` is true while pulses are being driven.

## Outputs: phone audio + board haptics

One warning is one `CueCommand`. The engine:
1. clamps each audio gain to `min(profile ceiling, −10 dBFS)` independently per side and each haptic intensity to
   `profile.hapticMax`, then plays the audio **now** through `localCueSink` (its return value is the audio ACK);
2. if the command has a haptic pattern, also sends a `CUE` packet (haptic fields only, no gains) to the cue board and
   awaits its ACK. Haptic-free cues (READY tick, calibration tones) never reach the wire.

Audio ACK semantics: `APPLIED`/`APPLIED_CLAMPED` good; `BUSY_DROPPED` a nack without escalation; any other
`AckStatus`, a negative failure value or a thrown exception counts toward the 3-failure escalation
(`cue_ack_missing`); **`AUDIO_ROUTE_LOST` (−2)** means the Bluetooth headset route is absent or lost and goes to
UNAVAILABLE immediately with reason **`headset_route_lost`**. A sink must never fall back to the phone speaker.
Failure streaks are counted **per channel** (board ACKs vs headset plays): a good answer on one channel never resets
the other's streak, so a silent board cannot hide behind a working headset or the reverse.

READY needs **both** paths confirmed: the board through a PROFILE/CUE ACK and the headset through a good local play.
Until the first good local play (and every `profileRetryUs` = 1 s while UNAVAILABLE) the engine plays a **muted link
probe** `CueCommand(READY, BOTH, −128, −128, NONE, 0, 0, 0, ttl)`: the sink plays nothing audible and returns APPLIED
only if its route is actually open (or `AUDIO_ROUTE_LOST`). Reconnecting the headset therefore recovers the system.

**Haptic-only (`HearingMode.HAPTIC_ONLY`) turns phone audio off on purpose, so the headset is not required:** the
sink is never called (no probe, no READY tick, no obstacle or system-off sound), READY needs only the board, and
sink failures cannot raise `headset_route_lost` or audio ACK failures. The board's ACKs, the phone alarm and vibration,
and the temple haptics keep every failure announced. Calibration tones (`sendCalibrationCue`) always go to the sink,
but while haptic-only their failure does not affect system state. Switching the mode resets the route verdict:
switching to STEREO/SSD re-proves the headset first (and goes UNAVAILABLE with `headset_route_lost` if there is none).

UNAVAILABLE: the system-off cue goes to the headset (when audio is on) **and** the board (haptic LSS), plus
`PhoneAlerts.systemOff()` and vibration, repeated every 5 s. PROFILE carries `mode` (phone audio semantics) and
`haptic_max`, which must be `1..255` (`MIN_HAPTIC_MAX`): the temples are the failure channel that survives phone
loss, so `HearingProfile` rejects 0. A cap of 1 is not a measured perceptibility guarantee.

Tier 0 (`frameSource = PHONE_CAMERA`): no camera board (never PINGed; its BEAT/clock are not required); the host feeds
`onPhoneFrame`; frame age > 500 ms is UNAVAILABLE (`frame_stale`); packets from `BoardId.CAM` are counted in
`ignoredCamPackets` and ignored. The cue board is always required (forward ToF + haptics + clock sync).

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
data class DescribeConfig(val timeoutUs: Long = 8_000_000, val maxFrameAgeUs: Long = 500_000)
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
    `nothing ahead`, `no obstacle(s)`, `empty path`, `all clear`, `free of obstacles`) → `speakDescription(text)`,
    state SPEAKING; nothing left → `say("Description unavailable.")`. If a MID/NEAR alert is active the answer is dropped silently.
  - `Failed` or no result within `timeoutUs` (8 s; `transport.cancel` is called) → `say("Description unavailable.")`.
- Interrupts (silent; the hazard owns the audio): MID or NEAR alert → `stopDescription()` (always) + cancel; system
  UNAVAILABLE → same. FAR alerts do not interrupt.
- SPEAKING ends at a generous estimate (1.5 s + 80 ms per character), on the next request, or on an interrupt.

## Hearing / calibration (`org.determine.core.hearing`)

```kotlin
enum class HearingMode(val wire: Int) { STEREO(0), SSD(1), HAPTIC_ONLY(2) }   // phone audio semantics; the board is always haptic-only
enum class Ear { LEFT, RIGHT }
data class BandProfile(val hz: Int, val thrDbL: Int?, val thrDbR: Int?, val slDb: Int, val offsetDbL: Int, val offsetDbR: Int) // thr null = unusable side
data class HearingProfile(deviceId, createdAtMs, mode, ceilingDb, bands, hapticGainL, hapticGainR, hapticMax, verifiedTrials, verifiedCorrect, fitNote) {
  fun levelDb(band: Int, ear: Ear): Int?    // thr + sl + offset, clamped to the phone ceiling; null = unusable
  // ceilingDb = local audio ceiling (never above −10 dBFS effective); hapticMax = board haptic cap, MIN_HAPTIC_MAX..255
  companion object { fun uncalibrated(ceilingDb: Int = -20): HearingProfile }
}
class Staircase(startDb, ceilingDb, ...)   // 2-down-1-up; level, answer(heard), done, unusable, thresholdDb
class CalibrationSession(ceilingDb: Int, sink: (CueCommand) -> Boolean, random: Random = Random.Default)
```
**Calibration sends cues through the Engine** (they play on the headset only; they carry no board haptics): build
the session with `sink = { cmd -> engine.sendCalibrationCue(cmd, now()) }`, and call `engine.setPaused(true)` during
calibration (obstacle cues off; PINGs continue). The sink must return true only for successful headset acceptance;
false or playback exceptions terminate the session at `CalStep.FAILED` with `failureReason`, clear its result, and
require reconnection and a fresh calibration. Route loss also invalidates an idle or finished-but-unapplied
session; never treat an unplayed tone as an unheard answer. Resume the user's pause setting on failure/cancel,
and apply `engine.setProfile(session.result()!!)` only after a final route check and `CalStep.DONE`.
Session steps (`session.step: CalStep` = FIT, THRESHOLD, LEVEL, BALANCE, VERIFY, DONE, FAILED):
`setFit(deviceId, weakerEar?, note)` → `answerHeard(Boolean)` repeatedly (current `ear`/`bandHz` exposed; `replay()` re-presents)
→ `setSensationLevel(10..20)` → `nudgeBalance(deltaDb)` / `replay()` / `confirmBalance()` (twice) → `answerSide(Ear)` ×5
→ `result()`. `chooseHapticOnly()` during an active calibration finishes with HAPTIC_ONLY, not after FAILED.
Calibration fits **audio levels per side**; the forward sensor never lateralizes hazards, so the left/right result
does not mean a hazard is on that side. Haptic-only warnings do not require a headset; calibration always does.

## Stable cli output (`phone/cli`) — the simulator parses this

The cli has no audio device: every cue's audio half is logged as an `AUDIO` line and acknowledged APPLIED
(`LogCueSink`); the haptic half is a real `CUE` packet on the wire.

One line per event, space-separated `key=value`, first token is the kind, second is ms since cli start:
```
STATE <ms> <STATE> reason=<token>
ALERT <ms> band=<FAR|MID|NEAR> dist_mm=<int|->
ALERT <ms> none
AUDIO <ms> cue=<id> side=<0|1|2> gl=<db> gr=<db> status=<AckStatus>
CUE-TX <ms> seq=<n> cue=<id> side=<0|1|2> hp=<pattern> hl=<0-255> hr=<0-255> flags=<n> ttl=<ms> deadline=<boardUs>
PROFILE-TX <ms> seq=<n> mode=<0|1|2> haptic_max=<1-255>
SPEAK <ms> text="<text>"
VIBRATE <ms> pattern=<a,b,c>
SYSTEM-OFF <ms>
DIAG <ms> state=.. cam_beat_ms=.. cam_offset_us=.. cam_rtt_us=.. cam_synced=.. cue_beat_ms=.. cue_offset_us=.. cue_rtt_us=.. cue_synced=.. frame_age_ms=.. range_age_ms=.. cap2cue_ms=.. cap2cue_p50=.. cap2cue_p95=.. ack_p50=.. ack_p95=.. cues=.. acks=.. nacks=.. missed=.. malformed=.. stale=.. range_mm=<mm|-> range_status=<VALID|NO_TARGET|UNKNOWN|INVALID_n|-> range_left=unavailable range_right=unavailable link_weak=<true|false> closing_mm_s=<num|-> approach=<true|false> exploring=<true|false>
```
Unknown values print `-`. Tokens never contain spaces (spaces → `_`). New keys may be appended at line end only
(`link_weak`, `closing_mm_s`, `approach`, `exploring` were appended this way; `closing_mm_s` has 2 decimals).
`range_mm` is a number only for `VALID`; `range_left`/`range_right` are always `unavailable` (one forward sensor).

### Opt-in live CLI profile control

`--control-stdin` enables bounded line-oriented local control; the default CLI does not read stdin.
`--haptic-max 1..255` selects the initial board cap (default 255). A controller writes:

```text
PROFILE <positive-request-id> <HAPTIC_ONLY|STEREO|SSD> <integer-ceiling:-60..-10> <integer-haptic-max:1..255>
```

The CLI validates before calling `Engine.setProfile` under the same lock as tick/packet handling.
It does not restart the engine. Lines exceeding 160 characters are rejected and drained before the
next command; EOF/closed control input does not disable the engine or watchdog. Results on stdout:

```text
PROFILE-RESULT <ms> request=<id> status=applied mode=<HAPTIC_ONLY|STEREO|SSD> ceiling_db=<db> haptic_max=<1-255>
PROFILE-RESULT <ms> request=<id-or--1> status=rejected error=<token>
```

Request 0 confirms the actual initial profile application when control mode starts; controller requests
must be positive. `applied` confirms engine configuration only. Subsequent `PROFILE-TX` reports wire
transmission; a board ACK reports acceptance, not physical actuation. Invalid requests retain the prior
profile. Haptic zero is invalid: the existing watchdog failure channel cannot be disabled.
