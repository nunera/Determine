package org.determine.app.runtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.determine.core.Alert
import org.determine.core.Diagnostics
import org.determine.core.RangeSnapshot
import org.determine.core.SystemState
import org.determine.core.hearing.CalStep
import org.determine.core.hearing.Ear
import org.determine.core.hearing.HearingMode
import org.determine.core.hearing.HearingProfile
import org.determine.core.FrameSourceKind
import org.determine.core.describe.DescribeOutcome
import org.determine.core.describe.DescribeResult

/** The engine's state as last reported through EngineListener.onState. */
data class StateInfo(val state: SystemState, val reason: String)

/** Snapshot of a running [org.determine.core.hearing.CalibrationSession] for the calibration screens. */
data class CalibrationView(
    val step: CalStep,
    val ear: Ear?,
    val bandHz: Int?,
    val bandIndex: Int?,
    val sensationLevelDb: Int,
    val balanceDb: Int,
    val balanceConfirms: Int,
    val verifyTrials: Int,
    val verifyCorrect: Int,
    val ceilingDb: Int,
    val result: HearingProfile?,
    val failureReason: String?,
)

/** Transport facts for the settings and judge screens. */
data class LinkInfo(
    val bound: Boolean,
    val bindError: String?,
    val camIp: String?,
    val cueIp: String?,
    val unattributedPackets: Long,
    val sendErrors: Long,
)

/** Which inputs this session uses; the phone-camera fallback is a software demo, not the proposed glasses (Arch §11.4). */
data class FallbackInfo(
    val frameSource: FrameSourceKind,
    val phoneCameraError: String?,
    val phoneAudioError: String?,
    val redactorAvailable: Boolean,
)

/**
 * The phone → Bluetooth bone-conduction headset audio route. [routeReady] is false whenever no headset output
 * exists or the track is not bound to it; the engine is then UNAVAILABLE (`headset_route_lost`). Never a speaker.
 */
data class HeadsetInfo(
    val present: Boolean,
    val name: String?,
    val routeReady: Boolean,
    val error: String?,
)

/** The last Describe answer or failure, for the judge screen (never the answer text: that is spoken only). */
data class DescribeInfo(
    val ok: Boolean,
    val reason: String?,
    val backend: String?,
    val silicon: String?,
    val inferenceMs: Long?,
    val serverTotalMs: Long?,
    val roundTripMs: Long,
) {
    companion object {
        fun from(r: DescribeResult, roundTripMs: Long) = when (r) {
            is DescribeResult.Ok -> DescribeInfo(true, null, r.backend, r.silicon, r.inferenceMs, r.totalMs, roundTripMs)
            is DescribeResult.Failed -> DescribeInfo(false, r.reason, null, null, null, null, roundTripMs)
        }
    }
}

/** Result of one "What's ahead?" press, so the UI can announce refusals. [atUs] makes repeats distinct. */
data class DescribeOutcomeEvent(val outcome: DescribeOutcome, val atUs: Long)

/**
 * Process-wide bridge between the platform session (Android service or desktop app, which owns the engine)
 * and the Compose UI. Values are written from the engine thread; StateFlow writes are thread-safe and
 * non-blocking, so publishing never delays the hazard loop.
 *
 * Commands are forwarded to the live [EngineHost]; with no service running they are ignored and the UI
 * disables the controls that need the engine.
 */
object DetermineRuntime {
    internal val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    internal val _state = MutableStateFlow<StateInfo?>(null)
    val state: StateFlow<StateInfo?> = _state.asStateFlow()

    internal val _alert = MutableStateFlow<Alert?>(null)
    val alert: StateFlow<Alert?> = _alert.asStateFlow()

    internal val _diagnostics = MutableStateFlow<Diagnostics?>(null)
    val diagnostics: StateFlow<Diagnostics?> = _diagnostics.asStateFlow()

    internal val _range = MutableStateFlow<RangeSnapshot?>(null)
    val range: StateFlow<RangeSnapshot?> = _range.asStateFlow()

    internal val _headset = MutableStateFlow<HeadsetInfo?>(null)
    val headset: StateFlow<HeadsetInfo?> = _headset.asStateFlow()

    internal val _link = MutableStateFlow<LinkInfo?>(null)
    val link: StateFlow<LinkInfo?> = _link.asStateFlow()

    internal val _calibration = MutableStateFlow<CalibrationView?>(null)
    val calibration: StateFlow<CalibrationView?> = _calibration.asStateFlow()

    internal val _profile = MutableStateFlow<HearingProfile?>(null)
    val profile: StateFlow<HearingProfile?> = _profile.asStateFlow()

    internal val _fallbacks = MutableStateFlow<FallbackInfo?>(null)
    val fallbacks: StateFlow<FallbackInfo?> = _fallbacks.asStateFlow()

    internal val _describe = MutableStateFlow<DescribeInfo?>(null)
    val describe: StateFlow<DescribeInfo?> = _describe.asStateFlow()

    internal val _describeOutcome = MutableStateFlow<DescribeOutcomeEvent?>(null)
    val describeOutcome: StateFlow<DescribeOutcomeEvent?> = _describeOutcome.asStateFlow()

    @Volatile internal var host: EngineHost? = null

    fun setPaused(paused: Boolean) { host?.setPaused(paused) }
    fun setSpearcons(enabled: Boolean) { host?.setSpearcons(enabled) }
    fun setMode(mode: HearingMode) { host?.setMode(mode) }
    fun setHapticMax(value: Int) { host?.setHapticMax(value) }

    fun startCalibration() { host?.startCalibration() }
    fun cancelCalibration() { host?.cancelCalibration() }
    fun calibration(action: CalibrationAction) { host?.calibrate(action) }

    /** "What's ahead?" — user-initiated Describe request (Arch §7.3). */
    fun describeAhead() { host?.describe() }

    internal fun clear() {
        _running.value = false
        _state.value = null
        _alert.value = null
        _diagnostics.value = null
        _range.value = null
        _headset.value = null
        _link.value = null
        _calibration.value = null
        _fallbacks.value = null
        _describeOutcome.value = null
    }
}

/** User actions on the calibration flow (Arch §9 steps), applied on the engine thread. */
sealed interface CalibrationAction {
    data class Fit(val deviceId: String, val weakerEar: Ear?) : CalibrationAction
    data class Heard(val heard: Boolean) : CalibrationAction
    data class SensationLevel(val db: Int) : CalibrationAction
    data class NudgeBalance(val deltaDb: Int) : CalibrationAction
    data object ConfirmBalance : CalibrationAction
    data class NameSide(val ear: Ear) : CalibrationAction
    data object Replay : CalibrationAction
    data object HapticOnly : CalibrationAction
    /** Store the finished profile and resume hazard cues. */
    data object Apply : CalibrationAction
}
